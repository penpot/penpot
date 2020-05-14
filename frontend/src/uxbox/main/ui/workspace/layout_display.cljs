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
   [uxbox.main.refs :as refs]))

(mf/defc grid-layout [{:keys [frame zoom params] :as props}]
  (let [{:keys [color size]} params
        {color-value :value color-opacity :opacity} (:color params)
        {frame-width :width frame-height :height :keys [x y]} frame]
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
                        :stroke-width (str (/ 1 zoom))}}])]]))

(defn calculate-column-layout [frame size gutter margin item-width layout-type]
  (let [{:keys [width height x y]} frame
        parts (/ width size)
        item-width (or item-width (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        item-height height
        initial-offset (case layout-type
                         :right (- width (* item-width size) (* gutter (dec size)) margin)
                         :center (/ (- width (* item-width size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch layout-type) (/ (- width (* item-width size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] (+ initial-offset x (* (+ item-width gutter) cur-val)))
        next-y (fn [cur-val] y)]
    [parts item-width item-height next-x next-y]))

(defn calculate-row-layout [frame size gutter margin item-height layout-type]
  (let [{:keys [width height x y]} frame
        parts (/ height size)
        item-width width
        item-height (or item-height (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        initial-offset (case layout-type
                         :right (- height (* item-height size) (* gutter (dec size)) margin)
                         :center (/ (- height (* item-height size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch layout-type) (/ (- height (* item-height size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] x)
        next-y (fn [cur-val] (+ initial-offset y (* (+ item-height gutter) cur-val)))]
    [parts item-width item-height next-x next-y]))

(mf/defc flex-layout [{:keys [frame zoom params orientation]}]
  (let [{:keys [color size type gutter margin item-width item-height]} params
        {color-value :value color-opacity :opacity} (:color params)

       ;; calculates the layout configuration
        [parts item-width item-height next-x next-y]
        (if (= orientation :column)
          (calculate-column-layout frame size gutter margin item-width type)
          (calculate-row-layout frame size gutter margin item-height type))]

    (for [cur-val (range 0 size)]
      [:rect {:x (next-x cur-val)
              :y (next-y cur-val)
              :width item-width
              :height item-height
              :style {:pointer-events "none"
                      :fill color-value
                      :opacity color-opacity}}])))

(mf/defc layout-display [{:keys [frame]}]
  (let [zoom (mf/deref refs/selected-zoom)
        layouts (:layouts frame)]
    (for [[index {:keys [type display params]}] (map-indexed vector layouts)]
      (let [props #js {:key (str (:id frame) "-layout-" index)
                       :frame frame
                       :zoom zoom
                       :params params
                       :orientation (cond (= type :column) :column
                                          (= type :row) :row
                                          :else nil) }]
        (when display
          (case type
            :square [:> grid-layout props]
            :column [:> flex-layout props]
            :row    [:> flex-layout props]))))))
