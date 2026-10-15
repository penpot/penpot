;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.archive-task
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http.client :as http]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.nitrate :as nitrate]
   [app.setup :as-alias setup]
   [integrant.core :as ig]
   [promesa.exec :as px]))

;; This task drains the audit log buffer into the long-term archives.
;;
;; Two separate jobs share this namespace and the queue helpers, one
;; per destination: `:audit-log-archive-nexus` and
;; `:audit-log-archive-nitrate`. Each job drains only its own queue
;; and deletes its pendings on completion; whoever deletes the last
;; pending also deletes the record ("last one out turns off the
;; light"), so no separate GC task is needed. Housekeeping (reaping
;; disabled pendings, sweeping orphans) lives in both jobs on purpose:
;; only the enabled job runs, so it must clean up for the rest.

;; Archiver ids are fixed constants, never reuse one: retired
;; pendings are reaped by id, so a reused id would inherit the
;; previous owner's queue.
(def archiver-nexus 1)
(def archiver-nitrate 2)

(def ^:private archivers
  [{:id archiver-nexus   :flag :nexus         :label "nexus"}
   {:id archiver-nitrate :flag :admin-console :label "nitrate"}])

(def ^:private xf:enabled-archivers
  "Static transducer: keep the archivers whose send flag is on.
  Allocated once so hot paths (every audited RPC) do not rebuild
  it per call; the flag lookup itself still reads the live flags."
  (comp (filter #(contains? cf/flags (:flag %)))
        (map :id)))

(defn enabled-archivers
  "Archivers allowed to work right now, from the send flags. Public:
  producers queue every record for exactly these archivers."
  []
  (into [] xf:enabled-archivers archivers))

(defn insert-pendings
  "Queue a record for every enabled archiver, nothing when none is
  on. One multi-row statement instead of one per archiver: this runs
  inside every audited RPC. Runs inside the row transaction: a
  rollback takes both."
  [cfg id]
  (let [archivers (enabled-archivers)]
    (when (seq archivers)
      (db/insert-many! cfg :audit-log-pending
                       [:audit-log-id :archiver]
                       (map (fn [archiver] [id archiver]) archivers)
                       {::sql/on-conflict-do-nothing true}))))

(def ^:private archiver-labels
  (into {} (map (juxt :id :label)) archivers))

(defn- archiver-label
  [archiver]
  (get archiver-labels archiver))

(defn- decode-row
  [{:keys [props ip-addr context] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))

    (db/pgobject? context)
    (assoc :context (db/decode-transit-pgobject context))

    (db/pgobject? ip-addr "inet")
    (assoc :ip-addr (db/decode-inet ip-addr))))

(def ^:private event-keys
  [:type
   :name
   :source
   :created-at
   :tracked-at
   :profile-id
   :ip-addr
   :props
   :context])

(defn- row->event
  [row]
  (select-keys row event-keys))

(defn- record-count
  [metrics id labels amount]
  (mtx/run! metrics :id id :labels labels :inc amount))

(defn- truncate-body
  [body limit]
  (when (some? body)
    (let [s (str body)]
      (if (> (count s) limit)
        (subs s 0 limit)
        s))))

