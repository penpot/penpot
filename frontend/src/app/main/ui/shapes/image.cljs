;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.image
  (:require
   [app.common.geom.shapes :as gsh]
   [app.config :as cfg]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [app.main.ui.shapes.embed :as embed]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        {:keys [x y width height metadata]} shape
        uri   (cfg/resolve-file-media metadata)
        embed (embed/use-data-uris [uri])

        transform (gsh/transform-matrix shape)

        fill-attrs (-> (attrs/extract-fill-attrs shape)
                       (obj/set! "width" width)
                       (obj/set! "height" height))

        render-id  (mf/use-ctx muc/render-ctx)
        fill-image-id (str "fill-image-" render-id)
        shape (assoc shape :fill-image fill-image-id)
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge! (attrs/extract-border-radius-attrs shape))
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :width width
                        :height height}))
        path? (some? (.-d props))]

    [:g
      [:defs
       [:pattern {:id fill-image-id
                  :patternUnits "userSpaceOnUse"
                  :x x
                  :y y
                  :height height
                  :width width
                  :data-loading (str (not (contains? embed uri)))}
        [:g
         [:> :rect fill-attrs]
         [:image {:xlinkHref (get embed uri uri)
                  :width width
                  :height height}]]]]
     [:& shape-custom-stroke {:shape shape}
      (if path?
        [:> :path props]
        [:> :rect props])]]))
