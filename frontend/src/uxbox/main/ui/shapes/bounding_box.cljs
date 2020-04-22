;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.bounding-box
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.geom :as geom]
   [uxbox.util.debug :as debug]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.debug :refer [debug?]]))

(defn fix [num]
  (when num (.toFixed num 2)))

(mf/defc bounding-box
  {::mf/wrap-props false}
  [props]
  (when (debug? :bounding-boxes)
    (let [shape (unchecked-get props "shape")
          frame (unchecked-get props "frame")
          selrect (geom/transform-selrect frame shape)
          shape-path (-> shape
                         (geom/transform-apply-modifiers)
                         (geom/translate-to-frame frame))
          shape-center (geom/center shape-path)]
      [:g
       [:text {:x (:x selrect)
               :y (- (:y selrect) 5)
               :font-size 10
               :fill "red"
               :stroke "white"
               :stroke-width 0.1}
        (str/format "%s - (%s, %s)" (str/slice (str (:id shape)) 0 8) (fix (:x shape)) (fix (:y shape)))]

       [:circle {:cx (:x shape-center) :cy (:y shape-center) :r 5 :fill "yellow"}]

       [:rect  {:x (:x selrect)
                :y (:y selrect)
                :width (:width selrect)
                :height (:height selrect)
                :style {:stroke "red" :fill "transparent" :stroke-width "1px" :stroke-opacity 0.5}}]])))
