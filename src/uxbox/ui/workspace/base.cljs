(ns uxbox.ui.workspace.base
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.util.lens :as ul]
            [goog.events :as events])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static project-l
  (as-> (ul/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ st/state)))

(def ^:static page-l
  (as-> (ul/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ st/state)))

(def ^:static pages-l
  (as-> (ul/getter #(let [pid (get-in % [:workspace :project])]
                      (dp/project-pages % pid))) $
    (l/focus-atom $ st/state)))

(def ^:static workspace-l
  (as-> (l/in [:workspace]) $
    (l/focus-atom $ st/state)))

(def ^:static selected-shapes-l
  (as-> (l/in [:workspace :selected]) $
    (l/focus-atom $ st/state)))

(def ^:static toolboxes-l
  (as-> (l/in [:workspace :toolboxes]) $
    (l/focus-atom $ st/state)))

(def ^:static flags-l
  (as-> (l/in [:workspace :flags]) $
    (l/focus-atom $ st/state)))

(def ^:static shapes-by-id-l
  (as-> (l/key :shapes-by-id) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scroll Stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private scroll-b (rx/bus))

(defonce scroll-s
  (as-> scroll-b $
    (rx/merge $ (rx/of {:top 0 :left 0}))
    (rx/dedupe $)))

(defonce scroll-top-s
  (->> scroll-s
       (rx/map :top)
       (rx/dedupe)))

(defonce scroll-left-s
  (->> scroll-s
       (rx/map :left)
       (rx/dedupe)))

(defonce scroll-top (rx/to-atom scroll-top-s))
(defonce scroll-left (rx/to-atom scroll-left-s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce interactions-b (rx/bus))

(defn emit-interaction!
  [type]
  (rx/push! interactions-b type))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mouse Position Stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce mouse-b (rx/bus))
(defonce mouse-s
  (->> mouse-b
       (rx/filter #(= (:id %) (:id @page-l)))
       (rx/map :canvas-coords)
       (rx/share)))

(defonce mouse-absolute-s
  (->> mouse-b
       (rx/filter #(= (:id %) (:id @page-l)))
       (rx/map :window-coords)
       (rx/share)))

(defonce mouse-ctrl-s
  (->> mouse-b
       (rx/filter #(= (:id %) (:id @page-l)))
       (rx/map :ctrl)
       (rx/dedupe)
       (rx/share)))

(defn- coords-delta
  [[old new]]
  (let [[oldx oldy] old
        [newx newy] new]
    [(- newx oldx)
     (- newy oldy)]))

(defonce mouse-delta-s
  (->> mouse-s
       (rx/sample 10)
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))

(defonce mouse-position
  (->> mouse-s
       (rx/sample 10)
       (rx/to-atom)))

(defonce bounding-rect (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def viewport-height  2048)
(def viewport-width 2048)

(def document-start-x 50)
(def document-start-y 50)

