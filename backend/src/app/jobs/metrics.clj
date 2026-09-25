;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.metrics
  "Metric names, bounded labels and the jobs backlog sampler.

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

(def ^:private known-queues
  #{"default" "webhooks" "cron" "email"})

(def ^:private known-outcomes
  #{"completed" "failed" "cancelled"})

(def ^:private known-retry-reasons
  #{"backoff" "noop"})

(def ^:private known-stages
  #{"claim" "redis" "database" "dispatch" "execution" "terminal"})

(def ^:private known-job-names
  #{"sendmail"
    "delete-object"
    "demo-purge"
    "run-webhook"
    "process-webhook-event"
    "file-gc"
    "offload-file-data"
    "objects-gc"
    "storage-gc-deleted"
    "storage-gc-touched"
    "storage-pending-gc"
    "jobs-gc"
    "telemetry"
    "session-gc"
    "file-gc-scheduler"
    "audit-log-archive"
    "audit-log-gc"})

(def ^:private known-gc-kinds
  #{"expired" "retained"})

(def ^:private known-gc-actions
  #{"deleted" "touched"})

(def ^:private known-cron-outcomes
  #{"submitted" "skipped" "error" "interrupted"})

(def ^:private known-cron-reasons
  #{"none" "active" "failure" "interrupted"})

(def ^:private known-request-outcomes
  #{"replied" "error" "timeout"})

(defn queue-label
  "Return a bounded queue label. The database stores tenant-prefixed queues."
  [queue]
  (let [queue   (mtx/label queue "other")
        queue   (last (str/split queue #":"))
        queue   (str/lower (or queue "other"))]
    (if (contains? known-queues queue) queue "other")))

(defn- name-label [name]
  (let [name (mtx/label name "other")]
    (if (contains? known-job-names name) name "other")))

(defn- gc-kind-label [kind]
  (let [kind (str/lower (or (some-> kind d/name) "other"))]
    (if (contains? known-gc-kinds kind) kind "other")))

(defn- outcome-label [outcome]
  (let [outcome (str/lower (or (some-> outcome d/name) "failed"))]
    (if (contains? known-outcomes outcome) outcome "failed")))

(defn- retry-reason-label [reason]
  (let [reason (str/lower (or (some-> reason d/name) "backoff"))]
    (if (contains? known-retry-reasons reason) reason "backoff")))

(defn- stage-label [stage]
  (let [stage (str/lower (or (some-> stage d/name) "execution"))]
    (if (contains? known-stages stage) stage "execution")))

(defn- record! [metrics id labels value]
  (mtx/run! metrics :id id :labels labels :val value))

(defn- record-count! [metrics id labels amount]
  (mtx/run! metrics :id id :labels labels :inc amount))

(defn record-submitted
  [metrics name queue]
  (record-count! metrics :jobs-submitted
                 [(name-label name) (queue-label queue)]
                 1))

(defn record-dispatched
  [metrics name queue amount]
  (record-count! metrics :jobs-dispatched
                 [(name-label name) (queue-label queue)]
                 amount))

(defn record-outcome
  [metrics name queue outcome]
  (record-count! metrics :jobs-completed
                 [(name-label name) (queue-label queue)
                  (outcome-label outcome)]
                 1))

(defn record-retry
  [metrics name queue reason]
  (record-count! metrics :jobs-retries
                 [(name-label name) (queue-label queue)
                  (retry-reason-label reason)]
                 1))

(defn record-orphan
  [metrics queue]
  (record-count! metrics :jobs-orphaned
                 [(queue-label queue)]
                 1))

(defn record-rescheduled
  [metrics queue]
  (record-count! metrics :jobs-rescheduled
                 [(queue-label queue)]
                 1))

(defn record-queue-wait
  [metrics name queue millis]
  (record! metrics :jobs-queue-wait-timing
           [(name-label name) (queue-label queue)]
           (max 0 (long millis))))

(defn record-execution
  [metrics name queue millis]
  (record! metrics :jobs-execution-timing
           [(name-label name) (queue-label queue)]
           (max 0 (long millis))))

(defn record-total
  [metrics name queue outcome millis]
  (record! metrics :jobs-total-timing
           [(name-label name) (queue-label queue)
            (outcome-label outcome)]
           (max 0 (long millis))))

(defn record-dispatcher-batch
  [metrics stage outcome millis]
  (record! metrics :jobs-dispatcher-timing
           [(stage-label stage) (outcome-label outcome)]
           (max 0 (long millis))))

(defn record-dispatcher-size
  [metrics queue size]
  (record! metrics :jobs-dispatcher-batch-size
           [(queue-label queue)]
           size))

(defn record-gc-rows
  [metrics kind action amount]
  (let [kind   (gc-kind-label kind)
        action (str/lower (or (some-> action d/name) "deleted"))]
    (when (contains? known-gc-actions action)
      (record-count! metrics :jobs-gc-rows [kind action] amount))))

(defn record-gc-duration
  [metrics kind millis]
  (record! metrics :jobs-gc-timing
           [(gc-kind-label kind)]
           (max 0 (long millis))))

(defn record-cron
  [metrics outcome reason]
  (let [outcome (str/lower (or (some-> outcome d/name) "error"))
        reason  (str/lower (or (some-> reason d/name) "none"))]
    (when (contains? known-cron-outcomes outcome)
      (record-count! metrics :jobs-cron-total
                     [outcome
                      (if (contains? known-cron-reasons reason) reason "failure")]
                     1))))

(defn record-request
  [metrics outcome millis]
  (let [outcome (str/lower (or (some-> outcome d/name) "error"))]
    (when (contains? known-request-outcomes outcome)
      (record-count! metrics :jobs-requests-total [outcome] 1)
      (record! metrics :jobs-request-timing [outcome]
               (max 0 (long millis))))))

(def ^:private backlog-statuses
  ["new" "scheduled" "running" "retry" "completed" "failed" "cancelled"])

(def ^:private sql:backlog
  "SELECT status, count(*) AS n
     FROM job
    GROUP BY status")

(def ^:private sql:oldest-pending
  "SELECT coalesce(extract(epoch FROM (now() - min(created_at))), 0) AS age
     FROM job
    WHERE status IN ('new', 'scheduled', 'retry')")

(defn sample-backlog!
  "Update the current job backlog gauges. The query is intentionally small
  and runs periodically rather than from the worker hot path."
  [{:keys [::db/pool ::mtx/metrics] :as cfg}]
  (let [rows  (db/exec! pool [sql:backlog])
        by-id (into {} (map (juxt :status #(or (:n %) 0))) rows)
        age   (-> (db/exec-one! pool [sql:oldest-pending])
                  :age
                  (max 0)
                  (double))]
    (doseq [status backlog-statuses]
      (record! metrics :jobs-backlog [status] (get by-id status 0)))
    (record! metrics :jobs-oldest-pending-age [] age)
    age))

(defn- sample-jobs-metrics! [cfg]
  (try
    (sample-backlog! cfg)
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
                      (sample-jobs-metrics! cfg)
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
