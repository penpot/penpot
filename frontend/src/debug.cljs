;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns debug
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [promesa.core :as p]))

(defn ^:export set-logging
  ([level]
   (l/set-level! :app (keyword level)))
  ([ns level]
   (l/set-level! (keyword ns) (keyword level))))

(def debug-options
  #{;; Displays the bounding box for the shapes
    :bounding-boxes

    ;; Displays an overlay over the groups
    :group

    ;; Displays in the console log the events through the application
    :events

    ;; Display the boxes that represent the rotation and resize handlers
    :handlers

    ;; Displays the center of a selection
    :selection-center

    ;; When active the single selection will not take into account previous transformations
    ;; this is useful to debug transforms
    :simple-selection

    ;; When active the thumbnails will be displayed with a sepia filter
    :thumbnails

    ;; When active we can check in the browser the export values
    :show-export-metadata

    ;; Show text fragments outlines
    :text-outline

    ;; Disable thumbnail cache
    :disable-thumbnail-cache

    ;; Disable frame thumbnails
    :disable-frame-thumbnails
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

                        (obj/set! node "innerText" val)
                        (vreset! last cur)
                        (vreset! avg val)
                        (do-thing)))))]

    (.appendChild body node)
    (do-thing)))

(defn ^:export dump-state []
  (logjs "state" @st/state)
  nil)

(defn ^:export dump-data []
  (logjs "workspace-data" (get @st/state :workspace-data))
  nil)

(defn ^:export dump-buffer []
  (logjs "last-events" @st/last-events)
  nil)

(defn ^:export get-state [str-path]
  (let [path (->> (str/split str-path " ")
                  (map d/read-string)
                  vec)]
    (js/console.log (clj->js (get-in @st/state path))))
  nil)

(defn dump-objects'
  [state]
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])]
    (logjs "objects" objects)
    nil))

(defn ^:export dump-objects
  []
  (dump-objects' @st/state))

(defn dump-object'
  [state name]
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        result  (or (d/seek (fn [[_ shape]] (= name (:name shape))) objects)
                    (get objects (uuid/uuid name)))]
    (logjs name result)
    nil))

(defn ^:export dump-object
  [name]
  (dump-object' @st/state name))

(defn dump-selected'
  [state]
  (let [page-id (get state :current-page-id)
        objects (get-in state [:workspace-data :pages-index page-id :objects])
        selected (get-in state [:workspace-local :selected])
        result (->> selected (map (d/getf objects)))]
    (logjs "selected" result)
    nil))

(defn ^:export dump-selected
  []
  (dump-selected' @st/state))

(defn dump-tree'
  ([state] (dump-tree' state false false))
  ([state show-ids] (dump-tree' state show-ids false))
  ([state show-ids show-touched]
   (let [page-id    (get state :current-page-id)
         file-data  (get state :workspace-data)
         libraries  (get state :workspace-libraries)]
     (ctf/dump-tree file-data page-id libraries show-ids show-touched))))

(defn ^:export dump-tree
  ([] (dump-tree' @st/state))
  ([show-ids] (dump-tree' @st/state show-ids))
  ([show-ids show-touched] (dump-tree' @st/state show-ids show-touched)))

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

(defn ^:export reset-viewport
  []
  (st/emit!
   dw/reset-zoom
   (dw/update-viewport-position {:x (constantly 0) :y (constantly 0)})))

(defn ^:export hide-ui
  []
  (st/emit!
   (dw/toggle-layout-flag :hide-ui)))


(defn ^:export shortcuts
  []

  (letfn [(print-shortcuts [shortcuts]
            (.table js/console
                    (->> shortcuts
                         (map (fn [[key {:keys [command]}]]
                                [(d/name key)
                                 (if (vector? command)
                                   (str/join " | " command)
                                   command)]))
                         (into {})
                         (clj->js))))]
    (let [style "font-weight: bold; font-size: 1.25rem;"]
      (.log js/console "%c Dashboard" style)
      (print-shortcuts app.main.data.dashboard.shortcuts/shortcuts)

      (.log js/console "%c Workspace" style)
      (print-shortcuts app.main.data.workspace.shortcuts/shortcuts)

      (.log js/console "%c Path" style)
      (print-shortcuts app.main.data.workspace.path.shortcuts/shortcuts)

      (.log js/console "%c Viewer" style)
      (print-shortcuts app.main.data.viewer.shortcuts/shortcuts)))
  nil)

(defn ^:export nodeStats
  []
  (let [root-node (dom/query ".viewport .render-shapes")
        num-nodes (->> (dom/seq-nodes root-node) count)]
    #js {:number num-nodes}))

#_(defn modif->js
  [modif-tree objects]
  (clj->js (into {}
                 (map (fn [[k v]]
                        [(get-in objects [k :name]) v]))
                 modif-tree)))
