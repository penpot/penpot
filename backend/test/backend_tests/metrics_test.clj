;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.metrics-test
  (:require
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample
   io.prometheus.client.CollectorRegistry))

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
