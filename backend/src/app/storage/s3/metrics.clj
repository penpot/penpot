;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.storage.s3.metrics
  "Prometheus metrics for physical S3 API calls."
  (:require
   [app.common.logging :as l]
   [app.metrics :as mtx])
  (:import
   java.time.Duration
   software.amazon.awssdk.core.metrics.CoreMetric
   software.amazon.awssdk.metrics.MetricCollection
   software.amazon.awssdk.metrics.MetricPublisher))

(defn- operation-label
  [operation]
  (mtx/label operation "unknown"))

(defn- target-label
  [target]
  (mtx/label target "default"))

(defn- result-label
  [successful?]
  (if (true? successful?) "ok" "error"))

(defn- retries-count
  [retries]
  (try
    (long (or retries 0))
    (catch Throwable _ 0)))

(defn- duration-millis
  [duration]
  (when (instance? Duration duration)
    (.toMillis ^Duration duration)))

(defn- first-value
  [^MetricCollection collection metric]
  (first (.metricValues collection metric)))

(defn- record-collection!
  [metrics target ^MetricCollection collection]
  (let [operation (operation-label (first-value collection CoreMetric/OPERATION_NAME))]
    (if (= operation "unknown")
      (l/wrn :hint "ignoring s3 metric without operation name")
      (let [ok?      (first-value collection CoreMetric/API_CALL_SUCCESSFUL)
            retries  (retries-count (first-value collection CoreMetric/RETRY_COUNT))
            duration (duration-millis (first-value collection CoreMetric/API_CALL_DURATION))]
        (mtx/run! metrics :id :storage-s3-requests :inc 1
                  :labels [operation target (result-label ok?)])
        (when (pos? retries)
          (mtx/run! metrics :id :storage-s3-retries :inc retries
                    :labels [operation target]))
        (when (some? duration)
          (mtx/run! metrics :id :storage-s3-timing :val duration
                    :labels [operation target]))))))

(defn wrap-publisher
  "Return a MetricPublisher that records each S3 API call.

  `target` is the physical storage target id. Returns nil when `metrics`
  is nil so storage can run without instrumentation."
  [metrics target]
  (when metrics
    (let [target (target-label target)]
      (reify MetricPublisher
        (^void publish [_ ^MetricCollection collection]
          (try
            (record-collection! metrics target collection)
            (catch Throwable cause
              (l/dbg :hint "unable to record s3 metric" :cause cause)))
          nil)
        (^void close [_])))))
