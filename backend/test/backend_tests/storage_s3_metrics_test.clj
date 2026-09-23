;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-s3-metrics-test
  (:require
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.storage.s3.metrics :as s3m]
   [clojure.test :as t]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Histogram
   io.prometheus.client.Histogram$Child
   io.prometheus.client.Histogram$Child$Value
   java.time.Duration
   software.amazon.awssdk.core.metrics.CoreMetric
   software.amazon.awssdk.metrics.MetricCollector))

(defn- make-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:storage-s3-requests
                                       :storage-s3-retries
                                       :storage-s3-timing])}))

(defn- counter-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- histogram-sum
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Histogram instance (into-array String labels))
        value     (.get ^Histogram$Child child)]
    (.-sum ^Histogram$Child$Value value)))

(defn- api-call
  [entries]
  (let [collector (MetricCollector/create "ApiCall")]
    (doseq [[metric value] entries]
      (.reportMetric ^MetricCollector collector metric value))
    (.collect ^MetricCollector collector)))

(t/deftest publisher-records-operation-retries-and-duration
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        call      (api-call [[CoreMetric/OPERATION_NAME "PutObject"]
                             [CoreMetric/API_CALL_SUCCESSFUL true]
                             [CoreMetric/RETRY_COUNT 2]
                             [CoreMetric/API_CALL_DURATION (Duration/ofMillis 12)]])]
    (.publish publisher call)
    (t/is (= 1.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "ok"])))
    (t/is (= 2.0 (counter-value metrics :storage-s3-retries ["PutObject" "default"])))
    (t/is (= 12.0 (histogram-sum metrics :storage-s3-timing ["PutObject" "default"])))))

(t/deftest publisher-ignores-invalid-collections
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        empty-call (.collect ^MetricCollector (MetricCollector/create "ApiCall"))]
    (t/is (nil? (.publish publisher empty-call)))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "ok"])))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["unknown" "default" "ok"])))))

(t/deftest publisher-records-failed-calls
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        call      (api-call [[CoreMetric/OPERATION_NAME "PutObject"]
                             [CoreMetric/API_CALL_SUCCESSFUL false]
                             [CoreMetric/RETRY_COUNT 0]
                             [CoreMetric/API_CALL_DURATION (Duration/ofMillis 7)]])]
    (.publish publisher call)
    (t/is (= 1.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "error"])))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "ok"])))
    (t/is (= 7.0 (histogram-sum metrics :storage-s3-timing ["PutObject" "default"])))))

(t/deftest publisher-labels-custom-target
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :eu-west)
        call      (api-call [[CoreMetric/OPERATION_NAME "GetObject"]
                             [CoreMetric/API_CALL_SUCCESSFUL true]
                             [CoreMetric/RETRY_COUNT 0]
                             [CoreMetric/API_CALL_DURATION (Duration/ofMillis 3)]])]
    (.publish publisher call)
    (t/is (= 1.0 (counter-value metrics :storage-s3-requests ["GetObject" "eu-west" "ok"])))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["GetObject" "default" "ok"])))))

(t/deftest publisher-survives-record-failure
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        call      (api-call [[CoreMetric/OPERATION_NAME "PutObject"]
                             [CoreMetric/API_CALL_SUCCESSFUL true]
                             [CoreMetric/RETRY_COUNT 0]
                             [CoreMetric/API_CALL_DURATION (Duration/ofMillis 5)]])]
    (with-mocks [_mock {:target 'app.metrics/run-collector!
                        :throw (ex-info "boom" {})}]
      (t/is (nil? (.publish publisher call))))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "ok"])))))

(t/deftest publisher-treats-missing-success-flag-as-error
  ;; Documents the nil policy: a present operation without a success flag
  ;; counts as an error so silent SDK changes surface on dashboards.
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        call      (api-call [[CoreMetric/OPERATION_NAME "PutObject"]])]
    (.publish publisher call)
    (t/is (= 1.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "error"])))
    (t/is (= 0.0 (counter-value metrics :storage-s3-requests ["PutObject" "default" "ok"])))))

(t/deftest publisher-skips-retries-and-timing-when-absent
  (let [metrics   (make-metrics)
        publisher (s3m/wrap-publisher metrics :default)
        call      (api-call [[CoreMetric/OPERATION_NAME "GetObject"]
                             [CoreMetric/API_CALL_SUCCESSFUL true]
                             [CoreMetric/RETRY_COUNT 0]])]
    (.publish publisher call)
    (t/is (= 1.0 (counter-value metrics :storage-s3-requests ["GetObject" "default" "ok"])))
    (t/is (= 0.0 (counter-value metrics :storage-s3-retries ["GetObject" "default"])))
    (t/is (= 0.0 (histogram-sum metrics :storage-s3-timing ["GetObject" "default"])))))

(t/deftest s3-backend-is-wired-with-optional-metrics
  (t/is (= (ig/ref ::mtx/metrics)
           (get-in main/system-config [:app.storage.s3/backend ::mtx/metrics]))))
