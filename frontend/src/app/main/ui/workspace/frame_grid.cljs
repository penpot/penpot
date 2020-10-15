;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.frame-grid
  (:require
   [rumext.alpha :as mf]
   [app.main.refs :as refs]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.geom.shapes :as gsh]
   [app.util.geom.grid :as gg]))

(mf/defc square-grid [{:keys [frame zoom grid] :as props}]
  (let [{:keys [color size] :as params} (-> grid :params)
        {color-value :color color-opacity :opacity} (-> grid :params :color)
        ;; Support for old color format
        color-value (or color-value (:value (get-in grid [:params :color :value])))
        {frame-width :width frame-height :height :keys [x y]} frame]
    (when (> size 0)
      [:g.grid
       [:*
        (for [xs (range size frame-width size)]
          [:line {:key (str (:id frame) "-y-" xs)
                  :x1 (mth/round (+ x xs))
                  :y1 (mth/round y)
                  :x2 (mth/round (+ x xs))
                  :y2 (mth/round (+ y frame-height))
                  :style {:stroke color-value
                          :stroke-opacity color-opacity
                          :stroke-width (str (/ 1 zoom))}}])
        (for [ys (range size frame-height size)]
          [:line {:key (str (:id frame) "-x-" ys)
                  :x1 (mth/round x)
                  :y1 (mth/round (+ y ys))
                  :x2 (mth/round (+ x frame-width))
                  :y2 (mth/round (+ y ys))
                  :style {:stroke color-value
                          :stroke-opacity color-opacity
                          :stroke-width (str (/ 1 zoom))}}])]])))

(mf/defc layout-grid [{:keys [key frame zoom grid]}]
  (let [{color-value :color color-opacity :opacity} (-> grid :params :color)
        ;; Support for old color format
        color-value (or color-value (:value (get-in grid [:params :color :value])))
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
       (do
         [:rect {:key (str key "-" x "-" y)
                 :x (mth/round x)
                 :y (mth/round y)
                 :width (- (mth/round (+ x width)) (mth/round x))
                 :height (- (mth/round (+ y height)) (mth/round y))
                 :style style}]))]))

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
