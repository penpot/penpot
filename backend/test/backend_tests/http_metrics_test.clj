;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-metrics-test
  (:require
   [app.http :as http]
   [app.metrics :as mtx]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [promesa.exec :as px])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Gauge
   io.undertow.server.ConnectorStatistics
   java.util.concurrent.ScheduledThreadPoolExecutor
   org.xnio.management.XnioWorkerMXBean))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def metric-definitions
  {:http-worker-queue-size          {:name "penpot_http_worker_queue_size"
                                     :help "test"
                                     :type :gauge}
   :http-worker-busy-threads        {:name "penpot_http_worker_busy_threads"
                                     :help "test"
                                     :type :gauge}
   :http-worker-pool-size           {:name "penpot_http_worker_pool_size"
                                     :help "test"
                                     :type :gauge}
   :http-worker-max-pool-size       {:name "penpot_http_worker_max_pool_size"
                                     :help "test"
                                     :type :gauge}
   :http-connector-active-connections
   {:name "penpot_http_connector_active_connections"
    :help "test"
    :type :gauge}
   :http-connector-requests-total
   {:name "penpot_http_connector_requests_total"
    :help "test"
    :type :counter}
   :http-connector-errors-total
   {:name "penpot_http_connector_errors_total"
    :help "test"
    :type :counter}})

(defn- fake-metrics
  "Builds a minimal IMetrics instance backed by real prometheus
  collectors on a private registry."
  []
  (let [registry (CollectorRegistry.)
        collectors
        (into {}
              (map (fn [[id {:keys [name help type]}]]
                     (let [builder (case type
                                     :gauge   (Gauge/build)
                                     :counter (Counter/build))]
                       (doto builder
                         (.name name)
                         (.help help))
                       [id {:app.metrics.definition/type type
                            :app.metrics.definition/instance
                            (.register builder registry)}])))
              metric-definitions)]

    (reify app.metrics.IMetrics
      (get-registry [_] registry)
      (get-collector [_ id] (get collectors id))
      (get-handler [_] nil))))

(defn- gauge-value
  [^Gauge collector]
  (.get (.labels collector (make-array String 0))))

(defn- counter-value
  [^Counter collector]
  (.get (.labels collector (make-array String 0))))

