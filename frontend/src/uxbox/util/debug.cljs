(ns uxbox.util.debug
  "Debugging utils")

(def debug-options #{:bounding-boxes :group :events :rotation-handler :resize-handler :selection-center #_:simple-selection })

(defonce ^:dynamic *debug* (atom #{}))

(defn debug-all! [] (reset! *debug* debug-options))
(defn debug-none! [] (reset! *debug* #{}))
(defn debug! [option] (swap! *debug* conj option))
(defn -debug! [option] (swap! *debug* disj option))

(defn ^:export debug? [option] (@*debug* option))

(defn ^:export toggle-debug [name] (let [option (keyword name)]
                                     (if (debug? option)
                                       (-debug! option)
                                       (debug! option))))
(defn ^:export debug-all [] (debug-all!))
(defn ^:export debug-none [] (debug-none!))

(defn ^:export tap
  "Transducer function that can execute a side-effect `effect-fn` per input"
  [effect-fn]
  
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (effect-fn input)
       (rf result input)))))

(defn ^:export logjs
  ([str] (tap (partial logjs str)))
  ([str val]
   (js/console.log str (clj->js val))
   val))


