;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.image
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.main.data.images :as udi]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]))

;; --- Image Wrapper

(declare image-shape)

(mf/defc image-wrapper
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))

        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))]

    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& image-shape {:shape shape}]]))

;; --- Image Shape

(mf/defc image-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id x y width height rotation metadata]} shape
        transform (geom/transform-matrix shape)
        uri  (if (or (> (:thumb-width metadata) width)
                     (> (:thumb-height metadata) height))
               (:thumb-uri metadata)
               (:uri metadata))


        props (-> (attrs/extract-style-attrs shape)
                  (itr/obj-assign!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :preserveAspectRatio "none"
                        :xlinkHref uri
                        :width width
                        :height height}))]
    [:> "image" props]))
