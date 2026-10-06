;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.files-update
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.http.errors :as errors]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.redis :as rds]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]))

(declare ^:private get-lagged-changes)
(declare ^:private send-notifications!)
(declare ^:private update-file)
(declare ^:private update-file*)
(declare ^:private process-changes-and-validate)
(declare ^:private take-snapshot?)

;; PUBLIC API; intended to be used outside of this module
(declare update-file!)
(declare update-file-data!)
(declare persist-file!)
(declare invalidate-caches!)
(declare ^:private persist-branch-file!)
(declare get-file)

;; --- SCHEMA

(def ^:private
  schema:update-file
  [:map {:title "update-file"}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:changes {:optional true} [:vector cpc/schema:change]]
   [:changes-with-metadata {:optional true}
    [:vector [:map
              [:changes [:vector cpc/schema:change]]
              [:hint-origin {:optional true} :keyword]
              [:hint-events {:optional true} [:vector [:string {:max 250}]]]]]]
   [:skip-validate {:optional true} ::sm/boolean]])

(def ^:private
  schema:update-file-result
  [:vector {:title "update-file-result"}
   [:map
    [:changes [:vector cpc/schema:change]]
    [:file-id ::sm/uuid]
    [:id ::sm/uuid]
    [:revn {:min 0} ::sm/int]
    [:session-id ::sm/uuid]]])

;; --- HELPERS

(def ^:private sql:media-rows-by-id
  "SELECT id, file_id FROM file_media_object WHERE id = ANY(?)")

(defn- media-id-map-from-index
  "The media id map a branch's op log row stamps, from the remap a
  save's media fix-up applied: `{old-id -> new-id}` restricted to the
  copies whose source row the branch's source file owns, transit-encoded
  for the jsonb column. A copy of media from another file is branch-own
  media with no pairing to record, and only the source's pairing is
  what a comparison rebuilds from the stamps
  (`files_branch.clj::branch-media-pairs`)."
  [conn source-file-id media-index]
  (when-not (empty? media-index)
    (let [owners (->> (db/exec! conn [sql:media-rows-by-id
                                      (db/create-array conn "uuid"
                                                       (keys media-index))])
                      (into #{} (comp (filter #(= source-file-id (:file-id %)))
                                      (map :id))))
          owned   (fn [id] (contains? owners id))]
      (some-> (not-empty (into {} (filter (comp owned key)) media-index))
              db/tjson))))

;; File changes that affect to the library, and must be notified
;; to all clients using it.

(def ^:private library-change-types
  #{:add-color
    :mod-color
    :del-color
    :add-media
    :mod-media
    :del-media
    :add-component
    :mod-component
    :del-component
    :restore-component
    :add-typography
    :mod-typography
    :del-typography})

(def ^:private token-change-types
  #{:set-tokens-lib
    :set-token
    :set-token-set
    :set-token-theme
    :rename-token-set-group
    :move-token-set
    :move-token-set-group
    :set-base-font-size})

(def ^:private file-change-types
  #{:add-obj
    :mod-obj
    :del-obj
    :reg-objects
    :mov-objects})

