;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.shapes.bbox
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.util.debug :as debug]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.snap :as snap]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.debug :refer [debug?]]
   ["randomcolor" :as rdcolor]))

(defn fixed
  [num]
  (when num (.toFixed num 2)))

(mf/defc bounding-box
  {::mf/wrap-props false}
  [props]
  (when (debug? :bounding-boxes)
    (let [shape (unchecked-get props "shape")
          frame (unchecked-get props "frame")
          selrect (-> shape
                      (geom/selection-rect-shape)
                      (geom/translate-to-frame frame))
          shape-center (geom/center selrect)
          line-color (rdcolor #js {:seed (str (:id shape))})]
      [:g
       [:text {:x (:x selrect)
               :y (- (:y selrect) 5)
               :font-size 10
               :fill "red"
               :stroke "white"
               :stroke-width 0.1}
        (str/format "%s - (%s, %s)" (str/slice (str (:id shape)) 0 8) (fixed (:x shape)) (fixed (:y shape)))]

       [:rect  {:x (:x selrect)
                :y (:y selrect)
                :width (:width selrect)
                :height (:height selrect)
                :style {:stroke "red"
                        :fill "transparent"
                        :stroke-width "1px"
                        :stroke-opacity 0.5
                        :pointer-events "none"}}]

       #_(for [point (snap/shape-snap-points shape)]
         (let [point (gpt/subtract point (gpt/point (:x frame) (:y frame)))]
           [:* {:key (str "point-" (:id shape) "-" (:x point) "-" (:y point))}
            [:circle {:cx (:x point)
                      :cy (:y point)
                      :r 4
                      :fill line-color}]

            [:line {:x1 (:x point)
                    :y1 -10000
                    :x2 (:x point)
                    :y2 10000
                    :style {:stroke line-color :stroke-width "1"}
                    :opacity 0.4}]

            [:line {:x1 -10000
                    :y1 (:y point)
                    :x2 10000
                    :y2 (:y point)
                    :style {:stroke line-color :stroke-width "1"}
                    :opacity 0.4}]]
           ))
       ])))
