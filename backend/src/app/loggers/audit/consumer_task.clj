;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumer-task
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
;; per destination: `:audit-log-consumer-nexus` and
;; `:audit-log-consumer-admin-console`. Each job drains only its own queue
;; and deletes its pendings on completion; whoever deletes the last
;; pending also deletes the record ("last one out turns off the
;; light"), so no separate GC task is needed. Housekeeping (reaping
;; disabled pendings, sweeping orphans) lives in both jobs on purpose:
;; only the enabled job runs, so it must clean up for the rest.

;; Consumer ids are fixed constants, never reuse one: retired
;; pendings are reaped by id, so a reused id would inherit the
;; previous owner's queue.
(def consumer-nexus 1)
(def consumer-admin-console 2)

(def ^:private consumers
  [{:id consumer-nexus          :flag :nexus         :label "nexus"}
   {:id consumer-admin-console :flag :admin-console :label "admin-console"}])

(def ^:private xf:enabled-consumers
  "Static transducer: keep the consumers whose send flag is on.
  Allocated once so hot paths (every audited RPC) do not rebuild
  it per call; the flag lookup itself still reads the live flags."
  (comp (filter #(contains? cf/flags (:flag %)))
        (map :id)))

(defn enabled-consumers
  "Consumers allowed to work right now, from the send flags. Public:
  producers queue every record for exactly these consumers."
  []
  (into [] xf:enabled-consumers consumers))

(defn queue-pendings
  "Queue records for every enabled consumer, nothing when there is
  nothing to queue or no consumer is on. One multi-row statement for
  all ids instead of one per consumer: this runs inside every audited
  RPC and the bulk frontend ingest. Runs inside the row transaction:
  a rollback takes both."
  [cfg ids]
  (let [consumers (enabled-consumers)]
    (when (and (seq ids) (seq consumers))
      (db/insert-many! cfg :audit-log-pending
                       [:audit-log-id :consumer]
                       (for [id ids
                             consumer consumers]
                         [id consumer])
                       {::sql/on-conflict-do-nothing true}))))

(def ^:private consumer-labels
  (into {} (map (juxt :id :label)) consumers))

(defn- consumer-label
  [consumer]
  (get consumer-labels consumer))

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
;; so every consumer takes the same total order and a second
;; overlapping run waits for the first to commit, then sees whether
;; it was last. The brief blocking is accepted; skipping rows could
;; reorder archive arrival.
(def ^:private sql:pending-chunk
  "SELECT a.*
     FROM audit_log_pending p
     JOIN audit_log a ON a.id = p.audit_log_id
    WHERE p.consumer = ?::smallint
    ORDER BY a.created_at ASC, a.id ASC
    LIMIT 128
      FOR UPDATE OF a")

(def ^:private sql:delete-pendings
  "DELETE FROM audit_log_pending
    WHERE consumer = ?::smallint
      AND audit_log_id = ANY(?)")

(def ^:private sql:delete-drained
  "DELETE FROM audit_log a
    WHERE a.id = ANY(?)
      AND NOT EXISTS (
        SELECT 1 FROM audit_log_pending p
        WHERE p.audit_log_id = a.id
      )")

(defn- get-pending-rows
  [{:keys [::db/conn] :as cfg} consumer]
  (->> (db/exec! conn [sql:pending-chunk consumer])
       (not-empty)))

(defn- complete-batch
  "Delete our pendings for the batch, then the records nobody is
  waiting for anymore. Two statements on purpose: inside a single
  statement the NOT EXISTS would still see the pending just deleted
  and never delete a record."
  [{:keys [::db/conn]} consumer rows]
  (let [ids (db/create-array conn "uuid" (map :id rows))]
    (db/exec! conn [sql:delete-pendings consumer ids])
    (db/exec! conn [sql:delete-drained ids])))

(defn- generate-nexus-uri
  "Joins relative path segments to the Nexus backend URI, same shape
  as the admin-console helper in `app.nitrate` (there the ingest path
  is `api/audit-log`; here it is `api/audit`).
  Segments must not start with `/`. Returns nil when no base URI is
  configured, so the caller keeps the `:task-not-configured` guard."
  [& segments]
  (when-let [base (cf/get :nexus-uri)]
    (apply cf/join-uri base segments)))

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
                       :code :consumer-chunk-refused
                       :hint "nexus refused archive chunk"
                       :resp-status (:status resp)
                       :resp-body (truncate-body (:body resp) 500)
                       :events n)))))

