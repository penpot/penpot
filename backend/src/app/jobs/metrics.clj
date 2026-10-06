;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.metrics
  "Metric names, labels and the jobs backlog sampler.

  Labels are bounded by construction, not by allowlist: every value
  recorded here comes from a fixed call site (a job name from the
  registry, a queue from submit, an outcome from the lifecycle
  writers), never from params, IDs or exception text. A new value
  showing up in Prometheus means new code, which is exactly what you
  want to see: folding it into some other bucket would only hide it.

  This namespace is intentionally free of job handlers. Callers pass only
  values already known at their boundary; IDs, props and exception text never
  become metric labels."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.metrics :as mtx]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

(defn queue-label
  "Coerce a queue to its label: lowercase, with \"other\" only when there
  is no queue at all. A new queue shows up as-is: that means new code."
  [queue]
  (str/lower (mtx/label queue "other")))

;; Job names go to the metric as-is: the registry is fixed (one job-def
;; per name, wired in `app.main`), so cardinality is bounded by it.
;; Anything without a name (nil) still falls back to "other".
(defn- name-label [name]
  (mtx/label name "other"))

(defn- gc-kind-label [kind]
  (str/lower (or (some-> kind d/name) "other")))

(defn- outcome-label [outcome]
  (str/lower (or (some-> outcome d/name) "failed")))

(defn- retry-reason-label [reason]
  (str/lower (or (some-> reason d/name) "backoff")))

(defn- stage-label [stage]
  (str/lower (or (some-> stage d/name) "execution")))

(defn- record-value [metrics id labels value]
  (mtx/run! metrics :id id :labels labels :val value))

(defn- record-count [metrics id labels amount]
  (mtx/run! metrics :id id :labels labels :inc amount))

(defn record-submitted
  [cfg name queue]
  (record-count (mtx/instance cfg) :jobs-submitted
                [(name-label name) (queue-label queue)]
                1))

(defn record-dispatched
  [cfg name queue amount]
  (record-count (mtx/instance cfg) :jobs-dispatched
                [(name-label name) (queue-label queue)]
                amount))

(defn record-outcome
  [cfg name queue outcome]
  (record-count (mtx/instance cfg) :jobs-completed
                [(name-label name) (queue-label queue)
                 (outcome-label outcome)]
                1))

(defn record-retry
  [cfg name queue reason]
  (record-count (mtx/instance cfg) :jobs-retries
                [(name-label name) (queue-label queue)
                 (retry-reason-label reason)]
                1))

(defn record-event
  "Total number of job events stored. No labels: the interesting split is
  `kind`, and grouping a growing append-only table to build a metric is not
  worth the query on the write path."
  [cfg]
  (record-count (mtx/instance cfg) :jobs-events-total [] 1))

(defn record-orphan
  [cfg queue]
  (record-count (mtx/instance cfg) :jobs-orphaned
                [(queue-label queue)]
                1))

(defn record-rescheduled
  [cfg queue]
  (record-count (mtx/instance cfg) :jobs-rescheduled
                [(queue-label queue)]
                1))

(defn record-queue-wait
  [cfg name queue millis]
  (record-value (mtx/instance cfg) :jobs-queue-wait-timing
                [(name-label name) (queue-label queue)]
                (max 0 (long millis))))

(defn record-execution
  [cfg name queue millis]
  (record-value (mtx/instance cfg) :jobs-execution-timing
                [(name-label name) (queue-label queue)]
                (max 0 (long millis))))

(defn record-legacy-execution
  "Histogram `penpot_tasks_timing`, from the pre-jobs `task` queue.

  It only carries the job name label, with no queue to tell jobs of the
  same name apart. Use `record-execution`, which adds the queue label,
  for anything new. Kept exported only so the dashboards and the alerts
  that already read it keep working: like every other jobs metric,
  the label is the raw job name."
  [cfg name millis]
  (record-value (mtx/instance cfg) :tasks-timing
                [(mtx/label name "other")]
                (max 0 (long millis))))

