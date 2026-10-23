;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.common.schema :as sm]
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
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
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

(defn- collect-snapshot-liveness
  "Single snapshot scan collecting both fragment pointer ids and media
  ids referenced by unpurged snapshots. Runs once per file-gc pass and
  feeds clean-file-media! and clean-fragments!, so snapshot blobs are
  read and decoded only once instead of once per cleaner."
  [cfg file-id]
  (let [xf (comp (filter :data)
                 (map (fn [{:keys [data]}]
                        {:pointers (feat.fdata/get-used-pointer-ids data)
                         :media    (cfh/collect-used-media data)})))
        rf (completing (fn [acc {:keys [pointers media]}]
                         (-> acc
                             (update :pointers into pointers)
                             (update :media into media))))]
    (fsnap/reduce-snapshots cfg file-id xf rf {:pointers #{} :media #{}})))

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [{:keys [::db/conn ::timestamp] :as cfg} snap-media {:keys [id] :as file}]
  (let [used-media
        (into snap-media xf:collect-used-media [file])

        used-media
        (db/create-array conn "uuid" used-media)

        unused-media
        (->> (db/exec! conn [sql:mark-file-media-object-deleted timestamp id used-media])
             (into #{} (map :id)))]

    (jobs/heartbeat cfg)
    (doseq [id unused-media]
      (l/trc :obj "media-object"
             :file-id (str (:id file))
             :id (str id)))

    ;; The storage-usage consumer learns about this shrink path only
    ;; through this event: no RPC runs here. The team id travels in
    ;; props so the drain never joins production tables.
    (when (and (seq unused-media) (contains? cf/flags :audit-log))
      (let [team-id (first (audit/get-team-ids cfg {:file-ids [id]}))]
        (audit/submit cfg {:name "file-gc"
                           :type "action"
                           :props (cond-> {:file-id id}
                                    (some? team-id) (assoc :team-id team-id))
                           :context {:triggered-by "cron-task"}})))

    file))

(def ^:private sql:mark-file-object-thumbnails-deleted
  "UPDATE file_tagged_object_thumbnail
      SET deleted_at = ?
    WHERE file_id = ? AND object_id != ALL(?::text[])
   RETURNING object_id")

(defn- clean-file-object-thumbnails!
  [{:keys [::db/conn ::timestamp] :as cfg} {:keys [data] :as file}]
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

    (jobs/heartbeat cfg)
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

(defn- clean-file-thumbnails!
  [{:keys [::db/conn ::timestamp] :as cfg} {:keys [id revn] :as file}]
  (let [unused (->> (db/exec! conn [sql:mark-file-thumbnails-deleted timestamp id revn])
                    (into #{} (map :revn)))]

    (jobs/heartbeat cfg)
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

(defn- clean-fragments!
  "Mark fragment rows referenced neither by the current main file data
  nor by any unpurged snapshot. The file arrives with the original
  fragment pointers put back (see `reattach-unchanged`), so `main-used`
  holds its stored fragment ids; unchanged pages keep their ids and
  are never marked. Runs inside the caller transaction, which already
  holds FOR UPDATE on the file row."
  [{:keys [::db/conn ::timestamp] :as cfg} snap-pointers {:keys [id] :as file}]
  (let [main-used (into #{} xf:collect-pointers [file])
        used      (into main-used snap-pointers)
        unused (->> (db/exec! conn [sql:mark-deleted-data-fragments timestamp id
                                    (db/create-array conn "uuid" used)])
                    (into #{} bfc/xf-map-id))]

    (jobs/heartbeat cfg)
    (doseq [id unused]
      (l/trc :obj "fragment"
             :file-id (str (:id file))
             :id (str id)))

    file))

(defn- clean-media!
  "Collect the media, the thumbnails and the unreferenced deleted
  components of the file. The components still used by library users
  and the deleted components are computed beforehand, outside the write
  transaction, so the scan does not keep it open."
  [cfg {:keys [media]} used-remote deleted-components file]
  (let [file (->> file
                  (process-deleted-components used-remote deleted-components)
                  (clean-file-media! cfg media)
                  (clean-file-thumbnails! cfg)
                  (clean-file-object-thumbnails! cfg))]
    ;; Write guard: the snapshot about to be persisted must be
    ;; schema-valid, no matter what the repair produced.
    (cfv/validate-file-schema! file)
    file))

(defn- get-file
  "Plain read of the file: no row lock, no writes. The pages are read
  unrealized, so they keep their fragment ids, and realized right away
  because the schema decode and the repair loop cannot walk unresolved
  pointers. The pointer layout is kept aside so the write phase can put
  the original fragments back wherever nothing changed.
  Sanitizes first so legacy migrations do not choke on unconverted
  types, then migrates in memory and checks the scheduled revn against
  the stored one. The check runs before sanitizing and migrating, so a
  stale scheduled revn skips that work instead of discarding it.
  Returns a map with the migrated file, the (delayed, lazily resolved)
  libraries, the original pointer layout and the decoded pre-sanitize
  data; nil when the file is gone or the revn does not match."
  [cfg {:keys [file-id] :as props}]
  (when-let [file (bfc/get-file cfg file-id
                                :realize? false
                                :migrate? false
                                :throw-if-not-exists? false)]
    (when (or (nil? (:revn props)) (= (:revn props) (:revn file)))
      (let [original  (feat.fdata/original-pointers (:data file))
            ;; One connection for the whole realization: the fragment
            ;; loader needs one and every page of the file is read
            ;; here. No transaction: this phase must not hold a write
            ;; open.
            data      (db/run! cfg (fn [cfg]
                                     (feat.fdata/realize-pointers cfg file-id (:data file))))
            file      (assoc file :data data)
            libs      (delay (bfc/get-resolved-file-libraries cfg file))
            orig-data data
            ;; Migrate only when pending: without diff, migrate ends
            ;; in a full schema walk plus legacy feature migration.
            ;; Sanitize above stays unconditional: validation and
            ;; repair need it in memory.
            file      (cond-> (update file :data ctf/sanitize-file-data)
                        (fmg/need-migration? file)
                        (fmg/migrate-file libs))]
        {:file file
         :original original
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
    ;; clean-media!): a file that is still schema-invalid after the
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
  [{:keys [::db/conn ::timestamp] :as cfg}
   used-remote deleted-components {:keys [id revn] :as file} orig-data original]
  (if (= revn (lock-file-revn conn id))
    (let [liveness (collect-snapshot-liveness cfg id)
          processed (->> file
                         (clean-media! cfg liveness used-remote deleted-components))
          ;; Touch modified-at only on real change: a no-op run still
          ;; marks the file as trimmed (that is the point of the run)
          ;; but must not look like a new version to clients holding
          ;; an ETag.
          changed? (not (= orig-data (:data processed)))
          file     (-> processed
                       (cond-> changed? (assoc :modified-at timestamp))
                       (assoc :has-media-trimmed true))
          ;; Put the original fragment pointers back wherever nothing
          ;; changed: an untouched page keeps its fragment id and no
          ;; new fragment row is written for it.
          file     (feat.fdata/reattach-unchanged original file)
          file     (clean-fragments! cfg (:pointers liveness) file)]
      (bfc/update-file! cfg file)
      true)
    (do
      ;; :stale-revn covers both causes: the file changed since the
      ;; read, or the row is locked elsewhere and SKIP LOCKED skipped
      ;; it. Either way the scheduler retries the file later.
      (l/dbg :hint "skip cleaning, file changed or locked since repair" :file-id (str id))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-file-gc
  "Job handler: heal the file when its stored data does not validate,
  then clean the media, the thumbnails and the file data of one file.
  The read, the repair and the library-user scan run outside the write
  transaction, so a long scan never trips the idle-in-transaction
  timeout; only the write itself is transactional.
  Returns a result map `{:cleaned :file-id :reason}` (see
  `schema:file-gc-result`)."
  [cfg params]
  (let [cfg    (-> cfg
                   (assoc ::db/rollback (:rollback? params))
                   (assoc ::timestamp (ct/now)))
        result (try
                 ;; No write transaction here on purpose: the read,
                 ;; the repair and the library-user scan must not hold
                 ;; a transaction open (the scan loads every user file
                 ;; and used to trip the idle-in-transaction timeout).
                 (let [{:keys [file libs orig-data original]} (get-file cfg params)
                       repaired (some-> file (repair-file @libs))]
                   (if repaired
                     (let [deleted-components (ctkl/deleted-components-seq (:data repaired))
                           used-remote (find-used-components cfg (:id repaired) deleted-components)
                           cleaned? (db/tx-run! cfg process-file used-remote deleted-components repaired orig-data original)]
                       {:cleaned cleaned? :file-id (:id file)
                        :reason (if cleaned? :cleaned :stale-revn)})
                     {:cleaned false :file-id (:file-id params)
                      ;; Existence check only: fetch the identity columns
                      ;; so a big legacy blob is never read for this
                      ;; answer (:deleted-at stays so soft-deleted
                      ;; rows keep filtering out to :gone).
                      :reason (if (db/get* cfg :file {:id (:file-id params)}
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
                            :file-id (str (:file-id params))
                            :cause cause)
                     {:cleaned false :reason reason :file-id (:file-id params)})))]
    ;; The offload submit runs outside the result try/catch: a queue
    ;; failure after a successful write must not falsify the
    ;; cleaning outcome (the file is already marked trimmed, so no
    ;; retry would recover the dropped offload either way).
    (when (and (:cleaned result) (contains? cf/flags :tiered-file-data-storage))
      (try
        (jobs/submit cfg
                     {::jobs/name :offload-file-data
                      ;; Pass only the file id: the offload job ignores
                      ;; any other file-gc param, and forwarding the whole
                      ;; map would silently couple the two jobs.
                      ::jobs/params {:file-id (:file-id params)}
                      ::jobs/priority 10
                      ::jobs/delay 1000})
        (catch Throwable cause
          (l/err :hint "error submitting file-gc offload"
                 :file-id (str (:file-id params))
                 :cause cause))))
    result))

(def schema:file-gc-params
  ;; `:revn` is optional: the write phase re-checks the revision under
  ;; the row lock, so a run without it still cleans (and the file is
  ;; never marked with a revision the read did not see).
  [:map
   [:file-id ::sm/uuid]
   [:revn {:optional true} ::sm/int]])

(def schema:file-gc-result
  "The outcome of one file-gc run: whether the file was cleaned, which
  file it was and why it was not (when it was not)."
  [:map
   [:cleaned ::sm/boolean]
   [:file-id ::sm/uuid]
   [:reason ::sm/keyword]])

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :file-gc
   ::jobs/schema    schema:file-gc-params
   ::jobs/handler
   (fn [_context params]
     (execute-file-gc cfg params))
   ::jobs/decoder   (sm/decoder schema:file-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:file-gc-params)
   ::jobs/result-schema schema:file-gc-result})
