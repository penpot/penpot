;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.fdata
  "A `fdata/*` related feature migration helpers"
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.objects-map :as omap]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap.legacy]
   [app.util.pointer-map :as pmap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OBJECTS-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-objects
  "Apply a function to all objects-map on the file. Usualy used for convert
  the objects-map instances to plain maps"
  [fdata update-fn]
  (if (contains? fdata :pages-index)
    (update fdata :pages-index d/update-vals
            (fn [page]
              (update page :objects
                      (fn [objects]
                        (if (or (omap/objects-map? objects)
                                (omap.legacy/objects-map? objects))
                          (update-fn objects)
                          objects)))))
    fdata))


(defn realize-objects
  "Process a file and remove all instances of objects map realizing them
  to a plain data. Used in operation where is more efficient have the
  whole file loaded in memory or we going to persist it in an
  alterantive storage."
  [_cfg file]
  (update file :data process-objects (partial into {})))

(defn enable-objects-map
  [file & _opts]
  (let [update-page
        (fn [page]
          (update page :objects omap/wrap))

        update-data
        (fn [fdata]
          (update fdata :pages-index d/update-vals update-page))]

    (-> file
        (update :data update-data)
        (update :features conj "fdata/objects-map"))))

(defn disable-objects-map
  [file & _opts]
  (let [update-page
        (fn [page]
          (update page :objects #(into {} %)))

        update-data
        (fn [fdata]
          (update fdata :pages-index d/update-vals update-page))]

    (-> file
        (update :data update-data)
        (update :features disj "fdata/objects-map"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti resolve-file-data
  (fn [_cfg file] (get file :backend "legacy-db")))

(defmethod resolve-file-data "legacy-db"
  [_cfg {:keys [legacy-data] :as file}]
  (-> file
      (assoc :data legacy-data)
      (dissoc :legacy-data)))

(defmethod resolve-file-data "db"
  [_cfg file]
  (dissoc file :legacy-data))

(defmethod resolve-file-data "storage"
  [cfg {:keys [metadata] :as file}]
  (let [storage (sto/resolve cfg ::db/reuse-conn true)
        ref-id  (:storage-ref-id metadata)
        data    (->> (sto/get-object storage ref-id)
                     (sto/get-object-bytes storage))]
    (-> file
        (assoc :data data)
        (dissoc :legacy-data))))

(defn decode-file-data
  [_cfg {:keys [data] :as file}]
  (cond-> file
    (bytes? data)
    (assoc :data (blob/decode data))))

(def ^:private sql:insert-file-data
  "INSERT INTO file_data (file_id, id, created_at, modified_at, deleted_at,
                          type, backend, metadata, data)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")

(def ^:private sql:upsert-file-data
  (str sql:insert-file-data
       " ON CONFLICT (file_id, id)
         DO UPDATE SET modified_at=?,
                       deleted_at=?,
                       backend=?,
                       metadata=?,
                       data=?"))

(defn- upsert-in-database
  [cfg {:keys [id file-id created-at modified-at deleted-at type backend data metadata]}]
  (let [created-at  (or created-at (ct/now))
        metadata    (some-> metadata db/json)
        modified-at (or modified-at created-at)]

    (db/exec-one! cfg [sql:upsert-file-data
                       file-id id
                       created-at
                       modified-at
                       deleted-at
                       type
                       backend
                       metadata
                       data
                       modified-at
                       deleted-at
                       backend
                       metadata
                       data])))

(defn- handle-persistence
  [cfg {:keys [type backend id file-id data] :as params}]

  (cond
    (= backend "storage")
    (let [storage  (sto/resolve cfg)
          content  (sto/content data)
          sobject  (sto/put-object! storage
                                    {::sto/content content
                                     ::sto/touch true
                                     :bucket "file-data"
                                     :content-type "application/octet-stream"
                                     :file-id file-id
                                     :id id})
          metadata {:storage-ref-id (:id sobject)}
          params   (-> params
                       (assoc :metadata metadata)
                       (assoc :data nil))]
      (upsert-in-database cfg params))

    (= backend "db")
    (->> (dissoc params :metadata)
         (upsert-in-database cfg))

    (= backend "legacy-db")
    (cond
      (= type "main")
      (do
        (db/delete! cfg :file-data
                    {:id id :file-id file-id :type "main"}
                    {::db/return-keys false})
        (db/update! cfg :file
                    {:data data}
                    {:id file-id}
                    {::db/return-keys false}))

      (= type "snapshot")
      (do
        (db/delete! cfg :file-data
                    {:id id :file-id file-id :type "snapshot"}
                    {::db/return-keys false})
        (db/update! cfg :file-change
                    {:data data}
                    {:file-id file-id :id id}
                    {::db/return-keys false}))

      (= type "fragment")
      (upsert-in-database cfg
                          (-> (dissoc params :metadata)
                              (assoc :backend "db")))

      :else
      (throw (RuntimeException. "not implemented")))

    :else
    (throw (IllegalArgumentException.
            (str "backend '" backend "' not supported")))))

(defn process-metadata
  [cfg metadata]
  (when-let [storage-id (:storage-ref-id metadata)]
    (let [storage (sto/resolve cfg ::db/reuse-conn true)]
      (sto/touch-object! storage storage-id))))

(defn- default-backend
  [backend]
  (or backend (cf/get :file-data-backend)))

(def ^:private schema:metadata
  [:map {:title "Metadata"}
   [:storage-ref-id {:optional true} ::sm/uuid]])

(def decode-metadata-with-schema
  (sm/decoder schema:metadata sm/json-transformer))

(defn decode-metadata
  [metadata]
  (some-> metadata
          (db/decode-json-pgobject)
          (decode-metadata-with-schema)))

(def ^:private schema:update-params
  [:map {:closed true}
   [:id ::sm/uuid]
   [:type [:enum "main" "snapshot" "fragment"]]
   [:file-id ::sm/uuid]
   [:backend {:optional true} [:enum "db" "legacy-db" "storage"]]
   [:metadata {:optional true} [:maybe schema:metadata]]
   [:data {:optional true} bytes?]
   [:created-at {:optional true} ::ct/inst]
   [:modified-at {:optional true} [:maybe ::ct/inst]]
   [:deleted-at {:optional true} [:maybe ::ct/inst]]])

(def ^:private check-update-params
  (sm/check-fn schema:update-params :hint "invalid params received for update"))

(defn upsert!
  "Create or update file data"
  [cfg params & {:as opts}]
  (let [params (-> (check-update-params params)
                   (update :backend default-backend))]

    (some->> (:metadata params)
             (process-metadata cfg))

    (-> (handle-persistence cfg params)
        (db/get-update-count)
        (pos?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POINTER-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-pointer
  "A database loader pointer helper"
  [cfg file-id id]
  (let [fragment (some-> (db/get* cfg :file-data
                                  {:id id :file-id file-id :type "fragment"}
                                  {::sql/columns [:data :backend :id :metadata]})
                         (update :metadata decode-metadata))]

    (l/trc :hint "load pointer"
           :file-id (str file-id)
           :id (str id)
           :found (some? fragment))

    (when-not fragment
      (ex/raise :type :internal
                :code :fragment-not-found
                :hint "fragment not found"
                :file-id file-id
                :fragment-id id))

    (-> (resolve-file-data cfg fragment)
        (get :data)
        (blob/decode))))

(defn persist-pointers!
  "Persist all currently tracked pointer objects"
  [cfg file-id]
  (doseq [[id item] @pmap/*tracked*]
    (when (pmap/modified? item)
      (l/trc :hint "persist pointer" :file-id (str file-id) :id (str id))
      (let [content (-> item deref blob/encode)]
        (upsert! cfg {:id id
                      :file-id file-id
                      :type "fragment"
                      :data content})))))

(defn process-pointers
  "Apply a function to all pointers on the file. Usuly used for
  dereference the pointer to a plain value before some processing."
  [fdata update-fn]
  (let [update-fn' (fn [val]
                     (if (pmap/pointer-map? val)
                       (update-fn val)
                       val))]
    (-> fdata
        (d/update-vals update-fn')
        (update :pages-index d/update-vals update-fn'))))

(defn realize-pointers
  "Process a file and remove all instances of pointers realizing them to
  a plain data. Used in operation where is more efficient have the
  whole file loaded in memory."
  [cfg {:keys [id] :as file}]
  (binding [pmap/*load-fn* (partial load-pointer cfg id)]
    (update file :data process-pointers deref)))

(defn get-used-pointer-ids
  "Given a file, return all pointer ids used in the data."
  [fdata]
  (->> (concat (vals fdata)
               (vals (:pages-index fdata)))
       (into #{} (comp (filter pmap/pointer-map?)
                       (map pmap/get-id)))))

(defn enable-pointer-map
  "Enable the fdata/pointer-map feature on the file."
  [file & _opts]
  (-> file
      (update :data (fn [fdata]
                      (-> fdata
                          (update :pages-index d/update-vals pmap/wrap)
                          (d/update-when :components pmap/wrap))))
      (update :features conj "fdata/pointer-map")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn realize
  "A helper that combines realize-pointers and realize-objects"
  [cfg file]
  (->> file
       (realize-pointers cfg)
       (realize-objects cfg)))
