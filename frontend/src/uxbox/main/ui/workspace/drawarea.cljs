;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [lentes.core :as l]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.workers :as uwrk]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes :as shapes]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.path :as path]
            [uxbox.util.dom :as dom]))

;; --- Refs

(def drawing-shape
  (-> (l/key :drawing)
      (l/derive refs/workspace)))

;; --- Components

(declare generic-draw-area)
(declare path-draw-area)

(mx/defc draw-area
  {:mixins [mx/static mx/reactive]}
  []
  (when-let [shape (mx/react drawing-shape)]
    (if (= (:type shape) :path)
      (path-draw-area shape)
      (generic-draw-area shape))))

(mx/defc generic-draw-area
  [shape]
  (-> (assoc shape :drawing? true)
      (shapes/render-component)))

(mx/defc path-draw-area
  [{:keys [points] :as shape}]
  (letfn [(on-click [event]
            (dom/stop-propagation event)
            (st/emit! (udw/set-tooltip nil)
                      (udw/close-drawing-path)))
          (on-mouse-enter [event]
            (st/emit! (udw/set-tooltip "Click to close the path")))
          (on-mouse-leave [event]
            (st/emit! (udw/set-tooltip nil)))
          (drop-last-point [shape]
            (let [points (:points shape)
                  points (vec (butlast points))]
              (assoc shape :points points :close? true)))]
    (when-let [{:keys [x y]} (first points)]
      [:g
       (-> (assoc shape :drawing? true)
           (shapes/render-component))
       (when-not (:free shape)
         [:circle.close-bezier {:cx x
                                :cy y
                                :r 5
                                :on-click on-click
                                :on-mouse-enter on-mouse-enter
                                :on-mouse-leave on-mouse-leave}])])))
