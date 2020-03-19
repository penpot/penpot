;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as interop]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

;; --- Rect Wrapper

(declare rect-shape)

(mf/defrc rect-wrapper
  [props]
  (let [shape (unchecked-get props "shape")
        on-mouse-down #(common/on-mouse-down % shape)
        on-context-menu #(common/on-context-menu % shape)]
    [:g.shape {:on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& rect-shape {:shape shape}]]))

;; --- Rect Shape

(mf/defrc rect-shape
  [props]
  (let [shape (unchecked-get props "shape")
        ds-modifier (:displacement-modifier shape)
        rz-modifier (:resize-modifier shape)

        shape (cond-> shape
                (gmt/matrix? rz-modifier) (geom/transform rz-modifier)
                (gmt/matrix? ds-modifier) (geom/transform ds-modifier))

        {:keys [id x y width height rotation]} shape

        transform (when (and rotation (pos? rotation))
                    (str/format "rotate(%s %s %s)"
                                rotation
                                (+ x (/ width 2))
                                (+ y (/ height 2))))

        props (-> (attrs/extract-style-attrs2 shape)
                  (interop/obj-assign!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :width width
                        :height height}))]

    [:> "rect" props]))
