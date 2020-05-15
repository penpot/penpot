;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.layout-display
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.common.pages :as cp]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.layout :as ula]))

(mf/defc grid-layout [{:keys [frame zoom layout] :as props}]
  (let [{:keys [color size] :as params} (-> layout :params)
        {color-value :value color-opacity :opacity} (-> layout :params :color)
        {frame-width :width frame-height :height :keys [x y]} frame]
    [:g.layout
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
                        :stroke-width (str (/ 1 zoom))}}])]]))

(mf/defc flex-layout [{:keys [key frame zoom layout]}]
  (let [{color-value :value color-opacity :opacity} (-> layout :params :color)]
    [:g.layout
     (for [{:keys [x y width height]} (ula/layout-rects frame layout)]
       [:rect {:key (str key "-" x "-" y)
               :x x
               :y y
               :width width
               :height height
               :style {:fill color-value
                       :opacity color-opacity}}])]))

(mf/defc layout-display-frame [{:keys [frame zoom]}]
  (let [layouts (:layouts frame)]
    (for [[index {:keys [type display] :as layout}] (map-indexed vector layouts)]
      (let [props #js {:key (str (:id frame) "-layout-" index)
                       :frame frame
                       :zoom zoom
                       :layout layout}]
        (when display
          (case type
            :square [:> grid-layout props]
            :column [:> flex-layout props]
            :row    [:> flex-layout props]))))))


(mf/defc layout-display [{:keys [zoom]}]
  (let [frames (mf/deref refs/workspace-frames)]
    [:g.layout-display {:style {:pointer-events "none"}}
     (for [frame frames]
       [:& layout-display-frame {:key (str "layout-" (:id frame))
                                 :zoom zoom
                                 :frame (gsh/transform-shape frame)}])]))
