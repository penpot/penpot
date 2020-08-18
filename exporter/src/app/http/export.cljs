(ns app.http.export
  (:require
   [app.http.export-bitmap :as bitmap]
   [app.http.export-svg :as svg]
   [app.zipfile :as zip]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]))

(s/def ::name ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)
(s/def ::suffix string?)
(s/def ::scale number?)

(s/def ::export  (s/keys :req-un [::type ::suffix ::scale]))
(s/def ::exports (s/coll-of ::export :kind vector?))

(s/def ::handler-params
  (s/keys :req-un [::page-id ::object-id ::name ::exports]))

(declare handle-single-export)
(declare handle-multiple-export)
(declare perform-export)
(declare attach-filename)

(defn export-handler
  [{:keys [params browser cookies] :as request}]
  (let [{:keys [exports page-id object-id name]} (us/conform ::handler-params params)
        token  (.get ^js cookies "auth-token")]
    (case (count exports)
      0 (exc/raise :type :validation :code :missing-exports)
      1 (handle-single-export
         request
         (assoc (first exports)
                :name name
                :token token
                :page-id page-id
                :object-id object-id))
      (handle-multiple-export
       request
       (map (fn [item]
              (assoc item
                     :name name
                     :token token
                     :page-id page-id
                     :object-id object-id)) exports)))))

(defn- handle-single-export
  [{:keys [browser]} params]
  (p/let [result (perform-export browser params)]
    {:status 200
     :body (:content result)
     :headers {"content-type" (:mime-type result)
               "content-length" (:length result)}}))

(defn- handle-multiple-export
  [{:keys [browser]} exports]
  (let [proms (->> exports
                   (attach-filename)
                   (map (partial perform-export browser)))]
    (-> (p/all proms)
        (p/then (fn [results]
                  (reduce #(zip/add! %1 (:filename %2) (:content %2)) (zip/create) results)))
        (p/then (fn [fzip]
                  {:status 200
                   :headers {"content-type" "application/zip"}
                   :body (.generateNodeStream ^js fzip)})))))

(defn- perform-export
  [browser params]
  (case (:type params)
    :png  (bitmap/export browser params)
    :jpeg (bitmap/export browser params)
    :svg  (svg/export browser params)))

(defn- find-filename-candidate
  [params used]
  (loop [index 0]
    (let [candidate (str (str/slug (:name params))
                         (str/trim (str/blank? (:suffix params "")))
                         (when (pos? index)
                           (str "-" (inc index)))
                         (case (:type params)
                           :png  ".png"
                           :jpeg ".jpg"
                           :svg  ".svg"))]
      (if (contains? used candidate)
        (recur (inc index))
        candidate))))

(defn- attach-filename
  [exports]
  (loop [exports (seq exports)
         used   #{}
         result  []]
    (if (nil? exports)
      result
      (let [export    (first exports)
            candidate (find-filename-candidate export used)
            export    (assoc export :filename candidate)]
        (recur (next exports)
               (conj used candidate)
               (conj result export))))))
