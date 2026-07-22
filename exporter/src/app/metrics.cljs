;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.metrics
  "Minimal in-process Prometheus metrics (text exposition format, no
  external deps). Records per-render counters and duration/CPU histograms
  labeled by render backend (`wasm` vs `browser`) and export type, so the
  headless and browser export paths can be compared in Grafana."
  (:require
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private bucket-bounds
  [0.5 1 2.5 5 10 30 60 120])

;; {[backend type] {:count n :errors n :sum secs :cpu secs :buckets [n...]}}
(defonce ^:private state (atom {}))

(defn- empty-entry
  []
  {:count 0
   :errors 0
   :sum 0
   :cpu 0
   :buckets (vec (repeat (count bucket-bounds) 0))})

(defn- update-entry
  [entry duration cpu error?]
  (let [entry (or entry (empty-entry))]
    (-> entry
        (update :count inc)
        (cond-> error? (update :errors inc))
        (update :sum + duration)
        (update :cpu + cpu)
        ;; increment every bucket whose upper bound covers this duration;
        ;; that keeps the stored counts cumulative, as prometheus expects.
        (update :buckets
                (fn [buckets]
                  (vec (map-indexed
                        (fn [i count]
                          (if (<= duration (nth bucket-bounds i))
                            (inc count)
                            count))
                        buckets)))))))

(defn observe-render!
  [backend type duration-s cpu-s error?]
  (swap! state update [backend (name type)] update-entry duration-s cpu-s error?))

(defn- elapsed
  "Seconds elapsed and process CPU seconds consumed since `t0`/`c0`."
  [t0 c0]
  (let [duration (/ (js/Number (- (js/process.hrtime.bigint) t0)) 1e9)
        cusage   (js/process.cpuUsage c0)
        cpu      (/ (+ (unchecked-get cusage "user")
                       (unchecked-get cusage "system"))
                    1e6)]
    [duration cpu]))

(defn with-render-metrics
  "Runs `thunk` (returning a promise) and records its wall-clock duration
  and the process CPU delta under the given backend/type labels. CPU is a
  whole-process delta, so concurrent renders bleed into each other's
  numbers; fine for the low-concurrency comparison use case."
  [backend type thunk]
  (let [t0 (js/process.hrtime.bigint)
        c0 (js/process.cpuUsage)]
    (-> (p/do (thunk))
        (p/then (fn [result]
                  (let [[duration cpu] (elapsed t0 c0)]
                    (observe-render! backend type duration cpu false)
                    result)))
        (p/catch (fn [cause]
                   (let [[duration cpu] (elapsed t0 c0)]
                     (observe-render! backend type duration cpu true)
                     (p/rejected cause)))))))

(defn- labels-str
  [backend type & extra]
  (str "{backend=\"" backend "\",type=\"" type "\""
       (str/join "" extra)
       "}"))

(defn export-text
  "Renders all recorded metrics in the Prometheus text exposition format."
  []
  (let [entries @state
        lines
        (concat
         ["# HELP penpot_exporter_render_total Total renders processed."
          "# TYPE penpot_exporter_render_total counter"]
         (for [[[backend type] entry] entries]
           (str "penpot_exporter_render_total" (labels-str backend type) " " (:count entry)))

         ["# HELP penpot_exporter_render_errors_total Renders that failed."
          "# TYPE penpot_exporter_render_errors_total counter"]
         (for [[[backend type] entry] entries]
           (str "penpot_exporter_render_errors_total" (labels-str backend type) " " (:errors entry)))

         ["# HELP penpot_exporter_render_cpu_seconds_total Process CPU consumed during renders."
          "# TYPE penpot_exporter_render_cpu_seconds_total counter"]
         (for [[[backend type] entry] entries]
           (str "penpot_exporter_render_cpu_seconds_total" (labels-str backend type) " " (:cpu entry)))

         ["# HELP penpot_exporter_render_duration_seconds Render wall-clock duration."
          "# TYPE penpot_exporter_render_duration_seconds histogram"]
         (mapcat
          (fn [[[backend type] entry]]
            (concat
             (map-indexed
              (fn [i bound]
                (str "penpot_exporter_render_duration_seconds_bucket"
                     (labels-str backend type ",le=\"" bound "\"")
                     " " (nth (:buckets entry) i)))
              bucket-bounds)
             [(str "penpot_exporter_render_duration_seconds_bucket"
                   (labels-str backend type ",le=\"+Inf\"")
                   " " (:count entry))
              (str "penpot_exporter_render_duration_seconds_sum"
                   (labels-str backend type) " " (:sum entry))
              (str "penpot_exporter_render_duration_seconds_count"
                   (labels-str backend type) " " (:count entry))]))
          entries))]
    (str (str/join "\n" lines) "\n")))
