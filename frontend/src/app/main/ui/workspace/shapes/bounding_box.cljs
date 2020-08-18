;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.bounding-box
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.util.debug :as debug]
   [app.common.geom.shapes :as geom]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.util.debug :refer [debug?]]
   [app.main.refs :as refs]
   ["randomcolor" :as rdcolor]))

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

(mf/defc bounding-box
  {::mf/wrap-props false}
  [props]
  (when (debug? :bounding-boxes)
    (let [shape (unchecked-get props "shape")
          frame (unchecked-get props "frame")
          selrect (-> shape :selrect)
          shape-center (geom/center shape)
          line-color (rdcolor #js {:seed (str (:id shape))})
          zoom (mf/deref refs/selected-zoom)]
      [:g.bounding-box
       [:text {:x (:x selrect)
               :y (- (:y selrect) 5)
               :font-size 10
               :fill line-color
               :stroke "white"
               :stroke-width 0.1}
        (str/format "%s - (%s, %s)" (str/slice (str (:id shape)) 0 8) (fixed (:x shape)) (fixed (:y shape)))]

       [:& cross-point {:point shape-center
                        :zoom zoom
                        :color line-color}]

       (for [point (:points shape)]
         [:& cross-point {:point point
                          :zoom zoom
                          :color line-color}])

       [:rect  {:x (:x selrect)
                :y (:y selrect)
                :width (:width selrect)
                :height (:height selrect)
                :style {:stroke line-color
                        :fill "transparent"
                        :stroke-width "1px"
                        :stroke-opacity 0.5
                        :pointer-events "none"}}]])))
