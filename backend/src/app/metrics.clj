;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.metrics
  (:import
   io.prometheus.client.CollectorRegistry
   io.prometheus.client.Counter
   io.prometheus.client.Gauge
   io.prometheus.client.Summary
   io.prometheus.client.exporter.common.TextFormat
   io.prometheus.client.hotspot.DefaultExports
   java.io.StringWriter))

(defn- create-registry
  []
  (let [registry (CollectorRegistry.)]
    (DefaultExports/register registry)
    registry))

(defonce registry (create-registry))
(defonce cache (atom {}))

(defmacro with-measure
  [sym expr teardown]
  `(let [~sym (System/nanoTime)]
     (try
       ~expr
       (finally
         (let [~sym (/ (- (System/nanoTime) ~sym) 1000000)]
           ~teardown)))))

(defn make-counter
  [{:keys [id help] :as props}]
  (let [instance (doto (Counter/build)
                   (.name id)
                   (.help help))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (.inc ^Counter instance))

      (invoke [_ cmd val]
        (case cmd
          :wrap (fn
                  ([a]
                   (.inc ^Counter instance)
                   (val a))
                  ([a b]
                   (.inc ^Counter instance)
                   (val a b))
                  ([a b c]
                   (.inc ^Counter instance)
                   (val a b c)))

          (throw (IllegalArgumentException. "invalid arguments")))))))

(defn counter
  [{:keys [id] :as props}]
  (or (get @cache id)
      (let [v (make-counter props)]
        (swap! cache assoc id v)
        v)))

(defn make-gauge
  [{:keys [id help] :as props}]
  (let [instance (doto (Gauge/build)
                   (.name id)
                   (.help help))
        instance (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ cmd]
        (case cmd
          :inc (.inc ^Gauge instance)
          :dec (.dec ^Gauge instance))))))

(defn gauge
  [{:keys [id] :as props}]
  (or (get @cache id)
      (let [v (make-gauge props)]
        (swap! cache assoc id v)
        v)))

(defn make-summary
  [{:keys [id help] :as props}]
  (let [instance (doto (Summary/build)
                   (.name id)
                   (.help help)
                   (.quantile 0.5 0.05)
                   (.quantile 0.9 0.01)
                   (.quantile 0.99 0.001))
        instance  (.register instance registry)]
    (reify
      clojure.lang.IDeref
      (deref [_] instance)

      clojure.lang.IFn
      (invoke [_ val]
        (.observe ^Summary instance val))

      (invoke [_ cmd val]
        (case cmd
          :wrap (fn
                  ([a]
                   (with-measure $$
                     (val a)
                     (.observe ^Summary instance $$)))
                  ([a b]
                   (with-measure $$
                     (val a b)
                     (.observe ^Summary instance $$)))
                  ([a b c]
                   (with-measure $$
                     (val a b c)
                     (.observe ^Summary instance $$))))

          (throw (IllegalArgumentException. "invalid arguments")))))))

(defn summary
  [{:keys [id] :as props}]
  (or (get @cache id)
      (let [v (make-summary props)]
        (swap! cache assoc id v)
        v)))

(defn wrap-summary
  [f props]
  (let [sm (summary props)]
    (sm :wrap f)))

(defn wrap-counter
  [f props]
  (let [cnt (counter props)]
    (cnt :wrap f)))

(defn instrument-with-counter!
  [{:keys [var] :as props}]
  (let [cnt  (counter props)
        vars (if (var? var) [var] var)]
    (doseq [var vars]
      (alter-var-root var (fn [root]
                            (let [mdata (meta root)
                                  original (::counter-original mdata root)]
                              (with-meta
                                (cnt :wrap original)
                                (assoc mdata ::counter-original original))))))))

(defn instrument-with-summary!
  [{:keys [var] :as props}]
  (let [sm (summary props)]
    (alter-var-root var (fn [root]
                          (let [mdata (meta root)
                                original (::summary-original mdata root)]
                            (with-meta
                              (sm :wrap original)
                              (assoc mdata ::summary-original original)))))))

(defn dump
  [& _args]
  (let [samples (.metricFamilySamples ^CollectorRegistry registry)
        writer  (StringWriter.)]
    (TextFormat/write004 writer samples)
    {:headers {"content-type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

