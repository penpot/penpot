;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.binfile.common
  "A binfile related file processing common code, used for different
  binfile format implementations and management rpc methods."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.tokens :as cfo]
   [app.common.files.validate :as fval]
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.common.weak :as weak]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.features.file-migrations :as fmigr]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media.svg :as svg]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
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

;; Maximum size allowed for a single binary entry during binfile
;; import: 100MiB. Covers the storage blobs (`objects/` entries in v3,
;; streams in v1), whose declared size and hash are verified against the
;; imported bytes. Legitimate media objects fit comfortably below this;
;; anything larger is rejected instead of being buffered into memory.
(def ^:const default-max-binary-entry-size
  (* 1024 1024 100))

;; Maximum decompressed size allowed for a single JSON/text zip entry
;; (manifest, files, pages, shapes, colors, components, typographies,
;; tokens, plugin-data) during binfile import: 20MiB. Legitimate entries
;; are KB-sized, so this is deliberately much lower than
;; default-max-binary-entry-size and bounds the DEFLATE amplification of any
;; single entry.
(def ^:const default-max-text-entry-size
  (* 1024 1024 20))

;; Maximum total decompressed size allowed for all JSON/text zip entries
;; combined within a single import job: 500MiB. Bounds the case where many
;; entries, each individually under default-max-text-entry-size, still sum
;; to an unreasonable total. Set this high because legitimate giant
;; exports exist: hundreds of thousands of KB-sized entries (pages,
;; shapes) that never trip the per-entry cap but sum past 400MiB. The
;; per-entry cap still bounds single-entry DEFLATE amplification, so this
;; only moves the cumulative ceiling.
(def ^:const default-max-text-total-size
  (* 1024 1024 500))

;; Maximum number of entries allowed in the import zip: 500,000.
(def ^:const default-max-zip-entries
  (* 500 1000))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare get-resolved-file-libraries)
(declare update-file!)

(def file-attrs
  (sm/keys ctf/schema:file))

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
                  new-id (uuid/next)]
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

