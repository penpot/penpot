;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.bounding-box
  (:require
   ["randomcolor" :as rdcolor]
   [app.common.geom.shapes :as gsh]
   [app.main.refs :as refs]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn fixed
  [num]
  (when num (.toFixed num 2)))

(mf/defc cross-point [{:keys [point zoom color]}]
  (let [width (/ 5 zoom)]
    [:g.point
     [:line {:x1 (- (:x point) width) :y1 (- (:y point) width)
             :x2 (+ (:x point) width) :y2 (+ (:y point) width)
             :stroke color
             :stroke-width "1px"
             :stroke-opacity 0.5}]

     [:line {:x1 (+ (:x point) width) :y1 (- (:y point) width)
             :x2 (- (:x point) width) :y2 (+ (:y point) width)
             :stroke color
             :stroke-width "1px"
             :stroke-opacity 0.5}]]))

(mf/defc render-rect [{{:keys [x y width height]} :rect :keys [color transform]}]
  [:rect  {:x x
           :y y
           :width width
           :height height
           :transform (or transform "none")
           :style {:stroke color
                   :fill "none"
                   :stroke-width "1px"
                   :pointer-events "none"}}])

(mf/defc render-rect-points [{:keys [points color]}]
  (for [[p1 p2] (map vector points (concat (rest points) [(first points)]))]
    [:line {:x1 (:x p1)
            :y1 (:y p1)
            :x2 (:x p2)
            :y2 (:y p2)
            :style {:stroke color
                    :stroke-width "1px"}}]))

(mf/defc bounding-box
  {::mf/wrap-props false}
  [props]
  (let [shape        (unchecked-get props "shape")
        bounding-box (gsh/points->selrect (-> shape :points))
        shape-center (gsh/center-shape shape)
        line-color   (rdcolor #js {:seed (str (:id shape))})
        zoom         (mf/deref refs/selected-zoom)]

    [:g.bounding-box
     [:text {:x (:x bounding-box)
             :y (- (:y bounding-box) 5)
             :font-size 10
             :fill line-color
             :stroke "var(--color-white)"
             :stroke-width 0.1}
      (str/format "%s - (%s, %s)" (str/slice (str (:id shape)) 0 8) (fixed (:x bounding-box)) (fixed (:y bounding-box)))]

     [:g.center
      [:& cross-point {:point shape-center
                       :zoom zoom
                       :color line-color}]]

     [:g.points
      (for [point (:points shape)]
        [:& cross-point {:point point
                         :zoom zoom
                         :color line-color}])
      #_[:& render-rect-points {:points (:points shape)
                                :color line-color}]]

     [:g.selrect
      [:& render-rect {:rect (:selrect shape)
                       ;; :transform (gsh/transform-matrix shape)
                       :color line-color}]
      #_[:& render-rect {:rect bounding-box
                         :color line-color}]]]))
