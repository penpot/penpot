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
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.path :as path]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.worker :as wrk]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OBJECTS-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn enable-objects-map
  [file & _opts]
  (let [update-page
        (fn [page]
          (if (and (pmap/pointer-map? page)
                   (not (pmap/loaded? page)))
            page
            (update page :objects omap/wrap)))

        update-data
        (fn [fdata]
          (update fdata :pages-index d/update-vals update-page))]

    (-> file
        (update :data update-data)
        (update :features conj "fdata/objects-map"))))

(defn process-objects
  "Apply a function to all objects-map on the file. Usualy used for convert
  the objects-map instances to plain maps"
  [fdata update-fn]
  (if (contains? fdata :pages-index)
    (update fdata :pages-index d/update-vals
            (fn [page]
              (update page :objects
                      (fn [objects]
                        (if (omap/objects-map? objects)
                          (update-fn objects)
                          objects)))))
    fdata))


(defn realize-objects
  "Process a file and remove all instances of objects mao realizing them
  to a plain data. Used in operation where is more efficient have the
  whole file loaded in memory or we going to persist it in an
  alterantive storage."
  [_cfg file]
  (update file :data process-objects (partial into {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POINTER-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-pointer
  "A database loader pointer helper"
  [cfg file-id id]
  (let [fragment (db/get* cfg :file-data
                          {:id id :file-id file-id :type "fragment"}
                          {::sql/columns [:content :backend :id]})]

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

    ;; FIXME: conditional thread scheduling for decoding big objects
    (blob/decode (:data fragment))))

(defn persist-pointers!
  "Persist all currently tracked pointer objects"
  [cfg file-id]
  (let [conn (db/get-connection cfg)]
    (doseq [[id item] @pmap/*tracked*]
      (when (pmap/modified? item)
        (l/trc :hint "persist pointer" :file-id (str file-id) :id (str id))
        (let [content (-> item deref blob/encode)]
          (db/insert! conn :file-data
                      {:id id
                       :file-id file-id
                       :type "fragment"
                       :content content}))))))

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
;; PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn enable-path-data
  "Enable the fdata/path-data feature on the file."
  [file & _opts]
  (letfn [(update-object [object]
            (if (or (cfh/path-shape? object)
                    (cfh/bool-shape? object))
              (update object :content path/content)
              object))

          (update-container [container]
            (d/update-when container :objects d/update-vals update-object))]

    (-> file
        (update :data (fn [data]
                        (-> data
                            (update :pages-index d/update-vals update-container)
                            (d/update-when :components d/update-vals update-container))))
        (update :features conj "fdata/path-data"))))

(defn disable-path-data
  [file & _opts]
  (letfn [(update-object [object]
            (if (or (cfh/path-shape? object)
                    (cfh/bool-shape? object))
              (update object :content vec)
              object))

          (update-container [container]
            (d/update-when container :objects d/update-vals update-object))]

    (when-let [conn db/*conn*]
      (db/delete! conn :file-migration {:file-id (:id file)
                                        :name "0003-convert-path-content"}))
    (-> file
        (update :data (fn [data]
                        (-> data
                            (update :pages-index d/update-vals update-container)
                            (d/update-when :components d/update-vals update-container))))
        (update :features disj "fdata/path-data")
        (update :migrations disj "0003-convert-path-content")
        (vary-meta update ::fmg/migrated disj "0003-convert-path-content"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn realize
  "A helper that combines realize-pointers and realize-objects"
  [cfg file]
  (->> file
       (realize-pointers cfg)
       (realize-objects cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti resolve-file-data
  (fn [_cfg file] (or (get file :backend) "db")))

(defmethod resolve-file-data "db"
  [_cfg {:keys [legacy-data data] :as file}]
  (if (and (some? legacy-data) (not data))
    (-> file
        (assoc :data legacy-data)
        (dissoc :legacy-data))
    (dissoc file :legacy-data)))

(defmethod resolve-file-data "storage"
  [cfg object]
  (let [storage (sto/resolve cfg ::db/reuse-conn true)
        ref-id  (-> object :metadata :storage-ref-id)
        data    (->> (sto/get-object storage ref-id)
                     (sto/get-object-bytes storage))]
    (-> object
        (assoc :data data)
        (dissoc :legacy-data))))

(defn decode-file-data
  [{:keys [::wrk/executor]} {:keys [data] :as file}]
  (cond-> file
    (bytes? data)
    (assoc :data (px/invoke! executor #(blob/decode data)))))

(def ^:private sql:insert-file-data
  "INSERT INTO file_data (file_id, id, created_at, modified_at,
                          type, backend, metadata, data)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?)")

(def ^:private sql:upsert-file-data
  (str sql:insert-file-data
       " ON CONFLICT (file_id, id)
         DO UPDATE SET modified_at=?,
                       backend=?,
                       metadata=?,
                       data=?;"))

(defn- create-in-database
  [cfg {:keys [id file-id created-at modified-at type backend data metadata]}]
  (let [metadata    (some-> metadata db/json)
        created-at  (or created-at (ct/now))
        modified-at (or modified-at created-at)]
    (db/exec-one! cfg [sql:insert-file-data
                       file-id id
                       created-at
                       modified-at
                       type
                       backend
                       metadata
                       data])))

(defn- upsert-in-database
  [cfg {:keys [id file-id created-at modified-at type backend data metadata]}]
  (let [metadata    (some-> metadata db/json)
        created-at  (or created-at (ct/now))
        modified-at (or modified-at created-at)]

    (db/exec-one! cfg [sql:upsert-file-data
                       file-id id
                       created-at
                       modified-at
                       type
                       backend
                       metadata
                       data
                       modified-at
                       backend
                       metadata
                       data])))

(defmulti ^:private handle-persistence
  (fn [_cfg params] (:backend params)))

(defmethod handle-persistence "db"
  [_ params]
  (dissoc params :metadata))

(defmethod handle-persistence "storage"
  [{:keys [::sto/storage] :as cfg}
   {:keys [id file-id data] :as params}]

  (let [content  (sto/content data)
        sobject  (sto/put-object! storage
                                  {::sto/content content
                                   ::sto/touch true
                                   :bucket "file-data"
                                   :content-type "application/octet-stream"
                                   :file-id file-id
                                   :id id})
        metadata {:storage-ref-id (:id sobject)}]
    (-> params
        (assoc :metadata metadata)
        (assoc :data nil))))

(defn- process-metadata
  [cfg metadata]
  (when-let [storage-id (:storage-ref-id metadata)]
    (let [storage (sto/resolve cfg ::db/reuse-conn true)]
      (sto/touch-object! storage storage-id))))

(defn- default-backend
  [backend]
  (or backend (cf/get :file-storage-backend "db")))

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
   [:type [:enum "main" "snapshot"]]
   [:file-id ::sm/uuid]
   [:backend {:optional true} [:enum "db" "storage"]]
   [:metadata {:optional true} [:maybe schema:metadata]]
   [:data {:optional true} bytes?]
   [:created-at {:optional true} ::ct/inst]
   [:modified-at {:optional true} ::ct/inst]])

(def ^:private check-update-params
  (sm/check-fn schema:update-params :hint "invalid params received for update"))

(defn update!
  [cfg params & {:keys [throw-if-not-exists?]}]
  (let [params (-> (check-update-params params)
                   (update :backend default-backend))]

    (some->> (:metadata params) (process-metadata cfg))
    (let [result (handle-persistence cfg params)
          result (if throw-if-not-exists?
                   (create-in-database cfg result)
                   (upsert-in-database cfg result))]
      (-> result db/get-update-count pos?))))

(defn create!
  [cfg params]
  (update! cfg params :throw-on-conflict? true))

(def ^:private schema:delete-params
  [:map {:closed true}
   [:id ::sm/uuid]
   [:type [:enum "main" "snapshot"]]
   [:file-id ::sm/uuid]])

(def check-delete-params
  (sm/check-fn schema:delete-params :hint "invalid params received for delete"))

(defn delete!
  [cfg params]
  (when-let [fdata (db/get* cfg :file-data
                            (check-delete-params params))]

    (some->> (get fdata :metadata)
             (decode-metadata)
             (process-metadata cfg))

    (-> (db/delete! cfg :file-data params)
        (db/get-update-count)
        (pos?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCRIPTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:get-unmigrated-files
  "SELECT f.id, f.data,
          row_number() OVER w AS index
     FROM file AS f
    WHERE f.data IS NOT NULL
   WINDOW w AS (order by f.modified_at ASC)
    ORDER BY f.modified_at ASC")

(defn migrate-to-storage
  "Migrate the current existing files to store data in new storage
  tables."
  [system]
  (let [timestamp (ct/now)]
    (db/tx-run! system
                (fn [{:keys [::db/conn]}]
                  (run! (fn [{:keys [id data index]}]
                          (l/dbg :hint "migrating file" :file-id (str id) :index index)
                          (db/update! conn :file {:data nil} {:id id} ::db/return-keys false)
                          (db/insert! conn :file-data
                                      {:backend "db"
                                       :metadata nil
                                       :type "main"
                                       :data data
                                       :created-at timestamp
                                       :modified-at timestamp
                                       :file-id id
                                       :id id}
                                      {::db/return-keys false}))
                        (db/plan conn [sql:get-unmigrated-files]))))))


(def sql:get-migrated-files
  "SELECT f.id, f.data,
          row_number() OVER w AS index
     FROM file_data AS f
    WHERE f.data IS NOT NULL
      AND f.id = f.file_id
   WINDOW w AS (order by f.id ASC)
    ORDER BY f.id ASC")

(defn rollback-from-storage
  "Migrate back to the file table storage."
  [system]
  (db/tx-run! system
              (fn [{:keys [::db/conn]}]
                (run! (fn [{:keys [id data index]}]
                        (l/dbg :hint "rollback file" :file-id (str id) :index index)
                        (db/update! conn :file {:data data} {:id id} ::db/return-keys false)
                        (db/delete! conn :file-data {:id id} ::db/return-keys false))
                      (db/plan conn [sql:get-migrated-files])))))




