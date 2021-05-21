;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.fill-image
  (:require
   [app.common.geom.shapes :as gsh]
   [app.config :as cfg]
   [app.main.ui.shapes.embed :as embed]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc fill-image-pattern
  {::mf/wrap-props false}
  [props]

  (let [shape     (obj/get props "shape")
        render-id (obj/get props "render-id")]
    (when (contains? shape :fill-image)
      (let [{:keys [x y width height]} (:selrect shape)
            fill-image-id (str "fill-image-" render-id)
            uri (cfg/resolve-file-media (:fill-image shape))
            embed (embed/use-data-uris [uri])
            transform (gsh/transform-matrix shape)]

        [:pattern {:id fill-image-id
                   :patternUnits "userSpaceOnUse"
                   :x x
                   :y y
                   :height height
                   :width width
                   :patternTransform transform}
         [:image {:xlinkHref (get embed uri uri)
                  :width width
                  :height height}]]))))
