(ns app.common.perf
  (:require
   #?(:cljs [app.common.math :as mth])
   [app.common.uuid :as uuid]))

(defn timestamp []
  #?(:cljs (js/performance.now)
     :clj (. System (nanoTime))))

(defonce measures (atom {}))

(defn start
  ([]
   (start (uuid/next)))

  ([key]
   (swap! measures assoc key (timestamp))
   key))

(defn measure
  [key]
  (- (timestamp) (get @measures key)))

(defn benchmark
  "A helper function for perform a unitari benchmark on JS/CLJS. It
     uses browser native api so it only suitable to be executed in
     browser."
  [& _options]
  #?(:cljs
     (let [{:keys [f iterations name]
            :or {iterations 10000}} _options
           end-mark (str name ":end")]
       (println "=> benchmarking:" name)
       (println "--> warming up:" iterations)
       (loop [i iterations]
         (when (pos? i)
           (f)
           (recur (dec i))))
       (println "--> benchmarking:" iterations)
       (js/performance.mark name)
       (loop [i iterations]
         (when (pos? i)
           (f)
           (recur (dec i))))
       (js/performance.measure end-mark name)
       (let [[result] (js/performance.getEntriesByName end-mark)
             duration (mth/precision (.-duration ^js result) 6)
             avg      (mth/precision (/ duration iterations) 6)]
         (println "--> TOTAL:" (str duration "ms") "AVG:" (str avg "ms"))
         (js/performance.clearMarks name)
         (js/performance.clearMeasures end-mark)
         #js {:duration duration
              :avg avg}))))


