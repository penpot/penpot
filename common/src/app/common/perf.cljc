(ns app.common.perf
  (:require
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
