;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.image
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.util.object :as obj]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height rotation metadata]} shape
        transform (geom/transform-matrix shape)
        uri  (if (or (> (:thumb-width metadata) width)
                     (> (:thumb-height metadata) height))
               (:thumb-uri metadata)
               (:uri metadata))

        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :preserveAspectRatio "none"
                        :xlinkHref uri
                        :width width
                        :height height}))]
    [:> "image" props]))
