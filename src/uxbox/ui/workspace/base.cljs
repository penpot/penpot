(ns uxbox.ui.workspace.base
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.util.geom.point :as gpt]
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

(defonce scroll-b (rx/bus))

(defonce scroll-s
  (as-> scroll-b $
    (rx/merge $ (rx/of (gpt/point)))
    (rx/pr-log "scroll: " $)
    (rx/dedupe $)))

(defonce scroll-a
  (rx/to-atom scroll-s))

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

(defonce mouse-a
  (rx/to-atom mouse-s))

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
  (gpt/subtract new old))

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

(def ^:const viewport-width 4069)
(def ^:const viewport-height 2048)

(def ^:const document-start-x 600)
(def ^:const document-start-y 30)

(def ^:const document-start-scroll-x 550)
