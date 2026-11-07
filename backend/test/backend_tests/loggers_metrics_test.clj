;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.loggers-metrics-test
  "The reporter that turns log records into a counter."
  (:require
   [app.common.logging :as l]
   [app.loggers.metrics] ;; registers the integrant methods of the reporter key
   [app.main :as main]
   [app.metrics :as mtx]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry))

(def ^:private logger
  "The logger of a record is the namespace of the code that logs, and that is
  this namespace."
  (str *ns*))

(defn- init-metrics
  []
  (ig/init-key :app.metrics/metrics {:default main/default-metrics}))

(defn- init-reporter
  [metrics]
  (ig/init-key :app.loggers.metrics/reporter {::mtx/metrics metrics}))

(defn- halt-reporter!
  [reporter]
  (ig/halt-key! :app.loggers.metrics/reporter reporter))

(defn- sample
  "Value of the counter for one logger and level, read from the registry. Nil
  when that combination has never been published."
  [metrics logger level]
  (.getSampleValue ^CollectorRegistry (mtx/get-registry metrics)
                   "penpot_log_events_total"
                   (into-array String ["logger" "level"])
                   (into-array String [logger level])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COUNTING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest warn-and-error-are-counted-by-logger-and-level
  (let [metrics  (init-metrics)
        reporter (init-reporter metrics)]
    (try
      ;; `:sync?` so the record is emitted on this thread and the assertion
      ;; does not depend on the logging executor.
      (l/warn :hint "una prueba" ::l/sync? true)
      (l/error :hint "otra prueba" ::l/sync? true)

      (t/is (= 1.0 (sample metrics logger "warn")))
      (t/is (= 1.0 (sample metrics logger "error")))

      (l/error :hint "y otra mas" ::l/sync? true)
      (t/is (= 2.0 (sample metrics logger "error")))

      (finally
        (halt-reporter! reporter)))))

(t/deftest info-is-not-counted
  (let [metrics  (init-metrics)
        reporter (init-reporter metrics)]
    (try
      (l/info :hint "un mensaje normal" ::l/sync? true)
      (t/is (nil? (sample metrics logger "info")))

      (finally
        (halt-reporter! reporter)))))

(t/deftest fatal-is-counted
  (let [metrics  (init-metrics)
        reporter (init-reporter metrics)]
    (try
      ;; No macro of the logging emits `:fatal`; this is the only way a record
      ;; gets that level.
      (l/log :fatal :hint "algo muy malo" ::l/sync? true)
      (t/is (= 1.0 (sample metrics logger "fatal")))

      (finally
        (halt-reporter! reporter)))))

(t/deftest halting-the-reporter-stops-counting
  (let [metrics  (init-metrics)
        reporter (init-reporter metrics)]

    (halt-reporter! reporter)

    (l/error :hint "despues de parar" ::l/sync? true)
    (t/is (nil? (sample metrics logger "error")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SAFETY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest a-failing-publication-does-not-break-the-logging
  ;; The watcher fires from the `swap!` of the log record, inside the logging
  ;; path. With `::l/sync?` an exception here would reach whoever was logging;
  ;; on the async path it would end in the executor's uncaught handler, which
  ;; is outside the application's logging, taking that log line out of Loki and
  ;; out of this counter.
  (let [metrics  (init-metrics)
        reporter (init-reporter metrics)]
    (try
      (with-redefs [mtx/run! (fn [& _] (throw (ex-info "publicando mal" {})))]
        (t/is (nil? (l/error :hint "no debe propagar" ::l/sync? true))))

      (finally
        (halt-reporter! reporter)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFINITION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-log-events-metric-is-defined
  (let [metrics (init-metrics)]
    (t/is (= ["logger" "level"]
             (:app.metrics.definition/labels (mtx/get-collector metrics :log-events))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIRING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-system-declares-the-reporter
  ;; The reporter hooks itself to the logging, so declaring it in the system is
  ;; all the wiring there is. The tests of the suite remove it, so this is the
  ;; only place that pins it.
  (t/is (= {::mtx/metrics (ig/ref ::mtx/metrics)}
           (:app.loggers.metrics/reporter main/system-config))))
