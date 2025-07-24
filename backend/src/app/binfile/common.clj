;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.common
  "A binfile related file processing common code, used for different
  binfile format implementations and management rpc methods."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as fval]
   [app.common.logging :as l]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as feat.fdata]
   [app.features.file-migrations :as feat.fmigr]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]))

(set! *warn-on-reflection* true)

(def ^:dynamic *state* nil)
(def ^:dynamic *options* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Threshold in MiB when we pass from using
;; in-memory byte-array's to use temporal files.
(def temp-file-threshold
  (* 1024 1024 2))

;; A maximum (storage) object size allowed: 100MiB
(def ^:const max-object-size
  (* 1024 1024 100))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare get-resolved-file-libraries)

(def file-attrs
  #{:id
    :name
    :migrations
    :features
    :project-id
    :is-shared
    :version
    :data})

(defn parse-file-format
  [template]
  (assert (fs/path? template) "expected InputStream for `template`")

  (with-open [^java.lang.AutoCloseable input (io/input-stream template)]
    (let [buffer (byte-array 4)]
      (io/read-to-buffer input buffer)
      (if (and (= (aget buffer 0) 80)
               (= (aget buffer 1) 75)
               (= (aget buffer 2) 3)
               (= (aget buffer 3) 4))
        :binfile-v3
        :binfile-v1))))

(def xf-map-id
  (map :id))

(def xf-map-media-id
  (comp
   (mapcat (juxt :media-id
                 :thumbnail-id
                 :woff1-file-id
                 :woff2-file-id
                 :ttf-file-id
                 :otf-file-id))
   (filter uuid?)))

(def into-vec
  (fnil into []))

(def conj-vec
  (fnil conj []))

