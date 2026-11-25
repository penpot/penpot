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
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-changes :as fchanges]
   [app.features.file-migrations :as fmigr]
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
   [app.rpc.rlimit :as-alias rlimit]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [buddy.core.codecs :as bc]
   [buddy.core.hash :as bh]
   [clojure.set :as set]
   [cuerdas.core :as str]))

(declare ^:private get-lagged-changes)
(declare ^:private send-notifications)
(declare ^:private update-file)
(declare ^:private update-file*)
(declare ^:private process-changes-and-validate)
(declare ^:private take-snapshot?)
(declare ^:private invalidate-caches!)

;; PUBLIC API; intended to be used outside of this module
(declare update-file-data!)
(declare persist-file!)
(declare get-file)

(def ^:private catch-up-row-limit 128)
(def ^:private catch-up-window (ct/duration "24h"))

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
              [:commit-id {:optional true} ::sm/uuid]
              [:hint-origin {:optional true} :keyword]
              [:hint-events {:optional true} [:vector [:string {:max 250}]]]]]]
   [:skip-validate {:optional true} ::sm/boolean]])

(def ^:private
  schema:update-file-v2
  [:map {:title "update-file-v2" :closed true}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:skip-validate {:optional true} ::sm/boolean]
   [:repair-in-response {:optional true} ::sm/boolean]
   [:commits
    [:vector {:min 1}
     [:map {:closed true}
      [:commit-id ::sm/uuid]
      [:changes [:vector cpc/schema:change]]]]]])

(def ^:private schema:rejected-commits
  [:map
   [:commit-ids [:vector ::sm/uuid]]
   [:failed-commit-id ::sm/uuid]
   ;; The data the failed commit touches was invalid before it
   [:preexisting-damage {:optional true} :boolean]
   [:error
    [:map
     [:type :keyword]
     [:code {:optional true} [:maybe :keyword]]
     [:hint {:optional true} [:maybe :string]]
     [:details {:optional true} [:vector :any]]]]])

(def ^:private schema:update-file-result
  [:map {:title "update-file-result"}
   ;; The revision the file had before this update
   [:revn {:min 0} ::sm/int]
   ;; Every change stored after the incoming revision, this one included
   [:lagged
    [:vector
     [:map
      [:changes [:vector cpc/schema:change]]
      [:file-id ::sm/uuid]
      [:id ::sm/uuid]
      [:revn {:min 0} ::sm/int]
      [:session-id ::sm/uuid]]]]
   ;; Commits not saved: the first one that failed and every later one
   [:rejected {:optional true} schema:rejected-commits]])

(def ^:private schema:confirmed-row
  [:map {:closed true}
   [:revn [::sm/int {:min 0}]]
   [:id ::sm/uuid]])

(def ^:private schema:save-recovery
  [:or
   [:map {:closed true}
    [:mode [:enum :none :reload]]]
   [:map {:closed true}
    [:mode [:enum :wait :catch-up :reload]]
    [:since [::sm/int {:min 0}]]
    [:until [::sm/int {:min 0}]]
    [:required [:vector {:min 1 :max catch-up-row-limit} schema:confirmed-row]]]])

(def ^:private schema:update-file-v2-result
  [:map {:title "update-file-v2-result" :closed true}
   ;; The revision the file had before this update
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:written
    [:vector {:max 2}
     [:map {:closed true}
      [:id ::sm/uuid]
      [:revn {:min 0} ::sm/int]
      [:kind [:enum :edit :repair]]]]]
   [:recovery schema:save-recovery]
   ;; Present only when the request wrote a revision
   [:saved-revn {:optional true :min 0} ::sm/int]
   [:edit-matches-request {:optional true} ::sm/boolean]
   [:media-relinked {:optional true} ::sm/boolean]
   [:repair {:optional true}
    [:map
     [:id ::sm/uuid]
     [:revn {:min 0} ::sm/int]
     [:changes [:vector cpc/schema:change]]]]
   [:rejected {:optional true} schema:rejected-commits]])

(def ^:private schema:get-file-changes
  [:map {:title "get-file-changes" :closed true}
   [:file-id ::sm/uuid]
   [:since [::sm/int {:min 0}]]
   [:vern [::sm/int {:min 0}]]
   [:until {:optional true} [::sm/int {:min 0}]]
   [:confirm-revn {:optional true} [::sm/int {:min 0}]]
   [:confirm-id {:optional true} ::sm/uuid]])

(def ^:private schema:get-file-changes-result
  [:or
   [:map {:closed true}
    [:head [::sm/int {:min 0}]]
    [:vern [::sm/int {:min 0}]]
    [:reload :boolean]]
   [:map {:closed true}
    [:head [::sm/int {:min 0}]]
    [:vern [::sm/int {:min 0}]]
    [:through [::sm/int {:min 0}]]
    [:rows [:vector [:merge schema:confirmed-row
                     [:map
                      [:session-id ::sm/uuid]
                      [:changes [:vector cpc/schema:change]]]]]]
    [:confirmed-row {:optional true} schema:confirmed-row]]])

;; --- HELPERS

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

