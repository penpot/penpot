;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.metrics-test
  (:require
   [app.metrics :as mtx]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample))

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
