;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.icon
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]))

;; --- Icon Wrapper for workspace

(declare icon-shape)

(mf/defc icon-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        frame (unchecked-get props "frame")
        selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& icon-shape {:shape (geom/transform-shape frame shape)}]]))

;; --- Icon Wrapper for viewer

(mf/defc icon-viewer-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        frame (unchecked-get props "frame")

        {:keys [x y width height]} shape
        transform (geom/transform-matrix shape)

        show-interactions? (mf/deref refs/show-interactions?)

        on-mouse-down (mf/use-callback
                       (mf/deps shape)
                       #(common/on-mouse-down-viewer % shape))]
    [:g.shape {:on-mouse-down on-mouse-down
               :cursor (when (:interactions shape) "pointer")}
     [:*
       [:& icon-shape {:shape (geom/transform-shape frame shape)}]
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

;; --- Icon Shape

(mf/defc icon-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height metadata rotation content]} shape
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
