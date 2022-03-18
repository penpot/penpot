;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.handlers.export-shapes
  (:require
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]
   [app.redis :as redis]
   [app.handlers.resources :as rsc]
   [app.renderer.bitmap :as rb]
   [app.renderer.pdf :as rp]
   [app.renderer.svg :as rs]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private handle-exports)
(declare ^:private handle-single-export)
(declare ^:private handle-multiple-export)
(declare ^:private run-export)
(declare ^:private assign-file-name)

(s/def ::name ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)
(s/def ::suffix string?)
(s/def ::scale number?)
(s/def ::uri ::us/uri)
(s/def ::profile-id ::us/uuid)
(s/def ::wait ::us/boolean)

(s/def ::export
  (s/keys :req-un [::page-id ::file-id ::object-id ::type ::suffix ::scale ::name]))

(s/def ::exports
  (s/coll-of ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports ::profile-id]
          :opt-un [::uri ::wait ::name]))

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [exports] :as params}]
  (let [xform   (comp
                 (map #(assoc % :token auth-token))
                 (assign-file-name))
        exports (into [] xform exports)]
    (if (= 1 (count exports))
      (handle-single-export exchange (assoc params :export (first exports)))
      (handle-multiple-export exchange (assoc params :exports exports)))))

(defn- handle-single-export
  [exchange {:keys [export wait uri profile-id name] :as params}]
  (let [topic       (str profile-id)
        resource    (rsc/create (:type export) (or name (:name export)))

        on-progress (fn [progress]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :status "running"
                                  :progress progress}]
                        (redis/pub! topic data)))

        on-complete (fn [resource]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :size (:size resource)
                                  :name (:name resource)
                                  :status "ended"}]
                        (redis/pub! topic data)))

        on-error    (fn [cause]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :name (:name resource)
                                  :status "error"
                                  :cause (ex-message cause)}]
                        (redis/pub! topic data)))

        proc        (rsc/create-simple :task #(run-export export)
                                       :resource resource
                                       :on-progress on-progress
                                       :on-error on-error
                                       :on-complete on-complete)]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))

(defn- handle-multiple-export
  [exchange {:keys [exports wait uri profile-id name] :as params}]
  (let [tasks       (map #(fn [] (run-export %)) exports)
        topic       (str profile-id)
        resource    (rsc/create :zip (or name (-> exports first :name)))

        on-progress (fn [progress]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :name (:name resource)
                                  :status "running"
                                  :progress progress}]
                        (redis/pub! topic data)))

        on-complete (fn [resource]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :name (:name resource)
                                  :size (:size resource)
                                  :status "ended"}]
                        (redis/pub! topic data)))

        on-error    (fn [cause]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :name (:name resource)
                                  :status "error"
                                  :cause (ex-message cause)}]
                        (redis/pub! topic data)))

        proc        (rsc/create-zip :resource resource
                                    :tasks tasks
                                    :on-progress on-progress
                                    :on-complete on-complete
                                    :on-error on-error)]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))

(defn- run-export
  [{:keys [type] :as params}]
  (p/let [res (case type
                :png  (rb/render params)
                :jpeg (rb/render params)
                :svg  (rs/render params)
                :pdf  (rp/render params))]
    (assoc res :type type)))

(defn- assign-file-name
  "A transducer that assocs a candidate filename and avoid duplicates."
  []
  (letfn [(find-candidate [params used]
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
                  candidate))))]
    (fn [rf]
      (let [used (volatile! #{})]
        (fn
          ([] (rf))
          ([result] (rf result))
          ([result params]
           (let [candidate (find-candidate params @used)
                 params    (assoc params :filename candidate)]
             (vswap! used conj candidate)
             (rf result params))))))))
