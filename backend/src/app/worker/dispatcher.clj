;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.worker.dispatcher
  (:require
   [app.common.data :as d]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
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
  "SELECT id, queue, scheduled_at from job AS t
    WHERE t.scheduled_at <= ?::timestamptz
      AND (t.status = 'new' OR t.status = 'retry')
      AND queue ~~* ?::text
    ORDER BY t.priority DESC, t.scheduled_at
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(def ^:private sql:mark-job-scheduled
  "UPDATE job SET status = 'scheduled'
    WHERE id = ANY(?)")

(def ^:private sql:reschedule-lost
  "UPDATE job
      SET status='new', scheduled_at=?::timestamptz
     FROM (SELECT t.id
             FROM job AS t
            WHERE status = 'scheduled'
              AND (?::timestamptz - t.scheduled_at) > '5 min'::interval) AS subquery
    WHERE job.id=subquery.id
RETURNING job.id, job.queue")

(def ^:private sql:mark-orphan
  "UPDATE job
      SET status='failed', modified_at=?::timestamptz,
          error='{\"code\":\"orphan\"}'::jsonb
     FROM (SELECT t.id
             FROM job AS t
            WHERE status = 'running'
              AND t.modified_at < ?::timestamptz) AS subquery
    WHERE job.id=subquery.id
RETURNING job.id, job.queue")

(defn- encode-payload
  [{:keys [id scheduled-at]}]
  (json/encode [(str id) (ct/format-inst scheduled-at)]))

(defn- reschedule-lost-jobs
  [{:keys [::db/conn ::timestamp]}]
  (doseq [{:keys [id queue]} (db/exec! conn [sql:reschedule-lost timestamp timestamp]
                                       {:return-keys true})]
    (l/wrn :hint "reschedule"
           :id (str id)
           :queue queue)))

(defn- mark-orphan-jobs
  [{:keys [::db/conn ::timestamp ::lease] :as cfg}]
  (let [cutoff (ct/minus timestamp (or lease (cf/get-jobs-lease)))]
    (doseq [{:keys [id queue]} (db/exec! conn [sql:mark-orphan timestamp cutoff]
                                         {:return-keys true})]
      (l/wrn :hint "mark as orphan failed"
             :id (str id)
             :queue queue))))

(defn- get-jobs
  [{:keys [::db/conn ::timestamp ::batch-size ::wrk/tenant]}]
  (let [prefix (str tenant ":%")
        result (db/exec! conn [sql:select-next-jobs timestamp prefix batch-size])]
    (not-empty result)))

(defn- mark-as-scheduled
  [{:keys [::db/conn]} items]
  (let [ids (map :id items)
        sql [sql:mark-job-scheduled
             (db/create-array conn "uuid" ids)]]
    (db/exec-one! conn sql)))

(defn- push-jobs
  [{:keys [::rds/conn] :as cfg} [queue jobs]]
  (let [items (mapv encode-payload jobs)
        key   (str/ffmt "penpot.worker.queue:%" queue)]

    (rds/rpush conn key items)
    (mark-as-scheduled cfg jobs)

    (doseq [{:keys [id queue]} jobs]
      (l/trc :hist "schedule"
             :id (str id)
             :queue queue))))

(defn- run-batch'
  [cfg]
  (let [cfg (assoc cfg ::timestamp (ct/now))]
    ;; Reschedule lost in transit jobs (can happen when
    ;; redis server is restarted just after job is pushed)
    (reschedule-lost-jobs cfg)

    ;; Mark as failed all jobs that are still marked as running but
    ;; their last modification (heartbeat or progress) is older than
    ;; the configured lease
    (mark-orphan-jobs cfg)

    ;; Then, schedule the next jobs in queue
    (if-let [jobs (get-jobs cfg)]
      (->> (group-by :queue jobs)
           (run! (partial push-jobs cfg)))

      ;; If no jobs found on this batch run, we signal the
      ;; run-loop to wait for some time before start running
      ;; the next batch interation
      ::wait)))

(defn- sleep-after-error!
  [cfg]
  (px/sleep (or (::timeout cfg) (ct/duration "10s"))))

(defn run-batch!
  "Execute a single dispatch batch: reschedule lost jobs, mark orphans
  (lease-based) and claim pending jobs into their Redis queues. Exposed
  as a function for testability; the dispatcher thread loops on it."
  [cfg]
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
          (l/wrn :hint "redis exception (will retry in an instant)" :cause cause)
          (sleep-after-error! cfg))

        (db/sql-exception? cause)
        (do
          (l/wrn :hint "database exception (will retry in an instant)" :cause cause)
          (sleep-after-error! cfg))

        :else
        (do
          (l/err :hint "unhandled exception (will retry in an instant)" :cause cause)
          (sleep-after-error! cfg))))))

(defmethod ig/init-key ::wrk/dispatcher
  [_ {:keys [::db/pool ::wait-duration] :as cfg}]
  (letfn [(dispatcher []
            (l/inf :hint "started")
            (try
              (loop []
                (let [result (run-batch! cfg)]
                  (when (= result ::wait)
                    (px/sleep wait-duration))
                  (recur)))
              (catch InterruptedException _
                (l/trc :hint "interrupted"))
              (catch Throwable cause
                (l/err :hint "unexpected exception" :cause cause))
              (finally
                (l/inf :hint "terminated"))))]

    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)")
      (px/fn->thread dispatcher :name "penpot/worker-dispatcher"))))

(defmethod ig/halt-key! ::wrk/dispatcher
  [_ thread]
  (some-> thread px/interrupt!))
