;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.jobs-metrics-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.metrics :as jobs-metrics]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private metric-ids
  [:jobs-submitted
   :jobs-dispatched
   :jobs-completed
   :jobs-retries
   :jobs-orphaned
   :jobs-rescheduled
   :jobs-queue-wait-timing
   :jobs-execution-timing
   :jobs-total-timing
   :jobs-dispatcher-timing
   :jobs-dispatcher-batch-size
   :jobs-backlog
   :jobs-oldest-pending-age
   :jobs-gc-rows
   :jobs-gc-timing
   :jobs-cron-total
   :jobs-requests-total
   :jobs-request-timing
   :jobs-events-total])

;; The legacy histogram is registered too, so it can be asserted, but it is
;; not part of the `penpot_jobs_` contract the test above checks.
(def ^:private registry-ids (conj metric-ids :tasks-timing))

(defn- make-metrics []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics registry-ids)}))

(defn- metrics-cfg
  "The cfg the `record-*` functions resolve the instance from. They take
  a cfg, not an instance, so this is what a caller hands them."
  [metrics]
  {::mtx/metrics metrics})

(defn- counter-value [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- histogram-count
  "How many observations a histogram has recorded for an exact set of
  labels, read off the registry so the label set itself is checked."
  [metrics id labels]
  (let [definition (get main/default-metrics id)]
    (->> (enumeration-seq
          (.metricFamilySamples ^CollectorRegistry (mtx/get-registry metrics)))
         (filter (fn [^Collector$MetricFamilySamples family]
                   (= (.-name family) (::mdef/name definition))))
         (mapcat (fn [^Collector$MetricFamilySamples family]
                   (.samples family)))
         (filter (fn [^Collector$MetricFamilySamples$Sample sample]
                   (and (str/ends-with? (.-name sample) "_count")
                        (= labels (vec (.-labelValues sample))))))
         (map (fn [^Collector$MetricFamilySamples$Sample sample] (.-value sample)))
         ;; 0.0 and not 0: an empty sum must compare equal to the 0.0 the
         ;; assertions use, and (= 0 0.0) is false in Clojure
         (reduce + 0.0))))

(defn- gauge-value [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Gauge instance (into-array String labels))]
    (.get ^Gauge$Child child)))

