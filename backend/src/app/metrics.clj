;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.metrics
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.metrics.definition :as-alias mdef]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child
   io.prometheus.client.Histogram
   io.prometheus.client.Histogram$Child
   io.prometheus.client.SimpleCollector
   io.prometheus.client.Summary
   io.prometheus.client.Summary$Builder
   io.prometheus.client.Summary$Child
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.hotspot.DefaultExports
   java.io.StringWriter))

(set! *warn-on-reflection* true)

(declare create-registry)
(declare create-collector)
(declare handler)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS SERVICE PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-metrics
  {:update-file-changes
   {::mdef/name "penpot_rpc_update_file_changes_total"
    ::mdef/help "A total number of changes submitted to update-file."
    ::mdef/type :counter}

   :update-file-bytes-processed
   {::mdef/name "penpot_rpc_update_file_bytes_processed_total"
    ::mdef/help "A total number of bytes processed by update-file."
    ::mdef/type :counter}

   :rpc-mutation-timing
   {::mdef/name "penpot_rpc_mutation_timing"
    ::mdef/help "RPC mutation method call timming."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-command-timing
   {::mdef/name "penpot_rpc_command_timing"
    ::mdef/help "RPC command method call timming."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-query-timing
   {::mdef/name "penpot_rpc_query_timing"
    ::mdef/help "RPC query method call timing."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :websocket-active-connections
   {::mdef/name "penpot_websocket_active_connections"
    ::mdef/help "Active websocket connections gauge"
    ::mdef/type :gauge}

   :websocket-messages-total
   {::mdef/name "penpot_websocket_message_total"
    ::mdef/help "Counter of processed messages."
    ::mdef/labels ["op"]
    ::mdef/type :counter}

   :websocket-session-timing
   {::mdef/name "penpot_websocket_session_timing"
    ::mdef/help "Websocket session timing (seconds)."
    ::mdef/type :summary}

   :session-update-total
   {::mdef/name "penpot_http_session_update_total"
    ::mdef/help "A counter of session update batch events."
    ::mdef/type :counter}

   :tasks-timing
   {::mdef/name "penpot_tasks_timing"
    ::mdef/help "Background tasks timing (milliseconds)."
    ::mdef/labels ["name"]
    ::mdef/type :summary}

   :redis-eval-timing
   {::mdef/name "penpot_redis_eval_timing"
    ::mdef/help "Redis EVAL commands execution timings (ms)"
    ::mdef/labels ["name"]
    ::mdef/type :summary}

   :rpc-semaphore-queued-submissions
   {::mdef/name "penpot_rpc_semaphore_queued_submissions"
    ::mdef/help "Current number of queued submissions on RPC-SEMAPHORE."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-semaphore-used-permits
   {::mdef/name "penpot_rpc_semaphore_used_permits"
    ::mdef/help "Current number of used permits on RPC-SEMAPHORE."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-semaphore-acquires-total
   {::mdef/name "penpot_rpc_semaphore_acquires_total"
    ::mdef/help "Total number of acquire operations on RPC-SEMAPHORE."
    ::mdef/labels ["name"]
    ::mdef/type :counter}

   :executors-active-threads
   {::mdef/name "penpot_executors_active_threads"
    ::mdef/help "Current number of threads available in the executor service."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :executors-completed-tasks
   {::mdef/name "penpot_executors_completed_tasks_total"
    ::mdef/help "Aproximate number of completed tasks by the executor."
    ::mdef/labels ["name"]
    ::mdef/type :counter}

   :executors-running-threads
   {::mdef/name "penpot_executors_running_threads"
    ::mdef/help "Current number of threads with state RUNNING."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :executors-queued-submissions
   {::mdef/name "penpot_executors_queued_submissions"
    ::mdef/help "Current number of queued submissions."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}})

(s/def ::mdef/name string?)
(s/def ::mdef/help string?)
(s/def ::mdef/labels (s/every string? :kind vector?))
(s/def ::mdef/type #{:gauge :counter :summary :histogram})

(s/def ::mdef/instance
  #(instance? SimpleCollector %))

(s/def ::mdef/definition
  (s/keys :req [::mdef/name
                ::mdef/help
                ::mdef/type]
          :opt [::mdef/labels
                ::mdef/instance]))

(s/def ::definitions
  (s/map-of keyword? ::mdef/definition))

(s/def ::registry
  #(instance? CollectorRegistry %))

(s/def ::handler fn?)
(s/def ::metrics
  (s/keys :req [::registry
                ::handler
                ::definitions]))

(defmethod ig/init-key ::metrics
  [_ _]
  (l/info :action "initialize metrics")
  (let [registry    (create-registry)
        definitions (reduce-kv (fn [res k v]
                                 (->> (assoc v ::registry registry)
                                      (create-collector)
                                      (assoc res k)))
                               {}
                               default-metrics)]

    (us/verify! ::definitions definitions)

    {::handler (partial handler registry)
     ::definitions definitions
     ::registry registry}))

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

(defmulti run-collector! (fn [mdef _] (::mdef/type mdef)))
(defmulti create-collector ::mdef/type)

(defn run!
  [{:keys [::definitions]} {:keys [id] :as params}]
  (when-let [mobj (get definitions id)]
    (run-collector! mobj params)
    true))

(defn- create-registry
  []
  (let [registry (CollectorRegistry.)]
    (DefaultExports/register registry)
    registry))

(defn- is-array?
  [o]
  (let [oc (class o)]
    (and (.isArray ^Class oc)
         (= (.getComponentType oc) String))))

(defmethod run-collector! :counter
  [{:keys [::mdef/instance]} {:keys [inc labels] :or {inc 1 labels default-empty-labels}}]
  (let [instance (.labels instance (if (is-array? labels) labels (into-array String labels)))]
    (.inc ^Counter$Child instance (double inc))))

(defmethod run-collector! :gauge
  [{:keys [::mdef/instance]} {:keys [inc dec labels val] :or {labels default-empty-labels}}]
  (let [instance (.labels ^Gauge instance (if (is-array? labels) labels (into-array String labels)))]
    (cond (number? inc) (.inc ^Gauge$Child instance (double inc))
          (number? dec) (.dec ^Gauge$Child instance (double dec))
          (number? val) (.set ^Gauge$Child instance (double val)))))

(defmethod run-collector! :summary
  [{:keys [::mdef/instance]} {:keys [val labels] :or {labels default-empty-labels}}]
  (let [instance (.labels ^Summary instance (if (is-array? labels) labels (into-array String labels)))]
    (.observe ^Summary$Child instance val)))

(defmethod run-collector! :histogram
  [{:keys [::mdef/instance]} {:keys [val labels] :or {labels default-empty-labels}}]
  (let [instance (.labels ^Histogram instance (if (is-array? labels) labels (into-array String labels)))]
    (.observe ^Histogram$Child instance val)))

(defmethod create-collector :counter
  [{::mdef/keys [name help reg labels]
    ::keys [registry]
    :as props}]

  (let [registry (or registry reg)
        instance (.. (Counter/build)
                     (name name)
                     (help help))]
    (when (seq labels)
      (.labelNames instance (into-array String labels)))

    (assoc props ::mdef/instance (.register instance registry))))

(defmethod create-collector :gauge
  [{::mdef/keys [name help reg labels]
    ::keys [registry]
    :as props}]
  (let [registry (or registry reg)
        instance (.. (Gauge/build)
                     (name name)
                     (help help))]
    (when (seq labels)
      (.labelNames instance (into-array String labels)))

    (assoc props ::mdef/instance (.register instance registry))))

(defmethod create-collector :summary
  [{::mdef/keys [name help reg labels max-age quantiles buckets]
    ::keys [registry]
    :or {max-age 3600 buckets 12 quantiles default-quantiles}
    :as props}]
  (let [registry (or registry reg)
        builder  (doto (Summary/build)
                   (.name name)
                   (.help help))]

    (when (seq quantiles)
      (.maxAgeSeconds ^Summary$Builder builder ^long max-age)
      (.ageBuckets ^Summary$Builder builder buckets))

    (doseq [[q e] quantiles]
      (.quantile ^Summary$Builder builder q e))

    (when (seq labels)
      (.labelNames ^Summary$Builder builder (into-array String labels)))

    (assoc props ::mdef/instance (.register ^Summary$Builder builder registry))))

(defmethod create-collector :histogram
  [{::mdef/keys [name help reg labels buckets]
    ::keys [registry]
    :or {buckets default-histogram-buckets}
    :as props}]
  (let [registry (or registry reg)
        instance (doto (Histogram/build)
                   (.name name)
                   (.help help)
                   (.buckets (into-array Double/TYPE buckets)))]

    (when (seq labels)
      (.labelNames instance (into-array String labels)))

    (assoc props ::mdef/instance (.register instance registry))))
