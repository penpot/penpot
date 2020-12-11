;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.path
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [app.common.geom.shapes :as geom]
   [app.util.object :as obj]
   [app.util.geom.path :as ugp]))

;; --- Path Shape

(mf/defc path-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        background? (unchecked-get props "background?")
        ;; {:keys [id x y width height]} (geom/shape->rect-shape shape)
        {:keys [id x y width height]} (:selrect shape)
        transform (geom/transform-matrix shape)
        pdata (ugp/content->path (:content shape))
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:transform transform
                        :d pdata}))]
    (if background?
      [:g
       [:path {:stroke "transparent"
               :fill "transparent"
               :stroke-width "20px"
               :d pdata}]
       [:& shape-custom-stroke {:shape shape
                                :base-props props
                                :elem-name "path"}]]
      [:& shape-custom-stroke {:shape shape
                               :base-props props
                               :elem-name "path"}])))