(defn record-total
  [cfg name queue outcome millis]
  (record-value (mtx/instance cfg) :jobs-total-timing
                [(name-label name) (queue-label queue)
                 (outcome-label outcome)]
                (max 0 (long millis))))

(defn record-dispatcher-batch
  [cfg stage outcome millis]
  (record-value (mtx/instance cfg) :jobs-dispatcher-timing
                [(stage-label stage) (outcome-label outcome)]
                (max 0 (long millis))))

(defn record-dispatcher-size
  [cfg queue size]
  (record-value (mtx/instance cfg) :jobs-dispatcher-batch-size
                [(queue-label queue)]
                size))

(defn record-gc-rows
  [cfg kind action amount]
  (record-count (mtx/instance cfg) :jobs-gc-rows
                [(gc-kind-label kind)
                 (str/lower (or (some-> action d/name) "deleted"))]
                amount))

(defn record-gc-duration
  [cfg kind millis]
  (record-value (mtx/instance cfg) :jobs-gc-timing
                [(gc-kind-label kind)]
                (max 0 (long millis))))

(defn record-cron
  [cfg outcome reason]
  (record-count (mtx/instance cfg) :jobs-cron-total
                [(str/lower (or (some-> outcome d/name) "error"))
                 (str/lower (or (some-> reason d/name) "none"))]
                1))

(defn record-request
  [cfg outcome millis]
  (let [outcome (str/lower (or (some-> outcome d/name) "error"))
        metrics (mtx/instance cfg)]
    (record-count metrics :jobs-requests-total [outcome] 1)
    (record-value metrics :jobs-request-timing [outcome]
                  (max 0 (long millis)))))

(def ^:private backlog-statuses
  ["new" "scheduled" "running" "retry" "completed" "failed" "cancelled" "aborted"])

(def ^:private sql:backlog
  "SELECT status, count(*) AS n
     FROM job
    GROUP BY status")

(def ^:private sql:oldest-pending
  "SELECT coalesce(extract(epoch FROM (now() - min(created_at))), 0) AS age
     FROM job
    WHERE status IN ('new', 'scheduled', 'retry')")

(defn sample-backlog
  "Update the current job backlog gauges. The query is intentionally small
  and runs periodically rather than from the worker hot path."
  [{:keys [::db/pool] :as cfg}]
  (let [rows    (db/exec! pool [sql:backlog])
        by-id   (into {} (map (juxt :status #(or (:n %) 0))) rows)
        age     (-> (db/exec-one! pool [sql:oldest-pending])
                    :age
                    (max 0)
                    (double))
        metrics (mtx/instance cfg)]
    (doseq [status backlog-statuses]
      (record-value metrics :jobs-backlog [status] (get by-id status 0)))
    (record-value metrics :jobs-oldest-pending-age [] age)
    age))

(defn- sample-jobs-metrics [cfg]
  (try
    (sample-backlog cfg)
    (catch Throwable cause
      (l/warn :hint "unable to sample jobs metrics" :cause cause))))

(def ^:private sample-interval-ms 30000)

(defn create-sampler
  "Create the daemon scheduler used by the jobs metrics component."
  [cfg]
  (let [scheduler (px/scheduled-executor
                   :parallelism 1
                   :factory (px/thread-factory :prefix "penpot/jobs-metrics/"
                                               :daemon true))
        sample    (fn sample []
                    (try
                      (sample-jobs-metrics cfg)
                      (finally
                        (px/schedule scheduler sample-interval-ms sample))))]
    (px/schedule scheduler 0 sample)
    scheduler))

(def ^:private schema:sampler
  [:map
   ::db/pool
   ::mtx/metrics])

(defmethod ig/assert-key ::sampler
  [_ cfg]
  (assert (sm/check schema:sampler cfg)))

(defmethod ig/init-key ::sampler
  [_ {:keys [::db/pool] :as cfg}]
  (if (db/read-only? pool)
    (l/warn :hint "not started (db is read-only)")
    (create-sampler cfg)))

(defmethod ig/halt-key! ::sampler
  [_ sampler]
  (some-> sampler px/shutdown-now))
