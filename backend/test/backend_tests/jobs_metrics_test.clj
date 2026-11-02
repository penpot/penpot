;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.jobs-metrics-test
  (:require
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
   io.prometheus.client.Counter$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private metric-ids
  [:jobs-submit
   :jobs-dispatched
   :jobs-retries
   :jobs-claim-wait-timing
   :jobs-execution-timing
   :jobs-total-timing
   :jobs-dispatcher-batch-timing
   :jobs-dispatcher-batch-size
   :jobs-gc-rows
   :jobs-gc-pass-timing
   :jobs-cron-total
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

(defn- echo-defs
  "The minimal job-def the tests submit and drive through the lifecycle."
  []
  {:echo {::jobs/name      :echo
          ::jobs/schema    [:map]
          ::jobs/handler   (fn [_context params] params)
          ::jobs/decoder   identity
          ::jobs/validator (constantly true)}})

(t/deftest default-metrics-cover-the-jobs-observability-contract
  (doseq [id metric-ids]
    (let [definition (get main/default-metrics id)]
      (t/is (some? definition) id)
      (t/is (re-find #"^penpot_jobs_" (::mdef/name definition)) id)))
  (t/is (= ["name" "outcome"]
           (::mdef/labels (:jobs-submit main/default-metrics))))
  (t/is (= ["name" "outcome"]
           (::mdef/labels (:jobs-total-timing main/default-metrics))))
  (t/is (= ["name"]
           (::mdef/labels (:jobs-claim-wait-timing main/default-metrics))))
  (t/testing "one submit family and one terminal counter, with no twin ids"
    (t/is (not (contains? main/default-metrics :jobs-submitted)))
    (t/is (not (contains? main/default-metrics :jobs-skipped)))
    (t/is (not (contains? main/default-metrics :jobs-orphaned)))))

(t/deftest submit-metric-is-not-recorded-when-the-outer-transaction-rolls-back
  (let [metrics (make-metrics)
        defs    (echo-defs)
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
    (t/is (= 0.0 (counter-value metrics :jobs-submit ["echo" "created"])))))

(t/deftest submit-and-terminal-writers-record-production-events
  (let [metrics (make-metrics)
        defs    (echo-defs)
        cfg     {::jobs/defs  defs
                 ::db/pool    th/*pool*
                 ::mtx/metrics metrics}
        job-id  (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (jobs/complete cfg :job-id job-id :result {:ok true})
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "created"])))
    (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "completed"])))))

(t/deftest skip-if-active-records-skipped-metric
  (let [metrics (make-metrics)
        defs    (echo-defs)
        cfg     {::jobs/defs   defs
                 ::db/pool     th/*pool*
                 ::mtx/metrics metrics}
        _       (jobs/submit cfg {::jobs/name          :echo
                                  ::jobs/params        {}
                                  ::jobs/label         "skip-metric"
                                  ::jobs/skip-if-active true})
        skipped (jobs/submit cfg {::jobs/name          :echo
                                  ::jobs/params        {}
                                  ::jobs/label         "skip-metric"
                                  ::jobs/skip-if-active true})]
    (t/is (nil? skipped))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "skipped"])))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "created"]))
          "the withheld submit records no creation")))

(t/deftest dedupe-skip-is-counted-apart-from-a-creation
  (let [metrics  (make-metrics)
        cfg      {::jobs/defs   (echo-defs)
                  ::db/pool     th/*pool*
                  ::mtx/metrics metrics}
        options  {::jobs/name   :echo
                  ::jobs/params {}
                  ::jobs/label  "dedupe-metric"}
        created  (jobs/submit cfg (assoc options ::jobs/dedupe true))
        skipped  (jobs/submit cfg (assoc options ::jobs/dedupe true))]
    (t/is (some? created))
    (t/is (nil? skipped))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "created"])))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "skipped"])))
    (t/is (= 0.0 (counter-value metrics :jobs-submit ["echo" "refreshed"])))))

(t/deftest debounce-refresh-is-counted-apart-from-a-creation
  (let [metrics    (make-metrics)
        cfg        {::jobs/defs   (echo-defs)
                    ::db/pool     th/*pool*
                    ::mtx/metrics metrics}
        options    {::jobs/name     :echo
                    ::jobs/params   {}
                    ::jobs/label    "debounce-metric"
                    ::jobs/debounce true}
        created    (jobs/submit cfg options)
        refreshed  (jobs/submit cfg options)]
    (t/is (some? created))
    (t/is (= created refreshed)
          "a refresh answers with the id the row already had")
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "created"])))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["echo" "refreshed"])))
    (t/is (= 0.0 (counter-value metrics :jobs-submit ["echo" "skipped"])))))

(t/deftest the-terminal-histogram-count-is-the-counter-of-every-outcome
  (let [metrics   (make-metrics)
        cfg       {::jobs/defs   (echo-defs)
                   ::db/pool     th/*pool*
                   ::mtx/metrics metrics}
        claim!    (fn [id]
                    (jobs/claim cfg id (:scheduled-at (jobs/get-job cfg id))))
        completed (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})
        failed    (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})
        cancelled (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (claim! completed)
    (jobs/complete cfg :job-id completed :result {:ok true})
    (claim! failed)
    (jobs/fail cfg failed {:type :internal :code :boom :hint "boom"})
    (claim! cancelled)
    (jobs/cancel cfg cancelled)

    (t/testing "one observation per outcome, with no twin counter"
      (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "completed"])))
      (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "failed"])))
      (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "cancelled"])))
      (t/is (= 0.0 (histogram-count metrics :jobs-total-timing ["echo" "aborted"]))))))

(t/deftest lifecycle-helpers-use-labels-as-is
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-created cfg "delete-object")
    (jobs-metrics/record-skipped cfg "delete-object")
    (jobs-metrics/record-dispatched cfg "delete-object" 2)
    (jobs-metrics/record-total cfg "delete-object" :completed 42)
    (jobs-metrics/record-retry cfg "delete-object" :backoff)
    (jobs-metrics/record-gc-rows cfg :expired :deleted 3)
    (jobs-metrics/record-cron cfg :submitted :none)
    (jobs-metrics/record-request cfg :replied 12)
    (jobs-metrics/record-legacy-execution cfg "delete-object" 42)

    (t/is (= 1.0 (counter-value metrics :jobs-submit ["delete-object" "created"])))
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["delete-object" "skipped"])))
    (t/is (= 2.0 (counter-value metrics :jobs-dispatched ["delete-object"])))
    (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["delete-object" "completed"])))
    (t/is (= 1.0 (counter-value metrics :jobs-retries ["delete-object" "backoff"])))
    (t/is (= 3.0 (counter-value metrics :jobs-gc-rows ["expired" "deleted"])))
    (t/is (= 1.0 (counter-value metrics :jobs-cron-total ["submitted" "none"])))
    (t/is (= 1.0 (histogram-count metrics :jobs-request-timing ["replied"])))

    (t/testing "the legacy histogram keeps the raw job name, with no queue"
      (t/is (= 1.0 (histogram-count metrics :tasks-timing ["delete-object"]))))
    (t/testing "like every other jobs metric now, it does not fold an unknown name into \"other\""
      (jobs-metrics/record-legacy-execution cfg :brand-new-job 7)
      (t/is (= 1.0 (histogram-count metrics :tasks-timing ["brand-new-job"])))
      (t/is (= 0.0 (histogram-count metrics :tasks-timing ["other"]))))))

(t/deftest labels-go-to-metrics-as-is
  (t/testing "the queue of a batch is lowercased, other only when missing"
    (t/is (= "unexpected" (jobs-metrics/queue-label "unexpected")))
    (t/is (= "default" (jobs-metrics/queue-label "default")))
    (t/is (= "default" (jobs-metrics/queue-label :default)))
    (t/is (= "other" (jobs-metrics/queue-label nil))))
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-total cfg "echo" :unexpected 42)
    (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "unexpected"])))
    (t/testing "a new value is recorded, never folded or dropped"
      (jobs-metrics/record-gc-rows cfg :unknown :deleted 1)
      (jobs-metrics/record-cron cfg :brand-new :whatever)
      (jobs-metrics/record-request cfg :brand-new 5)
      (t/is (= 1.0 (counter-value metrics :jobs-gc-rows ["unknown" "deleted"])))
      (t/is (= 1.0 (counter-value metrics :jobs-cron-total ["brand-new" "whatever"])))
      (t/is (= 1.0 (histogram-count metrics :jobs-request-timing ["brand-new"]))))))

(t/deftest unknown-names-go-to-metrics-as-is
  (let [metrics (make-metrics)
        cfg     (metrics-cfg metrics)]
    (jobs-metrics/record-created cfg "unknown-job")
    (t/is (= 1.0 (counter-value metrics :jobs-submit ["unknown-job" "created"])))))

(t/deftest event-counter-has-no-labels-and-follows-the-events
  (let [metrics (make-metrics)
        cfg     {::db/pool th/*pool*
                 ::mtx/metrics metrics}
        defs    (echo-defs)
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
        defs    (echo-defs)
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
      (t/is (= 1.0 (histogram-count metrics :jobs-total-timing ["echo" "completed"]))))))

(t/deftest progress-counter-follows-the-report-that-outlives-the-caller
  (let [metrics (make-metrics)
        defs    (echo-defs)
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
                                    :progress {:stage :work}
                                    ::jobs/force? true)))
      (t/testing "the report is stored and counted, the caller rollback drops neither"
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event
                                            WHERE job_id = ? AND kind = 'progress'"
                                           job-id]))))
        (t/is (= (inc before) (counter-value metrics :jobs-events-total [])))))))

(t/deftest the-submit-carries-no-queue-and-the-batch-size-still-does
  (let [metrics (make-metrics)
        defs    {:export-binfile {::jobs/name      :export-binfile
                                  ::jobs/schema    [:map]
                                  ::jobs/handler   (fn [_context params] params)
                                  ::jobs/decoder   identity
                                  ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs  defs
                 ::db/pool    th/*pool*
                 ::mtx/metrics metrics}]

    (jobs/submit cfg {::jobs/name   :export-binfile
                      ::jobs/params {}
                      ::jobs/queue  :binfile})

    (t/testing "the submit is keyed by the job name and what it did"
      (t/is (= 1.0 (counter-value metrics :jobs-submit ["export-binfile" "created"])))
      (t/is (= ["name" "outcome"]
               (::mdef/labels (:jobs-submit main/default-metrics)))))

    (t/testing "a batch mixes job names, so it keeps the queue"
      (jobs-metrics/record-dispatcher-size cfg "binfile" 3)
      (t/is (= 1.0 (histogram-count metrics
                                    :jobs-dispatcher-batch-size
                                    ["binfile"])))
      (t/is (= ["queue"]
               (::mdef/labels (:jobs-dispatcher-batch-size main/default-metrics)))))))
