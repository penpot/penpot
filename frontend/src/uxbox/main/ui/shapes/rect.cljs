;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.object :as obj]))

(mf/defc rect-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height]} shape
        transform (geom/transform-matrix shape)
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :width width
                        :height height}))]

    [:& shape-custom-stroke {:shape shape
                             :base-props props
                             :elem-name "rect"}]))

