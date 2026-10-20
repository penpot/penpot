;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.metrics-test
  (:require
   [app.common.exceptions :as ex]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [clojure.test :as t]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample
   io.prometheus.client.CollectorRegistry
   java.util.concurrent.ScheduledThreadPoolExecutor))

(def ^:private valid-definitions? @#'app.metrics/valid-definitions?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sample-names
  [metrics]
  (->> (mtx/get-registry metrics)
       (.metricFamilySamples)
       (enumeration-seq)
       (mapcat (fn [^Collector$MetricFamilySamples family]
                 (map (fn [^Collector$MetricFamilySamples$Sample sample]
                        (.-name sample))
                      (.samples family))))
       (set)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest process-metrics-are-exported
  ;; the process cpu and file descriptor families come from the
  ;; prometheus client `StandardExports`, registered by `app.metrics`.
  ;; They are read reflectively from the OS MXBean and depend on the
  ;; `jdk.management` module at runtime: a pruned jlink JRE turns the
  ;; MXBean into `sun.management.BaseOperatingSystemImpl`, the reflective
  ;; getters fail and the families are silently dropped (that is how the
  ;; production backend lost `process_open_fds`). This test pins the
  ;; contract the fd alert relies on.
  (let [metrics (ig/init-key :app.metrics/metrics {:default {}})
        names   (sample-names metrics)]

    (t/is (contains? names "process_open_fds"))
    (t/is (contains? names "process_max_fds"))
    (t/is (contains? names "process_cpu_seconds_total"))))

(t/deftest label-coercion
  (t/are [value fallback expected]
         (= expected (mtx/label value fallback))
    "fs"      "unknown" "fs"
    :s3        "unknown" "s3"
    200       "unknown" "200"
    nil       "unknown" "unknown"
    nil       "default" "default"))

(t/deftest run-contains-label-arity-bug
  ;; A wrong label count throws inside the prometheus client; the
  ;; recording failure must not propagate to the caller.
  (let [registry  (CollectorRegistry.)
        collector (mtx/create-collector {::mdef/name "penpot_test_run_arity"
                                         ::mdef/help "test helper"
                                         ::mdef/type :counter
                                         ::mdef/labels ["a" "b"]
                                         :app.metrics/registry registry})
        instance  (reify mtx/IMetrics
                    (get-collector [_ _] collector))]
    (t/is (nil? (mtx/run! instance :id :x :inc 1 :labels ["only-one"])))))

(t/deftest run-fails-loudly-without-metrics-instance
  ;; A missing or invalid instance is a wiring bug: it must fail on every
  ;; invocation instead of silently dropping the measurement. With
  ;; asserts enabled this is an AssertionError; with asserts disabled the
  ;; collector lookup (outside the recording guard) throws instead.
  (t/is (thrown? Throwable (mtx/run! nil :id :x :inc 1)))
  (t/is (thrown? Throwable (mtx/run! :not-metrics :id :x :inc 1))))

(t/deftest run-records-on-success
  (let [registry  (CollectorRegistry.)
        collector (mtx/create-collector {::mdef/name "penpot_test_run"
                                         ::mdef/help "test helper"
                                         ::mdef/type :counter
                                         ::mdef/labels ["result"]
                                         :app.metrics/registry registry})
        instance  (reify mtx/IMetrics
                    (get-collector [_ _] collector))]
    (t/is (true? (mtx/run! instance :id :x :inc 1 :labels ["ok"])))))

(t/deftest instance-resolves-the-metrics-from-a-cfg
  ;; the resolver is a map read and nothing more: it hands back the very
  ;; instance, not a copy and not a wrapper
  (let [metrics (ig/init-key :app.metrics/metrics {:default {}})]
    (t/is (identical? metrics (mtx/instance {::mtx/metrics metrics})))))

(t/deftest instance-raises-on-a-cfg-without-metrics
  ;; A missing instance is a wiring bug, and the resolver is the one place
  ;; that says so: a module that resolves it at the top of a write sees the
  ;; failure before anything is persisted.
  (let [cause (try
                (mtx/instance {})
                (catch Throwable e e))]
    (t/is (some? cause))
    (t/is (= :missing-metrics (:code (ex-data cause))))
    (t/is (re-find #"::mtx/metrics" (:hint (ex-data cause))))))

(t/deftest definitions-schema-accepts-all-consumed-keys
  (t/is (true? (valid-definitions?
                {:test-timing
                 {::mdef/name "penpot_test_timing"
                  ::mdef/help "test"
                  ::mdef/type :histogram
                  ::mdef/labels ["op"]
                  ::mdef/buckets [5 10 25]}
                 :test-summary
                 {::mdef/name "penpot_test_summary"
                  ::mdef/help "test"
                  ::mdef/type :summary
                  ::mdef/quantiles [[0.5 0.01]]
                  ::mdef/max-age 60}}))))


(t/deftest definitions-schema-rejects-unknown-keys
  ;; A typo such as ::mdef/bucksets must fail at startup instead of
  ;; silently falling back to the default histogram buckets. The
  ;; definition map is closed, so unknown keys do not validate.
  (t/is (false? (valid-definitions?
                 {:bad {::mdef/name "penpot_bad_metric"
                        ::mdef/help "typo check"
                        ::mdef/type :histogram
                        ::mdef/bucksets [100]}}))))

(t/deftest create-sampler-reschedules-after-error
  ;; The docstring promise, tested once for both call sites: an unexpected error
  ;; on a single sample must not cancel the following runs. The first sample
  ;; runs immediately and throws; the next one must still be scheduled.
  (let [calls   (atom 0)
        sampler (mtx/create-sampler 1000 (fn []
                                           (swap! calls inc)
                                           (throw (ex-info "boom" {}))))]
    (try
      (t/is (loop [i 0]
              (cond (pos? @calls) true
                    (> i 200) false
                    :else (do (Thread/sleep 10) (recur (inc i)))))
            "the first sample must run immediately")

      (let [queue (.getQueue ^ScheduledThreadPoolExecutor sampler)]
        (t/is (loop [i 0]
                (cond (= 1 (.size queue)) true
                      (> i 200) false
                      :else (do (Thread/sleep 10) (recur (inc i)))))
              "the next sample must be scheduled after the error"))

      (finally
        (px/shutdown-now sampler)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HISTOGRAM BUCKETS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest histogram-bucket-tails-extend-past-7-5s
  ;; The sub-7.5s prefix stays identical, so p50/p90 resolution and the
  ;; history remain comparable; only the tail grows, per latency profile.
  (t/is (= mtx/default-histogram-buckets
           (subvec mtx/default-request-histogram-buckets 0 14)))
  (t/is (= mtx/default-histogram-buckets
           (subvec mtx/default-tasks-histogram-buckets 0 14)))
  (t/is (= mtx/default-histogram-buckets
           (subvec mtx/default-redis-histogram-buckets 0 14)))

  (t/is (= [10000 15000 30000 60000]
           (subvec mtx/default-request-histogram-buckets 14)))
  (t/is (= [10000 15000 30000 60000 120000 300000]
           (subvec mtx/default-tasks-histogram-buckets 14)))
  (t/is (= [10000]
           (subvec mtx/default-redis-histogram-buckets 14))))

(t/deftest default-metrics-wire-bucket-tails-per-profile
  ;; Requests and waits cover the 30s/60s CLIMIT timeouts, tasks run
  ;; until done (300s postgres statement timeout), Redis only needs the
  ;; pathological case covered.
  (doseq [id [:rpc-main-timing :rpc-management-timing :rpc-climit-timing
              :audit-http-handler-timing :http-server-dispatch-timing
              :update-file-validation-timing]]
    (t/is (= mtx/default-request-histogram-buckets
             (get-in main/default-metrics [id ::mdef/buckets]))
          (str "request buckets on " id)))
  (t/is (= mtx/default-tasks-histogram-buckets
           (get-in main/default-metrics [:tasks-timing ::mdef/buckets])))
  (t/is (= mtx/default-redis-histogram-buckets
           (get-in main/default-metrics [:redis-eval-timing ::mdef/buckets]))))

(defn- bucket-value
  "Cumulative count of the `le` bucket of a histogram, read from the
  registry. Nil when that boundary does not exist."
  [metrics mname label-names label-values le]
  (.getSampleValue ^CollectorRegistry (mtx/get-registry metrics)
                   (str mname "_bucket")
                   (into-array String (conj (vec label-names) "le"))
                   (into-array String (conj (vec label-values) le))))

(t/deftest histogram-tails-are-registered
  ;; A 9s observation must land in the new tail instead of piling up in
  ;; +Inf: every histogram used to stop at 7500.
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})]
    (mtx/run! metrics :id :rpc-main-timing :val 9000 :labels (into-array String ["update-file"]))
    (t/is (= 0.0 (bucket-value metrics "penpot_rpc_main_timing" ["name"] ["update-file"] "7500.0")))
    (t/is (= 1.0 (bucket-value metrics "penpot_rpc_main_timing" ["name"] ["update-file"] "10000.0")))
    (t/is (nil? (bucket-value metrics "penpot_rpc_main_timing" ["name"] ["update-file"] "120000.0")))

    (mtx/run! metrics :id :tasks-timing :val 200000 :labels (into-array String ["file-gc"]))
    (t/is (= 0.0 (bucket-value metrics "penpot_tasks_timing" ["name"] ["file-gc"] "60000.0")))
    (t/is (= 1.0 (bucket-value metrics "penpot_tasks_timing" ["name"] ["file-gc"] "300000.0")))

    (mtx/run! metrics :id :redis-eval-timing :val 9000 :labels (into-array String ["claim"]))
    (t/is (= 1.0 (bucket-value metrics "penpot_redis_eval_timing" ["name"] ["claim"] "10000.0")))
    (t/is (nil? (bucket-value metrics "penpot_redis_eval_timing" ["name"] ["claim"] "15000.0")))

    (mtx/run! metrics :id :http-server-dispatch-timing :val 45000)
    (t/is (= 1.0 (bucket-value metrics "penpot_http_server_dispatch_timing" [] [] "60000.0")))

    (mtx/run! metrics :id :update-file-validation-timing :val 9000 :labels (into-array String ["validate-affected"]))
    (t/is (= 0.0 (bucket-value metrics "penpot_rpc_update_file_validation_timing" ["step"] ["validate-affected"] "7500.0")))
    (t/is (= 1.0 (bucket-value metrics "penpot_rpc_update_file_validation_timing" ["step"] ["validate-affected"] "10000.0")))))
