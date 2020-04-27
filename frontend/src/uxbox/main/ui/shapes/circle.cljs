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
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]))

;; --- Circle Wrapper

(declare circle-shape)

(mf/defc circle-wrapper
  [{:keys [shape frame] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape)
        on-context-menu #(common/on-context-menu % shape)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& circle-shape {:shape (geom/transform-shape frame shape)}]
     [:& bounding-box {:shape shape :frame frame}]]))

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
    [:> "ellipse" props]))