;; NOTE: deliberately no SKIP LOCKED here. Batches must reach the
;; destinations in chronological order (Nexus numbers by arrival),
;; so every archiver takes the same total order and a second
;; overlapping run waits for the first to commit, then sees whether
;; it was last. The brief blocking is accepted; skipping rows could
;; reorder archive arrival.
(def ^:private sql:pending-chunk
  "SELECT a.*
     FROM audit_log_pending p
     JOIN audit_log a ON a.id = p.audit_log_id
    WHERE p.archiver = ?::smallint
    ORDER BY a.created_at ASC, a.id ASC
    LIMIT 128
      FOR UPDATE OF a")

(def ^:private sql:delete-pendings
  "DELETE FROM audit_log_pending
    WHERE archiver = ?::smallint
      AND audit_log_id = ANY(?)")

(def ^:private sql:delete-drained
  "DELETE FROM audit_log a
    WHERE a.id = ANY(?)
      AND NOT EXISTS (
        SELECT 1 FROM audit_log_pending p
        WHERE p.audit_log_id = a.id
      )")

(defn- get-pending-rows
  [{:keys [::db/conn] :as cfg} archiver]
  (->> (db/exec! conn [sql:pending-chunk archiver])
       (not-empty)))

(defn- complete-batch
  "Delete our pendings for the batch, then the records nobody is
  waiting for anymore. Two statements on purpose: inside a single
  statement the NOT EXISTS would still see the pending just deleted
  and never delete a record."
  [{:keys [::db/conn]} archiver rows]
  (let [ids (db/create-array conn "uuid" (map :id rows))]
    (db/exec! conn [sql:delete-pendings archiver ids])
    (db/exec! conn [sql:delete-drained ids])))

(defn- send-to-nexus
  "POST events to Nexus. Returns the number of events sent; throws on
  transport failure or non-204 refusal, carrying the attempted count
  as :events in the error data so the caller records the failure.
  No metrics or logs here: the runner reports the aborted job."
  [{:keys [::uri] :as cfg} events]
  (let [n      (count events)
        skey   (-> cfg ::setup/shared-keys :nexus)
        body   (t/encode {:events events})
        params {:uri uri
                :timeout 12000
                :method :post
                :headers {"content-type" "application/transit+json"
                          "origin" (str (cf/get :public-uri))
                          "x-shared-key" (str "nexus " skey)}
                :body body}
        resp   (try
                 (http/req cfg params {:skip-ssrf-check? true})
                 (catch Throwable cause
                   (throw (ex/raise :type :internal
                                    :code :nexus-send-failed
                                    :hint "unable to send events to nexus"
                                    :cause cause
                                    :events n))))]
    (if (= (:status resp) 204)
      n
      (throw (ex/raise :type :internal
                       :code :archive-chunk-refused
                       :hint "nexus refused archive chunk"
                       :resp-status (:status resp)
                       :resp-body (truncate-body (:body resp) 500)
                       :events n)))))

(def ^:private event-names-for-nitrate
  #{"accept-organization-invitation"
    "accept-team-invitation"
    "accept-team-invitation-from"
    "add-member-to-organization"
    "add-team-to-organization"
    "cancel-organization-invitation"
    "create-export-binfile-job"
    "create-file"
    "create-organization"
    "create-organization-attribute"
    "create-organization-invitation"
    "create-project"
    "create-team"
    "create-team-access-request"
    "create-team-invitation"
    "create-team-invitations"
    "create-webhook"
    "delete-file"
    "delete-font"
    "delete-organization"
    "delete-organization-member"
    "delete-project"
    "delete-team"
    "delete-team-invitation"
    "delete-team-member"
    "leave-team"
    "move-project"
    "move-team-to-organization"
    "organization-sso-auth-failed"
    "organization-sso-auth-started"
    "organization-sso-auth-succeeded"
    "permanently-delete-team-files"
    "remove-organization-team"
    "remove-team-from-organization"
    "rename-organization"
    "rename-project"
    "restore-deleted-team-files"
    "success-activate-organization-sso"
    "success-apply-organization-sso-changes"
    "update-organization-invitation"
    "update-organization-permissions"
    "update-team-invitation"
    "update-team-invitation-role"
    "update-team-member-role"
    "update-team-photo"
    "verify-token"})

;; Props id key -> email prop filled from profile.email (nitrate path only).
;; Some producers store ids as UUID strings (e.g. :user-who-send-invitation).
(def ^:private id->email-prop
  {:profile-id                 :profile-email
   :user-id                    :user-email
   :member-id                  :member-email
   :user-who-send-invitation   :user-who-send-invitation-email})

(defn- collect-team-ids-needing-name
  [events]
  (into #{}
        (comp (map :props)
              (keep (fn [props]
                      (when-let [team-id (and (not (contains? props :team-name))
                                              (uuid/coerce (:team-id props)))]
                        team-id))))
        events))

(defn- collect-profile-ids-needing-email
  [events]
  (into #{}
        (mapcat (fn [{:keys [props]}]
                  (keep (fn [[id-key email-key]]
                          (when-not (contains? props email-key)
                            (uuid/coerce (get props id-key))))
                        id->email-prop)))
        events))

(defn- load-team-names
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, name FROM team WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id :name) rows)))
    {}))

(defn- load-profile-emails
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, email FROM profile WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id :email) rows)))
    {}))

