;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.shapes.outline
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.util.object :as obj]
   [rumext.util :refer [map->obj]]
   [uxbox.main.ui.shapes.path :as path]))


(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        transform (gsh/transform-matrix shape)
        {:keys [id x y width height]} shape

        outline-type (case (:type shape)
                       :circle "ellipse"
                       (:curve :path) "path"
                       "rect")

        common {:fill "transparent"
                :stroke "#31EFB8"
                :stroke-width "1px"
                :pointer-events "none"
                :transform transform}

        props (case (:type shape)
                :circle
                {:cx (+ x (/ width 2))
                 :cy (+ y (/ height 2))
                 :rx (/ width 2)
                 :ry (/ height 2)}
                
                (:curve :path)
                {:d (path/render-path shape)}
                
                {:x x
                 :y y
                 :width width
                 :height height})]

    [:> outline-type (map->obj (merge common props))]))