(def ^:private event-names-for-admin-console
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

;; Props id key -> email prop filled from profile.email (admin-console path only).
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

(defn- enrich-events-for-admin-console
  "Enrich allowlisted events before the admin-console ingest:
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

(defn- send-to-admin-console
  "Ingest rows into the admin console. Returns {:sent :skipped}
  (filtered-out rows count as processed for the queue); throws
  carrying the attempted count as :events, like send-to-nexus. Enrich
  failures propagate untouched: a local DB/decode failure is ours, not
  the destination's, and must not count as a send failure."
  [cfg rows]
  (let [events   (filterv #(contains? event-names-for-admin-console (:name %)) rows)
        total    (count rows)
        skipped  (- total (count events))
        uri-host (some-> (cf/get :admin-console-uri) u/uri u/get-domain)]
    (l/dbg :hint "consumer events chunk ready"
           :consumer "admin-console"
           :uri-host uri-host
           :total total
           :skipped skipped)
    (if (empty? events)
      {:sent 0 :skipped skipped}
      ;; Enrich first, outside the try: a local DB/decode failure is
      ;; ours, not the destination's, and must not count as
      ;; admin-console/failed.
      (let [enriched (enrich-events-for-admin-console cfg events)
            n        (count events)]
        (try
          (nitrate/call cfg :ingest-audit-log {:events enriched})
          (l/dbg :hint "consumer events chunk sent"
                 :consumer "admin-console"
                 :uri-host uri-host
                 :sent n
                 :skipped skipped)
          {:sent n :skipped skipped}
          (catch Throwable cause
            (l/dbg :hint "consumer events chunk failed"
                   :consumer "admin-console"
                   :uri-host uri-host
                   :events n
                   :code :admin-console-send-failed)
            (throw (ex/raise :type :internal
                             :code :admin-console-send-failed
                             :hint "unable to archive events to the admin console"
                             :cause cause
                             :events n))))))))

(defn- consumer-nexus-chunk
  "Send one pending chunk to Nexus and complete it. Returns {:sent n}
  or nil when the queue is empty; throws on refusal. Takes a
  transactional cfg: the caller opens one transaction per chunk. No
  metrics here: a normal return means the transaction committed, so
  the caller records the success."
  [cfg]
  (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
  (when-let [rows (get-pending-rows cfg consumer-nexus)]
    (let [events (mapv row->event (mapv decode-row rows))
          ;; send-to-nexus returns the count or throws: a refusal
          ;; aborts the job, the rows stay for the next tick.
          n      (send-to-nexus cfg events)]

      (l/trc :hint "consumer events chunk"
             :consumer "nexus"
             :events n)

      (complete-batch cfg consumer-nexus rows)
      {:sent n})))

(defn- consumer-admin-console-chunk
  "Send one pending chunk to the admin console and complete it. Returns
  {:sent :skipped} or nil when the queue is empty; throws on failure.
  Takes a transactional cfg, no metrics here: same contract as
  consumer-nexus-chunk."
  [cfg]
  (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
  (when-let [rows (get-pending-rows cfg consumer-admin-console)]
    (let [decoded     (mapv decode-row rows)
          _           (l/trc :hint "consumer events chunk"
                             :consumer "admin-console"
                             :events (count decoded))
          admin-console-res (send-to-admin-console cfg decoded)]
      ;; Filtered-out rows count as processed for the
      ;; admin-console queue: their pending is done, the
      ;; record stays until every consumer is done.
      (complete-batch cfg consumer-admin-console rows)
      admin-console-res)))

(defn- drain-consumer
  "Drain one consumer queue chunk by chunk, one transaction per chunk.
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
  [consumer]
  (str "DELETE FROM audit_log_pending"
       " WHERE consumer = " consumer
       "   AND audit_log_id IN (SELECT audit_log_id FROM audit_log_pending"
       " WHERE consumer = " consumer
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
  "Drop the pendings of consumers that are off: a disabled destination
  will never drain them, and without this they would pin their records
  forever. This also absorbs any later deactivation, with no manual step.
  Dropped pendings count as discarded: their rows may still be archived
  by an enabled consumer, or swept unsent when nothing waits for them."
  [cfg]
  (let [total (->> consumers
                   (remove #(contains? cf/flags (:flag %)))
                   (map #(delete-batched cfg (sql:reap-pendings (:id %))))
                   (reduce + 0))]
    (when (pos? total)
      (record-count (mtx/instance cfg) :audit-consumer-discarded ["reaped"] total))
    total))

(defn- sweep-orphans
  "Delete records nobody waits for anymore: rows born while no
  consumer was on, rows left pending-less by the reap, and leftovers
  archived by the old task. Batched and normally a no-op. Deleted rows
  count as discarded: on a live system they were never archived."
  [cfg]
  (let [total (delete-batched cfg sql:sweep-orphans)]
    (when (pos? total)
      (record-count (mtx/instance cfg) :audit-consumer-discarded ["swept"] total))
    total))

(declare execute-audit-log-consumer-nexus)
(declare execute-audit-log-consumer-admin-console)

(def schema:audit-log-consumer-nexus-params
  "Overrides for the nexus job repl invocation defaults.
  Cron submits {}; system deps (pool, shared-keys, http) live on the
  job-def cfg closed over by the handler, not in job params.
  Closed on purpose: a stale key (like the old :enabled) must fail
  validation loudly instead of skipping silently."
  [:map {:closed true}
   [:uri {:optional true} ::sm/uri]])

(def schema:audit-log-consumer-admin-console-params
  "Params of the admin-console job: none. Cron submits {}; system deps
  (pool, admin-console client) live on the job-def cfg closed over by
  the handler, not in job params. Closed on purpose, same as the nexus
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
  (l/dbg :hint "audit log consumer job initialized"
         :consumer "nexus"
         :uri-host (some-> (cf/get :nexus-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-consumer-nexus
   ::jobs/schema    schema:audit-log-consumer-nexus-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-consumer-nexus cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-consumer-nexus-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-consumer-nexus-params)})

(defmethod ig/assert-key ::admin-console-job-def
  [_ cfg]
  ;; No assert on the admin-console client: it inits to nil without the
  ;; :admin-console flag, and the job skips on its flag anyway.
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::admin-console-job-def
  [_ cfg]
  ;; Same as the nexus job-def: destination host once at boot. The
  ;; ingest uri is resolved by nitrate/call at send time (it raises
  ;; when missing); here it is display-only.
  (l/dbg :hint "audit log consumer job initialized"
         :consumer "admin-console"
         :uri-host (some-> (cf/get :admin-console-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-consumer-admin-console
   ::jobs/schema    schema:audit-log-consumer-admin-console-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-consumer-admin-console cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-consumer-admin-console-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-consumer-admin-console-params)})

(defn- run-single-consumer
  "Drain one consumer queue, then housekeeping (reap disabled
  pendings, sweep orphans) and the per-run summary. Housekeeping lives
  in both jobs: only the enabled job runs, so it must reap the queues
  of the consumers that are off; both halves are idempotent deletes.
  A refused chunk (or a throw) aborts the job: the committed chunks
  stay sent, the rest waits for the next tick, and the cron retries.
  Every effect is rows and logs (drain metrics land here, outside
  every chunk transaction)."
  [cfg consumer send-chunk tpoint]
  (let [label   (consumer-label consumer)
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
                  (drain-consumer cfg send-chunk)
                  (catch Throwable cause
                    (let [n (:events (ex-data cause))]
                      (when (and (integer? n) (pos? n))
                        (record-count metrics :audit-consumer-events [label "failed"] n)))
                    (throw cause)))
        _       (let [{:keys [sent skipped]} drained]
                  (when (pos? sent)
                    (record-count metrics :audit-consumer-events [label "archived"] sent))
                  (when (pos? skipped)
                    (record-count metrics :audit-consumer-events [label "skipped"] skipped)))
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
             :consumer label)
      (l/dbg :hint "events archived"
             :consumer label
             :elapsed elapsed
             :sent sent
             :skipped skipped
             :chunks chunks
             :reaped reaped
             :swept swept))))

(defn execute-audit-log-consumer-nexus
  "Nexus job handler: drain the nexus queue in chunks, then clean up
  (heartbeat per iteration: the sent chunk batches can be long)."
  [cfg params]
  ;; NOTE: this let allows overwrite default configured values from
  ;; the repl, when manually invoking the task.
  (if-not (contains? cf/flags :nexus)
    (l/dbg :hint "audit log consumer (nexus) skipped, flag off")
    (let [uri (or (:uri params) (generate-nexus-uri "api/audit"))
          ;; Normalize to an uri object; params may carry a plain string
          ;; on direct invocations (validation only applies on submit!).
          uri (u/uri uri)
          cfg (assoc cfg ::uri uri)]
      (when-not uri
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "consumer task not configured, missing uri"))
      (run-single-consumer cfg consumer-nexus consumer-nexus-chunk (ct/tpoint)))))

(defn execute-audit-log-consumer-admin-console
  "Admin-console job handler: drain the admin-console queue in chunks,
  then clean up (heartbeat per iteration: the sent chunk batches can
  be long)."
  [cfg _params]
  (if-not (contains? cf/flags :admin-console)
    (l/dbg :hint "audit log consumer (admin-console) skipped, flag off")
    (run-single-consumer cfg consumer-admin-console consumer-admin-console-chunk (ct/tpoint))))
