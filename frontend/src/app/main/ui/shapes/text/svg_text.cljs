;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [app.main.store :as st]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  
  (let [{:keys [x y width height position-data] :as shape} (obj/get props "shape")
        zoom (or (get-in @st/state [:workspace-local :zoom]) 1)]
    [:text {:x x
            :y y
            :width width
            :height height
            :dominant-baseline "ideographic"
            :transform (gsh/transform-matrix shape)
            }
     (for [data position-data]
       [:tspan {:x (:x data)
                :y (:y data)
                :transform (:transform-inverse shape (gmt/matrix))
                :style {:fill "black"
                        :fill-opacity 1
                        :stroke "red"
                        :stroke-width (/ 0.5 zoom)
                        :font-family (:font-family data)
                        :font-size (:font-size data)
                        :font-weight (:font-weight data)
                        :text-transform (:text-transform data)
                        :text-decoration (:text-decoration data)
                        :font-style (:font-style data)
                        :direction (if (:rtl? data) "rtl" "ltr")
                        :white-space "pre"}}
        (:text data)])]))
