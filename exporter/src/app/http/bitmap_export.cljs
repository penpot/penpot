(ns app.http.bitmap-export
  (:require
   [cuerdas.core :as str]
   [app.browser :as bwr]
   [app.config :as cfg]
   [app.zipfile :as zip]
   [lambdaisland.glogi :as log]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as exc :include-macros true]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us])
  (:import
   goog.Uri))

(defn- screenshot-object
  [browser {:keys [page-id object-id token scale suffix type]}]
  (letfn [(handle [page]
            (let [path   (str "/render-object/" page-id "/" object-id)
                  uri    (doto (Uri. (:public-uri cfg/config))
                           (.setPath "/")
                           (.setFragment path))
                  cookie {:domain (str (.getDomain uri)
                                       ":"
                                       (.getPort uri))
                          :key "auth-token"
                          :value token}]
              (log/info :uri (.toString uri))
              (screenshot page (.toString uri) cookie)))

          (screenshot [page uri cookie]
            (p/do!
             (bwr/emulate! page {:viewport [1920 1080]
                                 :scale scale})
             (bwr/set-cookie! page cookie)
             (bwr/navigate! page uri)
             (bwr/eval! page (js* "() => document.body.style.background = 'transparent'"))
             (p/let [dom (bwr/select page "#screenshot")]
               (case type
                 :png (bwr/screenshot dom {:omit-background? true :type type})
                 :jpeg (bwr/screenshot dom {:omit-background? false :type type})))))]

    (bwr/exec! browser handle)))

(s/def ::name ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)

(s/def ::suffix string?)
(s/def ::scale number?)
(s/def ::export
  (s/keys :req-un [::type ::suffix ::scale]))

(s/def ::exports (s/coll-of ::export :kind vector?))

(s/def ::bitmap-handler-params
  (s/keys :req-un [::page-id ::object-id ::name ::exports]))

(declare handle-single-export)
(declare handle-multiple-export)
(declare perform-bitmap-export)
(declare attach-filename)

(defn bitmap-export-handler
  [{:keys [params browser cookies] :as request}]
  (let [{:keys [exports page-id object-id name]} (us/conform ::bitmap-handler-params params)
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


(defn handle-single-export
  [{:keys [browser]} params]
  (p/let [result (perform-bitmap-export browser params)]
    {:status 200
     :body (:content result)
     :headers {"content-type" (:mime-type result)
               "content-length" (:length result)}}))

(defn handle-multiple-export
  [{:keys [browser]} exports]
  (let [proms (->> exports
                   (attach-filename)
                   (map (partial perform-bitmap-export browser)))]
    (-> (p/all proms)
        (p/then (fn [results]
                  (reduce #(zip/add! %1 (:filename %2) (:content %2)) (zip/create) results)))
        (p/then (fn [fzip]
                  {:status 200
                   :headers {"content-type" "application/zip"}
                   :body (.generateNodeStream ^js fzip)})))))

(defn- find-filename-candidate
  [params used]
  (loop [index 0]
    (let [candidate (str (str/slug (:name params))
                         (if (not (str/blank? (:suffix params "")))
                           (:suffix params "")
                           (when (pos? index)
                             (str "-" (inc index))))
                         (case (:type params)
                           :png ".png"
                           :jpeg ".jpg"
                           :svg ".svg"))]
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

(defn- perform-bitmap-export
  [browser params]
  (p/let [content (screenshot-object browser params)]
    {:content content
     :filename (or (:filename params)
                   (find-filename-candidate params #{}))
     :length (alength content)
     :mime-type (case (:type params)
                  :png "image/png"
                  :jpeg "image/jpeg")}))

