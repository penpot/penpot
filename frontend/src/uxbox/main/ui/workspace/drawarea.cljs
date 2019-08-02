;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.data.workspace-drawing :as udwd]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as streams]
   [uxbox.main.ui.shapes :as shapes]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.path :as path]
   [uxbox.util.geom.point :as gpt]))

;; --- Components

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [zoom shape modifiers] :as props}]
  (if (= (:type shape) :path)
    [:& path-draw-area {:shape shape}]
    [:& generic-draw-area {:shape (assoc shape :modifiers modifiers)
                           :zoom zoom}]))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x1 y1 width height]} (geom/selection-rect shape)]
    [:g
     (shapes/render-shape shape)
     [:rect.main {:x x1 :y y1
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#333"
                          :fill "transparent"
                          :stroke-opacity "1"}}]]))

(mf/defc path-draw-area
  [{:keys [shape] :as props}]
  (letfn [(on-click [event]
            (dom/stop-propagation event)
            (st/emit! (udw/set-tooltip nil)
                      (udwd/close-drawing-path)))
          (on-mouse-enter [event]
            (st/emit! (udw/set-tooltip "Click to close the path")))
          (on-mouse-leave [event]
            (st/emit! (udw/set-tooltip nil)))]
    (when-let [{:keys [x y] :as segment} (first (:segments shape))]
      [:g
       (shapes/render-shape shape)
       (when-not (:free shape)
         [:circle.close-bezier
          {:cx x
           :cy y
           :r 5
           :on-click on-click
           :on-mouse-enter on-mouse-enter
           :on-mouse-leave on-mouse-leave}])])))
