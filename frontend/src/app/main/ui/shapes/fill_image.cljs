;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.fill-image
  (:require
   [app.common.geom.shapes :as gsh]
   [app.config :as cfg]
   [app.util.object :as obj]
   [rumext.alpha :as mf]
   [app.common.geom.point :as gpt]
   [app.main.ui.shapes.image :as image]))

(mf/defc fill-image-pattern
  {::mf/wrap-props false}
  [props]

  (let [shape     (obj/get props "shape")
        render-id (obj/get props "render-id")]
    (when (contains? shape :fill-image)
      (let [{:keys [x y width height]} (:selrect shape)
            fill-image-id (str "fill-image-" render-id)
            media (:fill-image shape)
            {:keys [uri loading]} (image/use-image-uri media)
            transform (gsh/transform-matrix shape)]

        [:pattern {:id fill-image-id
                   :patternUnits "userSpaceOnUse"
                   :x x
                   :y y
                   :height height
                   :width width
                   :patternTransform transform
                   :data-loading (str loading)}
         [:image {:xlinkHref uri
                  :width width
                  :height height}]]))))
