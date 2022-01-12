;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns debug
  (:require-macros
   [app.common.namedtuple :as nt])
  (:require
   [app.common.data :as d]
   [app.common.perf :as perf]
   [app.common.math :as mth]
   [app.common.geom.matrix :as gmt]
   [app.common.pages :as cp]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dwc]
   [app.main.store :as st]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [promesa.core :as p]))

(def debug-options
  #{;; Displays the bounding box for the shapes
    :bounding-boxes

    ;; Displays an overlay over the groups
    :group

    ;; Displays in the console log the events through the application
    :events

    ;; Display the boxes that represent the rotation handlers
    :rotation-handler

    ;; Display the boxes that represent the resize handlers
    :resize-handler

    ;; Displays the center of a selection
    :selection-center

    ;; When active the single selection will not take into account previous transformations
    ;; this is useful to debug transforms
    :simple-selection

    ;; When active the thumbnails will be displayed with a sepia filter
    :thumbnails
    })

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
  (logjs "state" @st/state)
  nil)

(defn ^:export dump-buffer []
  (logjs "state" @st/last-events)
  nil)

(defn ^:export get-state [str-path]
  (let [path (->> (str/split str-path " ")
                  (map d/read-string))]
    (clj->js (get-in @st/state path)))
  nil)

(defn ^:export dump-objects []
  (let [page-id (get @st/state :current-page-id)
        objects (get-in @st/state [:workspace-data :pages-index page-id :objects])]
    (logjs "objects" objects)
    nil))

(defn ^:export dump-object [name]
  (let [page-id (get @st/state :current-page-id)
        objects (get-in @st/state [:workspace-data :pages-index page-id :objects])
        result  (or (d/seek (fn [[_ shape]] (= name (:name shape))) objects)
                    (get objects (uuid/uuid name)))]
    (logjs name result)
    nil))

(defn ^:export dump-selected []
  (let [page-id (get @st/state :current-page-id)
        objects (get-in @st/state [:workspace-data :pages-index page-id :objects])
        selected (get-in @st/state [:workspace-local :selected])
        result (->> selected (map (d/getf objects)))]
    (logjs "selected" result)
    nil))

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

(defn ^:export apply-changes
  "Takes a Transit JSON changes"
  [^string changes*]

  (let [file-id (:current-file-id @st/state)
        changes (t/decode-str changes*)]
    (st/emit! (dwc/commit-changes {:redo-changes changes
                                   :undo-changes []
                                   :save-undo? true
                                   :file-id file-id}))))

(defn ^:export fetch-apply
  [^string url]
  (-> (p/let [response (js/fetch url)]
        (.text response))
      (p/then apply-changes)))

(nt/deftuple Matrix [:a :b :c :d :e :f])


;; (defn multiply!
;;   [^Matrix m1 ^Matrix m2]
;;   (let [bf1 (.-buff ^js m1)
;;         bf2 (.-buff ^js m2)
;;         m1a (aget bf1 0)
;;         m1b (aget bf1 1)
;;         m1c (aget bf1 2)
;;         m1d (aget bf1 3)
;;         m1e (aget bf1 4)
;;         m1f (aget bf1 5)

;;         m2a (aget bf2 0)
;;         m2b (aget bf2 1)
;;         m2c (aget bf2 2)
;;         m2d (aget bf2 3)
;;         m2e (aget bf2 4)
;;         m2f (aget bf2 5)]

;;     (aset bf1 0 (+ (* m1a m2a) (* m1c m2b)))
;;     (aset bf1 1 (+ (* m1b m2a) (* m1d m2b)))
;;     (aset bf1 2 (+ (* m1a m2c) (* m1c m2d)))
;;     (aset bf1 3 (+ (* m1b m2c) (* m1d m2d)))
;;     (aset bf1 4 (+ (* m1a m2e) (* m1c m2f) m1e))
;;     (aset bf1 5 (+ (* m1b m2e) (* m1d m2f) m1f))
;;     m1))

