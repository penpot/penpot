;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.circle
  (:require
   [app.common.geom.shapes :as geom]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc circle-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [x y width height]} shape
        transform (geom/transform-matrix shape)

        cx (+ x (/ width 2))
        cy (+ y (/ height 2))
        rx (/ width 2)
        ry (/ height 2)

        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:cx cx
                        :cy cy
                        :rx rx
                        :ry ry
                        :transform transform}))]

    [:& shape-custom-strokes {:shape shape}
     [:> :ellipse props]]))
