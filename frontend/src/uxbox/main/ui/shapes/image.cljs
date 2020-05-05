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

;; --- Image Wrapper for workspace

(declare image-shape)

(mf/defc image-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        selected (mf/deref refs/selected-shapes)
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

;; --- Image Wrapper for viewer

(mf/defc image-viewer-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [x y width height]} (geom/selection-rect-shape shape)
        show-interactions? (mf/deref refs/show-interactions?)
        on-mouse-down (mf/use-callback
                       (mf/deps shape)
                       #(common/on-mouse-down-viewer % shape))]
    [:g.shape {:on-mouse-down on-mouse-down
               :cursor (when (:interactions shape) "pointer")}
     [:*
       [:& image-shape {:shape shape}]
       (when (and (:interactions shape) show-interactions?)
         [:> "rect" #js {:x (- x 1)
                         :y (- y 1)
                         :width (+ width 2)
                         :height (+ height 2)
                         :fill "#31EFB8"
                         :stroke "#31EFB8"
                         :strokeWidth 1
                         :fillOpacity 0.2}])]]))

;; --- Image Shape

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
