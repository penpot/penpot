;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.metrics
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.logging :as l]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child
   io.prometheus.client.Summary
   io.prometheus.client.Summary$Child
   io.prometheus.client.Summary$Builder
   io.prometheus.client.Histogram
   io.prometheus.client.Histogram$Child
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.hotspot.DefaultExports
   io.prometheus.client.jetty.JettyStatisticsCollector
   org.eclipse.jetty.server.handler.StatisticsHandler
   java.io.StringWriter))

(set! *warn-on-reflection* true)

(declare create-registry)
(declare create)
(declare handler)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS SERVICE PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-metrics
  {:update-file-changes
   {:name "rpc_update_file_changes_total"
    :help "A total number of changes submitted to update-file."
    :type :counter}

   :update-file-bytes-processed
   {:name "rpc_update_file_bytes_processed_total"
    :help "A total number of bytes processed by update-file."
    :type :counter}

   :rpc-mutation-timing
   {:name "rpc_mutation_timing"
    :help "RPC mutation method call timming."
    :labels ["name"]
    :type :histogram}

   :rpc-query-timing
   {:name "rpc_query_timing"
    :help "RPC query method call timing."
    :labels ["name"]
    :type :histogram}

   :websocket-active-connections
   {:name "websocket_active_connections"
    :help "Active websocket connections gauge"
    :type :gauge}

   :websocket-messages-total
   {:name "websocket_message_total"
    :help "Counter of processed messages."
    :labels ["op"]
    :type :counter}

   :websocket-session-timing
   {:name "websocket_session_timing"
    :help "Websocket session timing (seconds)."
    :type :summary}

   :session-update-total
   {:name "http_session_update_total"
    :help "A counter of session update batch events."
    :type :counter}

   :tasks-timing
   {:name "penpot_tasks_timing"
    :help "Background tasks timing (milliseconds)."
    :labels ["name"]
    :type :summary}

   :rlimit-queued-submissions
   {:name "penpot_rlimit_queued_submissions"
    :help "Current number of queued submissions on RLIMIT."
    :labels ["name"]
    :type :gauge}

   :rlimit-used-permits
   {:name "penpot_rlimit_used_permits"
    :help "Current number of used permits on RLIMIT."
    :labels ["name"]
    :type :gauge}

   :rlimit-acquires-total
   {:name "penpot_rlimit_acquires_total"
    :help "Total number of acquire operations on RLIMIT."
    :labels ["name"]
    :type :counter}

   :executors-active-threads
   {:name "penpot_executors_active_threads"
    :help "Current number of threads available in the executor service."
    :labels ["name"]
    :type :gauge}

   :executors-running-threads
   {:name "penpot_executors_running_threads"
    :help "Current number of threads with state RUNNING."
    :labels ["name"]
    :type :gauge}

   :executors-queued-submissions
   {:name "penpot_executors_queued_submissions"
    :help "Current number of queued submissions."
    :labels ["name"]
    :type :gauge}})

(defmethod ig/init-key ::metrics
  [_ _]
  (l/info :action "initialize metrics")
  (let [registry    (create-registry)
        definitions (reduce-kv (fn [res k v]
                                 (->> (assoc v :registry registry)
                                      (create)
                                      (assoc res k)))
                               {}
                               default-metrics)]
    {:handler (partial handler registry)
     :definitions definitions
     :registry registry}))

(s/def ::handler fn?)
(s/def ::registry #(instance? CollectorRegistry %))
(s/def ::metrics
  (s/keys :req-un [::registry ::handler]))

(defn- handler
  [registry _ respond _]
  (let [samples  (.metricFamilySamples ^CollectorRegistry registry)
        writer   (StringWriter.)]
    (TextFormat/write004 writer samples)
    (respond {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
              :body (.toString writer)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-empty-labels (into-array String []))

(def default-quantiles
  [[0.5  0.01]
   [0.90 0.01]
   [0.99 0.001]])

(def default-histogram-buckets
  [1 5 10 25 50 75 100 250 500 750 1000 2500 5000 7500])

(defn run!
  [{:keys [definitions]} {:keys [id] :as params}]
  (when-let [mobj (get definitions id)]
    ((::fn mobj) params)
    true))

(defn create-registry
  []
  (let [registry (CollectorRegistry.)]
    (DefaultExports/register registry)
    registry))

(defn- is-array?
  [o]
  (let [oc (class o)]
    (and (.isArray ^Class oc)
         (= (.getComponentType oc) String))))

(defn make-counter
  [{:keys [name help registry reg labels] :as props}]
  (let [registry (or registry reg)
        instance (.. (Counter/build)
                     (name name)
                     (help help))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]

    {::instance instance
     ::fn (fn [{:keys [inc labels] :or {inc 1 labels default-empty-labels}}]
            (let [instance (.labels instance (if (is-array? labels) labels (into-array String labels)))]
              (.inc ^Counter$Child instance (double inc))))}))

(defn make-gauge
  [{:keys [name help registry reg labels] :as props}]
  (let [registry (or registry reg)
        instance (.. (Gauge/build)
                     (name name)
                     (help help))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]
    {::instance instance
     ::fn (fn [{:keys [inc dec labels val] :or {labels default-empty-labels}}]
            (let [instance (.labels ^Gauge instance (if (is-array? labels) labels (into-array String labels)))]
              (cond (number? inc) (.inc ^Gauge$Child instance (double inc))
                    (number? dec) (.dec ^Gauge$Child instance (double dec))
                    (number? val) (.set ^Gauge$Child instance (double val)))))}))

(defn make-summary
  [{:keys [name help registry reg labels max-age quantiles buckets]
    :or {max-age 3600 buckets 12 quantiles default-quantiles} :as props}]
  (let [registry (or registry reg)
        builder  (doto (Summary/build)
                   (.name name)
                   (.help help))
        _        (when (seq quantiles)
                   (.maxAgeSeconds ^Summary$Builder builder ^long max-age)
                   (.ageBuckets ^Summary$Builder builder buckets))
        _        (doseq [[q e] quantiles]
                   (.quantile ^Summary$Builder builder q e))
        _        (when (seq labels)
                   (.labelNames ^Summary$Builder builder (into-array String labels)))
        instance (.register ^Summary$Builder builder registry)]

    {::instance instance
     ::fn (fn [{:keys [val labels] :or {labels default-empty-labels}}]
            (let [instance (.labels ^Summary instance (if (is-array? labels) labels (into-array String labels)))]
              (.observe ^Summary$Child instance val)))}))

(defn make-histogram
  [{:keys [name help registry reg labels buckets]
    :or {buckets default-histogram-buckets}}]
  (let [registry (or registry reg)
        instance (doto (Histogram/build)
                   (.name name)
                   (.help help)
                   (.buckets (into-array Double/TYPE buckets)))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]

    {::instance instance
     ::fn (fn [{:keys [val labels] :or {labels default-empty-labels}}]
            (let [instance (.labels ^Histogram instance (if (is-array? labels) labels (into-array String labels)))]
              (.observe ^Histogram$Child instance val)))}))

(defn create
  [{:keys [type] :as props}]
  (case type
    :counter (make-counter props)
    :gauge   (make-gauge props)
    :summary (make-summary props)
    :histogram (make-histogram props)))

(defn instrument-jetty!
  [^CollectorRegistry registry ^StatisticsHandler handler]
  (doto (JettyStatisticsCollector. handler)
    (.register registry))
  nil)

