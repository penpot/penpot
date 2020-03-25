;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.icon
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))


;; --- Icon Wrapper

(declare icon-shape)

(mf/defc icon-wrapper
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& icon-shape {:shape shape}]]))

;; --- Icon Shape

(mf/defc icon-shape
  [{:keys [shape] :as props}]
  (let [ds-modifier (:displacement-modifier shape)
        rz-modifier (:resize-modifier shape)

        shape (cond-> shape
                (gmt/matrix? rz-modifier) (geom/transform rz-modifier)
                (gmt/matrix? ds-modifier) (geom/transform ds-modifier))

        {:keys [id x y width height metadata rotation content] :as shape} shape

        transform (when (and rotation (pos? rotation))
                    (str/format "rotate(%s %s %s)"
                                rotation
                                (+ x (/ width 2))
                                (+ y (/ height 2))))

        view-box (apply str (interpose " " (:view-box metadata)))

        props (-> (attrs/extract-style-attrs shape)
                  (itr/obj-assign!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :width width
                        :height height
                        :viewBox view-box
                        :preserveAspectRatio "none"
                        :dangerouslySetInnerHTML #js {:__html content}}))]


    [:g {:transform transform}
     [:> "svg" props]]))

;; --- Icon SVG

(mf/defc icon-svg
  [{:keys [shape] :as props}]
  (let [{:keys [content id metadata]} shape
        view-box (apply str (interpose " " (:view-box metadata)))
        props {:viewBox view-box
               :id (str "shape-" id)
               :dangerouslySetInnerHTML #js {:__html content}}]
    [:& "svg" props]))
