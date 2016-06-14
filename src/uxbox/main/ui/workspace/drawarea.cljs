;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.drawarea
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.main.constants :as c]
            [uxbox.common.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.core :as uuc]
            [uxbox.main.ui.shapes :as shapes]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.mixins :as mx]
            [uxbox.common.geom :as geom]
            [uxbox.common.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

;; --- State

(defonce drawing-shape (atom nil))
(defonce drawing-position (atom nil))

;; --- Draw Area (Component)

(declare watch-draw-actions)

(defn- draw-area-render
  [own]
  (let [shape (rum/react drawing-shape)
        position (rum/react drawing-position)]
    (when (and shape position)
      (-> (assoc shape :drawing? true)
          (geom/resize position)
          (shapes/render-component)))))

(defn- draw-area-will-mount
  [own]
  (assoc own ::sub (watch-draw-actions)))

(defn- draw-area-transfer-state
  [oldown own]
  (assoc own ::sub (::sub oldown)))

(defn- draw-area-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(def draw-area
  (mx/component
   {:render draw-area-render
    :name "draw-area"
    :will-mount draw-area-will-mount
    :will-unmount draw-area-will-unmount
    :transfer-state draw-area-transfer-state
    :mixins [mx/static rum/reactive]}))

;; --- Drawing Logic

(declare initialize)

(defn- watch-draw-actions
  []
  (let [stream (->> uuc/actions-s
                    (rx/map :type)
                    (rx/dedupe)
                    (rx/filter #(= "ui.shape.draw" %))
                    (rx/map #(:drawing @wb/workspace-l))
                    (rx/filter identity))]
    (rx/subscribe stream initialize)))

(declare initialize-icon-drawing)
(declare initialize-shape-drawing)

(defn- initialize
  [shape]
  (if (= (:type shape) :icon)
    (initialize-icon-drawing shape)
    (initialize-shape-drawing shape)))

(defn- initialize-icon-drawing
  "A drawing handler for icons."
  [shape]
  (let [{:keys [x y]} (gpt/divide @wb/mouse-canvas-a @wb/zoom-l)
        props {:x1 x :y1 y :x2 (+ x 100) :y2 (+ y 100)}
        shape (geom/setup shape props)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))))

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(declare on-draw)
(declare on-draw-complete)
(declare on-first-draw)

(defn- initialize-shape-drawing
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-l
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter #(empty? %))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> mouse
                    (rx/take-until stoper)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]

    (rx/subscribe firstpos #(on-first-draw shape %))
    (rx/subscribe stream on-draw nil on-draw-complete)))

(defn- on-first-draw
  [shape {:keys [x y] :as pt}]
  (let [shape (geom/setup shape {:x1 x :y1 y :x2 x :y2 y})]
    (reset! drawing-shape shape)))

(defn- on-draw
  [[pt ctrl?]]
  (let [pt (gpt/divide pt @wb/zoom-l)]
    (reset! drawing-position (assoc pt :lock ctrl?))))

(defn- on-draw-complete
  []
  (let [shape @drawing-shape
        shpos @drawing-position
                  shape (geom/resize shape shpos)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))
    (reset! drawing-position nil)
    (reset! drawing-shape nil)))