(defn- decode-row-features
  [{:keys [features] :as row}]
  (when row
    (cond-> row
      (db/pgarray? features) (assoc :features (db/decode-pgarray features #{})))))

(def sql:get-minimal-file
  "SELECT f.id,
          f.revn,
          f.modified_at,
          f.deleted_at
     FROM file AS f
    WHERE f.id = ?")

(defn get-minimal-file
  [cfg id & {:as opts}]
  (db/get-with-sql cfg [sql:get-minimal-file id] opts))

(def sql:files-with-data
  "SELECT f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.deleted_at,
          f.name,
          f.is_shared,
          f.has_media_trimmed,
          f.revn,
          f.data AS legacy_data,
          f.ignore_sync_until,
          f.comment_thread_seqn,
          f.features,
          f.version,
          f.vern,
          p.team_id,
          coalesce(fd.backend, 'legacy-db') AS backend,
          fd.metadata AS metadata,
          fd.storage_object_id AS storage_object_id,
          fd.data AS data
     FROM file AS f
     LEFT JOIN file_data AS fd ON (fd.file_id = f.id AND fd.id = f.id)
    INNER JOIN project AS p ON (p.id = f.project_id)")

(def sql:get-file
  (str sql:files-with-data " WHERE f.id = ?"))

(def sql:get-file-without-data
  "SELECT f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.deleted_at,
          f.name,
          f.is_shared,
          f.has_media_trimmed,
          f.revn,
          f.ignore_sync_until,
          f.comment_thread_seqn,
          f.features,
          f.version,
          f.vern,
          p.team_id
     FROM file AS f
    INNER JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?")

(defn- migrate-file
  "Apply the pending migrations to the file.

  With `persist-after-migration?` the migrated map is also persisted, which is
  only safe from a map read under the `file` row lock (see `get-file*`). The
  returned map is then the reattached one: decoded data where the pages the
  migration did not change keep their pointers and their fragment ids, plus
  the `:storage-object-id` of the row the write left, so a caller that
  persists again touches the current object. Without `persist-after-migration?`
  the realized map is returned, which is what a read-only context can do.
  `get-file*` realizes the result when the caller asks for it."
  [cfg {:keys [id] :as file} {:keys [persist-after-migration?]}]
  (fdata/with-tracked-pointers cfg id
    (let [libs                (delay (get-resolved-file-libraries cfg file))
          ;; Capture the pointer layout before realizing: the migration
          ;; runs on plain data, and unchanged positions must keep their
          ;; original fragment ids instead of being re-wrapped with new
          ;; ones on persist.
          original            (fdata/original-pointers (:data file))
          objects-map-before? (contains? (:features file) "fdata/objects-map")
          realized            (-> (fdata/realize cfg file)
                                  (fmg/migrate-file libs))
          objects-map-added?  (and (not objects-map-before?)
                                   (contains? (:features realized) "fdata/objects-map"))]

      (if persist-after-migration?
        (let [file    (if objects-map-added?
                        realized
                        (fdata/reattach-unchanged original realized))
              written (update-file! cfg file)]
          ;; When the file is migrated, we break the rule of not
          ;; performing mutations on get operations and update the file
          ;; with all migrations applied. The reattached map is also what
          ;; the read returns: the caller realizes it only when it asks.
          (assoc (fmigr/resolve-applied-migrations cfg file)
                 :storage-object-id (:storage-object-id written)))
        realized))))

(defn- fetch-row
  "Read the file row. With `load-data?` the file data columns come along, but
  they are not resolved nor decoded (see `attach-file-data`). With
  `load-migrations?` the applied migrations are resolved, which is what
  `need-migration?` expects. A skip-locked read answers nil instead of raising
  when the row is gone."
  [{:keys [::db/conn] :as cfg} id
   {:keys [include-deleted?
           skip-locked?
           lock-for-update?
           lock-for-share?
           throw-if-not-exists?
           load-migrations?
           load-data?]}]

  (let [sql  (if load-data?
               sql:get-file
               sql:get-file-without-data)
        sql  (cond
               lock-for-update?
               (str sql " FOR UPDATE of f")

               lock-for-share?
               (str sql " FOR SHARE of f")

               :else
               sql)

        sql  (if (and skip-locked? (or lock-for-share? lock-for-update?))
               (str sql " SKIP LOCKED")
               sql)

        file (db/get-with-sql conn [sql id] {::db/throw-if-not-exists false
                                             ::db/remove-deleted (not include-deleted?)})
        file (some-> file
                     (d/update-when :features db/decode-pgarray #{})
                     (d/update-when :metadata fdata/decode-metadata))
        file (if (and file load-migrations?)
               (fmigr/resolve-applied-migrations cfg file)
               file)]

    (or file
        (when-not (or skip-locked? (not throw-if-not-exists?))
          (ex/raise :type :not-found
                    :code :object-not-found
                    :hint "database object not found"
                    :table :file
                    :file-id id)))))


(defn- attach-file-data
  "Resolve the data of an already read row and decode it when `decode?`. It
  completes the map it receives instead of reading the row again. It does not
  realize: `get-file*` owns that step, once per path."
  [cfg file {:keys [decode?]}]
  (let [file (fdata/resolve-file-data cfg file)]
    (if decode?
      (fdata/decode-file-data cfg file)
      file)))

(defn- fetch-file
  "Read the row with its data resolved and decoded, under the requested lock."
  [cfg id options]
  (when-let [file (fetch-row cfg id options)]
    (attach-file-data cfg file options)))

(def ^:private
  default-get-file-options
  {:load-migrations? true
   :throw-if-not-exists? true
   :persist-after-migration? true
   :load-data? true
   :migrate? true
   :decode? true})

(defn- get-file*
  [{:keys [::db/conn] :as cfg} id options]

  (assert (db/connection? conn) "expected cfg with valid connection")

  (let [{:keys [migrate?
                realize?
                decode?
                load-data?
                lock-for-share?
                read-only?]
         :as options}
        (merge default-get-file-options options)

        read-only?
        (or read-only? (db/read-only? conn))]

    (when (and realize? (or (not load-data?) (not decode?)))
      (throw (IllegalArgumentException. "invalid options, you only can realize when loading data and decoding")))

    (when (and lock-for-share? load-data?)
      (throw (IllegalArgumentException. "invalid options, `:lock-for-share?` is only allowed on row-only reads")))

    (cond
      ;; The caller only wants the row: that read is the answer.
      (not load-data?)
      (fetch-row cfg id options)

      (and migrate? decode? (not read-only?))
      (when-let [file (fetch-row cfg id options)]
        (if (fmg/need-migration? file)
          (when-let [file (fetch-file cfg id (assoc options :lock-for-update? true))]
            (cond-> file
              (fmg/need-migration? file)
              (as-> $ (migrate-file cfg $ options))

              realize?
              (->> (fdata/realize cfg))))

          (let [file (attach-file-data cfg file options)]
            (cond-> file
              realize?
              (->> (fdata/realize cfg))))))

      :else
      (when-let [file (fetch-file cfg id options)]
        (cond-> file
          (and migrate? decode? (fmg/need-migration? file))
          (as-> $ (migrate-file cfg $ (assoc options :persist-after-migration? false)))

          realize?
          (->> (fdata/realize cfg)))))))

(defn get-file
  "Get file, resolve all features and apply migrations.

  Runs inside a transaction: it joins the caller's when the cfg already
  carries a connection and opens one otherwise, so the migration write
  commits with the read. Accepts a cfg map only: a bare pool or a
  connection raises.

  Useful when you have plan to apply massive or not surgical
  operations on file, because it removes the overhead of lazy fetching
  and decoding.

  Options:
  - `:realize?` returns plain data instead of fragment pointers; it
    requires `:load-data?` and `:decode?`.
  - `:decode?` decodes the stored blob (default true).
  - `:load-data?` reads the data columns (default true). A row-only read
    (`false`) is the only mode that accepts `:lock-for-share?`.
  - `:migrate?` applies the pending migrations on read (default true).
  - `:load-migrations?` resolves the applied migrations on a row read
    (default true).
  - `:lock-for-update?`, `:skip-locked?`, `:include-deleted?`,
    `:throw-if-not-exists?` and `:read-only?` are the usual read knobs.
  - `:persist-after-migration?` is internal: `get-file*` turns it off
    for the in-memory migration of a read-only context. Do not pass it."
  [cfg file-id & {:as opts}]
  (assert (map? cfg) "expected cfg map, not a pool or a connection")
  (db/tx-run! cfg get-file* file-id opts))

(defn clean-file-features
  [file]
  (update file :features (fn [features]
                           (if (set? features)
                             (-> features
                                 (cfeat/migrate-legacy-features)
                                 (set/difference cfeat/frontend-only-features)
                                 (set/difference cfeat/backend-only-features))
                             #{}))))

(defn check-file-exists
  [cfg id & {:keys [include-deleted?]
             :or {include-deleted? false}
             :as options}]
  (db/get-with-sql cfg [sql:get-minimal-file id]
                   {:db/remove-deleted (not include-deleted?)}))

(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    inner join file as f on (f.id = fpr.file_id)
    inner join project as p on (p.id = f.project_id)
    where fpr.file_id = ?
      and fpr.profile_id = ?
      and exists (select 1
                    from team_profile_rel as tpr
                   where tpr.team_id = p.team_id
                     and tpr.profile_id = fpr.profile_id)
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    inner join project as p on (p.id = ppr.project_id)
    where f.id = ?
      and ppr.profile_id = ?
      and exists (select 1
                    from team_profile_rel as tpr
                   where tpr.team_id = p.team_id
                     and tpr.profile_id = ppr.profile_id)")

(defn- get-file-permissions*
  [conn profile-id file-id]
  (when (and profile-id file-id)
    (db/exec! conn [sql:file-permissions
                    file-id profile-id
                    file-id profile-id
                    file-id profile-id])))

(defn get-file-permissions
  ([conn profile-id file-id]
   (let [rows     (get-file-permissions* conn profile-id file-id)
         is-owner (boolean (some :is-owner rows))
         is-admin (boolean (some :is-admin rows))
         can-edit (boolean (some :can-edit rows))]
     (when (seq rows)
       {:type :membership
        :is-owner is-owner
        :is-admin (or is-owner is-admin)
        :can-edit (or is-owner is-admin can-edit)
        :can-read true
        :is-logged (some? profile-id)})))

  ([conn profile-id file-id share-id]
   (let [perms  (get-file-permissions conn profile-id file-id)
         ldata  (some-> (db/get* conn :share-link {:id share-id :file-id file-id})
                        (dissoc :flags)
                        (update :pages db/decode-pgarray #{}))]

     ;; NOTE: in a future when share-link becomes more powerful and
     ;; will allow us specify which parts of the app is available, we
     ;; will probably need to tweak this function in order to expose
     ;; this flags to the frontend.
     (cond
       (some? perms) perms
       (some? ldata) {:type :share-link
                      :can-read true
                      :pages (:pages ldata)
                      :is-logged (some? profile-id)
                      :who-comment (:who-comment ldata)
                      :who-inspect (:who-inspect ldata)}))))


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
         (map decode-row-features))))

(defn get-team
  [cfg team-id]
  (-> (db/get cfg :team {:id team-id})
      (decode-row-features)))

(defn get-fonts
  [cfg team-id]
  (db/query cfg :team-font-variant
            {:team-id team-id
             :deleted-at nil}))

(def ^:private sql:get-files-rels
  "SELECT flr.*,
	      fls.synced_at
    FROM file_library_rel AS flr
    JOIN file AS l
      ON flr.library_file_id = l.id
    LEFT JOIN file_library_sync AS fls
      ON fls.file_id = flr.file_id
     AND fls.library_file_id = flr.library_file_id
   WHERE flr.file_id = ANY(?)
     AND l.deleted_at IS NULL
   ORDER BY flr.file_id, flr.library_file_id;")

(defn get-files-rels
  "Given a set of file-id's, return all matching relations with the libraries.
  Rows come back in stable `(file-id, library-file-id)` order so export
  and import resolve cycles deterministically."
  [cfg ids]

  (assert (set? ids) "expected a set of uuids")
  (assert (every? uuid? ids) "expected a set of uuids")

  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids (db/create-array conn "uuid" ids)]
                   (db/exec! conn [sql:get-files-rels ids])))))

(def ^:private sql:upsert-file-library-sync
  "INSERT INTO file_library_sync (file_id, library_file_id, synced_at)
   VALUES (?::uuid, ?::uuid, ?::timestamptz)
   ON CONFLICT (file_id, library_file_id)
   DO UPDATE SET synced_at = EXCLUDED.synced_at;")

(defn upsert-file-library-sync!
  [conn {:keys [file-id library-file-id synced-at]}]
  (db/exec-one! conn [sql:upsert-file-library-sync file-id library-file-id synced-at]))

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

(defn library-link-status
  "Check whether linking `file-id` to `library-id` is safe. Returns
  `:ok` when the link can be created, `:self-link` when both ids are
  the same, and `:circular` when the link would close a dependency
  cycle (i.e. `file-id` is already among the transitive libraries of
  `library-id`). Shared by the UI command (`link-file-to-library`,
  which raises) and the v3 importer (`import-file-relations`, which
  skips and logs), so both stay consistent."
  [cfg file-id library-id]
  (cond
    (= file-id library-id) :self-link
    (contains? (get-libraries cfg [library-id]) file-id) :circular
    :else :ok))

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
             :old-id (str old-id)
             :id (str (:id item)))
      (db/insert! conn :file-media-object item
                  {::db/return-keys false}))

    file))

(def sql:get-file-media
  "SELECT * FROM file_media_object WHERE id = ANY(?)")

(defn get-file-media*
  [{:keys [::db/conn] :as cfg} {:keys [data id] :as file}]
  (let [used (cfh/collect-used-media data)
        used (db/create-array conn "uuid" used)]
    (->> (db/exec! conn [sql:get-file-media used])
         (mapv (fn [row] (assoc row :file-id id))))))

(defn get-file-media
  [cfg file]
  (db/run! cfg get-file-media* file))

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

(defn absorb-unlinked-tokens-source
  "Make the file its own tokens source when its tokens source is not one
  of the given library-ids, as happens when the library was deleted. The
  tokens lib of the source is copied while the library still exists, even
  if deleted; otherwise the tokens source is removed and the file gets back
  the tokens status kept aside for its own tokens lib, if any."
  [cfg data library-ids]
  (let [source-id (cfo/get-tokens-source data)]
    (if (and (some? source-id)
             (not (contains? library-ids source-id)))
      (if-let [library (get-file cfg source-id
                                 :include-deleted? true
                                 :throw-if-not-exists? false)]
        (cfo/absorb-tokens-lib data (:data library))
        (cfo/detach-tokens-source data))
      data)))

(defn embed-assets
  [cfg data file-id]
  (let [library-ids (get-libraries cfg [file-id])
        data        (reduce (fn [data library-id]
                              (if-let [library (get-file cfg library-id :include-deleted? true)]
                                ;; The library is read without `:realize?`, so its
                                ;; `:components` may be a pointer map. Bind the
                                ;; library's own loader: the absorb walks the
                                ;; components and would raise without one.
                                (cond-> (fdata/with-pointers cfg library-id
                                          (ctf/absorb-assets data (:data library)))
                                  (= library-id (cfo/get-tokens-source data))
                                  (cfo/absorb-tokens-lib (:data library)))
                                data))
                            data
                            library-ids)]
    (absorb-unlinked-tokens-source cfg data library-ids)))

(def transaction-idle-timeout-ms
  "Idle-in-transaction guard for the long binfile transactions: 30
  minutes. The import and the export of a file run inside one
  transaction that can go quiet while the ZIP is decoded and the file
  data is realized. The longest operation observed in production is
  under 8 minutes, so this leaves room for bigger files and still
  aborts a connection that is left idle for half an hour."
  1800000)

(defn set-transaction-idle-timeout
  "Set `idle_in_transaction_session_timeout` for the current
  transaction. Must be called inside a transaction: `SET LOCAL` is
  scoped to it."
  [cfg]
  (let [conn (db/get-connection cfg)]
    (db/exec-one! conn [(str "SET LOCAL idle_in_transaction_session_timeout = "
                             transaction-idle-timeout-ms)])))

(defn configure-import-transaction
  [cfg]
  (set-transaction-idle-timeout cfg)
  (let [conn (db/get-connection cfg)]
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

(defn- encode-file
  [cfg {:keys [id features] :as file}]
  (let [file (if (and (contains? features "fdata/objects-map")
                      (:data file))
               (fdata/enable-objects-map file)
               file)

        file (if (and (contains? features "fdata/pointer-map")
                      (:data file))

               (binding [pmap/*tracked* (pmap/create-tracked :inherit true)]
                 (let [file (fdata/enable-pointer-map file)]
                   (fdata/persist-pointers! cfg id)
                   file))
               file)]

    (-> file
        (d/update-when :features into-array)
        (d/update-when :data blob/encode))))

(defn- file->params
  [file]
  (-> (select-keys file file-attrs)
      (assoc :data nil)
      (dissoc :team-id)
      (dissoc :metadata)
      (dissoc :migrations)))

(defn file->response
  "Strip the internal keys of a file map before it leaves the backend in a
  response. The file data storage pointer is bookkeeping for the write path:
  a `file-data` object is authentication-only, so a leaked id would let any
  logged-in profile fetch the blob from `/assets/by-id`. The metadata key of
  the old pointer is stripped too: a row the migration does not backfill keeps
  it, and the metadata schema is open, so it would travel in the response."
  [file]
  (-> file
      (dissoc :storage-object-id)
      (d/update-when :metadata #(dissoc % :storage-ref-id))))

(defn- file->file-data-params
  [{:keys [id] :as file}]
  (let [created-at  (or (:created-at file) (ct/now))
        modified-at (or (:modified-at file) created-at)]
    (-> (d/without-nils
         {:id id
          :type "main"
          :file-id id
          :data (:data file)
          :metadata (:metadata file)
          :created-at created-at
          :modified-at modified-at})
        ;; The storage object the persisted row pointed at (nil for a new or
        ;; imported file). `upsert!` touches it before replacing the row.
        (assoc :storage-object-id (:storage-object-id file)))))

(defn insert-file!
  "Insert a new file into the database table. Expectes a not-encoded file.
  Returns nil."
  [{:keys [::db/conn] :as cfg} file & {:as opts}]
  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

  (when (:migrations file)
    (fmigr/upsert-migrations! conn file))

  (let [file (encode-file cfg file)]
    (try
      (db/insert! conn :file
                  (file->params file)
                  (assoc opts ::db/return-keys false))
      (catch org.postgresql.util.PSQLException cause
        (if (db/duplicate-key-error? cause)
          (ex/raise :type :not-found
                    :code :object-not-found
                    :hint "file already exists"
                    :cause cause)
          (throw cause))))

    ;; An INSERT never replaces a persisted pointer: `duplicate-file` hands
    ;; the source file map here, and touching the source object would be a
    ;; write on a live object.
    (->> (file->file-data-params (dissoc file :storage-object-id))
         (fdata/upsert! cfg))

    nil))

(defn update-file!
  "Update an existing file on the database. Expects a not-encoded file map,
  as returned by `get-file`, and optionally the fields to change as keyword
  args; they are merged into the file map before persisting.

  The storage object the persisted row pointed at travels in the map
  (`:storage-object-id`), and `upsert!` touches it before replacing the row.
  That is why the caller must pass the map it read instead of building a
  partial one: nothing is queried here. The params only change mutable fields
  of the file: the pointer and the identity (`:id`, `:file-id`) always come
  from the read map.

  Returns the map `fdata/upsert!` answers with: `:written` and the
  `:storage-object-id` of the object this write created, so a caller that
  persists again touches the current object instead of the replaced one."
  [{:keys [::db/conn] :as cfg} file & {:as params}]

  ;; A map read with `:load-data? false` has no `:data`; persisting it would
  ;; wipe the file content. `ex/raise` instead of `assert`: the guard must
  ;; exist in the deployed binary, where `clojure.core/assert` is compiled
  ;; out.
  (when-not (contains? file :data)
    (ex/raise :type :internal
              :code :missing-file-data
              :hint "expected a file map read with data"
              :file-id (:id file)))

  (let [reset? (::reset-migrations? params false)
        ;; The pointer and the identity are bookkeeping of the persisted row,
        ;; not editable fields: they can only come from the read map. A pointer
        ;; in the params would skip the touch and leak the replaced blob; an
        ;; `:id` would retarget the UPDATE.
        file   (merge file (dissoc params ::reset-migrations?
                                   :storage-object-id
                                   :id
                                   :file-id))]

    (if reset?
      (fmigr/reset-migrations! conn file)
      (fmigr/upsert-migrations! conn file))

    (let [file        (encode-file cfg file)
          file-params (file->params (dissoc file :id))]

      (db/update! conn :file file-params
                  {:id (:id file)}
                  {::db/return-keys false})

      (fdata/upsert! cfg (file->file-data-params file)))))

(defn save-file!
  "Applies all the final validations and perist the file, binfile
  specific, should not be used outside of binfile domain.
  Returns nil"
  [{:keys [::timestamp] :as cfg} file & {:as opts}]

  (assert (ct/inst? timestamp) "expected valid timestamp")

  (let [file (-> file
                 (assoc :created-at timestamp)
                 (assoc :modified-at timestamp)
                 (assoc :ignore-sync-until (ct/plus timestamp (ct/duration {:seconds 5})))
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
		SELECT fl.*
		FROM file AS fl
		JOIN file_library_rel AS flr
		  ON flr.library_file_id = fl.id
		WHERE flr.file_id = ?::uuid

		UNION

		SELECT fl.*
		FROM file AS fl
		JOIN file_library_rel AS flr
		  ON flr.library_file_id = fl.id
		JOIN libs AS l
		  ON flr.file_id = l.id
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
		   l.is_shared,
		   l.version,
		   fls.synced_at,
		   NOT EXISTS (
		     SELECT 1 FROM file_library_rel AS direct
		      WHERE direct.file_id = ?::uuid
		        AND direct.library_file_id = l.id
		   ) AS is_indirect
	FROM libs AS l
	JOIN project AS p
	  ON p.id = l.project_id
	LEFT JOIN file_library_sync AS fls
	  ON fls.file_id = ?::uuid
	 AND fls.library_file_id = l.id
	WHERE l.deleted_at IS NULL;")

(defn get-file-libraries
  [conn file-id]
  (into []
        (map decode-row-features)
        (db/exec! conn [sql:get-file-libraries file-id file-id file-id])))

(defn get-resolved-file-libraries
  "Get all file libraries including itself. Returns an instance of
  LoadableWeakValueMap that allows do not have strong references to
  the loaded libraries and reduce memory pressure on having
  all this libraries at the same time on processing file validation
  or file migration.

  This still requires at least one library at time to be loaded while
  access to it is performed, but it improves considerable not having
  the need of loading all the libraries at the same time."
  [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
  (let [library-ids (->> (get-file-libraries conn (:id file))
                         (map :id)
                         (cons (:id file)))
        ;; Fragment rows are file-local and callers bind the loader of
        ;; the file they work on, so each library realizes its pointers
        ;; here, with its own id, before anything walks its data.
        load-fn     (fn [library-id]
                      (->> (get-file cfg library-id :migrate? false)
                           (fdata/realize-pointers cfg)))]
    (weak/loadable-weak-value-map library-ids load-fn {id file})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXTERNAL LIBRARY RESOLUTION HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn slugify-name
  "Slugify a library name for cross-environment matching.
  Lowercases, replaces non-alphanumeric runs with '-', strips
  leading/trailing '-'."
  [name]
  (str/slug name))

(def ^:private sql:get-files-names
  "SELECT id, name FROM file WHERE id = ANY(?)")

(defn get-files-names
  "Return [{:id uuid :name string}] for the given file ids."
  [cfg ids]
  (db/run! cfg
           (fn [{:keys [::db/conn]}]
             (let [ids-arr (db/create-array conn "uuid" ids)]
               (db/exec! conn [sql:get-files-names ids-arr])))))

(def ^:private sql:get-shared-files-for-team
  "SELECT f.id, f.name, f.project_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND f.is_shared = true
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL")

(defn get-shared-files-for-team
  "Return [{:id uuid :name string}] for all shared files in a team."
  [cfg team-id]
  (db/run! cfg
           (fn [{:keys [::db/conn]}]
             (db/exec! conn [sql:get-shared-files-for-team team-id]))))

(defn find-shared-files-by-slug
  "Return all shared files in `team-id` whose slugified name equals `slug`."
  [cfg team-id slug]
  (->> (get-shared-files-for-team cfg team-id)
       (filter #(= slug (slugify-name (:name %))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG IMPORT SANITIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def svg-content-type
  "Canonical content type of SVG media objects."
  "image/svg+xml")

(defn normalize-content-type
  "Canonicalize a stored content-type: lowercase, trimmed,
  parameters after `;` dropped. Returns nil for missing or unusable
  values so ancient bundle entries without content-type keep passing
  through untouched."
  [ctype]
  (when (string? ctype)
    (let [clean (-> ctype
                    (str/split #";" 2)
                    (first)
                    (str/trim)
                    (str/lower))]
      (when-not (str/empty? clean)
        clean))))

(defn svg-object?
  "True when the storage `object` claims the canonical SVG content
  type. Expects an already normalized object (see
  `normalize-content-type`): every import path normalizes the
  metadata right after reading it, so the comparison stays an exact
  match in a single place."
  [object]
  (= svg-content-type (:content-type object)))

(def schema:content-type
  "Typed content-type for binfile storage objects: a member of
  `cm/storage-object-types`. Values are canonicalized with
  `normalize-content-type` before validation, so legacy spellings
  keep importing while unknown types are rejected."
  [::sm/one-of {:format :string} cm/storage-object-types])

(defn check-storage-content-type
  "Check an imported storage `object` (already normalized, see
  `normalize-content-type`) against `cm/storage-object-types`.
  Returns nil when the type is allowed; raises `:type :validation`
  with `:code :media-type-not-allowed` (same as the upload path)
  when it is missing or unknown, so crafted bundles fail closed."
  [object]
  (when-not (contains? cm/storage-object-types (:content-type object))
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint "storage object declares an unknown content-type"
              :content-type (:content-type object))))

(defn sanitize-imported-svg
  "Sanitize the raw `bytes` of an imported storage `object` when it
  holds an SVG document. Expects an already normalized object (see
  `normalize-content-type`); returns nil when the object is not an
  SVG.

  Otherwise returns a map with the sanitized `:bytes`, their `:size`
  and their blake2b `:hash`, ready to persist with `sto/put-object!`.

  Raises a `:validation` exception when the SVG cannot be parsed, the
  same error the upload path reports."
  [object ^bytes raw]
  (when (svg-object? object)
    (let [sanitized (svg/sanitize-svg (String. ^bytes raw "UTF-8"))
          bytes     (.getBytes ^String sanitized "UTF-8")]
      {:bytes bytes
       :size  (alength ^bytes bytes)
       :hash  (sto/calculate-hash (java.io.ByteArrayInputStream. bytes))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REQUEST CFG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolve-export-type
  "The canonical export type of a request on the legacy RPC: an explicit
  `type` wins, and the deprecated boolean flags are translated. The
  creation command of a job does not need it, because it asks the caller
  for the type instead of guessing it."
  [{:keys [type include-libraries embed-assets]}]
  (cond
    (some? type)              type
    (true? include-libraries) :include-libraries
    (true? embed-assets)      :merge-libraries
    :else                     :detach-libraries))

(defn export-cfg
  "The domain cfg of an export: the frozen set of files, how their
  libraries are handled, and where the package is written."
  [cfg {:keys [file-ids export-type output]}]
  (-> cfg
      (assoc ::file-ids file-ids)
      (assoc ::export-type export-type)
      (assoc ::output output)))

(defn import-cfg
  "The domain cfg of an import: the destination team/profile/project, the
  name, the input and the anti-zip-bomb limits. The caller resolves the
  team, because what allows it to import there differs between the RPC and
  a job; what both must share is fixed here."
  [cfg {:keys [profile-id project-id team name input]}]
  (-> cfg
      (assoc ::features (cfeat/get-team-enabled-features cf/flags team))
      (assoc ::project-id project-id)
      (assoc ::profile-id profile-id)
      (assoc ::team-id (:id team))
      (assoc ::name name)
      (assoc ::input input)
      (assoc ::import-max-binary-entry-size (cf/get :binfile-import-max-binary-entry-size))
      (assoc ::import-max-text-entry-size (cf/get :binfile-import-max-text-entry-size))
      (assoc ::import-max-text-total-size (cf/get :binfile-import-max-text-total-size))
      (assoc ::import-max-zip-entries (cf/get :binfile-import-max-zip-entries))))
