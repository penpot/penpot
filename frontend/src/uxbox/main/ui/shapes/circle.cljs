;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.circle
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]
   [uxbox.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]))

;; --- Circle Wrapper for workspace

(declare circle-shape)

(mf/defc circle-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape)
        on-context-menu #(common/on-context-menu % shape)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& circle-shape {:shape shape}]]))

;; --- Circle Wrapper for viewer

(mf/defc circle-viewer-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height]} shape
        transform (geom/transform-matrix shape)

        show-interactions? (mf/deref refs/show-interactions?)

        on-mouse-down (mf/use-callback
                       (mf/deps shape)
                       #(common/on-mouse-down-viewer % shape))]

    [:g.shape {:on-mouse-down on-mouse-down
               :cursor (when (:interactions shape) "pointer")}
     [:*
       [:& circle-shape {:shape shape}]
       (when (and (:interactions shape) show-interactions?)
         [:> "rect" #js {:x (- x 1)
                         :y (- y 1)
                         :width (+ width 2)
                         :height (+ height 2)
                         :transform transform
                         :fill "#31EFB8"
                         :stroke "#31EFB8"
                         :strokeWidth 1
                         :fillOpacity 0.2}])]]))

;; --- Circle Shape

(mf/defc circle-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height]} shape
        transform (geom/transform-matrix shape)

        cx (+ x (/ width 2))
        cy (+ y (/ height 2))
        rx (/ width 2)
        ry (/ height 2)

        props (-> (attrs/extract-style-attrs shape)
                  (itr/obj-assign!
                   #js {:cx cx
                        :cy cy
                        :rx rx
                        :ry ry
                        :transform transform
                        :id (str "shape-" id)}))]
    [:& shape-custom-stroke {:shape shape
                             :base-props props
                             :elem-name "ellipse"}]))
