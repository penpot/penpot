;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.tasks.file-gc
  "A maintenance task that is responsible of: purge unused file media,
  clean unused object thumbnails and remove old file thumbnails.  The
  file is eligible to be garbage collected after some period of
  inactivity (the default threshold is 72h)."
  (:require
   [app.binfile.cleaner :as bfl]
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as feat.fdata]
   [app.features.file-snapshots :as fsnap]
   [app.storage :as sto]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private sql:mark-file-media-object-deleted
  "UPDATE file_media_object
      SET deleted_at = ?
    WHERE file_id = ? AND id != ALL(?::uuid[])
   RETURNING id")

(def xf:collect-used-media
  (comp
   (map :data)
   (mapcat cfh/collect-used-media)))

(defn- process-file-media
  "Performs the garbage collection of file media objects."
  [{:keys [::db/conn ::timestamp] :as cfg} {:keys [id] :as file}]
  (let [used-media
        (fsnap/reduce-snapshots cfg id xf:collect-used-media conj #{})

        used-media
        (into used-media xf:collect-used-media [file])

        used-media
        (db/create-array conn "uuid" used-media)

        unused-media
        (->> (db/exec! conn [sql:mark-file-media-object-deleted timestamp id used-media])
             (into #{} (map :id)))]

    (doseq [id unused-media]
      (l/trc :obj "media-object"
             :file-id (str (:id file))
             :id (str id)))

    file))

(def ^:private sql:mark-file-object-thumbnails-deleted
  "UPDATE file_tagged_object_thumbnail
      SET deleted_at = ?
    WHERE file_id = ? AND object_id != ALL(?::text[])
   RETURNING object_id")

(defn- process-file-object-thumbnails
  [{:keys [::db/conn ::timestamp]} {:keys [data] :as file}]
  (let [file-id (:id file)
        using   (->> (vals (:pages-index data))
                     (into #{} (comp
                                (mapcat (fn [{:keys [id objects]}]
                                          (->> (ctt/get-frames objects)
                                               (map #(assoc % :page-id id)))))
                                (mapcat (fn [{:keys [id page-id]}]
                                          (list
                                           (thc/fmt-object-id file-id page-id id "frame")
                                           (thc/fmt-object-id file-id page-id id "component")))))))

        ids    (into-array String using)
        unused (->> (db/exec! conn [sql:mark-file-object-thumbnails-deleted timestamp file-id ids])
                    (into #{} (map :object-id)))]

    (doseq [object-id unused]
      (l/trc :obj "object-thumbnail"
             :file-id (str file-id)
             :id object-id))

    file))

(def ^:private sql:mark-file-thumbnails-deleted
  "UPDATE file_thumbnail
      SET deleted_at = ?
    WHERE file_id = ? AND revn < ?
   RETURNING revn")

(defn- process-file-thumbnails
  [{:keys [::db/conn ::timestamp]} {:keys [id revn] :as file}]
  (let [unused (->> (db/exec! conn [sql:mark-file-thumbnails-deleted timestamp id revn])
                    (into #{} (map :revn)))]

    (doseq [revn unused]
      (l/trc :obj "thumbnail"
             :file-id (str id)
             :revn revn))

    file))

(def ^:private sql:get-files-for-library
  "SELECT f.id
     FROM file AS f
     LEFT JOIN file_library_rel AS fl ON (fl.file_id = f.id)
    WHERE fl.library_file_id = ?
      AND f.deleted_at IS null
    ORDER BY f.modified_at ASC")

(defn- get-used-components
  "Given a file and a set of components marked for deletion, return a
  filtered set of component ids that are still un use"
  [components library-id {:keys [data]}]
  (filter #(ctf/used-in? data library-id % :component) components))

(defn- get-library-user-ids
  "Materialize the rows of the files using this one as a library with
  a plain query (no cursor), so no write transaction needs to stay
  open while their file data is loaded."
  [cfg library-id]
  (db/exec! cfg [sql:get-files-for-library library-id]))

(defn- find-used-components
  "Compute the set of deleted component ids still in use by library
  users. Every user file is loaded on its own connection, outside any
  write transaction. Skips the scan entirely when there is nothing
  deleted to collect."
  [cfg file-id deleted-components]
  (if (seq deleted-components)
    (let [file-xform    (mapcat (partial get-used-components deleted-components file-id))
          library-xform (comp
                         (map :id)
                         ;; Sanitize on load like the read path does:
                         ;; instance-of? compares with =, so text ids
                         ;; would miss real uses and delete live data.
                         (map #(-> (bfc/get-file cfg % :realize? true :read-only? true)
                                   (update :data ctf/sanitize-file-data)))
                         file-xform)]
      (->> (get-library-user-ids cfg file-id)
           (transduce library-xform conj #{})))
    #{}))

(defn- process-deleted-components
  "Performs the garbage collection of unreferenced deleted components.
  Takes the set of component ids still used by library users and the
  deleted components, both computed beforehand outside any write
  transaction. The revn guard protects against changes to this file,
  but not against a library user starting to use a deleted component
  between the scan and the write: that race pre-dates this refactor
  and is accepted here."
  [used-remote deleted-components {:keys [id] :as file}]
  (let [file-id id
        file-xform
        (mapcat (partial get-used-components deleted-components file-id))

        used-local
        (into #{} file-xform [file])

        unused
        (transduce bfc/xf-map-id disj
                   (into #{} bfc/xf-map-id deleted-components)
                   (concat used-remote used-local))

        file
        (update file :data
                (fn [data]
                  (reduce (fn [data id]
                            (l/trc :obj "component"
                                   :file-id (str file-id)
                                   :id (str id))
                            (ctkl/delete-component data id))
                          data
                          unused)))]

    file))

(def ^:private sql:mark-deleted-data-fragments
  "UPDATE file_data
      SET deleted_at = ?
    WHERE file_id = ?
      AND id != ALL(?::uuid[])
      AND type = 'fragment'
      AND deleted_at IS NULL
   RETURNING id")

(def ^:private xf:collect-pointers
  (comp (map :data)
        (mapcat feat.fdata/get-used-pointer-ids)))

(defn- process-fragments
  [{:keys [::db/conn ::timestamp]} {:keys [id] :as file}]
  (let [used   (into #{} xf:collect-pointers [file])
        unused (->> (db/exec! conn [sql:mark-deleted-data-fragments timestamp id
                                    (db/create-array conn "uuid" used)])
                    (into #{} bfc/xf-map-id))]

    (doseq [fragment-id unused]
      (l/trc :obj "fragment"
             :file-id (str (:id file))
             :id (str fragment-id)))

    file))

(defn- process-media
  [cfg used-remote deleted-components file]
  (let [file (->> file
                  (process-deleted-components used-remote deleted-components)
                  (process-file-media cfg)
                  (process-file-thumbnails cfg)
                  (process-file-object-thumbnails cfg))]
    ;; Write guard (kept alongside the fast-fail gate in repair-file):
    ;; the snapshot about to be persisted must be schema-valid, no
    ;; matter what the repair produced.
    (cfv/validate-file-schema! file)
    file))

(defn- get-file
  "Plain read of the file: no row lock, no writes. Sanitizes first so
  legacy migrations do not choke on unconverted types, then migrates
  in memory and checks the scheduled revn against the stored one.
  This sanitize is the single canonical pass on the GC path: repair
  and cleaning downstream work on already-sanitized data, so
  bfl/clean-file deliberately does not sanitize again. Returns a map
  with the migrated file and the (delayed, lazily realized) resolved
  libraries, or nil when the file is gone or the revn does not match.
  Resolving once here and reusing the map downstream avoids running
  the recursive library query twice per file. Migrations apply in
 memory on this read and persist with the write transaction
 whenever the row lock commits, even on no-change runs that only
 store the trimmed mark. Also returns the decoded pre-sanitize
 :data as :orig-data, so the write phase can tell a real change
 from a no-op."
  [cfg {:keys [file-id] :as props}]
  (when-let [file (bfc/get-file cfg file-id
                                :realize? true
                                :migrate? false
                                :throw-if-not-exists? false)]
    ;; We should ensure that the scheduled file and the procesing file
    ;; has not changed since schedule, for this reason we check the
    ;; revn from props with the revn from retrieved file from database.
    ;; The check runs before sanitizing and migrating, so a stale
    ;; scheduled revn skips that work instead of discarding it.
    ;; TODO(2.20): drop the nil-revn tolerance once task props always
    ;; carry revn (enforced upstream in develop); every caller already
    ;; passes it through run-file-gc!/repair-file!.
    (when (or (nil? (:revn props)) (= (:revn props) (:revn file)))
      (let [libs (delay (bfc/get-resolved-file-libraries cfg file))
            orig-data (:data file)
            ;; Migrate only when pending: without diff, migrate ends
            ;; in a full schema walk plus legacy feature migration.
            ;; Sanitize above stays unconditional: validation and
            ;; repair need it in memory.
            file (cond-> (update file :data ctf/sanitize-file-data)
                   (fmg/need-migration? file)
                   (fmg/migrate-file libs))]
        {:file file
         :libs libs
         :orig-data orig-data}))))

(defn- repair-file
  "In-memory repair of a file snapshot: run the semantic repair loop
  when validation reports errors and check the schema of the result.
  Takes no lock and writes nothing; the write transaction persists
  the snapshot later. A file that is still invalid after the repair
  (by schema or by semantics) never reaches the write phase: the
  schema gate and the no-convergence gate below raise instead, so it
  is never marked as cleaned and a human can fix the file by hand.
  Returns the repaired file."
  [file libs]
  ;; Libraries resolve lazily through the weak map;
  ;; each one realizes on load with its own loader,
  ;; so validation needs no ambient *load-fn*.
  (let [file (cfr/repair-loop (bfl/clean-file file) libs)]
    ;; Fast-fail gate (kept alongside the write guard in
    ;; process-media): a file that is still schema-invalid after the
    ;; repair never pays the expensive library-user scan.
    (cfv/validate-file-schema! file)
    ;; No-convergence gate: the repair loop returns the input
    ;; unchanged when it cannot make the file clean (see
    ;; cfr/repair-loop), so semantic errors may still remain. A file
    ;; like that must not be stored as clean: raise with the codes
    ;; so the handler classifies the run as :unfixable, the file
    ;; stays unmarked and the scheduler retries it later.
    ;; Semantic re-validation costs one extra validation pass per
    ;; run: accepted in exchange for never persisting a partial
    ;; cleanup as clean.
    (let [errors (not-empty (cfv/validate-file file libs))]
      (when errors
        (l/warn :hint "file still invalid after repair"
                :file-id (str (:id file))
                :codes (frequencies (mapv :code errors)))
        (ex/raise :type :validation
                  :code :repair-did-not-converge
                  :hint "file did not converge after repair"
                  :file-id (:id file)
                  :details (mapv #(select-keys % [:code :hint]) errors))))
    file))

(def ^:private sql:lock-file-revn
  "SELECT f.revn FROM file AS f WHERE f.id = ? AND f.deleted_at IS NULL FOR UPDATE OF f SKIP LOCKED")

(defn- lock-file-revn
  "Take the row lock and return the current revn, or nil when the row
  is locked elsewhere or gone."
  [conn id]
  (:revn (db/exec-one! conn [sql:lock-file-revn id])))

(defn- process-file
  "The write transaction: take the row lock, check nobody modified
  the file since it was read, and run the garbage collection on its
  snapshot instead of re-reading it. The snapshot arrives already
  cleaned from the repair step, so this only collects, marks and
  persists. Takes the components still in use by library users and
  the deleted components (both as component maps; ids are extracted
  inside), both computed beforehand outside any write transaction,
  then the repaired file and its data as decoded before sanitize
  (:orig-data) to detect a real change: modified-at is only touched
  when the persisted snapshot differs, but the trim flag is always
  persisted so the scheduler does not retry the file. Never bumps
  revn: a new revision here would orphan the current thumbnail row,
  which the file list joins on."
  [{:keys [::db/conn ::timestamp] :as cfg} used-remote deleted-components {:keys [id revn] :as file} orig-data]
  (if (= revn (lock-file-revn conn id))
    (let [processed (->> file
                         (process-media cfg used-remote deleted-components)
                         (process-fragments cfg))
          ;; Touch modified-at only on real change: a no-op run still
          ;; marks the file as trimmed (that is the point of the run)
          ;; but must not look like a new version to clients holding
          ;; an ETag.
          changed?  (not (= orig-data (:data processed)))
          file      (-> processed
                        (cond-> changed? (assoc :modified-at timestamp))
                        (assoc :has-media-trimmed true))]
      (bfc/update-file! cfg file)
      true)

    (do
      ;; :stale-revn covers both causes: the file changed since the
      ;; read, or the row is locked elsewhere and SKIP LOCKED skipped
      ;; it. Either way the scheduler retries the file later.
      (l/dbg :hint "skip cleaning, file changed or locked since repair" :file-id (str id))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    ;; Deliberately no sto/configure here: file-data persistence
    ;; resolves storage without ::db/reuse-conn, so put-object!
    ;; commits in its own transaction. A rolled-back run can leave
    ;; an unreferenced storage row, which deduplication reuses and
    ;; storage gc reaps; this avoids the opposite leak (backend
    ;; content with no row, which nothing would reap).
    (let [cfg    (-> cfg
                     (assoc ::db/rollback (:rollback? props))
                     (assoc ::timestamp (ct/now)))
          result (try
                   (let [{:keys [file libs orig-data]} (get-file cfg props)
                         repaired (some-> file
                                          (repair-file @libs))]
                     (if repaired
                       (let [deleted-components (ctkl/deleted-components-seq (:data repaired))
                             used-remote (find-used-components cfg (:id repaired) deleted-components)
                             cleaned? (db/tx-run! cfg process-file used-remote deleted-components repaired orig-data)]
                         {:cleaned cleaned? :file-id (:id file)
                          :reason (if cleaned? :cleaned :stale-revn)})
                       {:cleaned false :file-id (:file-id props)
                        ;; Existence check only: fetch the identity columns
                        ;; so a big legacy blob is never read for this
                        ;; answer (:deleted-at stays so soft-deleted
                        ;; rows keep filtering out to :gone).
                        :reason (if (db/get* cfg :file {:id (:file-id props)}
                                             ::sql/columns [:id :deleted-at])
                                  :stale-revn
                                  :gone)}))
                   (catch Throwable cause
                     ;; Data failures (:validation from validate!/report-error,
                     ;; :data-validation from schema checks like the one in
                     ;; repair-file) are unfixable; anything else (DB,
                     ;; storage, asserts) is transient. Neither marks the
                     ;; file, so the scheduler retries the run later.
                     (let [edata  (ex-data cause)
                           reason (if (or (= :validation (:type edata))
                                          (= :data-validation (:code edata)))
                                    :unfixable
                                    :error)]
                       (l/err :hint "error on cleaning file"
                              :file-id (str (:file-id props))
                              :cause cause)
                       {:cleaned false :reason reason :file-id (:file-id props)})))]
      ;; The offload submit runs outside the result try/catch: a queue
      ;; failure after a successful write must not falsify the
      ;; cleaning outcome (the file is already marked trimmed, so no
      ;; retry would recover the dropped offload either way).
      (when (and (:cleaned result) (contains? cf/flags :tiered-file-data-storage))
        (try
          (wrk/submit! (-> cfg
                           (assoc ::wrk/task :offload-file-data)
                           (assoc ::wrk/params props)
                           (assoc ::wrk/priority 10)
                           (assoc ::wrk/delay 1000)))
          (catch Throwable cause
            (l/err :hint "error submitting file-gc offload"
                   :file-id (str (:file-id props))
                   :cause cause))))
      result)))