;; Not a real editor session: file-change messages with this id are
;; delivered to every connected session, including the saver.
(def repair-session-id
  #uuid "a11c0de5-0000-4000-8000-000000000001")

(defn- referential-integrity-error?
  [cause]
  (= :referential-integrity (:code (ex-data cause))))

(defn- record-repair-outcome
  [cfg outcome]
  (when-let [metrics (::mtx/metrics cfg)]
    (when (mtx/metrics? metrics)
      (mtx/run! metrics {:id :update-file-repair
                         :labels [(name outcome)]
                         :inc 1}))))

(defn- error-codes
  [errors]
  (into [] (keep :code) errors))

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(defn- legacy-params
  "Normalizes the params of `update-file`: the flat list and the groups of
  `:changes-with-metadata` are the forward changes of the save. The command
  keeps its old contract and carries no inverse."
  [{:keys [changes changes-with-metadata] :as params}]
  (let [groups (when (some? changes-with-metadata)
                 (mapv (fn [{:keys [commit-id] :as group}]
                         {:commit-id commit-id
                          :redo-changes (vec (:changes group))})
                       changes-with-metadata))
        redo-changes (if (some? groups)
                       (into [] (mapcat :redo-changes) groups)
                       (vec changes))]
    (assoc (dissoc params :repair-in-response)
           :redo-changes redo-changes
           ;; Saved commit by commit only when every group names its commit
           :commits (when (and (seq groups) (every? :commit-id groups))
                      groups))))

(defn- commit-params
  "Normalizes `update-file-v2` commits and flattens their changes in edit order."
  [{:keys [commits] :as params}]
  (let [groups (mapv (fn [{:keys [commit-id changes]}]
                       {:commit-id commit-id :redo-changes changes})
                     commits)]
    (assoc params
           :redo-changes (into [] (mapcat :redo-changes) groups)
           :commits groups)))

(defn- update-file
  "The body of the `update-file` and `update-file-v2` commands. `params`
  carries the changes already normalized: the forward list in
  `:redo-changes` and the per-commit groups, when present, in `:commits`."
  [{:keys [::mtx/metrics ::db/conn] :as cfg}
   {:keys [::rpc/profile-id id redo-changes] :as params}
   response-version]

  (files/check-edition-permissions! conn profile-id id)
  (db/xact-lock! conn id)

  (let [file     (get-file cfg id)
        team     (teams/get-team conn
                                 :profile-id profile-id
                                 :team-id (:team-id file))

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params))
                     (cfeat/check-file-features! (:features file)))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features (set/difference features cfeat/frontend-only-features))
                     (assoc :team team)
                     (assoc :file file))

        cfg      (assoc cfg ::timestamp (ct/now)
                        ::validation-timings (atom {}))

        tpoint   (ct/tpoint)]

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


    (mtx/run! metrics {:id :update-file-changes :inc (count redo-changes)})

    (binding [l/*context* (some-> (meta params)
                                  (get :app.http/request)
                                  (errors/request->context))]
      (try
        (-> (update-file* cfg params response-version)
            (rph/with-defer #(let [elapsed (tpoint)
                                   timings @(::validation-timings cfg)]
                               (l/dbg :hint "update-file"
                                      :file-id (str (:id file))
                                      :project-id (str (:project-id file))
                                      :team-id (str (:team-id file))
                                      :status "success"
                                      :time (ct/format-duration elapsed)
                                      :resolve-libs-ms (get timings :resolve-libs-ms)
                                      :process-changes-ms (get timings :process-changes-ms)
                                      :soft-validate-affected-ms (get timings :soft-validate-affected-ms)
                                      :soft-validate-schema-ms (get timings :soft-validate-schema-ms)
                                      :validate-affected-ms (get timings :validate-affected-ms)
                                      :validate-schema-ms (get timings :validate-schema-ms)
                                      :repair-ms (get timings :repair-ms)
                                      :reprocess-changes-ms (get timings :reprocess-changes-ms)))))
        (catch Throwable cause
          ;; NOTE: `with-defer` hooks only run on the success path
          ;; (`handle-response`), so timings collected before a hard
          ;; validation failure would be lost without this inline log.
          (let [timings @(::validation-timings cfg)]
            (l/dbg :hint "update-file"
                   :file-id (str (:id file))
                   :project-id (str (:project-id file))
                   :team-id (str (:team-id file))
                   :status "error"
                   :resolve-libs-ms (get timings :resolve-libs-ms)
                   :process-changes-ms (get timings :process-changes-ms)
                   :soft-validate-affected-ms (get timings :soft-validate-affected-ms)
                   :soft-validate-schema-ms (get timings :soft-validate-schema-ms)
                   :validate-affected-ms (get timings :validate-affected-ms)
                   :validate-schema-ms (get timings :validate-schema-ms)
                   :repair-ms (get timings :repair-ms)
                   :reprocess-changes-ms (get timings :reprocess-changes-ms)))
          (throw cause))))))

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
  [cfg params]
  (update-file cfg (legacy-params params) :v1))

(sv/defmethod ::update-file-v2
  {::rpc/id-type :file
   ::climit/id [[:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]

   ;; The command replaces `update-file`: the rate limits keep one bucket and
   ;; one counter per profile for both, so moving a client does not double
   ;; the quota.
   ::rlimit/method "update-file"

   ::webhooks/event? true
   ::webhooks/batch-timeout (ct/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file-v2
   ::sm/result schema:update-file-v2-result
   ::doc/module :files
   ::doc/added "2.21"
   ::db/transaction true}
  [cfg params]
  ;; The audit log, the webhooks and the telemetry keep the name of the
  ;; command this one replaces.
  (-> (update-file cfg (commit-params params) :v2)
      (rph/with-meta {::audit/name "update-file"})))

(defn- get-redo-digest
  [data]
  (bc/bytes->hex (bh/sha256 data)))

(defn- encode-revision
  "The payload stored for a revision: the forward changes of the save and
  an empty undo list, meaning no inverse was stored."
  [redo-changes]
  (blob/encode {:redo-changes redo-changes
                :undo-changes []}))

(defn- insert-revision!
  "Writes the revision row of a save and its payload.

  The payload lives in `file_data` with `type = \"revision\"`, the same id
  as the `file_change` row and the same deletion schedule. It is the place
  where the changes are heading: `file_change.changes` stays written for
  the readers deployed today, and a later step can read the payload with a
  fallback to the column."
  [{:keys [::db/conn] :as cfg}
   {:keys [id session-id profile-id created-at deleted-at retained file-id revn
           version redo-changes catch-up-vern]}]
  (let [redo     (blob/encode redo-changes)
        metadata (cond-> {}
                   retained (assoc :retained true)
                   (some? catch-up-vern) (assoc :catch-up-vern catch-up-vern
                                                :catch-up-redo-sha256 (get-redo-digest redo)))]
    (db/insert! conn :file-change
                {:id id
                 :kind fchanges/revision-kind
                 :session-id session-id
                 :profile-id profile-id
                 :created-at created-at
                 :updated-at created-at
                 :deleted-at deleted-at
                 :retained retained
                 :file-id file-id
                 :revn revn
                 :version version
                 :changes redo}
                {::db/return-keys false})

    (fdata/upsert! cfg (cond-> {:id id
                                :file-id file-id
                                :type "revision"
                                :created-at created-at
                                :deleted-at deleted-at
                                ;; A revision payload is a new row every save
                                :storage-object-id nil
                                :data (encode-revision redo-changes)}
                         (seq metadata) (assoc :metadata metadata)))))

(defn- save-revisions
  "Builds the rows in logical revision order. The edit owns the audit id
  and the repair uses the system session."
  [file-change-id session-id revn redo-changes repair]
  (let [edit {:id file-change-id
              :kind :edit
              :session-id session-id
              :redo-changes redo-changes}]
    (if repair
      (let [fix {:id (uuid/next)
                 :kind :repair
                 :session-id repair-session-id
                 :redo-changes (:changes repair)}]
        (if (:after-edit repair)
          [(assoc edit :revn (inc revn)) (assoc fix :revn (+ revn 2))]
          [(assoc fix :revn (inc revn)) (assoc edit :revn (+ revn 2))]))
      [(assoc edit :revn (inc revn))])))

(def ^:private sql:save-predecessors
  "SELECT s.id, s.revn, s.kind, s.label, s.created_at,
          (s.deleted_at IS NULL OR s.deleted_at > now()) AS live
     FROM file_change AS s
    WHERE s.file_id = ? AND s.revn > ? AND s.revn <= ?
      AND (s.label IS NULL OR s.kind = ?)
    ORDER BY s.revn
    LIMIT ?")

(defn- contiguous-revisions?
  [since until rows]
  (and (= (- until since) (count rows))
       (every? true? (map-indexed (fn [i row] (= (+ since i 1) (:revn row))) rows))))

(defn- revision-candidate?
  [{:keys [kind label]}]
  (and (= fchanges/revision-kind kind) (nil? label)))

(defn- recent-revision?
  [cutoff {:keys [created-at]}]
  (ct/is-after-or-equal? created-at cutoff))

(defn- get-save-recovery
  [{:keys [::db/conn ::timestamp]} {:keys [id] since :revn} until edit-matches?]
  (cond
    (= since until)
    {:mode :none}

    (> (- until since) catch-up-row-limit)
    {:mode :reload}

    :else
    (let [rows (db/exec! conn [sql:save-predecessors id since until
                               fchanges/revision-kind (inc catch-up-row-limit)])]
      ;; A legacy row without a kind must not hide a duplicate. Age and
      ;; expiry affect recovery but do not erase a complete identity proof.
      (if (and (contiguous-revisions? since until rows)
               (every? revision-candidate? rows))
        (let [cutoff (ct/minus timestamp catch-up-window)
              mode   (cond
                       (not-every? #(and (:live %) (recent-revision? cutoff %)) rows)
                       :reload

                       (not (and edit-matches? (contains? cf/flags :file-catch-up)))
                       :wait

                       ;; The GET must also prove the one edit just written.
                       (> (inc (count rows)) catch-up-row-limit)
                       :reload

                       :else
                       :catch-up)]
          {:mode mode
           :since since
           :until until
           :required (mapv #(select-keys % [:id :revn]) rows)})
        {:mode :reload}))))

(defn- get-update-file-v2-result
  [cfg {:keys [vern redo-changes] :as params} revn revisions rejected media-relinked]
  (let [written   (mapv #(select-keys % [:id :revn :kind]) revisions)
        ordinary? (and (= 1 (count written)) (nil? rejected) (not media-relinked))
        matches?  (and ordinary? (= redo-changes (:redo-changes (peek revisions))))]
    (cond-> {:revn revn
             :vern vern
             :written written
             :recovery (get-save-recovery cfg params revn matches?)}
      (seq written) (assoc :saved-revn (:revn (peek written)))
      ordinary? (assoc :edit-matches-request matches?))))

(defn- update-file*
  "Internal function, part of the update-file process, that encapsulates
  the changes application offload to a separated thread and emit all
  corresponding notifications.

  Follow the inner implementation to `update-file-data!` function.

  Only intended for internal use on this module."
  [{:keys [::db/conn ::timestamp] :as cfg}
   {:keys [profile-id file team features redo-changes commits session-id
           repair-in-response skip-validate] :as params}
   response-version]

  (fdata/with-tracked-pointers cfg (:id file)

    (let [file-features (-> features
                            (set/difference cfeat/frontend-only-features)
                            (set/union (:features file)))
          can-classify? (and (contains? cf/flags :file-catch-up)
                             (not (:is-shared file))
                             (= file-features (:features file)))
          file          (assoc file :features file-features)

          ;; We need to preserve the original revn for the response
          revn
          (get file :revn)

          file
          (binding [cfeat/*current*  features
                    cfeat/*previous* (:features file)]
            (update-file-data! (assoc cfg ::repair-in-response repair-in-response
                                      ::classify-catch-up can-classify?)
                               file
                               process-changes-and-validate
                               redo-changes commits skip-validate))

          ;; Only the accepted changes are stored and broadcast
          redo-changes   (::redo-changes file)
          rejected       (::rejected file)
          media-relinked (::media-relinked file)
          catch-up-vern  (::catch-up-vern file)
          file           (dissoc file ::redo-changes ::rejected ::media-relinked ::catch-up-vern)

          ;; A request whose first commit is rejected leaves the file as is
          saved?   (or (nil? rejected) (seq redo-changes))
          snapshot (when saved? (::snapshot file))

          retained   (contains? cf/flags :file-change-retention)
          deleted-at (when-not retained
                       (ct/plus timestamp (ct/duration {:hours 1})))

          ;; The id of the change row (xlog) goes to the audit event
          ;; too: a consumer that reads the event needs to know what the
          ;; save changed, and that row is the only place with it.
          file-change-id
          (uuid/next)

          repair
          (::repair file)

          revisions
          (when saved?
            (save-revisions file-change-id session-id revn redo-changes repair))

          response-repair
          (when (:after-edit repair)
            (let [{:keys [id revn redo-changes]} (peek revisions)]
              {:id id :revn revn :changes redo-changes}))

          snapshot-deleted-at
          (ct/plus timestamp (ldel/get-deletion-delay team))]

      (if repair
        (let [snapshot-file (or snapshot
                                (if (:after-edit repair)
                                  (:original repair)
                                  (fdata/realize cfg (:original repair))))]
          (fsnap/create! cfg snapshot-file
                         {:label (str "internal/repair/" revn)
                          :created-by "system"
                          :deleted-at snapshot-deleted-at
                          :profile-id profile-id
                          :session-id repair-session-id}))
        (when snapshot
          (fsnap/create! cfg snapshot
                         {:label (str "internal/snapshot/" revn)
                          :created-by "system"
                          :deleted-at snapshot-deleted-at
                          :profile-id profile-id
                          :session-id session-id})))

      (when saved?
        (when repair
          (l/wrn :hint "file auto-repaired"
                 :file-id (str (:id file))
                 :error-codes (error-codes (:errors repair))
                 :error-count (count (:errors repair))
                 :change-count (count (:changes repair)))
          (record-repair-outcome cfg :repaired))

        (doseq [[i row] (map-indexed vector revisions)]
          (insert-revision! cfg
                            (assoc row
                                   :profile-id profile-id
                                   ;; Preserve the order within this pair in the
                                   ;; legacy timestamp-ordered lagged query.
                                   :created-at (if (zero? i)
                                                 timestamp
                                                 (ct/plus timestamp (ct/duration {:millis i})))
                                   :deleted-at deleted-at
                                   :retained retained
                                   :file-id (:id file)
                                   :version (:version file)
                                   :catch-up-vern catch-up-vern)))

        (persist-file! cfg (dissoc file ::repair))

        (when (contains? cf/flags :redis-cache)
          (invalidate-caches! cfg file))

        ;; Peers must only see committed writes, even if audit or the
        ;; response fails after the transaction has committed.
        (db/after-commit
         (fn []
           (if response-repair
             (send-notifications cfg params file
                                 {:session-id session-id
                                  :revn (:revn file)
                                  :changes (into redo-changes (:changes response-repair))
                                  :revision-ids (mapv :id revisions)
                                  :repair-base-revn revn
                                  :repaired true})
             (doseq [{:keys [id session-id revn redo-changes]} revisions]
               (send-notifications cfg params file
                                   (cond-> {:session-id session-id :revn revn :changes redo-changes}
                                     (not= file-change-id id) (assoc :repaired true)
                                     repair-in-response (assoc :revision-ids [id]))))))))

      (with-meta (cond-> (if (= :v2 response-version)
                           (get-update-file-v2-result cfg params revn revisions rejected media-relinked)
                           {:revn revn :lagged (get-lagged-changes conn params)})
                   media-relinked
                   (assoc :media-relinked true)

                   response-repair
                   (assoc :repair response-repair)

                   (some? rejected)
                   (assoc :rejected rejected))
        {::audit/replace-props
         (cond-> {:id          (:id file)
                  :name        (:name file)
                  :features    (:features file)
                  :project-id  (:project-id file)
                  :team-id     (:team-id file)
                  ;; NOTE: `file` carries the post-update revn here (`revn`
                  ;; above is the pre-update one kept for the response).
                  :revn        (if saved? (:revn file) revn)
                  ;; How the save ended: every change written, the accepted
                  ;; prefix written and the rest rejected, or nothing
                  ;; written. A consumer must not read `update-file` as a
                  ;; state change without looking at this.
                  :outcome     (cond
                                 (not saved?)     "rejected"
                                 (some? rejected) "partial"
                                 :else            "saved")
                  ;; A rejected save does not call `persist-file!`, so the
                  ;; row keeps its previous instant: mirror the persisted
                  ;; value instead of claiming a modification.
                  :modified-at (if saved?
                                 (or timestamp (ct/now))
                                 (:modified-at file))}

           ;; The id of the change row inserted above: what the save
           ;; changed lives in that row, not in the event. A fully rejected
           ;; save writes no row, and an id that resolves to nothing cannot
           ;; be told apart from one the GC already purged.
           saved?
           (assoc :file-change-id file-change-id))}))))

(defn get-file
  "Get not-decoded file, only decodes the features set.

  The read takes the `file` row with FOR UPDATE: the save persists the
  map it read, so it must hold the row from the read to the write. The
  lock is exclusive, not shared, because the migration-on-read path may
  re-read the same row with FOR UPDATE, and a shared lock that upgrades
  deadlocks two concurrent readers."
  [cfg id]
  (bfc/get-file cfg id :decode? false :lock-for-update? true))

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

(defn- invalidate-caches!
  [cfg {:keys [id] :as file}]
  (rds/run! cfg (fn [{:keys [::rds/conn]}]
                  (let [key (files/file-summary-cache-key id)]
                    (rds/del conn key)))))

(defn- attach-snapshot
  "Attach snapshot data to the file. Snapshots share fragment rows with
  the live file, so the snapshot is kept as-is (with pointers); it is
  NOT realized/materialized here. The file-gc liveness set unions main
  and snapshots, so shared fragments survive while any of them needs
  them."
  [file]
  (assoc file ::snapshot file))

(defn- update-file-data!
  "Perform a file data transformation in with all update context setup.

  This function expected not-decoded file and transformation function. Returns
  an encoded file.

  This function is not responsible of saving the file. Fragment rows are
  persisted later by `persist-file!` (via `bfc/update-file!` inside the
  same transaction); nothing is written to `file_data` here."

  [cfg {:keys [id] :as file} update-fn & args]
  (let [data (blob/decode (:data file))
        file (assoc file :data (assoc data :id id))
        cfg  (cond-> cfg
               (not= id (:id data)) (dissoc ::classify-catch-up))
        libs (delay (bfc/get-resolved-file-libraries cfg file))

        need-migration?
        (fmg/need-migration? file)

        take-snapshot?
        (take-snapshot? file)

        ;; Attach the snapshot before migrating: a migration can rebuild a
        ;; page and give its PointerMap a new id with `modified?` set, and a
        ;; snapshot taken after that would keep the very pointer the save
        ;; then rewrites, ending up with post-save content.
        ;;
        ;; For avoid unnecesary overhead of creating multiple
        ;; pointers and handly internally with objects map in their
        ;; worst case (when probably all shapes and all pointers
        ;; will be readed in any case), we just realize/resolve them
        ;; before applying the migration to the file
        file
        (cond-> file
          ;; need-migration?
          ;; (->> (fdata/realize cfg))

          take-snapshot?
          (->> (attach-snapshot))

          need-migration?
          (fmg/migrate-file libs))]

    (apply update-fn (cond-> cfg need-migration? (assoc ::file-migrated true)) file args)))

(defn- soft-validate-file-schema!
  [file changes]
  (try
    (val/validate-file-schema-affected! file changes)
    (catch Throwable cause
      (l/error :hint "file schema validation error" :cause cause))))

(defn- record-validation-timing
  [cfg step-key step-label elapsed-ms]
  (when-let [metrics (::mtx/metrics cfg)]
    (when (mtx/metrics? metrics)
      (mtx/run! metrics {:id :update-file-validation-timing
                         :labels [step-label]
                         :val (double elapsed-ms)})))
  (when-let [timings (::validation-timings cfg)]
    (swap! timings assoc step-key elapsed-ms)))

(defn- soft-validate-file!
  [file libs changes]
  (try
    (val/validate-file-affected! file libs changes)
    (catch Throwable cause
      (l/error :hint "file validation error"
               :cause cause))))

(defmacro ^:private with-validation-timing
  "Runs `body` and records its time as the validation step `step-key`
  (`step-label` in the metric), also when it throws."
  [cfg step-key step-label & body]
  `(let [t# (ct/tpoint)]
     (try
       ~@body
       (finally
         (record-validation-timing ~cfg ~step-key ~step-label (inst-ms (t#)))))))

(defn- validate-file
  "Validates the entities `changes` affect and the schema of the data they
  modify."
  [cfg file libs changes skip-validate]
  (binding [pmap/*tracked* nil]
    (when (contains? cf/flags :soft-file-validation)
      (with-validation-timing cfg :soft-validate-affected-ms "soft-validate-affected"
        (soft-validate-file! file libs changes)))

    (when (contains? cf/flags :soft-file-schema-validation)
      (with-validation-timing cfg :soft-validate-schema-ms "soft-validate-schema"
        (soft-validate-file-schema! file changes)))

    (when (and (contains? cf/flags :file-validation)
               (not skip-validate))
      (with-validation-timing cfg :validate-affected-ms "validate-affected"
        (val/validate-file-affected! file libs changes)))

    (when (and (contains? cf/flags :file-schema-validation)
               (not skip-validate))
      (with-validation-timing cfg :validate-schema-ms "validate-schema"
        (val/validate-file-schema-affected! file changes)))))

(def ^:private debug-fault-prefix
  "Start of the plugin data key that asks for a debug save fault."
  "save-fault:")

(defn- get-debug-fault
  "Name of the fault a debug marker in `changes` asks for, or nil. A marker
  is a `:set-plugin-data` of the file in the `:penpot-debug` namespace with
  no value, so applying it changes nothing."
  [changes]
  (some (fn [{:keys [type namespace key]}]
          (when (and (= :set-plugin-data type)
                     (= :penpot-debug namespace)
                     (str/starts-with? key debug-fault-prefix))
            (subs key (count debug-fault-prefix))))
        changes))

(defn- raise-debug-fault
  "Throws the error named `fault`, standing in for a real failure while the
  changes are applied."
  [fault]
  (let [message "debug save fault"]
    (case fault
      "validation"     (ex/raise :type :validation :code :debug-fault :hint message)
      "runtime"        (throw (RuntimeException. message))
      "ex-info"        (throw (ex-info message {}))
      "assertion"      (throw (AssertionError. message))
      "sql"            (throw (org.postgresql.util.PSQLException.
                               message org.postgresql.util.PSQLState/CONNECTION_FAILURE))
      "io"             (throw (java.net.SocketTimeoutException. message))
      "eof"            (throw (java.io.EOFException. message))
      "interrupted"    (throw (InterruptedException. message))
      "not-found"      (ex/raise :type :not-found :code :object-not-found :hint message)
      "stack-overflow" (throw (StackOverflowError. message))
      (ex/raise :type :validation :code :unknown-debug-fault :hint fault))))

(defn- apply-redo-changes
  "Applies the edit. With the `debug-save-faults` flag, a debug marker
  in the changes makes this fail with the error it names."
  [cfg file changes]
  (when (contains? cf/flags :debug-save-faults)
    (some-> (get-debug-fault changes) (raise-debug-fault)))
  (with-validation-timing cfg :process-changes-ms "process-changes"
    (update file :data (fn [data]
                         (-> data
                             (cpc/process-changes changes)
                             (d/without-nils))))))

(defn- apply-changes
  [cfg file libs changes skip-validate]
  (let [file (apply-redo-changes cfg file changes)]
    (validate-file cfg file libs changes skip-validate)
    file))

(defn- decode-objects
  "Decodes the objects of the pages `changes` touch. Every file derived from
  `file` shares them, so each attempt does not decode them again."
  [file changes]
  (doseq [page-id (into #{} (keep :page-id) changes)]
    (let [objects (get-in file [:data :pages-index page-id :objects])]
      (doseq [id (keys objects)]
        (get objects id)))))

(defn- infrastructure-error?
  [cause]
  (some (fn [cause]
          (or (instance? java.sql.SQLException cause)
              (instance? java.io.IOException cause)
              (instance? InterruptedException cause)
              ;; A file fragment that cannot be loaded, or an application
              ;; interrupt (including exceptions that wrap these failures).
              (contains? #{:not-found :interrupt} (:type (ex-data cause)))
              (contains? #{:fragment-not-found :missing-storage-object}
                         (:code (ex-data cause)))))
        (take-while some? (iterate ex-cause cause))))

(defn- change-failure?
  "True when `cause` comes from applying or validating changes. Only these
  reject a commit; any other error fails the whole request."
  [cause]
  (and (or (instance? Exception cause)
           (instance? AssertionError cause))
       (not (infrastructure-error? cause))))

(defn- preexisting-damage?
  "True when `file` already fails validation on the pages and components
  `changes` touch, before they are applied."
  [file libs changes skip-validate]
  (try
    (binding [pmap/*tracked* nil]
      (when (and (contains? cf/flags :file-validation)
                 (not skip-validate))
        (val/validate-file-affected! file libs changes))
      (when (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate))
        (val/validate-file-schema-affected! file changes)))
    false
    (catch Throwable cause
      (if (change-failure? cause)
        true
        (throw cause)))))

(defn- rejection
  "Describes `commits` as rejected, the first one failing with `cause`.
  With `damaged`, the data that commit touches was invalid before it."
  [commits cause damaged]
  (let [data (ex-data cause)]
    (cond-> {:commit-ids       (mapv :commit-id commits)
             :failed-commit-id (:commit-id (first commits))
             ;; An ex-info without a type reports like any unexpected exception
             :error            (if (:type data)
                                 (cond-> {:type (:type data)
                                          :code (:code data)
                                          :hint (or (:hint data) (ex-message cause))}
                                   (:details data)
                                   (assoc :details (mapv #(dissoc % :shape) (:details data))))
                                 {:type :internal
                                  :code :unexpected-error
                                  :hint (or (ex-message cause) (.getName (class cause)))})}
      damaged
      (assoc :preexisting-damage true))))

(defn- apply-prefix
  "Applies and validates the first `n` commits of `attempt`, starting from
  its `:file` and its `:tracked` pointers. Returns the resulting `:file`,
  with the pointers to persist in `:tracked` and the media refs in `:refs`,
  or the cause of the failure."
  [{:keys [cfg file libs commits skip-validate state tracked]} n]
  (reset! pmap/*tracked* tracked)
  (reset! state {})
  (let [redo-changes (into [] (comp (take n) (mapcat :redo-changes)) commits)]
    (try
      (let [file (apply-changes cfg file libs redo-changes skip-validate)]
        {:file    (assoc file ::redo-changes redo-changes)
         :tracked @pmap/*tracked*
         :refs    @state})
      (catch Throwable cause
        (when-not (change-failure? cause)
          (throw cause))
        cause))))

(defn- apply-commits
  "Keeps the longest prefix of the `:commits` of `attempt` that applies and
  validates, found by bisection, and rejects the rest. `cause` is the
  failure of all of them together. Takes about log2 of the commits
  attempts; a commit that repairs an earlier one can keep both. Leaves the
  pointers and media refs of the kept prefix in place and returns its file,
  with the accepted changes in `::redo-changes` and the rejection in
  `::rejected`, which tells whether the data the first rejected commit
  touches was already invalid. The whole search is timed as the `retry`
  step of `cfg`."
  [cfg {:keys [file libs commits skip-validate state tracked] :as attempt} cause]
  (let [tpoint (ct/tpoint)]
    (with-validation-timing cfg :retry-ms "retry"
      (loop [lo       0
             hi       (count commits)
             cause    cause
             attempts 0
             accepted nil]
        (if (> (- hi lo) 1)
          (let [mid    (quot (+ lo hi) 2)
                result (apply-prefix attempt mid)]
            (if (instance? Throwable result)
              (recur lo mid result (inc attempts) accepted)
              (recur mid hi cause (inc attempts) result)))
          (let [rejected (subvec commits lo)
                base     (:file accepted file)
                damaged  (preexisting-damage? base libs (:redo-changes (first rejected))
                                              skip-validate)]
            (reset! pmap/*tracked* (:tracked accepted tracked))
            (reset! state (:refs accepted {}))
            (l/error :hint "update-file commit rejected"
                     :file-id (str (:id file))
                     :commit-id (str (:commit-id (first rejected)))
                     :rejected (count rejected)
                     :commits (count commits)
                     :attempts attempts
                     :preexisting-damage damaged
                     :elapsed (ct/format-duration (tpoint))
                     :cause cause)
            (assoc (:file accepted (assoc file ::redo-changes []))
                   ::rejected (rejection rejected cause damaged))))))))

(defn- run-repair
  [file libs changes errors]
  (cfr/repair-file-until-valid
   file libs
   (fn [file applied]
     (fdata/with-untracked
       (val/validate-file-affected file libs (into applied changes))))
   :initial-errors errors))

(defn- repair-control
  "The legacy R → U control. Only a change failure can rule it out."
  [cfg original libs changes]
  (try
    (when-let [errors (fdata/with-untracked
                        (val/validate-file-affected original libs changes))]
      (let [result (with-validation-timing cfg :repair-ms "repair"
                     (run-repair original libs changes errors))
            fixes  (:changes result)]
        (if-let [remaining (seq (:errors result))]
          (do
            (l/wrn :hint "file auto-repair did not recover the file"
                   :file-id (str (:id original))
                   :error-codes (error-codes remaining)
                   :error-count (count remaining))
            (record-repair-outcome cfg :unrepaired)
            nil)
          (do
            ;; The control retains the same media refs as a legacy save.
            (reset! cpc/*state* {})
            (let [file (with-validation-timing cfg :reprocess-changes-ms "reprocess-changes"
                         (update (:file result) :data
                                 #(d/without-nils (cpc/process-changes % changes))))]
              (fdata/with-untracked
                (val/validate-file-affected! file libs (into fixes changes))
                (when (contains? cf/flags :file-schema-validation)
                  (with-validation-timing cfg :validate-schema-ms "validate-schema"
                    (val/validate-file-schema! file))))
              {:file file :changes fixes :errors errors})))))
    (catch Throwable cause
      (when-not (change-failure? cause)
        (throw cause))
      (l/wrn :hint "file auto-repair failed"
             :file-id (str (:id original))
             :cause cause)
      (record-repair-outcome cfg :unrepaired)
      nil)))

(defn- error-occurrence
  [{:keys [code shape] :as error}]
  ;; Some validators put the broken reference in :args or the hint,
  ;; others only in the shape. Unreported references (for example, the
  ;; duplicate slot or the component's bad main-instance id) are ambiguous.
  (when (contains? #{:frame-not-found :parent-not-found :child-not-found
                     :child-not-in-parent :invalid-parent :component-not-found}
                   code)
    (assoc error :shape
           (select-keys shape [:type :parent-id :frame-id :component-id :component-file
                               :main-instance :component-root :shape-ref]))))

(defn- known-damage?
  [before after]
  (let [before (frequencies (keep error-occurrence before))]
    (and (seq after)
         (every? (fn [[occurrence n]] (and occurrence (<= n (get before occurrence 0))))
                 (frequencies (map error-occurrence after))))))

(defn- same-entries?
  [a b]
  (or (identical? a b)
      (and (= (count a) (count b))
           (every? (fn [[id a-entry]]
                     (when-let [entry (find b id)]
                       (let [b-entry (val entry)]
                         (or (identical? a-entry b-entry)
                             (= (fdata/realize-entry a-entry)
                                (fdata/realize-entry b-entry))))))
                   a))))

(defn- same-data?
  "Compare values rather than PointerMap identities. Every replay starts
  from the same base; its shared, untouched pages need not be loaded."
  [a b]
  (and (same-entries? (:pages-index a) (:pages-index b))
       (same-entries? (dissoc a :pages-index) (dissoc b :pages-index))))

(defn- ordinary-shape?
  [objects id]
  (loop [id id seen #{}]
    (when-let [shape (get objects id)]
      (and (= id (:id shape))
           (not (contains? seen id))
           (not-any? #(some? (get shape %))
                     [:component-id :component-file :component-root :main-instance :shape-ref])
           (or (= id uuid/zero)
               (let [parent-id (:parent-id shape)
                     children  (:shapes (get objects parent-id))]
                 (and (vector? children)
                      (some #{id} children)
                      (recur parent-id (conj seen id)))))))))

(defn- get-expected-rename
  "Apply only the reproducible effect, without the generic change processor."
  [data {:keys [type id page-id operations] :as change}]
  (when (and (= :mod-obj type)
             (= #{:type :id :page-id :operations} (set (keys change)))
             (uuid? page-id)
             (not= uuid/zero id)
             (= 1 (count operations)))
    (let [{op-type :type :keys [attr val] :as op} (first operations)]
      (when (and (= :set op-type)
                 (= :name attr)
                 (string? val)
                 (every? #{:type :attr :val :ignore-touched :ignore-geometry} (keys op))
                 (every? boolean? (vals (select-keys op [:ignore-touched :ignore-geometry]))))
        (let [objects-path [:pages-index page-id :objects]
              objects      (get-in data objects-path)
              shape        (get objects id)
              children     (:shapes shape)]
          (when (and (string? (:name shape))
                     (or (nil? children) (= [] children))
                     (ordinary-shape? objects id))
            (assoc-in data (into objects-path [id :name]) val)))))))

(defn- exactly-reproducible-redo?
  [before after changes]
  (when (seq changes)
    (fdata/with-untracked
      (let [expected (reduce (fn [data change]
                               (or (get-expected-rename data change)
                                   (reduced nil)))
                             before changes)]
        ;; Compare the whole persisted document, not just the shapes. A save
        ;; can, for example, remove top-level nils that the client would keep.
        (and expected (same-data? expected after))))))

(defn- repair-after-edit
  [original edited libs changes errors control]
  (try
    (let [{:keys [file] fixes :changes remaining :errors} (run-repair edited libs changes errors)
          data (:data file)]
      (when (and (seq fixes)
                 (empty? remaining)
                 ;; Validity alone cannot show that the edit had its intended
                 ;; effect on damaged data. Require the validated control's
                 ;; result, which also establishes the full file schema.
                 (same-data? data (-> control :file :data))
                 ;; The repair iterates. The revision collapses those passes
                 ;; to one call, and the peer event collapses the edit too.
                 ;; Each boundary can change the second pass for :touched.
                 (binding [pmap/*tracked* nil
                           cpc/*state* nil]
                   (and (same-data? data (cpc/process-changes (:data edited) fixes))
                        (same-data? data (cpc/process-changes (:data original) (into changes fixes))))))
        {:file file :changes fixes :errors (:errors control) :after-edit true}))
    (catch Throwable cause
      (when-not (change-failure? cause)
        (throw cause))
      nil)))

(defn- repair-preexisting-damage
  [cfg original edited libs changes tracked cause]
  (let [libs           (or libs {})
        edited-tracked @pmap/*tracked*
        edited-refs    @cpc/*state*
        errors         (:details (ex-data cause))]
    (reset! pmap/*tracked* tracked)
    (reset! cpc/*state* {})
    (when-let [control (repair-control cfg original libs changes)]
      (let [control-tracked @pmap/*tracked*
            control-refs    @cpc/*state*
            candidate       (when (and (::repair-in-response cfg) edited
                                       ;; The snapshot can precede a migration,
                                       ;; which is not a revision delta.
                                       (not (::file-migrated cfg))
                                       ;; Relinking media can rewrite the persisted
                                       ;; file after applying its deltas. Keep the
                                       ;; legacy order when it may be needed.
                                       (empty? (:media-refs control-refs))
                                       (empty? (:media-refs edited-refs))
                                       (or (known-damage? (:errors control) errors)
                                           ;; A no-op cannot have introduced new
                                           ;; damage, even when a validator omits
                                           ;; the exact offending reference.
                                           (same-data? (:data original) (:data edited))))
                              (reset! pmap/*tracked* edited-tracked)
                              (reset! cpc/*state* edited-refs)
                              (let [candidate (repair-after-edit original edited libs changes errors control)]
                                (when (empty? (:media-refs @cpc/*state*))
                                  candidate)))
            chosen          (or candidate control)]
        (when-not candidate
          (reset! pmap/*tracked* control-tracked)
          (reset! cpc/*state* control-refs))
        (-> (:file chosen)
            (assoc :revn (+ (:revn original) 2))
            (assoc ::redo-changes changes)
            (assoc ::repair (assoc (dissoc chosen :file)
                                   :original (dissoc original ::snapshot))))))))

(defn- repair-or-reject
  "Handles the failure `cause` of applying all `changes` at once. Repairs
  damage `original` already had when `cause` is a referential integrity
  error. If that does not save every change and there are `commits`,
  keeps the longest prefix of them that validates."
  [cfg original file edited libs changes commits skip-validate tracked cause]
  (let [repaired
        (when (and (change-failure? cause)
                   (referential-integrity-error? cause)
                   (contains? cf/flags :file-validation)
                   (not skip-validate))
          (repair-preexisting-damage cfg original edited libs changes tracked cause))]
    (cond
      (some? repaired)
      repaired

      (and (some? commits)
           (change-failure? cause))
      (apply-commits cfg
                     {;; The retries record no step timings
                      :cfg           (dissoc cfg ::mtx/metrics ::validation-timings)
                      :file          file
                      :libs          libs
                      :commits       (vec commits)
                      :skip-validate skip-validate
                      :state         cpc/*state*
                      :tracked       tracked}
                     cause)

      :else
      (throw cause))))

(defn- get-media-relink-changes
  "Describe the file rewrite on just the page shapes visited by relinking."
  [before after media-refs]
  (letfn [(shape-change [[page-id id]]
            (let [path     [:data :pages-index page-id :objects id]
                  shape    (get-in before path)
                  relinked (get-in after path)]
              (when (not= shape relinked)
                (let [ops (reduce-kv (fn [ops attr value]
                                       (cond-> ops
                                         (not= value (get shape attr))
                                         (conj {:type :set :attr attr :val value :ignore-touched true})))
                                     [] relinked)]
                  ;; ignore-touched protects copy overrides but does not
                  ;; suppress the second pass that marks a main modified.
                  ;; A final no-op setter clears the flag for this change.
                  {:type :mod-obj :page-id page-id :id id
                   :operations (conj ops (peek ops))}))))]
    (into [] (comp (filter :page-id)
                   (map (juxt :page-id :shape-id))
                   (distinct)
                   (keep shape-change))
          media-refs)))

(defn- relink-media
  [cfg file media-refs]
  (let [relinked (bfc/update-media-references! cfg file media-refs)
        repair   (::repair file)
        changes  (when (and (::repair-in-response cfg) repair (not (:after-edit repair)))
                   (get-media-relink-changes file relinked media-refs))]
    (cond-> relinked
      (seq changes) (-> (update ::redo-changes into changes)
                        (assoc ::media-relinked true)))))

(defn- process-changes-and-validate
  "Applies and validates all `changes` at once. If that fails, repairs
  damage the file already had or keeps the longest prefix of the
  `commits` that validates."
  [cfg file changes commits skip-validate]
  (let [original file

        should-resolve-libs?
        (and (or (contains? cf/flags :file-validation)
                 (contains? cf/flags :soft-file-validation))
             (not skip-validate))

        libs
        (when should-resolve-libs?
          (with-validation-timing cfg :resolve-libs-ms "resolve-libs"
            (bfc/get-resolved-file-libraries cfg file)))

        ;; The main purpose of this atom is provide a contextual state
        ;; for the changes subsystem where optionally some hints can
        ;; be provided for the changes processing. Right now we are
        ;; using it for notify about the existence of media refs when
        ;; a new shape is added.
        state
        (atom {})

        ;; True when validation reads every object of the touched pages
        schema-check?
        (or (contains? cf/flags :soft-file-schema-validation)
            (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate)))

        file
        (-> (files/check-version! file)
            (update :revn inc))

        tracked
        @pmap/*tracked*

        file
        (binding [cpc/*state* state]
          ;; Decoded on `file`, the objects the schema check reads are
          ;; shared by every retry
          (when (and (next commits) schema-check?)
            (decode-objects file changes))
          (let [edited (try
                         (apply-redo-changes cfg file changes)
                         (catch Throwable cause cause))]
            (if (instance? Throwable edited)
              (repair-or-reject cfg original file nil libs changes commits
                                skip-validate tracked edited)
              (let [failure (try
                              (validate-file cfg edited libs changes skip-validate)
                              nil
                              (catch Throwable cause cause))]
                (if failure
                  (repair-or-reject cfg original file edited libs changes commits
                                    skip-validate tracked failure)
                  (assoc edited ::redo-changes changes))))))]

    (if-let [media-refs (-> @state :media-refs not-empty)]
      (relink-media cfg file media-refs)
      (cond-> file
        (and (::classify-catch-up cfg)
             (not (::file-migrated cfg))
             (not (::repair file))
             (not (::rejected file))
             (exactly-reproducible-redo? (:data original) (:data file) (::redo-changes file)))
        (assoc ::catch-up-vern (:vern original))))))

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

(def ^:private catch-up-redo-limit (* 4 1024 1024))
(def ^:private catch-up-decoded-limit (* 16 1024 1024))

(def ^:private sql:catch-up-head
  "SELECT f.id, f.revn, f.vern, f.version,
          fd.metadata -> 'catch-up-boundary' AS boundary
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id AND p.deleted_at IS NULL)
     JOIN team AS t ON (t.id = p.team_id AND t.deleted_at IS NULL)
     LEFT JOIN file_data AS fd ON (fd.file_id = f.id AND fd.id = f.id
                                  AND fd.type = 'main' AND fd.deleted_at IS NULL)
    WHERE f.id = ? AND f.deleted_at IS NULL")

(def ^:private sql:catch-up-candidates
  "WITH candidates AS MATERIALIZED (
     SELECT s.id, s.file_id, s.revn, s.session_id, s.kind, s.label, s.created_at,
            (s.deleted_at IS NULL OR s.deleted_at > now()) AS live,
            octet_length(s.changes) AS redo_size
       FROM file_change AS s
      WHERE s.file_id = ? AND s.revn > ? AND s.revn <= ?
        AND (s.label IS NULL OR s.kind = ?)
      ORDER BY s.revn
      LIMIT ?
   )
   SELECT c.id, c.revn, c.session_id, c.kind, c.label, c.created_at, c.live, c.redo_size,
          sum(c.redo_size) OVER () <= ? AS within_budget,
          fd.metadata ->> 'catch-up-vern' AS vern,
          fd.metadata ->> 'catch-up-redo-sha256' AS digest,
          fd.type = 'revision'
          AND (fd.deleted_at IS NULL OR fd.deleted_at > now())
          AND (CASE fd.backend
                 WHEN 'db' THEN fd.data IS NOT NULL AND fd.storage_object_id IS NULL
                 WHEN 'storage' THEN fd.data IS NULL AND EXISTS (
                   SELECT 1 FROM storage_object AS so
                    WHERE so.id = fd.storage_object_id
                      AND so.deleted_at IS NULL AND so.status = 'valid')
                 ELSE false
               END) AS payload_ready
     FROM candidates AS c
     LEFT JOIN file_data AS fd ON (fd.file_id = c.file_id AND fd.id = c.id)
    ORDER BY c.revn")

(def ^:private sql:catch-up-redo
  "SELECT id, changes FROM file_change WHERE file_id = ? AND id = ANY(?)")

(defn- valid-catch-up-boundary?
  "Only an explicit boundary can prove the current epoch. Restoring a
  snapshot can assign zero again, so `file.vern = 0` proves nothing alone."
  [{:keys [boundary vern revn]} since]
  (let [{kind :kind boundary-vern :vern boundary-revn :revn}
        (some-> boundary db/decode-json-pgobject)]
    (and (= vern boundary-vern)
         (int? boundary-revn)
         (<= 0 boundary-revn since revn)
         (case kind
           "genesis" (zero? boundary-vern)
           "restore" (pos? boundary-revn)
           false))))

(defn- eligible-catch-up-row?
  [vern {:keys [live session-id payload-ready within-budget redo-size digest]
         row-vern :vern :as row}]
  (and (revision-candidate? row)
       live session-id payload-ready within-budget redo-size digest
       (= (str vern) row-vern)))

(defn- get-catch-up-candidates
  [conn file-id since through vern confirm-revn confirm-id cutoff]
  (let [end        (or confirm-revn through)
        candidates (if (= since end)
                     []
                     (db/exec! conn [sql:catch-up-candidates file-id since end
                                     fchanges/revision-kind (inc catch-up-row-limit) catch-up-redo-limit]))]
    ;; Do not filter out bad or expired candidates before checking that each
    ;; revision number has exactly one row.
    (when (and (contiguous-revisions? since end candidates)
               (every? #(eligible-catch-up-row? vern %) candidates)
               (or (nil? confirm-id) (= confirm-id (:id (peek candidates))))
               ;; The confirmation counts toward every budget and still has
               ;; to be live, but it isn't one of the missing deltas.
               (every? #(recent-revision? cutoff %)
                       (if confirm-id (pop candidates) candidates)))
      candidates)))

(defn- get-catch-up-rows
  [conn file-id candidates]
  (if (empty? candidates)
    []
    (let [ids   (db/create-array conn "uuid" (map :id candidates))
          blobs (into {} (map (juxt :id :changes)) (db/exec! conn [sql:catch-up-redo file-id ids]))]
      (loop [candidates (seq candidates)
             limit      catch-up-decoded-limit
             result     []]
        (if-let [{:keys [id digest] :as row} (first candidates)]
          (let [bytes (get blobs id)]
            ;; Verify the exact bytes against the server's classification
            ;; before passing any length from the blob to the decoder.
            (when (and bytes (= digest (get-redo-digest bytes)))
              (when-let [{:keys [data uncompressed-size]}
                         (try
                           (blob/decode-bounded bytes limit)
                           (catch Exception _ nil))]
                (recur (next candidates)
                       (- limit uncompressed-size)
                       (conj result (assoc (select-keys row [:id :revn :session-id])
                                           :changes data))))))
          result)))))

(defn- get-file-changes*
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id file-id since until vern confirm-revn confirm-id]}]
  (files/check-read-permissions! cfg profile-id file-id)
  (let [{head :revn head-vern :vern :as file}
        (db/get-with-sql conn [sql:catch-up-head file-id])
        through (or until head)
        result  {:head head :vern head-vern}
        cutoff  (ct/minus (ct/now) catch-up-window)]
    (or (when (and (contains? cf/flags :file-catch-up)
                   (= vern head-vern)
                   (<= since through head)
                   (or (nil? confirm-revn)
                       (and (< through confirm-revn)
                            (= confirm-revn (inc through))
                            (<= confirm-revn head)))
                   (<= (- (or confirm-revn through) since) catch-up-row-limit)
                   (valid-catch-up-boundary? file since)
                   (not (fmg/need-migration? (fmigr/resolve-applied-migrations cfg file))))
          (when-let [candidates (get-catch-up-candidates conn file-id since through vern
                                                         confirm-revn confirm-id cutoff)]
            (when-let [rows (get-catch-up-rows conn file-id candidates)]
              (if confirm-id
                (assoc result :through through :rows (pop rows)
                       :confirmed-row {:revn confirm-revn :id confirm-id})
                (assoc result :through through :rows rows)))))
        (assoc result :reload true))))

(sv/defmethod ::get-file-changes
  "Read a complete reproducible revision range, or require a full file read."
  {::doc/added "2.21"
   ::doc/module :files
   ::audit/skip true
   ::rpc/id-type :file
   ::sm/params schema:get-file-changes
   ::sm/result schema:get-file-changes-result}
  [cfg {:keys [confirm-revn confirm-id] :as params}]
  (when (not= (some? confirm-revn) (some? confirm-id))
    (ex/raise :type :validation
              :code :params-validation
              :hint "confirm-revn and confirm-id must be supplied together"))
  (-> (db/transact! (::db/pool cfg)
                    (fn [conn] (get-file-changes* (assoc cfg ::db/conn conn) params))
                    {:read-only true :isolation :repeatable-read})
      (rph/with-header "cache-control" "no-store")))

(defn- send-notifications
  [cfg {:keys [team session-id profile-id repair-in-response]} file
   {:keys [changes revn repaired repair-base-revn] :as event}]
  (let [lchanges (when (:is-shared file)
                   (seq (filter library-change? changes)))
        msgbus   (::mbus/msgbus cfg)
        common   {:profile-id profile-id :file-id (:id file) :revn revn}]

    (mbus/pub! msgbus
               :topic (:id file)
               :message (cond-> (merge common event {:type :file-change :vern (:vern file)})
                          lchanges (assoc :library-change true)))

    (when lchanges
      (mbus/pub! msgbus
                 :topic (:id team)
                 :message (cond-> (assoc common
                                         :type :library-change
                                         :session-id session-id
                                         :modified-at (ct/now)
                                         :changes lchanges)
                            repair-in-response (assoc :vern (:vern file))
                            (and repair-in-response repaired) (assoc :repaired true)
                            (some? repair-base-revn) (assoc :repair-base-revn repair-base-revn))))))
