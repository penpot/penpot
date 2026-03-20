;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.perf
  "Performance profiling for react components."
  (:require-macros [app.util.perf])
  (:require
   ["react" :as react]
   ["tdigest" :as td]
   [app.common.math :as mth]
   [goog.functions :as f]
   [rumext.v2 :as mf]))

;; For use it, just wrap the component you want to profile with
;; `perf/profiler` component and pass a label for debug purpose.
;;
;; Example:
;;
;; [:& perf/profiler {:label "viewport"}
;;  [:section
;;   [:& some-component]]]
;;
;; This will catch all renders and print to the console the
;; percentiles of render time measures. The log function is
;; automatically debounced to avoid excessive spam to the console.

(defn tdigest
  []
  (specify! (td/TDigest.)
    ITransientCollection
    (-conj! [this n]
      (.push this n)
      this)

    (-persistent! [this]
      this)))

(defn tdigest-summary
  [^js td]
  (str "samples=" (unchecked-get td "n") "\n"
       "Q50=" (.percentile td 0.50) "\n"
       "Q75=" (.percentile td 0.75) "\n"
       "Q95=" (.percentile td 0.90) "\n"
       "MAX=" (.percentile td 1)))

(defn timestamp
  []
  (js/performance.now))

(def registry (js/Map.))

(def register-measure
  (let [insert!
        (fn [name measure]
          (let [td (.get registry name)]
            (if td
              (conj! td measure)
              (.set registry name (conj! (tdigest) measure)))))

        print-single-summary!
        (fn [name td]
          (js/console.log (str "[measure: " name "] " (tdigest-summary td))))

        print-summary!
        (f/debounce
         #(.forEach registry (fn [td name] (print-single-summary! name td)))
         500)]
    (fn [name measure]
      (insert! name measure)
      (print-summary!))))

(defn measurable
  [name f]
  (fn [& args]
    (app.util.perf/with-measure name
      (apply f args))))

(defn on-render-factory
  [label]
  (let [td  (tdigest)
        log (f/debounce
             (fn [phase td]
               (js/console.log (str "[profile: " label " (" phase ")] "
                                    (tdigest-summary td))))
             300)]
    (fn [id phase adur, bdur, st, ct, itx]
      (conj! td adur)
      (log phase td))))

(mf/defc profiler
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        label    (unchecked-get props "label")
        enabled? (unchecked-get props "enabled")
        enabled? (if (nil? enabled?) true enabled?)
        on-render (mf/use-memo
                   (mf/deps label)
                   #(on-render-factory label))]
    (if enabled?
      [:> react/Profiler #js {:id label
                              :onRender on-render}
       children]
      children)))

(defn benchmark
  [& {:keys [run-fn chk-fn iterations name gc]
      :or {iterations 10000}}]
  (let [end-mark  (str name ":end")
        blackhole (volatile! nil)]
    (println "=> benchmarking:" name)
    (when gc
      (println "-> force gc: true"))

    (println "--> warming up:  " (* iterations 2))
    (when (fn? gc) (gc))
    (loop [i (* iterations 2)]
      (when (pos? i)
        (vreset! blackhole (run-fn))
        (recur (dec i))))
    (println "--> benchmarking:" iterations)
    (when (fn? gc) (gc))
    (js/performance.mark name)
    (loop [i iterations]
      (when (pos? i)
        (vreset! blackhole (run-fn))
        (recur (dec i))))
    (js/performance.measure end-mark name)

    (when (fn? chk-fn)
      (when-not (chk-fn @blackhole)
        (println "--> EE: failed chk-fn")))


    (let [[result] (js/performance.getEntriesByName end-mark)
          duration (mth/precision (.-duration ^js result) 4)
          avg      (mth/precision (/ duration iterations) 4)]
      (println "--> TOTAL:" (str duration " ms"))
      (println "--> AVG  :" (str avg " ms"))
      (println "")
      (js/performance.clearMarks name)
      (js/performance.clearMeasures end-mark)
      #js {:duration duration
           :avg avg})))

