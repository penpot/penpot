;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.export
  (:require
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]
   [app.renderer.bitmap :as rb]
   [app.renderer.pdf :as rp]
   [app.renderer.svg :as rs]
   [app.zipfile :as zip]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(s/def ::name ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)
(s/def ::suffix string?)
(s/def ::scale number?)

(s/def ::export  (s/keys :req-un [::type ::suffix ::scale]))
(s/def ::exports (s/coll-of ::export :kind vector?))

(s/def ::handler-params
  (s/keys :req-un [::page-id ::file-id ::object-id ::name ::exports]))

(declare handle-single-export)
(declare handle-multiple-export)
(declare perform-export)
(declare attach-filename)

(defn export-handler
  [{:keys [params cookies] :as request}]
  (let [{:keys [exports page-id file-id object-id name]} (us/conform ::handler-params params)
        token  (.get ^js cookies "auth-token")]
    (case (count exports)
      0 (exc/raise :type :validation
                   :code :missing-exports)

      1 (-> (first exports)
            (assoc :name name)
            (assoc :token token)
            (assoc :file-id file-id)
            (assoc :page-id page-id)
            (assoc :object-id object-id)
            (handle-single-export))

      (->> exports
           (map (fn [item]
                  (-> item
                      (assoc :name name)
                      (assoc :token token)
                      (assoc :file-id file-id)
                      (assoc :page-id page-id)
                      (assoc :object-id object-id))))
           (handle-multiple-export)))))

(defn- handle-single-export
  [params]
  (p/let [result (perform-export params)]
    {:status 200
     :body (:content result)
     :headers {"content-type" (:mime-type result)
               "content-length" (:length result)}}))

(defn- handle-multiple-export
  [exports]
  (let [proms (->> exports
                   (attach-filename)
                   (map perform-export))]
    (-> (p/all proms)
        (p/then (fn [results]
                  (reduce #(zip/add! %1 (:filename %2) (:content %2)) (zip/create) results)))
        (p/then (fn [fzip]
                  {:status 200
                   :headers {"content-type" "application/zip"}
                   :body (.generateNodeStream ^js fzip)})))))

(defn- perform-export
  [params]
  (case (:type params)
    :png  (rb/render params)
    :jpeg (rb/render params)
    :svg  (rs/render params)
    :pdf  (rp/render params)))

(defn- find-filename-candidate
  [params used]
  (loop [index 0]
    (let [candidate (str (:name params)
                         (:suffix params "")
                         (when (pos? index)
                           (str "-" (inc index)))
                         (case (:type params)
                           :png  ".png"
                           :jpeg ".jpg"
                           :svg  ".svg"
                           :pdf  ".pdf"))]
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
