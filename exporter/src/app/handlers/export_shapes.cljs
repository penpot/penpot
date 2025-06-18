;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.handlers.export-shapes
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.handlers.resources :as rsc]
   [app.redis :as redis]
   [app.renderer :as rd]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private handle-single-export)
(declare ^:private handle-multiple-export)
(declare ^:private assoc-file-name)
(declare prepare-exports)

;; Regex to clean namefiles
(def sanitize-file-regex #"[\\/:*?\"<>|]")

(s/def ::file-id ::us/uuid)
(s/def ::filename ::us/string)
(s/def ::name ::us/string)
(s/def ::object-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)
(s/def ::wait ::us/boolean)

(s/def ::export
  (s/keys :req-un [::page-id ::file-id ::object-id ::type ::suffix ::scale ::name]
          :opt-un [::share-id]))

(s/def ::exports
  (s/coll-of ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports ::profile-id]
          :opt-un [::wait ::name ::skip-children]))

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [exports] :as params}]
  (let [exports (prepare-exports exports auth-token)]
    (if (and (= 1 (count exports))
             (= 1 (count (-> exports first :objects))))
      (handle-single-export exchange (-> params
                                         (assoc :export (first exports))
                                         (dissoc :exports)))
      (handle-multiple-export exchange (assoc params :exports exports)))))

(defn- handle-single-export
  [exchange {:keys [export wait profile-id name skip-children] :as params}]
  (let [topic       (str profile-id)
        resource    (rsc/create (:type export) (or name (:name export)))

        on-progress (fn [{:keys [path] :as object}]
                      (p/do
                        ;; Move the generated path to the resource
                        ;; path destination.
                        (sh/move! path (:path resource))

                        (when-not wait
                          (redis/pub! topic {:type :export-update
                                             :resource-id (:id resource)
                                             :status "running"
                                             :total 1
                                             :done 1})
                          (redis/pub! topic {:type :export-update
                                             :resource-id (:id resource)
                                             :filename (:filename resource)
                                             :name (:name resource)
                                             :status "ended"}))))
        on-error    (fn [cause]
                      (l/error :hint "unexpected error on export multiple"
                               :cause cause)
                      (if wait
                        (p/rejected cause)
                        (redis/pub! topic {:type :export-update
                                           :resource-id (:id resource)
                                           :status "error"
                                           :cause (ex-message cause)})))
        export      (assoc export :skip-children skip-children)
        proc        (-> (rd/render export on-progress)
                        (p/then (constantly resource))
                        (p/catch on-error))]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))

(defn- handle-multiple-export
  [exchange {:keys [exports wait profile-id name skip-children] :as params}]
  (let [resource    (rsc/create :zip (or name (-> exports first :name)))
        total       (count exports)
        topic       (str profile-id)

        on-progress (fn [{:keys [done]}]
                      (when-not wait
                        (let [data {:type :export-update
                                    :resource-id (:id resource)
                                    :status "running"
                                    :total total
                                    :done done}]
                          (redis/pub! topic data))))

        on-complete (fn []
                      (when-not wait
                        (let [data {:type :export-update
                                    :name (:name resource)
                                    :filename (:filename resource)
                                    :resource-id (:id resource)
                                    :status "ended"}]
                          (redis/pub! topic data))))

        on-error    (fn [cause]
                      (l/error :hint "unexpected error on multiple exportation" :cause cause)
                      (if wait
                        (p/rejected cause)
                        (redis/pub! topic {:type :export-update
                                           :resource-id (:id resource)
                                           :status "error"
                                           :cause (ex-message cause)})))

        zip         (rsc/create-zip :resource resource
                                    :on-complete on-complete
                                    :on-error on-error
                                    :on-progress on-progress)

        append      (fn [{:keys [filename path] :as object}]
                      (rsc/add-to-zip! zip path (str/replace filename sanitize-file-regex "_")))

        proc        (-> (p/do
                          (p/loop [exports (seq exports)]
                            (when-let [export (some-> (first exports)
                                                      (assoc :skip-children skip-children))]
                              (p/do
                                (rd/render export append)
                                (p/recur (rest exports)))))
                          (.finalize zip))
                        (p/then (constantly resource))
                        (p/catch on-error))]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))


(defn- assoc-file-name
  "A transducer that assocs a candidate filename and avoid duplicates."
  []
  (letfn [(find-candidate [params used]
            (loop [index 0]
              (let [candidate (str (:name params)
                                   (:suffix params "")
                                   (when (pos? index)
                                     (str/concat "-" (inc index)))
                                   (mime/get-extension (:type params)))]
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

(def ^:const ^:private
  default-partition-size 50)

(defn prepare-exports
  [exports token]
  (letfn [(process-group [group]
            (sequence (comp (partition-all default-partition-size)
                            (map process-partition))
                      group))

          (process-partition [[part1 :as part]]
            {:file-id (:file-id part1)
             :page-id (:page-id part1)
             :share-id (:share-id part1)
             :name    (:name part1)
             :token   token
             :type    (:type part1)
             :scale   (:scale part1)
             :objects (mapv part-entry->object part)})

          (part-entry->object [entry]
            {:id (:object-id entry)
             :filename (:filename entry)
             :name (:name entry)
             :suffix (:suffix entry)})]

    (let [xform (comp
                 (map #(assoc % :token token))
                 (assoc-file-name))]
      (->> (sequence xform exports)
           (d/group-by (juxt :scale :type))
           (map second)
           (into [] (mapcat process-group))))))