(defn multiply!
  [bf1 bf2]
  (let [m1a (aget bf1 0)
        m1b (aget bf1 1)
        m1c (aget bf1 2)
        m1d (aget bf1 3)
        m1e (aget bf1 4)
        m1f (aget bf1 5)

        m2a (aget bf2 0)
        m2b (aget bf2 1)
        m2c (aget bf2 2)
        m2d (aget bf2 3)
        m2e (aget bf2 4)
        m2f (aget bf2 5)]

    (aset bf1 0 (+ (* m1a m2a) (* m1c m2b)))
    (aset bf1 1 (+ (* m1b m2a) (* m1d m2b)))
    (aset bf1 2 (+ (* m1a m2c) (* m1c m2d)))
    (aset bf1 3 (+ (* m1b m2c) (* m1d m2d)))
    (aset bf1 4 (+ (* m1a m2e) (* m1c m2f) m1e))
    (aset bf1 5 (+ (* m1b m2e) (* m1d m2f) m1f))))

(defn multiply
  ([m1 m2]
   (let [buff1 (.slice (.-buff ^js m1) 0)]
     (multiply! buff1 (.-buff ^js m2))
     (Matrix. buff1 nil)))

  ([m1 m2 m3]
   (let [buff1 (.slice (.-buff ^js m1) 0)]
     (multiply! buff1 (.-buff ^js m2))
     (multiply! buff1 (.-buff ^js m3))
     (Matrix. buff1 nil)))

  ([m1 m2 m3 m4]
   (let [buff1 (.slice (.-buff ^js m1) 0)]
     (multiply! buff1 (.-buff ^js m2))
     (multiply! buff1 (.-buff ^js m3))
     (multiply! buff1 (.-buff ^js m4))
     (Matrix. buff1 nil)))

  ([m1 m2 m3 m4 m5]
   (let [buff1 (.slice (.-buff ^js m1) 0)]
     (multiply! buff1 (.-buff ^js m2))
     (multiply! buff1 (.-buff ^js m3))
     (multiply! buff1 (.-buff ^js m4))
     (multiply! buff1 (.-buff ^js m5))
     (Matrix. buff1 nil))))

(defn ^:export bench-matrix-multiply
  []
  (let [ma1 (make-matrix 1 2 3 4 5 6)
        ma2 (make-matrix 6 5 4 3 2 1)]

    (perf/benchmark
     :f (fn []
          (dotimes [i 100]
            (when-not (multiply ma1 ma2)
              (throw (ex-info "foobar" {}))))
          :result)
     :name "tuple matrix"))

  (let [ma1 (gmt/matrix 1 2 3 4 5 6)
        ma2 (gmt/matrix 6 5 4 3 2 1)]
    (perf/benchmark
     :f (fn []
          (dotimes [i 100]
            (when-not (gmt/multiply ma1 ma2)
              (throw (ex-info "foobar" {}))))
          :result)
     :name "orig matrix")))

(defn ^:export bench-matrix-multiply-bulk-5
  []
  (let [ma1 (make-matrix 1 2 3 4 5 6)
        ma2 (make-matrix 6 5 4 3 2 1)
        ma3 (make-matrix 9 8 7 6 5 4)
        ma4 (make-matrix 7 6 5 4 3 2)
        ma5 (make-matrix 1 9 2 8 4 7)]

    (prn "result1" (multiply ma1 ma2 ma3 ma4 ma5))
    (perf/benchmark
     :f (fn []
          (dotimes [i 100]
            (when-not (multiply ma1 ma2 ma3 ma4 ma5)
              (throw (ex-info "foobar" {}))))
          :result)
     :name "tuple matrix"))

  (let [ma1 (gmt/matrix 1 2 3 4 5 6)
        ma2 (gmt/matrix 6 5 4 3 2 1)
        ma3 (gmt/matrix 9 8 7 6 5 4)
        ma4 (gmt/matrix 7 6 5 4 3 2)
        ma5 (gmt/matrix 1 9 2 8 4 7)]

    (prn "result2" (gmt/multiply ma1 ma2 ma3 ma4 ma5))
    (perf/benchmark
     :f (fn []
          (dotimes [i 100]
            (when-not (gmt/multiply ma1 ma2 ma3 ma4 ma5)
              (throw (ex-info "foobar" {}))))
          :result)
     :name "orig matrix")))
