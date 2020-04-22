(ns uxbox.util.debug
  "Debugging utils")

(def debug-options #{:bounding-boxes :group :events #_:simple-selection})

(defonce ^:dynamic *debug* (atom #{:bounding-boxes}))

(defn debug-all! [] (reset! *debug* debug-options))
(defn debug-none! [] (reset! *debug* #{}))
(defn debug! [option] (swap! *debug* conj option))
(defn -debug! [option] (swap! *debug* disj option))
(defn debug? [option] (@*debug* option))

(defn tap
  "Transducer function that can execute a side-effect `effect-fn` per input"
  [effect-fn]
  
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (effect-fn input)
       (rf result input)))))

(defn logjs
  ([str] (tap (partial logjs str)))
  ([str val]
   (js/console.log str (clj->js val))
   val))

(defn dump-state []
  (logjs "state" @uxbox.main.store/state))

(defn dump-objects []
  (let [page-id (get @uxbox.main.store/state :page-id)]
    (logjs "state" (get-in @uxbox.main.store/state [:workspace-data page-id :objects]))))
