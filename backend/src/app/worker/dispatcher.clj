;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.dispatcher
  (:require
   [app.common.data :as d]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.metrics :as metrics]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.worker :as wrk]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

(def ^:private schema:dispatcher
  [:map
   [::wrk/tenant ::sm/text]
   [::lease {:optional true} ::ct/duration]
   ::mtx/metrics
   ::db/pool
   ::rds/client])

(defmethod ig/expand-key ::wrk/dispatcher
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::timeout (ct/duration "10s"))
         (assoc ::batch-size 100)
         (assoc ::wait-duration (ct/duration "5s"))
         (assoc ::lease (cf/get-jobs-lease)))})

(defmethod ig/assert-key ::wrk/dispatcher
  [_ cfg]
  (assert (sm/check schema:dispatcher cfg)))

(def ^:private sql:select-next-jobs
  "SELECT id, name, queue, scheduled_at from job AS t
    WHERE t.scheduled_at <= ?::timestamptz
      AND (t.status = 'new' OR t.status = 'retry')
      AND t.tenant = ?
    ORDER BY t.priority DESC, t.scheduled_at
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(def ^:private sql:mark-job-scheduled
  "UPDATE job SET status = 'scheduled', modified_at = ?
    WHERE id = ANY(?)")

;; The sweeps are bulk UPDATEs that run before every dispatch batch, so
;; they are the queries where a missing tenant filter does the most
;; damage: without it any instance with a worker aborts another
;; instance's jobs.
(def ^:private lost-batch-size
  "Rows per lost-sweep read. The dispatcher loops without pausing while it
  finds work, so an unbounded read would turn a big `scheduled` backlog
  into a repeated scan. Aborting a row takes it out of the candidate set,
  so the next pass continues where this one stopped."
  1000)

(def ^:private sql:lost-past-cap
  "The `scheduled` rows of one tenant that have waited past the cap, oldest
  first. They are lost behind a backlog or in a queue with no consumer
  whatever the queue holds, so the sweep aborts them without an `LLEN`.
  `modified_at` is the hand-off time (the push), so it measures time in the
  queue, not the due time."
  "SELECT id, queue, modified_at
     FROM job
    WHERE status = 'scheduled'
      AND tenant = ?
      AND modified_at < ?::timestamptz
    ORDER BY modified_at ASC
    LIMIT ?")

(def ^:private sql:lost-in-grace
  "The `scheduled` rows of one tenant inside the grace window, oldest first.
  These are the only candidates that need the per-queue `LLEN`: they are
  lost when nothing is waiting for them."
  "SELECT id, queue, modified_at
     FROM job
    WHERE status = 'scheduled'
      AND tenant = ?
      AND modified_at >= ?::timestamptz
      AND modified_at < ?::timestamptz
    ORDER BY modified_at ASC
    LIMIT ?")

(def ^:private sql:abort-lost
  "Abort the lost rows by id. The status, the tenant and the hand-off time
  are repeated in the outer WHERE so a claim, a heartbeat or a competing
  dispatcher that lands between the candidate read and this UPDATE affects
  no row."
  "UPDATE job
      SET status='aborted', modified_at=?::timestamptz, error=?::jsonb
    WHERE id = ANY(?)
      AND status = 'scheduled'
      AND tenant = ?
      AND modified_at < ?::timestamptz
RETURNING job.id, job.queue, job.name, job.created_at")

(def ^:private sql:mark-orphan
  "Abort the running rows past the lease. The predicate is repeated in the
  outer WHERE so a heartbeat that renews the row between the subquery and
  the update affects no row."
  "UPDATE job
      SET status='aborted', modified_at=?::timestamptz, error=?::jsonb
     FROM (SELECT t.id
             FROM job AS t
            WHERE status = 'running'
              AND t.tenant = ?
              AND t.modified_at < ?::timestamptz) AS subquery
    WHERE job.id=subquery.id
      AND job.status = 'running'
      AND job.modified_at < ?::timestamptz
RETURNING job.id, job.queue, job.name, job.created_at")

(def ^:private lost-grace
  "How long a `scheduled` row may wait before its queue is inspected. It
  also covers the pop-to-claim gap of a message popped right after the
  hand-off. A message that waited longer in the queue and is popped while
  the sweep runs is not covered: the window is the pop-to-claim round trip
  and the abort can win it (see `abort-lost-jobs`)."
  (ct/duration "5m"))

(def ^:private lost-cap
  "Beyond this age a `scheduled` row is aborted even if its queue still has
  messages: the job is lost behind a backlog, or its queue has no consumer.
  A daily cron lost in a backed-up queue can miss one run, because its live
  row blocks the next submit until the cap."
  (ct/duration "24h"))

;; the error travels as text and PostgreSQL casts it: a PGobject parameter
;; on a `::jsonb` placeholder is not what the driver expects here. Same
;; for the `end` event payload below.
(def ^:private orphan-error-json
  (json/encode jobs/orphan-error))

(def ^:private lost-error-json
  (json/encode jobs/lost-error))

(def ^:private aborted-outcome-json
  (json/encode {:outcome "aborted"}))

(def ^:private sql:insert-aborted-events
  "INSERT INTO job_event (job_id, kind, payload)
   SELECT unnest(?::uuid[]), 'end', ?::jsonb")

(defn- encode-payload
  [{:keys [id scheduled-at]}]
  (json/encode [(str id) (ct/format-inst scheduled-at)]))

(defn- record-aborted
  "The durable side of one abort sweep: the `end` events in the same
  transaction (one bulk insert, never one per row) and the terminal metric
  after the commit. No msgbus notification: an aborted job alerts through
  the error log and `penpot_jobs_total_ms{outcome=\"aborted\"}`."
  [{:keys [::db/conn ::timestamp] :as cfg} rows]
  (when (seq rows)
    (db/exec-one! conn [sql:insert-aborted-events
                        (db/create-array conn "uuid" (map :id rows))
                        aborted-outcome-json]))
  (db/after-commit
   (fn []
     (doseq [{:keys [name created-at]} rows]
       (metrics/record-total cfg name :aborted
                             (- (inst-ms timestamp) (inst-ms created-at)))))))

(defn- get-waiting
  "Messages waiting in each of the given queues, one LLEN per queue."
  [{:keys [::rds/conn ::wrk/tenant]} queues]
  (into {}
        (map (fn [queue]
               [queue (rds/llen conn (wrk/queue-key tenant queue))]))
        queues))

(defn- abort-lost-jobs
  "Abort the rows that were handed to a queue and never claimed. A row is
  lost when it has been `scheduled` past the cap (behind a backlog, or in a
  queue without a consumer), or when its queue has nothing waiting and it
  has been `scheduled` longer than the grace (a worker that popped it died
  before claiming). Lost rows are never re-dispatched: nothing returns to
  `new`, which keeps the dedupe index out of this path.

  The two branches read a bounded page of candidates, oldest first: the
  dispatcher loops without pausing while it finds work, so an unbounded
  read would scan a big `scheduled` backlog on every iteration. Aborting
  removes a row from the next read. Only the grace rows pay the `LLEN`; a
  row past the cap is lost whatever its queue holds, so a younger lost row
  behind a large legitimate backlog may wait for the cap instead of the
  grace.

  The grace is measured from the push, so a worker that pops a message
  older than the grace and stalls before claiming it can still lose that
  job: the sweep cannot tell a slow worker from a dead one."
  [cfg]
  (let [{:keys [::db/conn ::timestamp ::wrk/tenant]} cfg
        grace    (ct/minus timestamp lost-grace)
        cap      (ct/minus timestamp lost-cap)
        capped   (db/exec! conn [sql:lost-past-cap tenant cap lost-batch-size])
        in-grace (db/exec! conn [sql:lost-in-grace tenant cap grace lost-batch-size])
        waiting  (get-waiting cfg (into #{} (map :queue) in-grace))
        lost     (into (vec capped)
                       (filter (fn [{:keys [queue]}]
                                 (zero? (get waiting queue))))
                       in-grace)]
    (when (seq lost)
      (let [aborted (db/exec! conn [sql:abort-lost
                                    timestamp
                                    lost-error-json
                                    (db/create-array conn "uuid" (map :id lost))
                                    tenant
                                    grace]
                              {:return-keys true})]
        (record-aborted cfg aborted)
        (db/after-commit
         (fn []
           (doseq [{:keys [id queue name]} aborted]
             (l/wrn :hint "marked job as aborted (never claimed after being scheduled)"
                    :name name
                    :id (str id)
                    :tenant tenant
                    :queue queue))))))))

(defn- mark-orphan-jobs
  "Abort the running jobs whose process stopped reporting: their lease
  expired, so nothing alive can finish them."
  [{:keys [::db/conn ::timestamp ::lease ::wrk/tenant] :as cfg}]
  (let [cutoff (ct/minus timestamp (or lease (cf/get-jobs-lease)))
        rows    (db/exec! conn [sql:mark-orphan timestamp orphan-error-json
                                tenant cutoff cutoff]
                          {:return-keys true})]
    (record-aborted cfg rows)
    (db/after-commit
     (fn []
       (doseq [{:keys [id queue name]} rows]
         (l/err :hint "marked job as aborted (orphan lease expired)"
                :name name
                :id (str id)
                :tenant tenant
                :queue queue))))))

(defn- get-jobs
  [{:keys [::db/conn ::timestamp ::batch-size ::wrk/tenant]}]
  (let [result (db/exec! conn [sql:select-next-jobs timestamp tenant batch-size])]
    (not-empty result)))

(defn- mark-as-scheduled
  [{:keys [::db/conn]} items]
  (let [ids (map :id items)
        sql [sql:mark-job-scheduled
             (ct/now)
             (db/create-array conn "uuid" ids)]]
    (db/exec-one! conn sql)))

(defn- push-jobs
  [{:keys [::rds/conn ::wrk/tenant] :as cfg} [queue jobs]]
  ;; Mark first, push last: a crash between mark and push leaves a
  ;; `scheduled` row that the lost sweep aborts after its window, while the
  ;; reverse order leaves a payload for a row the dispatcher will push
  ;; again (two deliveries of the same job).
  (mark-as-scheduled cfg jobs)
  (let [items (mapv encode-payload jobs)
        key   (wrk/queue-key tenant queue)]

    (rds/rpush conn key items)
    (metrics/record-dispatcher-size cfg queue (count jobs))
    (doseq [{:keys [id name queue]} jobs]
      (metrics/record-dispatched cfg name 1)
      (l/trc :hist "schedule"
             :tenant tenant
             :queue queue
             :job-id (str id)))))

(defn- run-batch'
  [cfg]
  (let [cfg    (assoc cfg ::timestamp (ct/now))
        tpoint (ct/tpoint)]
    ;; The jobs that were handed to a queue and never claimed are
    ;; terminal: a lost message is not re-dispatched, so no job ever goes
    ;; back to `new` and the dedupe index stays out of this path.
    (abort-lost-jobs cfg)

    ;; Mark as aborted all jobs that are still marked as running but
    ;; their last modification (heartbeat or progress) is older than
    ;; the configured lease
    (mark-orphan-jobs cfg)

    ;; Then, schedule the next jobs in queue
    (let [result (if-let [jobs (get-jobs cfg)]
                   (do
                     (->> (group-by :queue jobs)
                          (run! (partial push-jobs cfg)))
                     nil)

                   ;; If no jobs found on this batch run, we signal the
                   ;; run-loop to wait for some time before start running
                   ;; the next batch iteration
                   ::wait)]
      (db/after-commit
       #(metrics/record-dispatcher-batch cfg :dispatch :completed (inst-ms (tpoint))))
      result)))

(defn- sleep-after-error
  [cfg]
  (px/sleep (or (::timeout cfg) (ct/duration "10s"))))

(defn run-batch
  "Execute a single dispatch batch: abort the lost scheduled jobs, abort
  the orphans (lease-based) and claim pending jobs into their Redis
  queues. Exposed as a function for testability; the dispatcher thread
  loops on it."
  [cfg]
  (let [tpoint (ct/tpoint)
        tenant (::wrk/tenant cfg)]
    (try
      (let [rconn (rds/connect cfg)]
        (try
          (-> cfg
              (assoc ::rds/conn rconn)
              (db/tx-run! run-batch'))
          (finally
            (.close ^AutoCloseable rconn))))
      (catch InterruptedException cause
        (throw cause))

      (catch Exception cause
        (cond
          (rds/exception? cause)
          (do
            (metrics/record-dispatcher-batch cfg :redis :failed (inst-ms (tpoint)))
            (l/wrn :hint "redis exception (will retry in an instant)" :tenant tenant :cause cause)
            (sleep-after-error cfg))

          (db/sql-exception? cause)
          (do
            (metrics/record-dispatcher-batch cfg :database :failed (inst-ms (tpoint)))
            (l/wrn :hint "database exception (will retry in an instant)" :tenant tenant :cause cause)
            (sleep-after-error cfg))

          :else
          (do
            (metrics/record-dispatcher-batch cfg :execution :failed (inst-ms (tpoint)))
            (l/err :hint "unhandled exception (will retry in an instant)" :tenant tenant :cause cause)
            (sleep-after-error cfg)))))))

(defmethod ig/init-key ::wrk/dispatcher
  [_ {:keys [::db/pool ::wait-duration ::wrk/tenant] :as cfg}]
  (letfn [(dispatcher []
            (l/inf :hint "started" :tenant tenant)
            (try
              (loop []
                (let [result (run-batch cfg)]
                  (when (= result ::wait)
                    (px/sleep wait-duration))
                  (recur)))
              (catch InterruptedException _
                (l/trc :hint "interrupted" :tenant tenant))
              (catch Throwable cause
                (l/err :hint "unexpected exception" :tenant tenant :cause cause))
              (finally
                (l/inf :hint "terminated" :tenant tenant))))]

    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)" :tenant tenant)
      (px/fn->thread dispatcher :name "penpot/worker-dispatcher"))))

(defmethod ig/halt-key! ::wrk/dispatcher
  [_ thread]
  (some-> thread px/interrupt!))