(defn initial-state
  []
  {:storage-objects #{}
   :files #{}
   :teams #{}
   :projects #{}})

(defn collect-storage-objects
  [state items]
  (update state :storage-objects into xf-map-media-id items))

(defn collect-summary
  [state key items]
  (update state key into xf-map-media-id items))

(defn lookup-index
  [id]
  (when id
    (let [val (get-in @*state* [:index id])]
      (l/trc :fn "lookup-index" :id (str id) :result (some-> val str) ::l/sync? true)
      (or val id))))

(defn remap-id
  [item key]
  (cond-> item
    (contains? item key)
    (update key lookup-index)))

(defn- index-object
  [index obj & attrs]
  (reduce (fn [index attr-fn]
            (let [old-id (attr-fn obj)
                  new-id (if (::overwrite *options*) old-id (uuid/next))]
              (assoc index old-id new-id)))
          index
          attrs))

(defn update-index
  ([coll]
   (update-index {} coll identity))
  ([index coll]
   (update-index index coll identity))
  ([index coll attr]
   (reduce #(index-object %1 %2 attr) index coll)))

(defn decode-row
  [{:keys [data changes features] :as row}]
  (when row
    (cond-> row
      features (assoc :features (db/decode-pgarray features #{}))
      changes  (assoc :changes (blob/decode changes))
      data     (assoc :data (blob/decode data)))))


(defn decode-file
  "A general purpose file decoding function that resolves all external
  pointers, run migrations and return plain vanilla file map"
  [cfg {:keys [id] :as file} & {:keys [migrate?] :or {migrate? true}}]
  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
    (let [file (->> file
                    (feat.fmigr/resolve-applied-migrations cfg)
                    (feat.fdata/resolve-file-data cfg))
          libs (delay (get-resolved-file-libraries cfg file))]

      (-> file
          (update :features db/decode-pgarray #{})
          (update :data blob/decode)
          (update :data feat.fdata/process-pointers deref)
          (update :data feat.fdata/process-objects (partial into {}))
          (update :data assoc :id id)
          (cond-> migrate? (fmg/migrate-file libs))))))

(defn get-file
  "Get file, resolve all features and apply migrations.

  Usefull when you have plan to apply massive or not cirurgical
  operations on file, because it removes the ovehead of lazy fetching
  and decoding."
  [cfg file-id & {:as opts}]
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (some->> (db/get* conn :file {:id file-id}
                                   (assoc opts ::db/remove-deleted false))
                          (decode-file cfg)))))

(defn clean-file-features
  [file]
  (update file :features (fn [features]
                           (if (set? features)
                             (-> features
                                 (cfeat/migrate-legacy-features)
                                 (set/difference cfeat/frontend-only-features)
                                 (set/difference cfeat/backend-only-features))
                             #{}))))

(defn get-project
  [cfg project-id]
  (db/get cfg :project {:id project-id}))

(def ^:private sql:get-teams
  "SELECT t.* FROM team WHERE id = ANY(?)")

(defn get-teams
  [cfg ids]
  (let [conn (db/get-connection cfg)
        ids  (db/create-array conn "uuid" ids)]
    (->> (db/exec! conn [sql:get-teams ids])
         (map decode-row))))

(defn get-team
  [cfg team-id]
  (-> (db/get cfg :team {:id team-id})
      (decode-row)))

(defn get-fonts
  [cfg team-id]
  (db/query cfg :team-font-variant
            {:team-id team-id
             :deleted-at nil}))

(defn get-files-rels
  "Given a set of file-id's, return all matching relations with the libraries"
  [cfg ids]

  (assert (set? ids) "expected a set of uuids")
  (assert (every? uuid? ids) "expected a set of uuids")

  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids (db/create-array conn "uuid" ids)
                       sql (str "SELECT flr.* FROM file_library_rel AS flr "
                                "  JOIN file AS l ON (flr.library_file_id = l.id) "
                                " WHERE flr.file_id = ANY(?) AND l.deleted_at IS NULL")]
                   (db/exec! conn [sql ids])))))

(def ^:private sql:get-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.id
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ANY(?)
    UNION
     SELECT fl.id
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT DISTINCT l.id
     FROM libs AS l")

(defn get-libraries
  "Get all libraries ids related to provided file ids"
  [cfg ids]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids' (db/create-array conn "uuid" ids)]
                   (->> (db/exec! conn [sql:get-libraries ids'])
                        (into #{} xf-map-id))))))

(defn get-file-object-thumbnails
  "Return all file object thumbnails for a given file."
  [cfg file-id]
  (->> (db/query cfg :file-tagged-object-thumbnail
                 {:file-id file-id
                  :deleted-at nil})
       (not-empty)))

(defn get-file-thumbnail
  "Return the thumbnail for the specified file-id"
  [cfg {:keys [id revn]}]
  (db/get* cfg :file-thumbnail
           {:file-id id
            :revn revn
            :data nil}
           {::sql/columns [:media-id :file-id :revn]}))

(def ^:private sql:get-missing-media-references
  "SELECT fmo.*
     FROM file_media_object AS fmo
    WHERE fmo.id = ANY(?::uuid[])
      AND file_id != ?")

(defn update-media-references!
  "Given a file and a coll of media-refs, check if all provided
  references are correct or fix them in-place"
  [{:keys [::db/conn] :as cfg} {file-id :id :as file} media-refs]
  (let [missing-index
        (reduce (fn [result {:keys [id] :as fmo}]
                  (assoc result id
                         (-> fmo
                             (assoc :id (uuid/next))
                             (assoc :file-id file-id)
                             (dissoc :created-at)
                             (dissoc :deleted-at))))
                {}
                (db/exec! conn [sql:get-missing-media-references
                                (->> (into #{} xf-map-id media-refs)
                                     (db/create-array conn "uuid"))
                                file-id]))

        lookup-index
        (fn [id]
          (if-let [mobj (get missing-index id)]
            (do
              (l/trc :hint "lookup index"
                     :file-id (str file-id)
                     :snap-id (str (:snapshot-id file))
                     :id (str id)
                     :result (str (get mobj :id)))
              (get mobj :id))

            id))

        update-shapes
        (fn [data {:keys [page-id shape-id]}]
          (d/update-in-when data [:pages-index page-id :objects shape-id] cfh/relink-refs lookup-index))

        file
        (update file :data #(reduce update-shapes % media-refs))]

    (doseq [[old-id item] missing-index]
      (l/dbg :hint "create missing references"
             :file-id (str file-id)
             :snap-id (str (:snapshot-id file))
             :old-id (str old-id)
             :id (str (:id item)))
      (db/insert! conn :file-media-object item
                  {::db/return-keys false}))

    file))

(def sql:get-file-media
  "SELECT * FROM file_media_object WHERE id = ANY(?)")

(defn get-file-media
  [cfg {:keys [data] :as file}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [used (cfh/collect-used-media data)
                       used (db/create-array conn "uuid" used)]
                   (db/exec! conn [sql:get-file-media used])))))

(def ^:private sql:get-team-files-ids
  "SELECT f.id FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?")

(defn get-team-files-ids
  "Get a set of file ids for the specified team-id"
  [{:keys [::db/conn]} team-id]
  (->> (db/exec! conn [sql:get-team-files-ids team-id])
       (into #{} xf-map-id)))

(def ^:private sql:get-team-projects
  "SELECT p.* FROM project AS p
    WHERE p.team_id = ?
      AND p.deleted_at IS NULL")

(defn get-team-projects
  "Get a set of project ids for the team"
  [cfg team-id]
  (->> (db/exec! cfg [sql:get-team-projects team-id])
       (into #{} xf-map-id)))

(def ^:private sql:get-project-files
  "SELECT f.id FROM file AS f
    WHERE f.project_id = ?
      AND f.deleted_at IS NULL")

(defn get-project-files
  "Get a set of file ids for the project"
  [{:keys [::db/conn]} project-id]
  (->> (db/exec! conn [sql:get-project-files project-id])
       (into #{} xf-map-id)))

(defn remap-thumbnail-object-id
  [object-id file-id]
  (str/replace-first object-id #"^(.*?)/" (str file-id "/")))

(defn- relink-shapes
  "A function responsible to analyze all file data and
  replace the old :component-file reference with the new
  ones, using the provided file-index."
  [data]
  (cfh/relink-refs data lookup-index))

(defn- relink-media
  "A function responsible of process the :media attr of file data and
  remap the old ids with the new ones."
  [media]
  (reduce-kv (fn [res k v]
               (let [id (lookup-index k)]
                 (if (uuid? id)
                   (-> res
                       (assoc id (assoc v :id id))
                       (dissoc k))
                   res)))
             media
             media))

(defn- relink-colors
  "A function responsible of process the :colors attr of file data and
  remap the old ids with the new ones."
  [colors]
  (reduce-kv (fn [res k v]
               (if (:image v)
                 (update-in res [k :image :id] lookup-index)
                 res))
             colors
             colors))

(defn embed-assets
  [cfg data file-id]
  (let [library-ids (get-libraries cfg [file-id])]
    (reduce (fn [data library-id]
              (if-let [library (get-file cfg library-id)]
                (ctf/absorb-assets data (:data library))
                data))
            data
            library-ids)))

(defn disable-database-timeouts!
  [cfg]
  (let [conn (db/get-connection cfg)]
    (db/exec-one! conn ["SET LOCAL idle_in_transaction_session_timeout = 0"])
    (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])))

(defn process-file
  [cfg {:keys [id] :as file}]
  (let [libs (delay (get-resolved-file-libraries cfg file))]
    (-> file
        (update :data (fn [fdata]
                        (-> fdata
                            (assoc :id id)
                            (dissoc :recent-colors))))
        (update :data (fn [fdata]
                        (-> fdata
                            (update :pages-index relink-shapes)
                            (update :components relink-shapes)
                            (update :media relink-media)
                            (update :colors relink-colors)
                            (d/without-nils))))
        (fmg/migrate-file libs)

        ;; NOTE: this is necessary because when we just creating a new
        ;; file from imported artifact or cloned file there are no
        ;; migrations registered on the database, so we need to persist
        ;; all of them, not only the applied
        (vary-meta dissoc ::fmg/migrated))))

(defn encode-file
  [{:keys [::db/conn] :as cfg} {:keys [id features] :as file}]
  (let [file (if (contains? features "fdata/objects-map")
               (feat.fdata/enable-objects-map file)
               file)

        file (if (contains? features "fdata/pointer-map")
               (binding [pmap/*tracked* (pmap/create-tracked)]
                 (let [file (feat.fdata/enable-pointer-map file)]
                   (feat.fdata/persist-pointers! cfg id)
                   file))
               file)]

    (-> file
        (update :features db/encode-pgarray conn "text")
        (update :data blob/encode))))

(defn get-params-from-file
  [file]
  (let [params {:has-media-trimmed (:has-media-trimmed file)
                :ignore-sync-until (:ignore-sync-until file)
                :project-id (:project-id file)
                :features (:features file)
                :name (:name file)
                :is-shared (:is-shared file)
                :version (:version file)
                :data (:data file)
                :id (:id file)
                :deleted-at (:deleted-at file)
                :created-at (:created-at file)
                :modified-at (:modified-at file)
                :revn (:revn file)
                :vern (:vern file)}]

    (-> (d/without-nils params)
        (assoc :data-backend nil)
        (assoc :data-ref-id nil))))

(defn insert-file!
  "Insert a new file into the database table"
  [{:keys [::db/conn] :as cfg} file & {:as opts}]
  (feat.fmigr/upsert-migrations! conn file)
  (let [params (-> (encode-file cfg file)
                   (get-params-from-file))]
    (db/insert! conn :file params opts)))

(defn update-file!
  "Update an existing file on the database."
  [{:keys [::db/conn ::sto/storage] :as cfg} {:keys [id] :as file} & {:as opts}]
  (let [file   (encode-file cfg file)
        params (-> (get-params-from-file file)
                   (dissoc :id))]

    ;; If file was already offloaded, we touch the underlying storage
    ;; object for properly trigger storage-gc-touched task
    (when (feat.fdata/offloaded? file)
      (some->> (:data-ref-id file) (sto/touch-object! storage)))

    (feat.fmigr/upsert-migrations! conn file)
    (db/update! conn :file params {:id id} opts)))

(defn save-file!
  "Applies all the final validations and perist the file, binfile
  specific, should not be used outside of binfile domain"
  [{:keys [::timestamp] :as cfg} file & {:as opts}]

  (assert (dt/instant? timestamp) "expected valid timestamp")

  (let [file (-> file
                 (assoc :created-at timestamp)
                 (assoc :modified-at timestamp)
                 (assoc :ignore-sync-until (dt/plus timestamp (dt/duration {:seconds 5})))
                 (update :features
                         (fn [features]
                           (-> (::features cfg #{})
                               (set/union features)
                               ;; We never want to store
                               ;; frontend-only features on file
                               (set/difference cfeat/frontend-only-features)))))]

    (when (contains? cf/flags :file-schema-validation)
      (fval/validate-file-schema! file))

    (when (contains? cf/flags :soft-file-schema-validation)
      (let [result (ex/try! (fval/validate-file-schema! file))]
        (when (ex/exception? result)
          (l/error :hint "file schema validation error" :cause result))))

    (insert-file! cfg file opts)))


(def ^:private sql:get-file-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ?::uuid
    UNION
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT l.id,
          l.features,
          l.project_id,
          p.team_id,
          l.created_at,
          l.modified_at,
          l.deleted_at,
          l.name,
          l.revn,
          l.vern,
          l.synced_at,
          l.is_shared
     FROM libs AS l
    INNER JOIN project AS p ON (p.id = l.project_id)
    WHERE l.deleted_at IS NULL OR l.deleted_at > now();")

(defn get-file-libraries
  [conn file-id]
  (into []
        (comp
         ;; FIXME: :is-indirect set to false to all rows looks
         ;; completly useless
         (map #(assoc % :is-indirect false))
         (map decode-row))
        (db/exec! conn [sql:get-file-libraries file-id])))

(defn get-resolved-file-libraries
  "A helper for preload file libraries"
  [{:keys [::db/conn] :as cfg} file]
  (->> (get-file-libraries conn (:id file))
       (into [file] (map #(get-file cfg (:id %))))
       (d/index-by :id)))
