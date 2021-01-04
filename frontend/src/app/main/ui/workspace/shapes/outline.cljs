;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.outline
  (:require
   [rumext.alpha :as mf]
   [app.common.geom.shapes :as gsh]
   [app.util.object :as obj]
   [rumext.util :refer [map->obj]]
   [app.main.refs :as refs]
   [app.util.geom.path :as ugp]))


(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [zoom (mf/deref refs/selected-zoom)
        shape (unchecked-get props "shape")
        color (unchecked-get props "color")
        transform (gsh/transform-matrix shape)
        {:keys [id x y width height]} shape

        outline-type (case (:type shape)
                       :circle "ellipse"
                       :path "path"
                       "rect")

        common {:fill "transparent"
                :stroke color
                :strokeWidth (/ 1 zoom)
                :pointerEvents "none"
                :transform transform}

        props (case (:type shape)
                :circle
                {:cx (+ x (/ width 2))
                 :cy (+ y (/ height 2))
                 :rx (/ width 2)
                 :ry (/ height 2)}

                :path
                {:d (ugp/content->path (:content shape))
                 :transform nil}

                {:x x
                 :y y
                 :width width
                 :height height})]

    [:> outline-type (map->obj (merge common props))]))
