;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]))

;; --- Rect Wrapper

(declare rect-shape)

(mf/defc rect-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        frame (unchecked-get props "frame")
        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))
        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))]
    [:g.shape {:on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& rect-shape {:shape (geom/transform-shape frame shape) }]
     [:& bounding-box {:shape shape :frame frame}]]))

;; --- Rect Shape

(mf/defc rect-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height]} shape
        transform (geom/transform-matrix shape)
        props (-> (attrs/extract-style-attrs shape)
                  (itr/obj-assign!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :width width
                        :height height}))]
    [:> "rect" props]))
