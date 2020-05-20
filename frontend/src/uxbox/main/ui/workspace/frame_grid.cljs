;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.frame-grid
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.common.pages :as cp]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.grid :as gg]))

(mf/defc square-grid [{:keys [frame zoom grid] :as props}]
  (let [{:keys [color size] :as params} (-> grid :params)
        {color-value :value color-opacity :opacity} (-> grid :params :color)
        {frame-width :width frame-height :height :keys [x y]} frame]
    (when (> size 0)
      [:g.grid
       [:*
        (for [xs (range size frame-width size)]
          [:line {:key (str (:id frame) "-y-" xs)
                  :x1 (+ x xs)
                  :y1 y
                  :x2 (+ x xs)
                  :y2 (+ y frame-height)
                  :style {:stroke color-value
                          :stroke-opacity color-opacity
                          :stroke-width (str (/ 1 zoom))}}])
        (for [ys (range size frame-height size)]
          [:line {:key (str (:id frame) "-x-" ys)
                  :x1 x
                  :y1 (+ y ys)
                  :x2 (+ x frame-width)
                  :y2 (+ y ys)
                  :style {:stroke color-value
                          :stroke-opacity color-opacity
                          :stroke-width (str (/ 1 zoom))}}])]])))

(mf/defc layout-grid [{:keys [key frame zoom grid]}]
  (let [{color-value :value color-opacity :opacity} (-> grid :params :color)
        gutter (-> grid :params :gutter)
        gutter? (and (not (nil? gutter)) (not= gutter 0))

        style (if gutter?
                #js {:fill color-value
                     :opacity color-opacity}
                #js {:stroke color-value
                     :strokeOpacity color-opacity
                     :fill "transparent"})]
    [:g.grid
     (for [{:keys [x y width height]} (gg/grid-areas frame grid)]
       [:rect {:key (str key "-" x "-" y)
               :x x
               :y y
               :width width
               :height height
               :style style}])]))

(mf/defc grid-display-frame [{:keys [frame zoom]}]
  (let [grids (:grids frame)]
    (for [[index {:keys [type display] :as grid}] (map-indexed vector grids)]
      (let [props #js {:key (str (:id frame) "-grid-" index)
                       :frame frame
                       :zoom zoom
                       :grid grid}]
        (when display
          (case type
            :square [:> square-grid props]
            :column [:> layout-grid props]
            :row    [:> layout-grid props]))))))


(mf/defc frame-grid [{:keys [zoom]}]
  (let [frames (mf/deref refs/workspace-frames)]
    [:g.grid-display {:style {:pointer-events "none"}}
     (for [frame frames]
       [:& grid-display-frame {:key (str "grid-" (:id frame))
                               :zoom zoom
                               :frame (gsh/transform-shape frame)}])]))
