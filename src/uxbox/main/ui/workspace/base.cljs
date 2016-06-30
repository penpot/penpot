;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.base
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.state.project :as stpr]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom.point :as gpt]
            [goog.events :as events])
  (:import goog.events.EventType))

;; --- Lenses

(def ^:const workspace-l
  (as-> (l/in [:workspace]) $
    (l/derive $ st/state)))

(def ^:const project-l
  (letfn [(getter [state]
            (let [project (get-in state [:workspace :project])]
              (get-in state [:projects-by-id project])))]
    (as-> (l/lens getter) $
      (l/derive $ st/state))))

(def ^:const page-l
  (letfn [(getter [state]
            (let [page (get-in state [:workspace :page])]
              (get-in state [:pages-by-id page])))]
    (as-> (l/lens getter) $
      (l/derive $ st/state))))

(def ^:const selected-shapes-l
  (as-> (l/in [:selected]) $
    (l/derive $ workspace-l)))

(def ^:const toolboxes-l
  (as-> (l/in [:toolboxes]) $
    (l/derive $ workspace-l)))

(def ^:const flags-l
  (as-> (l/in [:flags]) $
    (l/derive $ workspace-l)))

(def ^:const shapes-by-id-l
  (as-> (l/key :shapes-by-id) $
    (l/derive $ st/state)))

(def ^:const zoom-l
  (-> (l/in [:workspace :zoom])
      (l/derive st/state)))

(def ^:const alignment-l
  (letfn [(getter [flags]
            (and (contains? flags :grid/indexed)
                 (contains? flags :grid/alignment)
                 (contains? flags :grid)))]
    (-> (l/lens getter)
        (l/derive flags-l))))

;; --- Scroll Stream

(defonce scroll-b (rx/bus))

(defonce scroll-s
  (as-> scroll-b $
    (rx/sample 10 $)
    (rx/merge $ (rx/of (gpt/point)))
    (rx/dedupe $)))

(defonce scroll-a
  (rx/to-atom scroll-s))

;; --- Mouse Position Stream

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
       (rx/map #(gpt/divide % @zoom-l))
       (rx/mapcat (fn [point]
                    (if @alignment-l
                      (uds/align-point point)
                      (rx/of point))))
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))