(defn now
  []
  (js/performance.now))

(defn tpoint
  "Create a measurement checkpoint for time measurement of potentially
  asynchronous flow."
  []
  (let [p1 (now)]
    #(js/Math.floor (- (now) p1))))

(defn measure-time-to-render [event]
  (if (and (exists? js/globalThis)
           (exists? (.-requestAnimationFrame js/globalThis))
           (exists? (.-scheduler js/globalThis))
           (exists? (.-postTask (.-scheduler js/globalThis))))
    (let [start (timestamp)]
      (js/requestAnimationFrame
       #(js/scheduler.postTask
         (fn []
           (let [end (timestamp)]
             (println (str "[" event "]" (- end start)))))
         #js {"priority" "user-blocking"})))))

;; --- DEVTOOLS PERF LOGGING

(defonce ^:private longtask-observer* (atom nil))
(defonce ^:private stall-timer* (atom nil))
(defonce ^:private current-op* (atom nil))

(defn- install-long-task-observer
  []
  (when (and (some? (.-PerformanceObserver js/window)) (nil? @longtask-observer*))
    (let [observer (js/PerformanceObserver.
                    (fn [list _]
                      (doseq [entry (.getEntries list)]
                        (let [dur (.-duration entry)
                              start (.-startTime entry)
                              attrib (.-attribution entry)
                              attrib-count (when attrib (.-length attrib))
                              first-attrib (when (and attrib-count (> attrib-count 0)) (aget attrib 0))
                              attrib-name (when first-attrib (.-name first-attrib))
                              attrib-ctype (when first-attrib (.-containerType first-attrib))
                              attrib-cid (when first-attrib (.-containerId first-attrib))
                              attrib-csrc (when first-attrib (.-containerSrc first-attrib))]

                          (.warn js/console (str "[perf] long task " (Math/round dur) "ms at " (Math/round start) "ms"
                                                 (when first-attrib
                                                   (str " attrib:name=" attrib-name
                                                        " ctype=" attrib-ctype
                                                        " cid=" attrib-cid
                                                        " csrc=" attrib-csrc))))))))]
      (.observe observer #js{:entryTypes #js["longtask"]})
      (reset! longtask-observer* observer))))

(defn- start-event-loop-stall-logger
  "Log event loop stalls by measuring setInterval drift.

  Params:
    - interval-ms: base interval
    - threshold-ms: drift over which we report
  "
  [interval-ms threshold-ms]
  (when (nil? @stall-timer*)
    (let [last (atom (.now js/performance))
          id (js/setInterval
              (fn []
                (let [now (.now js/performance)
                      expected (+ @last interval-ms)
                      drift (- now expected)
                      current-op @current-op*
                      measures (.getEntriesByType js/performance "measure")
                      mlen (.-length measures)
                      last-measure (when (> mlen 0) (aget measures (dec mlen)))
                      meas-name (when last-measure (.-name last-measure))
                      meas-detail (when last-measure (.-detail last-measure))
                      meas-count (when meas-detail (unchecked-get meas-detail "count"))]
                  (reset! last now)
                  (when (> drift threshold-ms)
                    (.warn js/console
                           (str "[perf] event loop stall: " (Math/round drift) "ms"
                                (when current-op (str " op=" current-op))
                                (when meas-name (str " last=" meas-name))
                                (when meas-count (str " count=" meas-count)))))))
              interval-ms)]
      (reset! stall-timer* id))))

(defn setup
  "Install perf observers in dev builds. Safe to call multiple times.
  Perf logs are disabled by default. Enable them with the :perf-logs
  flag in config."
  []
  (install-long-task-observer)
  (start-event-loop-stall-logger 50 100)
  ;; Expose simple API on window for manual control in devtools
  (let [api #js {:reset (fn []
                          (try
                            (.clearMarks js/performance)
                            (.clearMeasures js/performance)
                            (catch :default _ nil)))}]
    (unchecked-set js/window "PenpotPerf" api)))