(defn- fake-mxbean
  [{:keys [queue busy pool max]
    :or {queue 0 busy 0 pool 4 max 512}}]
  (reify XnioWorkerMXBean
    (getProviderName [_] "test")
    (getName [_] "test")
    (isShutdownRequested [_] false)
    (getCoreWorkerPoolSize [_] 32)
    (getMaxWorkerPoolSize [_] max)
    (getWorkerPoolSize [_] pool)
    (getBusyWorkerThreadCount [_] busy)
    (getIoThreadCount [_] 16)
    (getWorkerQueueSize [_] queue)
    (getServerMXBeans [_] #{})))

(defn- fake-connector-statistics
  [{:keys [requests errors active]
    :or {requests 0 errors 0 active 0}}]
  (reify ConnectorStatistics
    (getRequestCount [_] requests)
    (getBytesSent [_] 0)
    (getBytesReceived [_] 0)
    (getErrorCount [_] errors)
    (getProcessingTime [_] 0)
    (getMaxProcessingTime [_] 0)
    (getActiveConnections [_] active)
    (getMaxActiveConnections [_] active)
    (getActiveRequests [_] 0)
    (getMaxActiveRequests [_] 0)
    (reset [_] nil)))

(defn- sample-worker!
  [metrics mxbean]
  (http/sample-worker-metrics! metrics ^XnioWorkerMXBean mxbean))

(defn- sample-connector!
  [metrics state cs]
  (http/sample-connector-metrics! metrics state ^ConnectorStatistics cs))

(defn- registry-samples-count
  [metrics]
  (-> (mtx/get-registry metrics)
      (.metricFamilySamples)
      (enumeration-seq)
      (vec)))

(defn- collector-instance
  [metrics id]
  (:app.metrics.definition/instance (mtx/get-collector metrics id)))

(defn- make-state []
  (atom {:last-requests 0 :last-errors 0}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test: worker metrics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest sample-worker-metrics-publish-all-gauges
  (let [metrics (fake-metrics)
        mxbean  (fake-mxbean {:queue 2 :busy 10 :pool 40 :max 512})]

    (sample-worker! metrics mxbean)

    (t/is (= 2.0  (gauge-value (collector-instance metrics :http-worker-queue-size))))
    (t/is (= 10.0 (gauge-value (collector-instance metrics :http-worker-busy-threads))))
    (t/is (= 40.0 (gauge-value (collector-instance metrics :http-worker-pool-size))))
    (t/is (= 512.0 (gauge-value (collector-instance metrics :http-worker-max-pool-size))))))

(t/deftest sample-worker-metrics-skips-negative-samples
  ;; the xnio MXBean occasionally returns -1 on the busy thread count;
  ;; a negative value is a missing measurement, not a zero.
  (let [metrics (fake-metrics)
        mxbean  (fake-mxbean {:queue 0 :busy -1 :pool 4 :max 512})]

    (sample-worker! metrics mxbean)

    (t/is (= 0.0 (gauge-value (collector-instance metrics :http-worker-queue-size))))
    (t/is (= 4.0 (gauge-value (collector-instance metrics :http-worker-pool-size))))
    (t/is (= 512.0 (gauge-value (collector-instance metrics :http-worker-max-pool-size))))
    (t/is (= 0.0
             (gauge-value (collector-instance metrics :http-worker-busy-threads))))))

(t/deftest sample-worker-metrics-on-nil-args-does-nothing
  (let [metrics (fake-metrics)
        mxbean  (fake-mxbean {:queue 1 :busy 1 :pool 2 :max 4})]

    (t/is (true? (http/sample-worker-metrics! nil mxbean)))
    (t/is (true? (http/sample-worker-metrics! metrics nil)))

    ;; nothing was published: every gauge keeps its initial value.
    (t/is (= 0.0 (gauge-value (collector-instance metrics :http-worker-queue-size))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test: connector metrics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest sample-connector-metrics-publishes-gauges-and-counters
  (let [metrics (fake-metrics)
        state   (make-state)
        cs      (fake-connector-statistics {:requests 10 :errors 2 :active 5})]

    (sample-connector! metrics state cs)

    (t/is (= 5.0 (gauge-value (collector-instance metrics :http-connector-active-connections))))
    (t/is (= 10.0 (counter-value (collector-instance metrics :http-connector-requests-total))))
    (t/is (= 2.0 (counter-value (collector-instance metrics :http-connector-errors-total))))))

(t/deftest sample-connector-metrics-accumulates-delta
  (let [metrics (fake-metrics)
        state   (make-state)]

    (sample-connector! metrics state (fake-connector-statistics {:requests 10 :errors 0 :active 1}))
    (sample-connector! metrics state (fake-connector-statistics {:requests 25 :errors 0 :active 1}))

    (t/is (= 25.0 (counter-value (collector-instance metrics :http-connector-requests-total))))
    (t/is (= 0.0 (counter-value (collector-instance metrics :http-connector-errors-total))))))

(t/deftest sample-connector-metrics-skips-negative-delta
  ;; when the undertow counters are reset, the computed delta can go
  ;; negative: the counter must not decrease, and the reference must be
  ;; updated so the next sampling continues from the new watermark.
  (let [metrics (fake-metrics)
        state   (make-state)]

    (sample-connector! metrics state (fake-connector-statistics {:requests 20 :errors 5 :active 0}))
    (sample-connector! metrics state (fake-connector-statistics {:requests 10 :errors 3 :active 0}))
    (sample-connector! metrics state (fake-connector-statistics {:requests 15 :errors 6 :active 0}))

    (t/is (= 25.0 (counter-value (collector-instance metrics :http-connector-requests-total))))
    (t/is (= 8.0 (counter-value (collector-instance metrics :http-connector-errors-total))))))

(t/deftest sample-connector-metrics-on-nil-cs-does-nothing
  (let [metrics (fake-metrics)
        state   (make-state)]

    (t/is (true? (http/sample-connector-metrics! metrics state nil)))
    (t/is (true? (http/sample-connector-metrics! nil state (fake-connector-statistics {}))))

    (t/is (= 0.0
             (counter-value (collector-instance metrics :http-connector-requests-total))))))

(t/deftest sample-connector-metrics-state-advances-with-reset
  ;; after a reset (total decreased) followed by more requests, the
  ;; next delta must be computed from the new watermark and count only
  ;; the requests after the reset.
  (let [metrics (fake-metrics)
        state   (make-state)]

    (sample-connector! metrics state (fake-connector-statistics {:requests 10 :errors 0 :active 0}))
    (sample-connector! metrics state (fake-connector-statistics {:requests 5  :errors 0 :active 0})) ; reset to 5
    (sample-connector! metrics state (fake-connector-statistics {:requests 8  :errors 0 :active 0}))

    (t/is (= 13.0 (counter-value (collector-instance metrics :http-connector-requests-total))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test: sampler lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest create-metrics-sampler-lifecycle
  ;; a smoke test of the lifecycle wiring: the sampler is created with
  ;; a running scheduler and ends up shut down.
  (let [metrics (fake-metrics)
        sampler (http/create-metrics-sampler nil metrics)]
    (try
      (t/is (some? sampler))
      (t/is (px/executor? sampler))
      (t/is (not (px/shutdown? sampler)))
      (finally
        (px/shutdown-now sampler)
        (t/is (px/shutdown? sampler))))))

(t/deftest create-metrics-sampler-reschedules-after-error
  ;; the docstring promise: an unexpected error on a single sample must
  ;; not cancel the following runs. The first sample runs immediately
  ;; and throws; the next one must still be scheduled afterwards.
  (let [calls (atom 0)]
    (with-redefs [http/sample-http-metrics! (fn [_ _ _]
                                              (swap! calls inc)
                                              (throw (ex-info "boom" {})))]
      (let [sampler (http/create-metrics-sampler nil (fake-metrics))
            queue   (.getQueue ^ScheduledThreadPoolExecutor sampler)]
        (try
          (t/is (loop [i 0]
                  (cond (pos? @calls) true
                        (> i 200) false
                        :else (do (Thread/sleep 10) (recur (inc i)))))
                "the first sample must run immediately")

          (t/is (loop [i 0]
                  (cond (= 1 (.size queue)) true
                        (> i 200) false
                        :else (do (Thread/sleep 10) (recur (inc i)))))
                "the next sample must be scheduled after the error")
          (finally
            (px/shutdown-now sampler)))))))
