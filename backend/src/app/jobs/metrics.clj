;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.metrics
  "Metric names and labels used by the jobs substrate.

  Labels are bounded by construction, not by allowlist: every value
  recorded here comes from a fixed call site (a job name from the
  registry, a queue from the dispatcher batch, an outcome from submit or
  the lifecycle writers), never from params, IDs or exception text. A new
  value showing up in Prometheus means new code, which is exactly what
  you want to see: folding it into some other bucket would only hide it.

  This namespace is intentionally free of job handlers. Callers pass only
  values already known at their boundary; IDs, props and exception text never
  become metric labels."
  (:require
   [app.common.data :as d]
   [app.metrics :as mtx]
   [cuerdas.core :as str]))

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

(defn- record-submit
  "One measurement with three outcomes: the submit created a row, skipped
  it because a live job covered the key (`::jobs/skip-if-active`, any status
  from `new` to `retry`), or refreshed the not-started row that covered it
  (`::jobs/debounce`). The outcome comes from a fixed call site, so it has
  no fallback, unlike `outcome-label`, which would say `failed` for a
  submission that cannot fail."
  [cfg outcome name]
  (record-count (mtx/instance cfg) :jobs-submit
                [(name-label name) (d/name outcome)]
                1))

(defn record-created
  [cfg name]
  (record-submit cfg :created name))

(defn record-skipped
  [cfg name]
  (record-submit cfg :skipped name))

(defn record-refreshed
  [cfg name]
  (record-submit cfg :refreshed name))

(defn record-dispatched
  [cfg name amount]
  (record-count (mtx/instance cfg) :jobs-dispatched
                [(name-label name)]
                amount))

(defn record-retry
  [cfg name reason]
  (record-count (mtx/instance cfg) :jobs-retries
                [(name-label name) (retry-reason-label reason)]
                1))

(defn record-event
  "Total number of job events stored. No labels: the interesting split is
  `kind`, and grouping a growing append-only table to build a metric is not
  worth the query on the write path."
  [cfg]
  (record-count (mtx/instance cfg) :jobs-events-total [] 1))

(defn record-claim-wait
  "How late a claim happened: `now - scheduled_at` at the moment the
  runner takes the job, including the gap the dispatcher added. It is not
  time spent inside a queue, hence the name."
  [cfg name millis]
  (record-value (mtx/instance cfg) :jobs-claim-wait-timing
                [(name-label name)]
                (max 0 (long millis))))

(defn record-execution
  [cfg name millis]
  (record-value (mtx/instance cfg) :jobs-execution-timing
                [(name-label name)]
                (max 0 (long millis))))

(defn record-legacy-execution
  "Histogram `penpot_tasks_timing`, from the pre-jobs `task` queue.

  It carries the job name label, like every other jobs metric. Kept
  exported only so the dashboards and the alerts that already read it
  keep working: the label is the raw job name."
  [cfg name millis]
  (record-value (mtx/instance cfg) :tasks-timing
                [(mtx/label name "other")]
                (max 0 (long millis))))

(defn record-total
  [cfg name outcome millis]
  (record-value (mtx/instance cfg) :jobs-total-timing
                [(name-label name) (outcome-label outcome)]
                (max 0 (long millis))))

(defn record-dispatcher-batch
  [cfg stage outcome millis]
  (record-value (mtx/instance cfg) :jobs-dispatcher-batch-timing
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
  "One pass of the jobs garbage collector, `expired` or `retained`."
  [cfg kind millis]
  (record-value (mtx/instance cfg) :jobs-gc-pass-timing
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
    ;; No separate counter: the timing `_count` is the request total
    ;; per outcome.
    (record-value metrics :jobs-request-timing [outcome]
                  (max 0 (long millis)))))

(defn- offload-result-label
  "Coerce an offload outcome to its label: lowercase, with `failed`
  only when there is no outcome at all. Values come from the fixed
  call sites of the offload handler (`ok`, `skip`, `error`), never
  from params, IDs or exception text."
  [result]
  (str/lower (mtx/label result "failed")))

(defn- offload-type-label
  "Coerce a file data type to its label: lowercase, with `other` only
  when there is no type at all. Values are the fixed `file_data.type`
  values (`main`, `snapshot`, `fragment`)."
  [type]
  (str/lower (mtx/label type "other")))

(defn record-offload-rows
  "Count file data rows moved by the offload job, broken down by row
  type. Only successful runs emit rows, so there is no result label."
  [cfg type amount]
  (record-count (mtx/instance cfg) :file-data-offload-rows
                [(offload-type-label type)]
                amount))

(defn record-offload-bytes
  "Count file data bytes moved by the offload job, broken down by row
  type. Only successful runs emit bytes, so there is no result label."
  [cfg type amount]
  (record-count (mtx/instance cfg) :file-data-offload-bytes
                [(offload-type-label type)]
                amount))

(defn record-offload-duration
  "Time one offload run from lock to summary (milliseconds). Recorded
  for every outcome (`ok`, `skip`, `error`), so its count is the run
  total per outcome."
  [cfg result millis]
  (record-value (mtx/instance cfg) :file-data-offload-timing
                [(offload-result-label result)]
                (max 0 (long millis))))
