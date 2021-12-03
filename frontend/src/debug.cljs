;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns debug
  (:import [goog.math AffineTransform])
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.perf :as perf]
   [app.main.store :as st]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(def debug-options #{:bounding-boxes :group :events :rotation-handler :resize-handler :selection-center :export :import #_:simple-selection})

;; These events are excluded when we activate the :events flag
(def debug-exclude-events
  #{:app.main.data.workspace.notifications/handle-pointer-update
    :app.main.data.workspace.selection/change-hover-state})

(defonce ^:dynamic *debug* (atom #{#_:events}))

(defn debug-all! [] (reset! *debug* debug-options))
(defn debug-none! [] (reset! *debug* #{}))
(defn debug! [option] (swap! *debug* conj option))
(defn -debug! [option] (swap! *debug* disj option))

(defn ^:export ^boolean debug?
  [option]
  (if *assert*
    (boolean (@*debug* option))
    false))

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

(when (exists? js/window)
  (set! (.-dbg ^js js/window) clj->js)
  (set! (.-pp ^js js/window) pprint))


(defonce widget-style "
  background: black;
  bottom: 10px;
  color: white;
  height: 20px;
  padding-left: 8px;
  position: absolute;
  right: 10px;
  width: 40px;
  z-index: 99999;
  opacity: 0.5;
")

(defn ^:export fps
  "Adds a widget to keep track of the average FPS's"
  []
  (let [last (volatile! (.now js/performance))
        avg  (volatile! 0)
        node (-> (.createElement js/document "div")
                 (obj/set! "id" "fps")
                 (obj/set! "style" widget-style))
        body (obj/get js/document "body")

        do-thing (fn do-thing []
                   (timers/raf
                    (fn []
                      (let [cur (.now js/performance)
                            ts (/ 1000 (* (- cur @last)))
                            val (+ @avg (* (- ts @avg) 0.1))]

                        (obj/set! node "innerText" (mth/precision val 0))
                        (vreset! last cur)
                        (vreset! avg val)
                        (do-thing)))))]

    (.appendChild body node)
    (do-thing)))

(defn ^:export dump-state []
  (logjs "state" @st/state))

(defn ^:export dump-buffer []
  (logjs "state" @st/last-events))

(defn ^:export get-state [str-path]
  (let [path (->> (str/split str-path " ")
                  (map d/read-string))]
    (clj->js (get-in @st/state path))))

(defn ^:export dump-objects []
  (let [page-id (get @st/state :current-page-id)]
    (logjs "state" (get-in @st/state [:workspace-data :pages-index page-id :objects]))))

(defn ^:export dump-object [name]
  (let [page-id (get @st/state :current-page-id)
        objects (get-in @st/state [:workspace-data :pages-index page-id :objects])
        target  (or (d/seek (fn [[_ shape]] (= name (:name shape))) objects)
                    (get objects (uuid name)))]
    (->> target
         (logjs "state"))))

(defn ^:export dump-tree
  ([] (dump-tree false false))
  ([show-ids] (dump-tree show-ids false))
  ([show-ids show-touched]
   (let [page-id    (get @st/state :current-page-id)
         objects    (get-in @st/state [:workspace-data :pages-index page-id :objects])
         components (get-in @st/state [:workspace-data :components])
         libraries  (get @st/state :workspace-libraries)
         root (d/seek #(nil? (:parent-id %)) (vals objects))]

     (letfn [(show-shape [shape-id level objects]
               (let [shape (get objects shape-id)]
                 (println (str/pad (str (str/repeat "  " level)
                                        (:name shape)
                                        (when (seq (:touched shape)) "*")
                                        (when show-ids (str/format " <%s>" (:id shape))))
                                   {:length 20
                                    :type :right})
                          (show-component shape objects))
                 (when show-touched
                   (when (seq (:touched shape))
                     (println (str (str/repeat "  " level)
                                 "    "
                                 (str (:touched shape)))))
                   (when (:remote-synced? shape)
                     (println (str (str/repeat "  " level)
                                 "    (remote-synced)"))))
                 (when (:shapes shape)
                   (dorun (for [shape-id (:shapes shape)]
                            (show-shape shape-id (inc level) objects))))))

             (show-component [shape objects]
               (if (nil? (:shape-ref shape))
                 ""
                 (let [root-shape        (cp/get-component-shape shape objects)
                       component-id      (when root-shape (:component-id root-shape))
                       component-file-id (when root-shape (:component-file root-shape))
                       component-file    (when component-file-id (get libraries component-file-id nil))
                       component         (when component-id
                                           (if component-file
                                             (get-in component-file [:data :components component-id])
                                             (get components component-id)))
                       component-shape   (when (and component (:shape-ref shape))
                                           (get-in component [:objects (:shape-ref shape)]))]
                   (str/format " %s--> %s%s%s"
                               (cond (:component-root? shape) "#"
                                     (:component-id shape) "@"
                                     :else "-")
                               (when component-file (str/format "<%s> " (:name component-file)))
                               (or (:name component-shape) "?")
                               (if (or (:component-root? shape)
                                       (nil? (:component-id shape))
                                       true)
                                 ""
                                 (let [component-id      (:component-id shape)
                                       component-file-id (:component-file shape)
                                       component-file    (when component-file-id (get libraries component-file-id nil))
                                       component         (if component-file
                                                           (get-in component-file [:data :components component-id])
                                                           (get components component-id))]
                                   (str/format " (%s%s)"
                                               (when component-file (str/format "<%s> " (:name component-file)))
                                               (:name component))))))))]

       (println "[Page]")
       (show-shape (:id root) 0 objects)

       (dorun (for [component (vals components)]
                (do
                  (println)
                  (println (str/format "[%s]" (:name component)))
                  (show-shape (:id component) 0 (:objects component)))))))))

(when *assert*
  (defonce debug-subscription
    (->> st/stream
         (rx/filter ptk/event?)
         (rx/filter (fn [s] (and (debug? :events)
                                 (not (debug-exclude-events (ptk/type s))))))
         (rx/subs #(println "[stream]: " (ptk/repr-event %))))))

(defn ^:export bench-matrix
  []
  (let [iterations 1000000

        good (gmt/multiply (gmt/matrix 1 2 3 4 5 6)
                           (gmt/matrix 1 2 3 4 5 6))

        k1 (perf/start)
        _ (dotimes [_ iterations]
            (when-not (= good (gmt/-old-multiply (gmt/matrix 1 2 3 4 5 6)
                                                 (gmt/matrix 1 2 3 4 5 6)))
              (throw "ERROR")))
        m1 (perf/measure k1)

        k2 (perf/start)
        _ (dotimes [_ iterations]
            (when-not (= good (gmt/multiply (gmt/matrix 1 2 3 4 5 6)
                                            (gmt/matrix 1 2 3 4 5 6)))
              (throw "ERROR")))
        m2 (perf/measure k2)

        k3 (perf/start)
        _ (dotimes [_ iterations]
            (let [res (.concatenate (AffineTransform. 1 2 3 4 5 6)
                                    (AffineTransform. 1 2 3 4 5 6))
                  res (gmt/matrix (.-m00_ res) (.-m10_ res) (.-m01_ res) (.-m11_ res) (.-m02_ res) (.-m12_ res))]
              
              (when-not (= good res)
                (throw "ERROR"))))
        m3 (perf/measure k3)
        ]

    (println "Clojure matrix. Total: " m1 " (" (/ m1 iterations) ")")
    (println "Clojure matrix (NEW). Total: " m2 " (" (/ m2 iterations) ")")
    (println "Affine transform (with new). Total: " m3 " (" (/ m3 iterations) ")")))