(defn- maybe-assoc-team-name
  [event team-names]
  (let [props (:props event)]
    (if-let [team-name (and (not (contains? props :team-name))
                            (when-let [team-id (uuid/coerce (:team-id props))]
                              (get team-names team-id)))]
      (assoc-in event [:props :team-name] team-name)
      event)))

(defn- maybe-assoc-emails
  [event emails]
  (update event :props
          (fn [props]
            (reduce-kv (fn [props id-key email-key]
                         (if-let [email (and (not (contains? props email-key))
                                             (when-let [id (uuid/coerce (get props id-key))]
                                               (get emails id)))]
                           (assoc props email-key email)
                           props))
                       props
                       id->email-prop))))

(defn- enrich-events-for-nitrate
  "Enrich allowlisted events before nitrate ingest:
  1. Fill missing :team-name from team id (including soft-deleted).
  2. Fill missing emails from matching id keys (including soft-deleted
     profiles): :profile-email, :user-email, :member-email,
     :user-who-send-invitation-email."
  [{:keys [::db/conn]} events]
  (let [team-ids    (collect-team-ids-needing-name events)
        profile-ids (collect-profile-ids-needing-email events)
        team-names  (load-team-names conn team-ids)
        emails      (load-profile-emails conn profile-ids)]
    (mapv (fn [event]
            (-> event
                (maybe-assoc-team-name team-names)
                (maybe-assoc-emails emails)))
          events)))

(defn- send-to-nitrate
  "Ingest rows into Nitrate. Returns {:sent :skipped} (filtered-out
  rows count as processed for the queue); throws carrying the
  attempted count as :events, like send-to-nexus. Enrich failures
  propagate untouched: a local DB/decode failure is ours, not
  nitrate's, and must not count as a send failure."
  [cfg rows]
  (let [events  (filterv #(contains? event-names-for-nitrate (:name %)) rows)
        skipped (- (count rows) (count events))]
    (if (empty? events)
      {:sent 0 :skipped skipped}
      ;; Enrich first, outside the try: a local DB/decode failure is
      ;; ours, not nitrate's, and must not count as nitrate/failed.
      (let [enriched (enrich-events-for-nitrate cfg events)
            n        (count events)]
        (try
          (nitrate/call cfg :ingest-audit-log {:events enriched})
          {:sent n :skipped skipped}
          (catch Throwable cause
            (throw (ex/raise :type :internal
                             :code :nitrate-send-failed
                             :hint "unable to archive events to nitrate"
                             :cause cause
                             :events n))))))))

