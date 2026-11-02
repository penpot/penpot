;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.fdata
  "A `fdata/*` related feature migration helpers"
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
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
  "Ensure every page holds an ObjectsMap. Unloaded pointers are left
  untouched: the fragment they point to was written by an `encode-file`
  that already wrapped its objects, and loading it only to wrap it again
  costs one page read per untouched page on every save. A page whose
  fragment predates the feature is wrapped when it is first loaded or
  edited; a data migration that enables the feature must rewrite the
  data itself."
  [file & _opts]
  (let [update-page
        (fn [page]
          (if (and (pmap/pointer-map? page) (not (pmap/loaded? page)))
            page
            (update page :objects omap/wrap)))

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
  [cfg {:keys [storage-object-id] :as file}]
  (let [storage (sto/resolve cfg ::db/reuse-conn true)
        sobject (or (sto/get-object storage storage-object-id)
                    (ex/raise :type :internal
                              :code :missing-storage-object
                              :hint "file data row has no readable storage object"
                              :file-id (or (:file-id file) (:id file))
                              :id (:id file)
                              :storage-object-id storage-object-id))
        data    (->> (sto/get-object-bytes storage sobject))]
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
                          type, backend, metadata, data, storage_object_id)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

(def ^:private sql:upsert-file-data
  (str sql:insert-file-data
       " ON CONFLICT (file_id, id)
         DO UPDATE SET modified_at=?,
                       deleted_at=?,
                       backend=?,
                       metadata=?,
                       data=?,
                       storage_object_id=?"))

(defn- upsert-in-database
  [cfg {:keys [id file-id created-at modified-at deleted-at type backend data metadata storage-object-id]}]
  (let [created-at  (or created-at (ct/now))
        ;; The metadata pointer is dead (the column is the only source of
        ;; truth). Clean it here, the single point where every branch
        ;; serializes the metadata, so no write can resurrect it.
        metadata    (some-> metadata (dissoc :storage-ref-id) (db/json))
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
                       storage-object-id
                       modified-at
                       deleted-at
                       backend
                       metadata
                       data
                       storage-object-id])))

(defn- handle-persistence
  [cfg {:keys [type backend id file-id data] :as params}]

  (cond
    (= backend "storage")
    ;; IMPORTANT: we strongly do not reuse the main connection that can
    ;; run inside a transaction because the storage upload process can
    ;; fail in the middle of uploading and leave garbage on the underlying
    ;; backend, if we participate in the main transaction and it aborts
    ;; we will lose all registry of the pending to reconcile blobs
    ;; what the storage subsystem registers in other parallel
    ;; transaction
    (let [storage  (sto/resolve cfg)
          content  (sto/content data)
          sobject  (sto/put-object! storage
                                    {::sto/content content
                                     ::sto/touch true
                                     :bucket "file-data"
                                     :content-type "application/octet-stream"
                                     :file-id file-id
                                     :id id})
          params   (-> params
                       (assoc :storage-object-id (:id sobject))
                       (assoc :data nil))]
      (upsert-in-database cfg params))

    (= backend "db")
    (upsert-in-database cfg (assoc params :storage-object-id nil))

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
        ;; every mutation of `file_change` moves `updated_at`: the partition
        ;; runbook uses it as the watermark of the delta passes
        (db/update! cfg :file-change
                    {:data data
                     :updated-at (ct/now)}
                    {:file-id file-id :id id}
                    {::db/return-keys false}))

      (= type "fragment")
      (upsert-in-database cfg
                          (-> (dissoc params :metadata)
                              (assoc :backend "db")
                              (assoc :storage-object-id nil)))

      :else
      (throw (RuntimeException. "not implemented")))

    :else
    (throw (IllegalArgumentException.
            (str "backend '" backend "' not supported")))))

(defn touch-storage-object
  "Mark a storage object as touched so the storage GC can reclaim it once
  nothing references it anymore. Used when a file data row replaces or
  deletes the object it pointed at. Expects a non-nil object id; callers
  guard with `some->`/`some->>`."
  [cfg storage-object-id]
  (-> (sto/resolve cfg ::db/reuse-conn true)
      (sto/touch-object! storage-object-id)))

(defn- default-backend
  [backend]
  (or backend (cf/get :file-data-backend)))

(defn decode-metadata
  [metadata]
  (some-> metadata
          (db/decode-json-pgobject)
          (ctf/decode-file-metadata)))

(def ^:private schema:update-params
  [:map {:closed true}
   [:id ::sm/uuid]
   [:type [:enum "main" "snapshot" "fragment"]]
   [:file-id ::sm/uuid]
   [:backend {:optional true} [:enum "db" "legacy-db" "storage"]]
   [:metadata {:optional true} ctf/schema:file-metadata]
   ;; The storage object the persisted row pointed at before this write; it is
   ;; the object to touch when the row replaces it. Required so a new caller
   ;; must decide it explicitly (nil for a new row) instead of leaking it.
   [:storage-object-id [:maybe ::sm/uuid]]
   [:data {:optional true} bytes?]
   [:created-at {:optional true} ::ct/inst]
   [:modified-at {:optional true} [:maybe ::ct/inst]]
   [:deleted-at {:optional true} [:maybe ::ct/inst]]])

(def ^:private check-update-params
  (sm/check-fn schema:update-params :hint "invalid params received for update"))

(defn upsert!
  "Create or update a file data row.

  `:storage-object-id` is the storage object the persisted row pointed at
  before this write (nil for a new row or a row that had none), not the
  object the new content will live in: the write resolves that itself. The
  previous object is marked as touched before the row is replaced, so the
  storage GC can reclaim the blob once nothing references it.

  The function does not query the row: the caller must hand the pointer it
  read. Returns true when a row was written."
  [cfg params & {:as opts}]
  (let [params (-> (check-update-params params)
                   (update :backend default-backend))]

    (some->> (:storage-object-id params)
             (touch-storage-object cfg))

    (-> (handle-persistence cfg params)
        (db/get-update-count)
        (pos?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POINTER-MAP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-pointer
  "A database loader pointer helper"
  [cfg file-id id]
  ;; Keep the include-marked behavior explicit: `db/get*` only filters
  ;; rows by `deleted_at` when that column is selected, and a fragment a
  ;; previous pass marked must still load until `objects-gc` purges it.
  (let [fragment (some-> (db/get* cfg :file-data
                                  {:id id :file-id file-id :type "fragment"}
                                  {::sql/columns [:data :backend :file_id :id :metadata :storage_object_id]
                                   ::db/remove-deleted false})
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
        ;; A modified pointer has a fresh id, so the fragment row is always a
        ;; new insert: there is no previous storage object to touch.
        (upsert! cfg {:id id
                      :file-id file-id
                      :type "fragment"
                      :storage-object-id nil
                      :data content})))))

(defmacro with-pointers
  "Bind the fragment loader for `file-id` around `body`. All pointer
  derefs must happen inside the body; a pointer derefed outside of it
  raises because there is no loader bound."
  [cfg file-id & body]
  `(binding [pmap/*load-fn* (partial load-pointer ~cfg ~file-id)]
     ~@body))

(defmacro with-tracked
  "Bind a fresh tracked set around `body`, without a fragment loader.
  For write paths that create pointers before any file exists to load
  from (e.g. file creation): wrapping tracks new pointers for
  persistence, while any deref still fails loudly."
  [& body]
  `(binding [pmap/*tracked* (pmap/create-tracked)]
     ~@body))

(defmacro with-untracked
  "Run `body` with pointer tracking disabled. For validation passes
   that must not track anything for persistence: pointers created
   inside are not collected, and derefs still go through the bound
   loader when there is one."
  [& body]
  `(binding [pmap/*tracked* nil]
     ~@body))

(defmacro with-tracked-pointers
  "Bind a fresh tracked set plus the fragment loader for `file-id`
  around `body`. For write paths that create or modify pointers."
  [cfg file-id & body]
  `(binding [pmap/*tracked* (pmap/create-tracked)
             pmap/*load-fn* (partial load-pointer ~cfg ~file-id)]
     ~@body))

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
  plain data. Accepts a file map (arity 1) or raw file data with its
  file id (arity 2): a snapshot map carries the snapshot id, so always
  pass the live file id explicitly."
  ([cfg {:keys [id] :as file}]
   (update file :data #(realize-pointers cfg id %)))
  ([cfg file-id data]
   (with-pointers cfg file-id
     (process-pointers data deref))))

(defn get-used-pointer-ids
  "Given a file data map, return all pointer ids used in the data.
  This is a plain readout with no liveness meaning of its own: file-gc
  unions it over the main file and every unpurged snapshot (see
  `app.tasks.file-gc/clean-fragments!` for the actual liveness rule)."
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

(defn realize-entry
  "Realize a file data entry (a page or the components map) to plain
  data for value comparison: deref pointers and convert objects-maps
  with the same `into {}` production uses. Requires *load-fn* bound."
  [entry]
  (let [entry (if (pmap/pointer-map? entry) (deref entry) entry)]
    (if (and (map? entry) (contains? entry :objects))
      (let [objects (:objects entry)]
        (assoc entry :objects (if (omap/objects-map? objects)
                                (into {} objects)
                                objects)))
      entry)))

(defn original-pointers
  "Snapshot the current pointer layout of file data, mapping each
  pointer position to its PointerMap. No loading involved."
  [fdata]
  {:root  (into {} (filter (fn [[_ v]] (pmap/pointer-map? v))) fdata)
   :pages (into {} (filter (fn [[_ v]] (pmap/pointer-map? v)))
                (:pages-index fdata {}))})

(defn reattach-unchanged
  "Put back the original fragment pointers wherever the current value
  is value-equal to the stored fragment, so unchanged positions keep
  their ids and no new fragment rows are persisted for them. `current`
  may be a PointerMap (file-gc) or a plain page (the migration read
  path); `orig` always comes from `original-pointers`. Anything added,
  removed or actually changed keeps the current value. Requires
  *load-fn* bound."
  [{:keys [root pages]} file]
  (letfn [(reattach [current orig]
            (if (or (identical? current orig)
                    (= (realize-entry current) (realize-entry orig)))
              orig
              current))
          (reattach-all [m origs]
            (reduce-kv (fn [m k orig]
                         (if (contains? m k)
                           (update m k reattach orig)
                           m))
                       m origs))]
    (update file :data (fn [data]
                         (cond-> (reattach-all data root)
                           (contains? data :pages-index)
                           (update :pages-index reattach-all pages))))))

