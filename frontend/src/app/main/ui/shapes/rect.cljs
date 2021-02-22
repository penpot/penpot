;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.rect
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [app.common.geom.shapes :as geom]
   [app.util.object :as obj]
   [app.main.ui.shapes.gradients :refer [gradient]]

   [cuerdas.core :as str]
   [app.common.uuid :as uuid]
   [app.common.geom.point :as gpt]))

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
                        :width width
                        :height height}))]

    [:& shape-custom-stroke {:shape shape
                             :base-props props
                             :elem-name 
                             (if (.-d props)
                               "path"
                               "rect")}]))