(defn- archive-nexus-chunk
  "Send one pending chunk to Nexus and complete it. Returns {:sent n}
  or nil when the queue is empty; throws on refusal. Takes a
  transactional cfg: the caller opens one transaction per chunk. No
  metrics here: a normal return means the transaction committed, so
  the caller records the success."
  [cfg]
  (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
  (when-let [rows (get-pending-rows cfg archiver-nexus)]
    (let [events (mapv row->event (mapv decode-row rows))
          ;; send-to-nexus returns the count or throws: a refusal
          ;; aborts the job, the rows stay for the next tick.
          n      (send-to-nexus cfg events)]

      (l/trc :hint "archive events chunk"
             :archiver "nexus"
             :events n)

      (complete-batch cfg archiver-nexus rows)
      {:sent n})))

(defn- archive-nitrate-chunk
  "Send one pending chunk to Nitrate and complete it. Returns
  {:sent :skipped} or nil when the queue is empty; throws on failure.
  Takes a transactional cfg, no metrics here: same contract as
  archive-nexus-chunk."
  [cfg]
  (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
  (when-let [rows (get-pending-rows cfg archiver-nitrate)]
    (let [decoded     (mapv decode-row rows)
          _           (l/trc :hint "archive events chunk"
                             :archiver "nitrate"
                             :events (count decoded))
          nitrate-res (send-to-nitrate cfg decoded)]
      ;; Filtered-out rows count as processed for the
      ;; nitrate queue: their pending is done, the
      ;; record stays until every archiver is done.
      (complete-batch cfg archiver-nitrate rows)
      nitrate-res)))

(defn- drain-archiver
  "Drain one archiver queue chunk by chunk, one transaction per chunk.
  Returns totals; a chunk the destination refuses (or that throws)
  aborts the drain with a throw, and the committed chunks so far
  stay sent."
  [cfg send-chunk]
  (loop [sent    0
         skipped 0
         chunks  0]
    ;; One transaction per chunk: fetch, send and complete commit (or
    ;; roll back) together, and the next chunk sees a fresh snapshot.
    (if-let [res (db/tx-run! cfg send-chunk)]
      (do
        (jobs/heartbeat cfg)
        (px/sleep 100)
        (recur (+ sent ^long (:sent res))
               (+ skipped ^long (:skipped res 0))
               (inc chunks)))
      {:sent sent :skipped skipped :chunks chunks})))

(defn- sql:reap-pendings
  [archiver]
  (str "DELETE FROM audit_log_pending"
       " WHERE archiver = " archiver
       "   AND audit_log_id IN (SELECT audit_log_id FROM audit_log_pending"
       " WHERE archiver = " archiver
       " ORDER BY audit_log_id LIMIT 5000)"))

(def ^:private sql:sweep-orphans
  "DELETE FROM audit_log a
    WHERE a.id IN (SELECT a2.id FROM audit_log a2
                    WHERE NOT EXISTS (SELECT 1 FROM audit_log_pending p
                                       WHERE p.audit_log_id = a2.id)
                    ORDER BY a2.id LIMIT 5000)")

(defn- delete-batched
  "Run a batched DELETE until it removes nothing. Returns the total."
  [cfg sql]
  (loop [total 0]
    (let [n (db/get-update-count (db/exec-one! cfg [sql]))]
      (if (pos? n)
        (do
          (px/sleep 100)
          (recur (+ total ^long n)))
        total))))

(defn- reap-disabled
  "Drop the pendings of archivers that are off: a disabled destination
  will never drain them, and without this they would pin their records
  forever. This also absorbs any later deactivation, with no manual step.
  Dropped pendings count as discarded: their rows may still be archived
  by an enabled archiver, or swept unsent when nothing waits for them."
  [cfg]
  (let [total (->> archivers
                   (remove #(contains? cf/flags (:flag %)))
                   (map #(delete-batched cfg (sql:reap-pendings (:id %))))
                   (reduce + 0))]
    (when (pos? total)
      (record-count (mtx/instance cfg) :audit-archive-discarded ["reaped"] total))
    total))

(defn- sweep-orphans
  "Delete records nobody waits for anymore: rows born while no
  archiver was on, rows left pending-less by the reap, and leftovers
  archived by the old task. Batched and normally a no-op. Deleted rows
  count as discarded: on a live system they were never archived."
  [cfg]
  (let [total (delete-batched cfg sql:sweep-orphans)]
    (when (pos? total)
      (record-count (mtx/instance cfg) :audit-archive-discarded ["swept"] total))
    total))

(declare execute-audit-log-archive-nexus)
(declare execute-audit-log-archive-nitrate)

(def schema:audit-log-archive-nexus-params
  "Overrides for the nexus job repl invocation defaults.
  Cron submits {}; system deps (pool, shared-keys, http) live on the
  job-def cfg closed over by the handler, not in job params.
  Closed on purpose: a stale key (like the old :enabled) must fail
  validation loudly instead of skipping silently."
  [:map {:closed true}
   [:uri {:optional true} ::sm/uri]])

(def schema:audit-log-archive-nitrate-params
  "Params of the nitrate job: none. Cron submits {}; system deps
  (pool, nitrate client) live on the job-def cfg closed over by the
  handler, not in job params. Closed on purpose, same as the nexus
  schema."
  [:map {:closed true}])

(defmethod ig/assert-key ::nexus-job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (http/client? (::http/client cfg)) "expected a valid http client")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::nexus-job-def
  [_ cfg]
  ;; Effective destination, once at boot: per-run logs carry counts,
  ;; the runner says the job started, this says where it sends.
  (l/dbg :hint "audit log archive job initialized"
         :archiver "nexus"
         :uri-host (some-> (cf/get :audit-log-archive-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-archive-nexus
   ::jobs/schema    schema:audit-log-archive-nexus-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-archive-nexus cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-archive-nexus-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-archive-nexus-params)})

(defmethod ig/assert-key ::nitrate-job-def
  [_ cfg]
  ;; No assert on the nitrate client: it inits to nil without the
  ;; :admin-console flag, and the job skips on its flag anyway.
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::nitrate-job-def
  [_ cfg]
  ;; Same as the nexus job-def: destination host once at boot. The
  ;; ingest uri is resolved by nitrate/call at send time (it raises
  ;; when missing); here it is display-only.
  (l/dbg :hint "audit log archive job initialized"
         :archiver "nitrate"
         :uri-host (some-> (cf/get :admin-console-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-archive-nitrate
   ::jobs/schema    schema:audit-log-archive-nitrate-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-archive-nitrate cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-archive-nitrate-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-archive-nitrate-params)})

(defn- run-single-archiver
  "Drain one archiver queue, then housekeeping (reap disabled
  pendings, sweep orphans) and the per-run summary. Housekeeping lives
  in both jobs: only the enabled job runs, so it must reap the queues
  of the archivers that are off; both halves are idempotent deletes.
  A refused chunk (or a throw) aborts the job: the committed chunks
  stay sent, the rest waits for the next tick, and the cron retries.
  Every effect is rows and logs (drain metrics land here, outside
  every chunk transaction)."
  [cfg archiver send-chunk tpoint]
  (let [label   (archiver-label archiver)
        metrics (mtx/instance cfg)
        reaped  (reap-disabled cfg)
        ;; All drain metrics land here, outside every chunk
        ;; transaction. Success is recorded on the totals: a normal
        ;; chunk return means its transaction committed (a rollback
        ;; always throws, taking the failure path instead). The send
        ;; fns carry the attempted count as :events; anything else (a
        ;; local DB failure) is not a destination failure and stays
        ;; uncounted. The throw keeps rolling: a failed chunk aborts
        ;; the job, the cron retries.
        drained (try
                  (drain-archiver cfg send-chunk)
                  (catch Throwable cause
                    (let [n (:events (ex-data cause))]
                      (when (and (integer? n) (pos? n))
                        (record-count metrics :audit-archive-events [label "failed"] n)))
                    (throw cause)))
        _       (let [{:keys [sent skipped]} drained]
                  (when (pos? sent)
                    (record-count metrics :audit-archive-events [label "archived"] sent))
                  (when (pos? skipped)
                    (record-count metrics :audit-archive-events [label "skipped"] skipped)))
        swept   (sweep-orphans cfg)
        sent    (:sent drained)
        skipped (:skipped drained)
        chunks  (:chunks drained)
        ;; Strict binding on purpose: log props evaluate lazily in
        ;; the logger thread, so the elapsed must be frozen here.
        elapsed (ct/format-duration (tpoint))]
    (if (and (zero? sent)
             (zero? skipped)
             (zero? reaped)
             (zero? swept))
      (l/dbg :hint "no pending audit events"
             :archiver label)
      (l/dbg :hint "events archived"
             :archiver label
             :elapsed elapsed
             :sent sent
             :skipped skipped
             :chunks chunks
             :reaped reaped
             :swept swept))))

(defn execute-audit-log-archive-nexus
  "Nexus job handler: drain the nexus queue in chunks, then clean up
  (heartbeat per iteration: the sent chunk batches can be long)."
  [cfg params]
  ;; NOTE: this let allows overwrite default configured values from
  ;; the repl, when manually invoking the task.
  (if-not (contains? cf/flags :nexus)
    (l/dbg :hint "audit log archive (nexus) skipped, flag off")
    (let [uri (cf/get :audit-log-archive-uri)
          uri (or uri (:uri params))
          ;; Normalize to an uri object; params may carry a plain string
          ;; on direct invocations (validation only applies on submit!).
          uri (u/uri uri)
          cfg (assoc cfg ::uri uri)]
      (when-not uri
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "archive task not configured, missing uri"))
      (run-single-archiver cfg archiver-nexus archive-nexus-chunk (ct/tpoint)))))

(defn execute-audit-log-archive-nitrate
  "Nitrate job handler: drain the nitrate queue in chunks, then clean
  up (heartbeat per iteration: the sent chunk batches can be long)."
  [cfg _params]
  (if-not (contains? cf/flags :admin-console)
    (l/dbg :hint "audit log archive (nitrate) skipped, flag off")
    (run-single-archiver cfg archiver-nitrate archive-nitrate-chunk (ct/tpoint))))
