;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.ui.workspace.drawarea
  "Drawing components."
  (:require
   [rumext.alpha :as mf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.store :as st]
   [app.main.ui.workspace.shapes :as shapes]
   [app.common.geom.shapes :as gsh]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]))

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [shape zoom] :as props}]
  (when (:id shape)
    (case (:type shape)
      (:path :curve) [:& path-draw-area {:shape shape}]
      [:& generic-draw-area {:shape shape :zoom zoom}])))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height] :as kk} (:selrect (gsh/transform-shape shape))]
    (when (and x y
               (not (d/nan? x))
               (not (d/nan? y)))

      [:g
       [:& shapes/shape-wrapper {:shape shape}]
       [:rect.main {:x x :y y
                    :width width
                    :height height
                    :style {:stroke "#1FDEA7"
                            :fill "transparent"
                            :stroke-width (/ 1 zoom)}}]])))

(mf/defc path-draw-area
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)

        on-click
        (fn [event]
          (dom/stop-propagation event)
          (st/emit! (dw/assign-cursor-tooltip nil)
                    dd/close-drawing-path
                    :path/end-path-drawing))

        on-mouse-enter
        (fn [event]
          (let [msg (t locale "workspace.viewport.click-to-close-path")]
            (st/emit! (dw/assign-cursor-tooltip msg))))

        on-mouse-leave
        (fn [event]
          (st/emit! (dw/assign-cursor-tooltip nil)))]

    (when-let [{:keys [x y] :as segment} (first (:segments shape))]
      [:g
       [:& shapes/shape-wrapper {:shape shape}]
       (when (not= :curve (:type shape))
         [:circle.close-bezier
          {:cx x
           :cy y
           :r 5
           :on-click on-click
           :on-mouse-enter on-mouse-enter
           :on-mouse-leave on-mouse-leave}])])))
