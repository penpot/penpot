;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.metrics
  (:require
   [app.common.exceptions :as ex]
   [app.util.logging :as l]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Gauge
   io.prometheus.client.Summary
   io.prometheus.client.Histogram
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.hotspot.DefaultExports
   io.prometheus.client.jetty.JettyStatisticsCollector
   org.eclipse.jetty.server.handler.StatisticsHandler
   java.io.StringWriter))

(declare instrument-vars!)
(declare instrument)
(declare create-registry)
(declare create)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handler
  [registry _request]
  (let [samples  (.metricFamilySamples ^CollectorRegistry registry)
        writer   (StringWriter.)]
    (TextFormat/write004 writer samples)
    {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

(s/def ::definitions
  (s/map-of keyword? map?))

(defmethod ig/pre-init-spec ::metrics [_]
  (s/keys :opt-un [::definitions]))

(defmethod ig/init-key ::metrics
  [_ {:keys [definitions] :as cfg}]
  (l/info :action "initialize metrics")
  (let [registry    (create-registry)
        definitions (reduce-kv (fn [res k v]
                                 (->> (assoc v :registry registry)
                                      (create)
                                      (assoc res k)))
                               {}
                               definitions)]
    {:handler (partial handler registry)
     :definitions definitions
     :registry registry}))

(s/def ::handler fn?)
(s/def ::registry #(instance? CollectorRegistry %))
(s/def ::metrics
  (s/keys :req-un [::registry ::handler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-registry
  []
  (let [registry (CollectorRegistry.)]
    (DefaultExports/register registry)
    registry))

(defmacro with-measure
  [& {:keys [expr cb]}]
  `(let [start# (System/nanoTime)
         tdown# ~cb]
     (try
       ~expr
       (finally
         (tdown# (/ (- (System/nanoTime) start#) 1000000))))))

(defn make-counter
  [{:keys [name help registry reg labels] :as props}]
  (let [registry (or registry reg)
        instance (.. (Counter/build)
                     (name name)
                     (help help))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (.inc ^Counter instance))

      (invoke [_ cmd labels]
        (.. ^Counter instance
            (labels (into-array String labels))
            (inc))))))

(defn make-gauge
  [{:keys [name help registry reg labels] :as props}]
  (let [registry (or registry reg)
        instance (.. (Gauge/build)
                     (name name)
                     (help help))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]

    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (case cmd
          :inc (.inc ^Gauge instance)
          :dec (.dec ^Gauge instance)))

      (invoke [_ cmd labels]
        (let [labels (into-array String [labels])]
          (case cmd
            :inc (.. ^Gauge instance (labels labels) (inc))
            :dec (.. ^Gauge instance (labels labels) (dec))))))))

(def default-quantiles
  [[0.75 0.02]
   [0.99 0.001]])

(defn make-summary
  [{:keys [name help registry reg labels max-age quantiles buckets]
    :or {max-age 3600 buckets 6 quantiles default-quantiles} :as props}]
  (let [registry (or registry reg)
        instance (doto (Summary/build)
                   (.name name)
                   (.help help))
        _        (when (seq quantiles)
                   (.maxAgeSeconds ^Summary instance max-age)
                   (.ageBuckets ^Summary instance buckets))
        _        (doseq [[q e] quantiles]
                   (.quantile ^Summary instance q e))
        _        (when (seq labels)
                   (.labelNames instance (into-array String labels)))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd val]
        (.observe ^Summary instance val))

      (invoke [_ cmd val labels]
        (.. ^Summary instance
            (labels (into-array String labels))
            (observe val))))))

(def default-histogram-buckets
  [1 5 10 25 50 75 100 250 500 750 1000 2500 5000 7500])

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
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd val]
        (.observe ^Histogram instance val))

      (invoke [_ cmd val labels]
        (.. ^Histogram instance
            (labels (into-array String labels))
            (observe val))))))

(defn create
  [{:keys [type] :as props}]
  (case type
    :counter (make-counter props)
    :gauge   (make-gauge props)
    :summary (make-summary props)
    :histogram (make-histogram props)))

(defn wrap-counter
  ([rootf mobj]
   (let [mdata (meta rootf)
         origf (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
          (mobj :inc)
          (origf a))
         ([a b]
          (mobj :inc)
          (origf a b))
         ([a b & more]
          (mobj :inc)
          (apply origf a b more)))
       (assoc mdata ::original origf))))
  ([rootf mobj labels]
   (let [mdata  (meta rootf)
         origf  (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
          (mobj :inc labels)
          (origf a))
         ([a b]
          (mobj :inc labels)
          (origf a b))
         ([a b & more]
          (mobj :inc labels)
          (apply origf a b more)))
       (assoc mdata ::original origf)))))

(defn wrap-summary
  ([rootf mobj]
   (let [mdata  (meta rootf)
         origf  (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
         (with-measure
           :expr (origf a)
           :cb   #(mobj :observe %)))
         ([a b]
          (with-measure
            :expr (origf a b)
            :cb   #(mobj :observe %)))
         ([a b & more]
          (with-measure
            :expr (apply origf a b more)
            :cb   #(mobj :observe %))))
       (assoc mdata ::original origf))))

  ([rootf mobj labels]
   (let [mdata  (meta rootf)
         origf  (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
         (with-measure
           :expr (origf a)
           :cb   #(mobj :observe % labels)))
         ([a b]
          (with-measure
            :expr (origf a b)
            :cb   #(mobj :observe % labels)))
         ([a b & more]
          (with-measure
            :expr (apply origf a b more)
            :cb   #(mobj :observe % labels))))
       (assoc mdata ::original origf)))))

(defn instrument-vars!
  [vars {:keys [wrap] :as props}]
  (let [obj (create props)]
    (cond
      (instance? Counter @obj)
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-counter) obj))

      (instance? Summary @obj)
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-summary) obj))

      :else
      (ex/raise :type :not-implemented))))

(defn instrument
  [f {:keys [wrap] :as props}]
  (let [obj (create props)]
    (cond
      (instance? Counter @obj)
      ((or wrap wrap-counter) f obj)

      (instance? Summary @obj)
      ((or wrap wrap-summary) f obj)

      (instance? Histogram @obj)
      ((or wrap wrap-summary) f obj)

      :else
      (ex/raise :type :not-implemented))))

(defn instrument-jetty!
  [^CollectorRegistry registry ^StatisticsHandler handler]
  (doto (JettyStatisticsCollector. handler)
    (.register registry))
  nil)

