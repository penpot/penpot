;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.metrics
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.metrics.definition :as-alias mdef]
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

(defmulti run-collector! (fn [mdef _] (::mdef/type mdef)))
(defmulti create-collector ::mdef/type)

(defn run!
  [instance & {:keys [id] :as params}]
  (assert (metrics? instance) "expected valid metrics instance")
  (when-let [mobj (get-collector instance id)]
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
