;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.image
  (:require
   [app.common.geom.shapes :as geom]
   [app.config :as cfg]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.embed :as embed]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        {:keys [x y width height metadata]} shape
        uri   (cfg/resolve-file-media metadata)
        embed (embed/use-data-uris [uri])

        transform (geom/transform-matrix shape)
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :width width
                        :height height
                        :preserveAspectRatio "none"
                        :data-loading (str (not (contains? embed uri)))}))

        on-drag-start (fn [event]
                        ;; Prevent browser dragging of the image
                        (dom/prevent-default event))]

    [:> "image" (obj/merge!
                 props
                 #js {:xlinkHref (get embed uri uri)
                      :onDragStart on-drag-start})]))
