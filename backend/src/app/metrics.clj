;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.metrics
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
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
(declare handler)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Defaults
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def default-metrics
  {:profile-register
   {:name "actions_profile_register_count"
    :help "A global counter of user registrations."
    :type :counter}

   :profile-activation
   {:name "actions_profile_activation_count"
    :help "A global counter of profile activations"
    :type :counter}

   :update-file-changes
   {:name "rpc_update_file_changes_total"
    :help "A total number of changes submitted to update-file."
    :type :counter}

   :update-file-bytes-processed
   {:name "rpc_update_file_bytes_processed_total"
    :help "A total number of bytes processed by update-file."
    :type :counter}

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
    :quantiles []
    :type :summary}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  [registry _request]
  (let [samples  (.metricFamilySamples ^CollectorRegistry registry)
        writer   (StringWriter.)]
    (TextFormat/write004 writer samples)
    {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

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

    {::instance instance
     ::fn (fn [{:keys [by labels] :or {by 1}}]
            (if labels
              (.. ^Counter instance
                  (labels (into-array String labels))
                  (inc by))
              (.inc ^Counter instance by)))}))

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
     ::fn (fn [{:keys [cmd by labels] :or {by 1}}]
            (if labels
              (let [labels (into-array String [labels])]
                (case cmd
                  :inc (.. ^Gauge instance (labels labels) (inc by))
                  :dec (.. ^Gauge instance (labels labels) (dec by))))
              (case cmd
                :inc (.inc ^Gauge instance by)
                :dec (.dec ^Gauge instance by))))}))

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

    {::instance instance
     ::fn (fn [{:keys [val labels]}]
            (if labels
              (.. ^Summary instance
                  (labels (into-array String labels))
                  (observe val))
              (.observe ^Summary instance val)))}))

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

    {::instance instance
     ::fn (fn [{:keys [val labels]}]
            (if labels
              (.. ^Histogram instance
                  (labels (into-array String labels))
                  (observe val))
              (.observe ^Histogram instance val)))}))

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
          ((::fn mobj) nil)
          (origf a))
         ([a b]
          ((::fn mobj) nil)
          (origf a b))
         ([a b c]
          ((::fn mobj) nil)
          (origf a b c))
         ([a b c d]
          ((::fn mobj) nil)
          (origf a b c d))
         ([a b c d & more]
          ((::fn mobj) nil)
          (apply origf a b c d more)))
       (assoc mdata ::original origf))))
  ([rootf mobj labels]
   (let [mdata  (meta rootf)
         origf  (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
          ((::fn mobj) {:labels labels})
          (origf a))
         ([a b]
          ((::fn mobj) {:labels labels})
          (origf a b))
         ([a b & more]
          ((::fn mobj) {:labels labels})
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
           :cb   #((::fn mobj) {:val %})))
         ([a b]
          (with-measure
            :expr (origf a b)
            :cb   #((::fn mobj) {:val %})))
         ([a b & more]
          (with-measure
            :expr (apply origf a b more)
            :cb   #((::fn mobj) {:val %}))))
       (assoc mdata ::original origf))))

  ([rootf mobj labels]
   (let [mdata  (meta rootf)
         origf  (::original mdata rootf)]
     (with-meta
       (fn
         ([a]
         (with-measure
           :expr (origf a)
           :cb   #((::fn mobj) {:val % :labels labels})))
         ([a b]
          (with-measure
            :expr (origf a b)
            :cb   #((::fn mobj) {:val % :labels labels})))
         ([a b & more]
          (with-measure
            :expr (apply origf a b more)
            :cb   #((::fn mobj) {:val % :labels labels}))))
       (assoc mdata ::original origf)))))

(defn instrument-vars!
  [vars {:keys [wrap] :as props}]
  (let [obj (create props)]
    (cond
      (instance? Counter (::instance obj))
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-counter) obj))

      (instance? Summary (::instance obj))
      (doseq [var vars]
        (alter-var-root var (or wrap wrap-summary) obj))

      :else
      (ex/raise :type :not-implemented))))

(defn instrument
  [f {:keys [wrap] :as props}]
  (let [obj (create props)]
    (cond
      (instance? Counter (::instance obj))
      ((or wrap wrap-counter) f obj)

      (instance? Summary (::instance obj))
      ((or wrap wrap-summary) f obj)

      (instance? Histogram (::instance obj))
      ((or wrap wrap-summary) f obj)

      :else
      (ex/raise :type :not-implemented))))

(defn instrument-jetty!
  [^CollectorRegistry registry ^StatisticsHandler handler]
  (doto (JettyStatisticsCollector. handler)
    (.register registry))
  nil)

