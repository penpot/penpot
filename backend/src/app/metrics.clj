;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.metrics
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.metrics.definition :as-alias mdef]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child
   io.prometheus.client.Histogram
   io.prometheus.client.Histogram$Child
   io.prometheus.client.hotspot.DefaultExports
   io.prometheus.client.SimpleCollector
   io.prometheus.client.Summary
   io.prometheus.client.Summary$Builder
   io.prometheus.client.Summary$Child
   java.io.StringWriter))

(set! *warn-on-reflection* true)

(declare create-registry)
(declare create-collector)
(declare handler)

(defprotocol IMetrics
  (get-registry [_])
  (get-collector [_ id])
  (get-handler [_]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS SERVICE PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/register!
 {:type ::collector
  :pred #(instance? SimpleCollector %)
  :type-properties
  {:title "collector"
   :description "An instance of SimpleCollector"}})

(sm/register!
 {:type ::registry
  :pred  #(instance? CollectorRegistry %)
  :type-properties
  {:title "Metrics Registry"
   :description "Instance of CollectorRegistry"}})

(def ^:private schema:definitions
  [:map-of :keyword
   [:map {:title "definition"}
    [::mdef/name :string]
    [::mdef/help :string]
    [::mdef/type [:enum :gauge :counter :summary :histogram]]
    [::mdef/labels {:optional true} [::sm/vec :string]]
    [::mdef/instance {:optional true} ::collector]]])

(defn metrics?
  [o]
  (satisfies? IMetrics o))

(sm/register!
 {:type ::metrics
  :pred metrics?})

(def ^:private valid-definitions?
  (sm/validator schema:definitions))

(defmethod ig/assert-key ::metrics
  [_ {:keys [default]}]
  (assert (valid-definitions? default) "expected valid definitions"))

(defmethod ig/init-key ::metrics
  [_ cfg]
  (l/info :action "initialize metrics")
  (let [registry    (create-registry)
        definitions (reduce-kv (fn [res k v]
                                 (->> (assoc v ::registry registry)
                                      (create-collector)
                                      (assoc res k)))
                               {}
                               (:default cfg))]

    (reify
      IMetrics
      (get-handler [_]
        (partial handler registry))
      (get-collector [_ id]
        (get definitions id))
      (get-registry [_]
        registry))))

(defn- handler
  [registry _]
  (let [samples  (.metricFamilySamples ^CollectorRegistry registry)
        writer   (StringWriter.)]
    (TextFormat/write004 writer samples)
    {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

(defmethod ig/assert-key ::routes
  [_ {:keys [::metrics]}]
  (assert (metrics? metrics) "expected a valid instance for metrics"))

(defmethod ig/init-key ::routes
  [_ {:keys [::metrics]}]
  ["/metrics" {:handler (get-handler metrics)
               :allowed-methods #{:get}}])

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

;; Tail for request and wait timings (RPC, CLIMIT waits, audit handler,
;; HTTP dispatch): covers the 30s/60s CLIMIT timeouts while keeping the
;; sub-7.5s resolution of the default buckets untouched.
(def default-request-histogram-buckets
  [1 5 10 25 50 75 100 250 500 750 1000 2500 5000 7500
   10000 15000 30000 60000])

;; Tail for background tasks (GC sweeps, file workers): they run until
;; done with no CLIMIT timeout, bounded in practice by the 300s postgres
;; statement timeout.
(def default-tasks-histogram-buckets
  [1 5 10 25 50 75 100 250 500 750 1000 2500 5000 7500
   10000 15000 30000 60000 120000 300000])

;; Tail for Redis eval: sub-second in practice, one extra bucket covers
;; the pathological (timeout) case without adding idle series.
(def default-redis-histogram-buckets
  [1 5 10 25 50 75 100 250 500 750 1000 2500 5000 7500 10000])

(defmulti run-collector! (fn [mdef _] (::mdef/type mdef)))
(defmulti create-collector ::mdef/type)

(defn run!
  [instance & {:keys [id] :as params}]
  (assert (metrics? instance) "expected valid metrics instance")
  (when-let [mobj (get-collector instance id)]
    (run-collector! mobj params)
    true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SAMPLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-sampler
  "Create a daemon scheduler that runs `sample` every `interval-ms`,
  starting right away. A single thread is used, named after `name`
  (default `metrics`), and an unexpected error on a single sample does NOT
  cancel the following runs: the next run is scheduled in a `finally`.
  The caller owns the returned scheduler and has to shut it down
  (`px/shutdown-now`) when its component halts."
  ([interval-ms sample]
   (create-sampler interval-ms sample {}))
  ([interval-ms sample {:keys [name] :or {name "metrics"}}]
   (let [prefix    (str "penpot/" name "/")
         scheduler (px/scheduled-executor
                    :parallelism 1
                    :factory (px/thread-factory :prefix prefix :daemon true))
         task      (fn task []
                     (try
                       (sample)
                       (finally
                         (px/schedule scheduler interval-ms task))))]

     (px/schedule scheduler 0 task)
     scheduler)))

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
  (let [instance (.labels ^Counter instance (if (is-array? labels) labels (into-array String labels)))]
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