(defn- library-change?
  [{:keys [type] :as change}]
  (or (contains? library-change-types type)
      (contains? token-change-types type)
      (contains? file-change-types type)))

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(sv/defmethod ::update-file
  {::rpc/id-type :file
   ::climit/id [[:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]

   ::webhooks/event? true
   ::webhooks/batch-timeout (ct/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file
   ::sm/result schema:update-file-result
   ::doc/module :files
   ::doc/added "1.17"
   ::db/transaction true}
  [{:keys [::mtx/metrics ::db/conn] :as cfg}
   {:keys [::rpc/profile-id id changes changes-with-metadata] :as params}]

  (files/check-edition-permissions! conn profile-id id)
  (db/xact-lock! conn id)

  ;; The timer starts BEFORE `get-file`. A branch file stores no payload,
  ;; so that read folds the merge-base snapshot and the op log into the
  ;; derived document, and every other branch command (compare, merge,
  ;; update-from-main, materialize) creates its timer ahead of its own
  ;; file reads. Starting here puts a branch save's `branch-duration-ms`
  ;; on the same footing as theirs: the number covers the read, and for a
  ;; branch the derive inside it, as well as the rest of the save. The
  ;; trace below covers the read for an ordinary save as well, which is
  ;; what the request actually cost.
  (let [tpoint   (ct/tpoint)
        file     (get-file cfg id)
        team     (teams/get-team conn
                                 :profile-id profile-id
                                 :team-id (:team-id file))

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params))
                     (cfeat/check-file-features! (:features file)))

        changes  (if changes-with-metadata
                   (->> changes-with-metadata (mapcat :changes) vec)
                   (vec changes))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features (set/difference features cfeat/frontend-only-features))
                     (assoc :team team)
                     (assoc :file file)
                     (assoc :changes changes))

        cfg      (assoc cfg ::timestamp (ct/now))]

    (when (not= (:vern params)
                (:vern file))
      (ex/raise :type :validation
                :code :vern-conflict
                :hint "A different version has been restored for the file."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    (when (> (:revn params)
             (:revn file))
      (ex/raise :type :validation
                :code :revn-conflict
                :hint "The incoming revision number is greater that stored version."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    ;; When newly computed features does not match exactly with the
    ;; features defined on team row, we update it
    (when-let [features (-> features
                            (set/difference (:features team))
                            (set/difference cfeat/no-team-inheritable-features)
                            (not-empty))]
      (let [features (-> features
                         (set/union (:features team))
                         (set/difference cfeat/no-team-inheritable-features)
                         (into-array))]
        (db/update! conn :team
                    {:features features}
                    {:id (:id team)}
                    {::db/return-keys false})))


    (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

    (binding [l/*context* (some-> (meta params)
                                  (get :app.http/request)
                                  (errors/request->context))]
      (-> (update-file* cfg params tpoint)
          (rph/with-defer #(let [elapsed (tpoint)]
                             (l/trace :hint "update-file" :time (ct/format-duration elapsed))))))))

(defn- update-file*
  "Internal function, part of the update-file process, that encapsulates
  the changes application offload to a separated thread and emit all
  corresponding notifications.

  Follow the inner implementation to `update-file-data!` function.

  `tpoint` is the request's own timer, threaded in so that a save routed
  to a branch's op log can report its duration on the audit event the way
  the other branch commands do. The timer is started by `update-file`
  before it reads the file, so a branch save's number includes the derive
  that every other branch command includes too.

  Only intended for internal use on this module."
  [{:keys [::db/conn ::timestamp] :as cfg}
   {:keys [profile-id file team features changes session-id skip-validate] :as params}
   tpoint]

  (binding [pmap/*tracked* (pmap/create-tracked)
            pmap/*load-fn* (partial fdata/load-pointer cfg (:id file))]

    (let [file (assoc file :features
                      (-> features
                          (set/difference cfeat/frontend-only-features)
                          (set/union (:features file))))

          ;; We need to preserve the original revn for the response
          revn
          (get file :revn)

          ;; The changes application runs the media fix-up, which may
          ;; rewrite the media references. It returns the change vector
          ;; as fixed: that is the vector a branch appends to its op
          ;; log, the state its derive reproduces. The xlog row below
          ;; keeps the client's changes, exactly as on an ordinary save.
          ;; `media-index` is the id remapping the fix-up applied
          ;; ({foreign-id -> copy-id}); a branch stamps it on the op log
          ;; row so the comparison pairs from the branch's own record.
          [file op-changes media-index]
          (binding [cfeat/*current*  features
                    cfeat/*previous* (:features file)]
            (update-file-data! cfg file
                               process-changes-and-validate
                               changes skip-validate))

          deleted-at
          (ct/plus timestamp (ct/duration {:hours 1}))]

      (when-let [file (::snapshot file)]
        (let [deleted-at (ct/plus timestamp (ldel/get-deletion-delay team))
              label      (str "internal/snapshot/" revn)]

          (fsnap/create! cfg file
                         {:label label
                          :created-by "system"
                          :deleted-at deleted-at
                          :profile-id profile-id
                          :session-id session-id})))

      ;; Insert change (xlog) with deleted_at in a future data for
      ;; make them automatically eleggible for GC once they expires
      (db/insert! conn :file-change
                  {:id (uuid/next)
                   :session-id session-id
                   :profile-id profile-id
                   :created-at timestamp
                   :updated-at timestamp
                   :deleted-at deleted-at
                   :file-id (:id file)
                   :revn (:revn file)
                   :version (:version file)
                   :features (into-array (:features file))
                   :changes (blob/encode changes)}
                  {::db/return-keys false})

      (if (:is-branch file)
        (persist-branch-file! cfg file op-changes media-index)
        (persist-file! cfg file))

      (when (contains? cf/flags :redis-cache)
        (invalidate-caches! cfg file))

      ;; Send asynchronous notifications
      (send-notifications! cfg params file)

      (let [branch?  (:is-branch file)
            duration (inst-ms (tpoint))]

        ;; A save routed to a branch's op log is the preview's most
        ;; frequent operation, so it reports its duration and outcome in
        ;; the log exactly like the other branch commands print theirs.
        ;; Without this line the backend log shows five operation kinds
        ;; where the audit table records six. An ordinary save prints
        ;; nothing at all.
        (when branch?
          (l/inf :hint "branch operation" :operation "save-branch"
                 :outcome "saved" :duration duration))

        (with-meta {:revn revn :lagged (get-lagged-changes conn params)}
          {::audit/replace-props
           (cond-> {:id         (:id file)
                    :name       (:name file)
                    :features   (:features file)
                    :project-id (:project-id file)
                    :team-id    (:team-id file)}

             ;; The ordinary save path is untouched: the props above are
             ;; the ones it has always carried.
             branch?
             (assoc :branch-operation "save-branch"
                    :branch-outcome "saved"
                    :branch-duration-ms duration))})))))

(defn get-file
  "Get not-decoded file, only decodes the features set."
  [cfg id]
  (bfc/get-file cfg id :decode? false :lock-for-share? true))

(defn persist-file!
  "Function responsible of persisting already encoded file. Should be
  used together with `get-file` and `update-file-data!`.

  It also updates the project modified-at attr."
  [{:keys [::db/conn ::timestamp] :as cfg} file]
  (let [;; The timestamp can be nil because this function is also
        ;; intended to be used outside of this module
        modified-at
        (or timestamp (ct/now))

        file
        (-> file
            (dissoc ::snapshot)
            (assoc :modified-at modified-at)
            (assoc :has-media-trimmed false))]

    (db/update! conn :project
                {:modified-at modified-at}
                {:id (:project-id file)}
                {::db/return-keys false})

    (bfc/update-file! cfg file)))

(defn- persist-branch-file!
  "Persist a branch file save. A branch stores no data payload: the
  change vector is appended to the branch's op log (`file_branch_change`)
  and the `file` row (revn, version, features, modified-at) plus the
  project modified-at are updated as usual, without any `file_data`
  write. The transient `file_change` xlog row (inserted by the caller)
  keeps the lagged-changes machinery working unchanged.

  The vector stores the media fix-up the save ran: `update-file*` passes
  the changes as rewritten by `process-changes-and-validate`, so the
  derive reproduces the copied media rows and their references.

  The row records the data version the changes were applied at:
  `update-file-data!` migrates the document to the current version
  before it applies them, and the derive
  (`app.binfile.common/branch-file-data`) migrates its replay to that
  version before it applies the row.

  It also records the media id map the fix-up applied, as applied
  ({old-id -> new-id}, the branch's copy is the new one), filtered to
  the copies whose source row the branch's source file owns: a copy of
  media from another file is branch-own media with no main counterpart,
  and only the source's pairing is what a comparison rebuilds from the
  stamps (`files_branch.clj::branch-media-pairs`)."
  [{:keys [::db/conn ::timestamp] :as cfg} file changes media-index]
  (let [modified-at (or timestamp (ct/now))

        file
        (-> file
            (dissoc ::snapshot)
            (assoc :modified-at modified-at)
            (assoc :has-media-trimmed false))

        branch
        (db/get* conn :file-branch {:branch-file-id (:id file)})]

    (when (nil? branch)
      (ex/raise :type :not-found
                :code :branch-metadata-missing
                :hint "branch metadata not found for branch file"
                :file-id (:id file)))

    (db/update! conn :project
                {:modified-at modified-at}
                {:id (:project-id file)}
                {::db/return-keys false})

    (db/insert! conn :file-branch-change
                {:id (uuid/next)
                 :branch-id (:id branch)
                 :file-id (:id file)
                 :revn (:revn file)
                 :changes (blob/encode changes)
                 :media-id-map (media-id-map-from-index
                                conn (:source-file-id branch) media-index)
                 :data-version (fmg/data-version)
                 :created-at modified-at
                 :updated-at modified-at}
                {::db/return-keys false})

    (bfc/update-file-row! cfg file)
    nil))


(defn invalidate-caches!
  "Drop the cached library summary of `file`. Every write path that
  persists a file calls it under the `:redis-cache` flag."
  [cfg {:keys [id] :as file}]
  (rds/run! cfg (fn [{:keys [::rds/conn]}]
                  (let [key (files/file-summary-cache-key id)]
                    (rds/del conn key)))))

(defn- attach-snapshot
  "Attach snapshot data to the file. This should be called before the
  upcoming file operations are applied to the file."
  [cfg migrated? file]
  (let [snapshot (if migrated? file (fdata/realize cfg file))]
    (assoc file ::snapshot snapshot)))

(defn- update-file-data!
  "Perform a file data transformation in with all update context setup.

  This function expected not-decoded file and transformation function. Returns
  an encoded file.

  This function is not responsible of saving the file. It only saves
  fdata/pointer-map modified fragments."

  [cfg {:keys [id] :as file} update-fn & args]
  (let [file (update file :data (fn [data]
                                  (-> data
                                      (blob/decode)
                                      (assoc :id id))))
        libs (delay (bfc/get-resolved-file-libraries cfg file))

        need-migration?
        (fmg/need-migration? file)

        take-snapshot?
        (take-snapshot? file)

        ;; For avoid unnecesary overhead of creating multiple
        ;; pointers and handly internally with objects map in their
        ;; worst case (when probably all shapes and all pointers
        ;; will be readed in any case), we just realize/resolve them
        ;; before applying the migration to the file
        file
        (cond-> file
          ;; need-migration?
          ;; (->> (fdata/realize cfg))

          need-migration?
          (fmg/migrate-file libs)

          take-snapshot?
          (->> (attach-snapshot cfg need-migration?)))]

    (apply update-fn cfg file args)))

(defn- soft-validate-file-schema!
  [file]
  (try
    (val/validate-file-schema! file)
    (catch Throwable cause
      (l/error :hint "file schema validation error" :cause cause))))

(defn- soft-validate-file!
  [file libs changes]
  (try
    (val/validate-file-affected! file libs changes)
    (catch Throwable cause
      (l/error :hint "file validation error"
               :cause cause))))

(defn- process-changes-and-validate
  [cfg file changes skip-validate]
  (let [libs
        (when (and (or (contains? cf/flags :file-validation)
                       (contains? cf/flags :soft-file-validation))
                   (not skip-validate))
          (bfc/get-resolved-file-libraries cfg file))

        ;; The main purpose of this atom is provide a contextual state
        ;; for the changes subsystem where optionally some hints can
        ;; be provided for the changes processing. Right now we are
        ;; using it for notify about the existence of media refs when
        ;; a new shape is added.
        state
        (atom {})

        file
        (binding [cpc/*state* state]
          (-> (files/check-version! file)
              (update :revn inc)
              (update :data cpc/process-changes changes)
              (update :data d/without-nils)))

        ;; The media fix-up copies the foreign media rows into the file
        ;; and rewrites the references on the file data. A branch
        ;; persists no data: its state is the change vector, so the same
        ;; rewrite is recorded in it and the derive reproduces the fix
        ;; (`bm/remap-changes` is a no-op with an empty remap). The
        ;; remap itself travels out: a branch stamps it on the op log
        ;; row (`persist-branch-file!`), so the comparison reads the
        ;; pairing from the branch's own record.
        [file op-changes media-index]
        (if-let [media-refs (-> @state :media-refs not-empty)]
          (let [[file media-index] (bfc/update-media-references! cfg file media-refs)]
            [file (bm/remap-changes changes media-index) media-index])
          [file changes nil])]

    (binding [pmap/*tracked* nil]
      (when (contains? cf/flags :soft-file-validation)
        (soft-validate-file! file libs changes))

      (when (contains? cf/flags :soft-file-schema-validation)
        (soft-validate-file-schema! file))

      (when (and (contains? cf/flags :file-validation)
                 (not skip-validate))
        (val/validate-file-affected! file libs changes))

      (when (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate))
        (val/validate-file-schema! file)))

    [file op-changes media-index]))

(defn- take-snapshot?
  "Defines the rule when file `data` snapshot should be saved."
  [{:keys [revn modified-at] :as file}]
  (when (contains? cf/flags :auto-file-snapshot)
    (let [freq    (or (cf/get :auto-file-snapshot-every) 20)
          timeout (or (cf/get :auto-file-snapshot-timeout)
                      (ct/duration {:hours 1}))]

      (or (= 1 freq)
          (zero? (mod revn freq))
          (> (inst-ms (ct/diff modified-at (ct/now)))
             (inst-ms timeout))))))

(def ^:private sql:lagged-changes
  "select s.id, s.revn, s.file_id,
          s.session_id, s.changes
     from file_change as s
    where s.file_id = ?
      and s.revn > ?
    order by s.created_at asc")

(defn- get-lagged-changes
  [conn {:keys [id revn] :as params}]
  (->> (db/exec! conn [sql:lagged-changes id revn])
       (filter :changes)
       (mapv (fn [row]
               (update row :changes blob/decode)))))

(defn- send-notifications!
  [cfg {:keys [team changes session-id] :as params} file]
  (let [lchanges (filter library-change? changes)
        msgbus   (::mbus/msgbus cfg)]

    (mbus/pub! msgbus
               :topic (:id file)
               :message {:type :file-change
                         :profile-id (:profile-id params)
                         :file-id (:id file)
                         :session-id (:session-id params)
                         :revn (:revn file)
                         :vern (:vern file)
                         :changes changes})

    (when (and (:is-shared file) (seq lchanges))
      (mbus/pub! msgbus
                 :topic (:id team)
                 :message {:type :library-change
                           :profile-id (:profile-id params)
                           :file-id (:id file)
                           :session-id session-id
                           :revn (:revn file)
                           :modified-at (ct/now)
                           :changes lchanges}))))
