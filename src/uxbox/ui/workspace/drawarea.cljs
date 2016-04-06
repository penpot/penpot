;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.drawarea
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.shapes :as ush]
            [uxbox.state :as st]
            [uxbox.data.workspace :as udw]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom.point :as gpt]
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
    (when shape
      (-> (assoc shape :drawing? true)
          (ush/resize position)
          (uusc/render-shape identity)))))

(defn- draw-area-will-mount
  [own]
  (assoc own ::sub (watch-draw-actions)))

(defn- draw-area-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(defn- draw-area-transfer-state
  [oldown own]
  (assoc own ::sub (::sub oldown)))

(def draw-area
  (mx/component
   {:render draw-area-render
    :name "draw-area"
    :will-mount draw-area-will-mount
    :will-unmount draw-area-will-unmount
    :mixins [mx/static rum/reactive]}))

;; --- Drawing Logic

(declare initialize-icon-drawing)
(declare initialize-shape-drawing)

(defn- watch-draw-actions
  []
  (letfn [(initialize [shape]
            (println "initialize" shape)
            (if (= (:type shape) :builtin/icon)
              (initialize-icon-drawing shape)
              (initialize-shape-drawing shape)))]
    (as-> uuc/actions-s $
      (rx/map :type $)
      (rx/dedupe $)
      (rx/filter #(= "ui.shape.draw" %) $)
      (rx/map #(:drawing @wb/workspace-l) $)
      (rx/filter identity $)
      (rx/on-value $ initialize))))

(defn- initialize-icon-drawing
  "A drawing handler for icons."
  [shape]
  (let [{:keys [x y]} (gpt/divide @wb/mouse-canvas-a @wb/zoom-l)
        props {:x1 x :y1 y :x2 (+ x 100) :y2 (+ y 100)}
        shape (ush/initialize shape props)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil))))

(defn- initialize-shape-drawing
  "A drawing handler for generic shapes such as rect, circle, text, etc."
  [shape]
  (letfn [(on-value [[pt ctrl?]]
            (let [pt (gpt/divide pt @wb/zoom-l)]
              (reset! drawing-position (assoc pt :lock ctrl?))))

          (on-complete []
            (let [shape @drawing-shape
                  shpos @drawing-position
                  shape (ush/resize shape shpos)]
              (rs/emit! (uds/add-shape shape)
                        (udw/select-for-drawing nil))
              (reset! drawing-position nil)
              (reset! drawing-shape nil)))]

  (let [{:keys [x y] :as pt} (gpt/divide @wb/mouse-canvas-a @wb/zoom-l)
        shape (ush/initialize shape {:x1 x :y1 y :x2 x :y2 y})
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter #(empty? %))
                    (rx/take 1))]

    (reset! drawing-shape shape)
    (reset! drawing-position (assoc pt :lock false))

    (as-> wb/mouse-canvas-s $
      (rx/take-until stoper $)
      (rx/with-latest-from vector wb/mouse-ctrl-s $)
      (rx/subscribe $ on-value nil on-complete)))))