(t/deftest default-metrics-cover-the-jobs-observability-contract
  (doseq [id metric-ids]
    (let [definition (get main/default-metrics id)]
      (t/is (some? definition) id)
      (t/is (re-find #"^penpot_jobs_" (::mdef/name definition)) id)))
  (t/is (= ["name" "queue" "outcome"]
           (::mdef/labels (:jobs-completed main/default-metrics))))
  (t/is (= ["status"]
           (::mdef/labels (:jobs-backlog main/default-metrics)))))

(t/deftest submit-metric-is-not-recorded-when-the-outer-transaction-rolls-back
  (let [metrics (make-metrics)
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   (fn [_context params] params)
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs   defs
                 ::db/pool     th/*pool*
                 ::mtx/metrics metrics}]
    (t/is (thrown? Exception
                   (db/tx-run! cfg
                               (fn [tx-cfg]
                                 (jobs/submit tx-cfg
                                              {::jobs/name :echo
                                               ::jobs/params {}})
                                 (throw (ex-info "rollback" {}))))))
    (t/is (= 0.0 (counter-value metrics :jobs-submitted ["echo" "default"])))))

(t/deftest submit-and-terminal-writers-record-production-events
  (let [metrics (make-metrics)
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   (fn [_context params] params)
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs  defs
                 ::db/pool    th/*pool*
                 ::mtx/metrics metrics}
        job-id  (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (jobs/complete cfg :job-id job-id :result {:ok true})
    (t/is (= 1.0 (counter-value metrics :jobs-submitted ["echo" "default"])))
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "default" "completed"])))))

(t/deftest lifecycle-helpers-use-labels-as-is
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-submitted cfg "delete-object" "default")
    (jobs-metrics/record-dispatched cfg "delete-object" "default" 2)
    (jobs-metrics/record-outcome cfg "delete-object" "default" :completed)
    (jobs-metrics/record-retry cfg "delete-object" "default" :backoff)
    (jobs-metrics/record-orphan cfg "webhooks")
    (jobs-metrics/record-rescheduled cfg "custom")
    (jobs-metrics/record-gc-rows cfg :expired :deleted 3)
    (jobs-metrics/record-cron cfg :submitted :none)
    (jobs-metrics/record-request cfg :replied 12)
    (jobs-metrics/record-legacy-execution cfg "delete-object" 42)

    (t/is (= 1.0 (counter-value metrics :jobs-submitted ["delete-object" "default"])))
    (t/is (= 2.0 (counter-value metrics :jobs-dispatched ["delete-object" "default"])))
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["delete-object" "default" "completed"])))
    (t/is (= 1.0 (counter-value metrics :jobs-retries ["delete-object" "default" "backoff"])))
    (t/is (= 1.0 (counter-value metrics :jobs-orphaned ["webhooks"])))
    (t/is (= 1.0 (counter-value metrics :jobs-rescheduled ["custom"])))
    (t/is (= 3.0 (counter-value metrics :jobs-gc-rows ["expired" "deleted"])))
    (t/is (= 1.0 (counter-value metrics :jobs-cron-total ["submitted" "none"])))
    (t/is (= 1.0 (counter-value metrics :jobs-requests-total ["replied"])))

    (t/testing "the legacy histogram keeps the raw job name, with no queue"
      (t/is (= 1.0 (histogram-count metrics :tasks-timing ["delete-object"]))))
    (t/testing "like every other jobs metric now, it does not fold an unknown name into \"other\""
      (jobs-metrics/record-legacy-execution cfg :brand-new-job 7)
      (t/is (= 1.0 (histogram-count metrics :tasks-timing ["brand-new-job"])))
      (t/is (= 0.0 (histogram-count metrics :tasks-timing ["other"]))))))

(t/deftest labels-go-to-metrics-as-is
  (t/testing "queues are lowercased, with other only when missing"
    (t/is (= "unexpected" (jobs-metrics/queue-label "unexpected")))
    (t/is (= "default" (jobs-metrics/queue-label "default")))
    (t/is (= "default" (jobs-metrics/queue-label :default)))
    (t/is (= "other" (jobs-metrics/queue-label nil))))
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-outcome cfg "echo" "unexpected" :unexpected)
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "unexpected" "unexpected"])))
    (t/testing "a new value is recorded, never folded or dropped"
      (jobs-metrics/record-gc-rows cfg :unknown :deleted 1)
      (jobs-metrics/record-cron cfg :brand-new :whatever)
      (jobs-metrics/record-request cfg :brand-new 5)
      (t/is (= 1.0 (counter-value metrics :jobs-gc-rows ["unknown" "deleted"])))
      (t/is (= 1.0 (counter-value metrics :jobs-cron-total ["brand-new" "whatever"])))
      (t/is (= 1.0 (counter-value metrics :jobs-requests-total ["brand-new"]))))))

(t/deftest sampler-is-wired-and-does-not-start-on-read-only
  (t/is (contains? main/worker-config :app.jobs.metrics/sampler))
  (let [metrics (make-metrics)
        cfg     {::db/pool th/*pool*
                 ::mtx/metrics metrics}]
    (with-redefs [db/read-only? (constantly true)]
      (t/is (nil? (ig/init-key :app.jobs.metrics/sampler cfg))))
    (let [sampler (ig/init-key :app.jobs.metrics/sampler cfg)]
      (try
        (t/is (some? sampler))
        (finally
          (ig/halt-key! :app.jobs.metrics/sampler sampler))))))

(t/deftest unknown-names-go-to-metrics-as-is
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-submitted cfg "unknown-job" "default")
    (t/is (= 1.0 (counter-value metrics :jobs-submitted ["unknown-job" "default"])))))

(t/deftest backlog-sampler-updates-status-and-age-gauges
  (let [metrics (make-metrics)
        cfg     {::db/pool th/*pool*
                 ::mtx/metrics metrics}
        now     (ct/now)]
    (th/db-insert! :job {:id           (uuid/next)
                         :name         "echo"
                         :tenant       (cf/get :tenant)
                         :queue        "default"
                         :params       (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "new"
                         :scheduled-at now
                         :created-at   (ct/in-past {:minutes 2})
                         :modified-at  now})
    (jobs-metrics/sample-backlog cfg)
    (t/is (= 1.0 (gauge-value metrics :jobs-backlog ["new"])))
    (t/is (= 0.0 (gauge-value metrics :jobs-backlog ["completed"])))
    (t/is (= 0.0 (gauge-value metrics :jobs-backlog ["aborted"])))
    (t/is (>= (gauge-value metrics :jobs-oldest-pending-age []) 120.0))))

(t/deftest event-counter-has-no-labels-and-follows-the-events
  (let [metrics (make-metrics)
        cfg     {::db/pool th/*pool*
                 ::mtx/metrics metrics}
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   (fn [_context params] params)
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg2    (assoc cfg ::jobs/defs defs)
        job-id  (jobs/submit cfg2 {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg2 job-id (:scheduled-at (jobs/get-job cfg2 job-id)))
    (jobs/complete cfg2 :job-id job-id :result {:ok true})
    (t/testing "one start and one end, counted together with no labels"
      (t/is (= 2.0 (counter-value metrics :jobs-events-total []))))

    (t/testing "a transition that affects no row adds no event and no count"
      (t/is (= 0 (jobs/complete cfg2 :job-id job-id :result {:ok true})))
      (t/is (= 2.0 (counter-value metrics :jobs-events-total []))))))

(t/deftest event-counter-follows-a-lifecycle-write-that-outlives-the-caller
  (let [metrics (make-metrics)
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   (fn [_context params] params)
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs   defs
                 ::db/pool     th/*pool*
                 ::mtx/metrics metrics}
        job-id  (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (t/is (= 1.0 (counter-value metrics :jobs-events-total [])))

    ;; the completion runs inside a transaction that rolls back. It owns
    ;; its own, so the event and both counters survive the caller.
    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/complete (assoc cfg ::db/conn conn)
                                 :job-id job-id :result {:ok true})))

    (t/testing "the end event is stored and counted"
      (t/is (= 2.0 (counter-value metrics :jobs-events-total [])))
      (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event"])))))

    (t/testing "the terminal counter followed its own commit, not the caller one"
      (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "default" "completed"]))))))

(t/deftest progress-counter-follows-the-report-that-outlives-the-caller
  (let [metrics (make-metrics)
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   (fn [_context params] params)
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs   defs
                 ::db/pool     th/*pool*
                 ::mtx/metrics metrics}
        job-id  (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (let [before (counter-value metrics :jobs-events-total [])]
      (db/tx-run! (assoc cfg ::db/rollback true)
                  (fn [{:keys [::db/conn]}]
                    (jobs/heartbeat (assoc cfg ::db/conn conn)
                                    :job-id job-id
                                    :progress {:current 1}
                                    ::jobs/force? true)))
      (t/testing "the report is stored and counted, the caller rollback drops neither"
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event
                                            WHERE job_id = ? AND kind = 'progress'"
                                           job-id]))))
        (t/is (= (inc before) (counter-value metrics :jobs-events-total [])))))))
