(ns uxbox.ui.workspace.base
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.state.project :as stpr]
            [uxbox.data.workspace :as dw]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.lens :as ul]
            [goog.events :as events])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static project-l
  (letfn [(getter [state]
            (let [project (get-in state [:workspace :project])]
              (get-in state [:projects-by-id project])))]
    (as-> (ul/getter getter) $
      (l/focus-atom $ st/state))))

(def ^:static page-l
  (letfn [(getter [state]
            (let [page (get-in state [:workspace :page])]
              (get-in state [:pages-by-id page])))]
    (as-> (ul/getter getter) $
      (l/focus-atom $ st/state))))

(def ^:static pages-l
  (letfn [(getter [state]
            (let [project (get-in state [:workspace :project])]
              (stpr/project-pages state project)))]
    (as-> (ul/getter getter) $
      (l/focus-atom $ st/state))))

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

(defonce scroll-b (rx/bus))

(defonce scroll-s
  (as-> scroll-b $
    (rx/sample 10 $)
    (rx/merge $ (rx/of (gpt/point)))
    (rx/dedupe $)))

(defonce scroll-a
  (rx/to-atom scroll-s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mouse Position Stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce mouse-b (rx/bus))
(defonce mouse-s
  (rx/dedupe mouse-b))

(defonce mouse-canvas-s
  (->> mouse-s
       (rx/map :canvas-coords)
       (rx/share)))

(defonce mouse-canvas-a
  (rx/to-atom mouse-canvas-s))

(defonce mouse-viewport-s
  (->> mouse-s
       (rx/map :viewport-coords)
       (rx/share)))

(defonce mouse-viewport-a
  (rx/to-atom mouse-viewport-s))

(defonce mouse-absolute-s
  (->> mouse-s
       (rx/map :window-coords)
       (rx/share)))

(defonce mouse-ctrl-s
  (->> mouse-s
       (rx/map :ctrl)
       (rx/share)))

(defn- coords-delta
  [[old new]]
  (gpt/subtract new old))

(defonce mouse-delta-s
  (->> mouse-viewport-s
       (rx/sample 10)
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const viewport-width 4000)
(def ^:const viewport-height 4000)

(def ^:const canvas-start-x 1200)
(def ^:const canvas-start-y 1200)
(def ^:const canvas-scroll-padding 50)
(def ^:const canvas-start-scroll-x (- canvas-start-x canvas-scroll-padding))
(def ^:const canvas-start-scroll-y (- canvas-start-y canvas-scroll-padding))
