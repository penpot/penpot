;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.icon
  (:require
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))


;; --- Icon Component

(declare icon-shape)

(mf/defc icon-component
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& icon-shape {:shape shape}]]))

;; --- Icon Shape

(defn- rotate
  ;; TODO: revisit, i'm not sure if this function is duplicated.
  [mt {:keys [x1 y1 x2 y2 width height rotation] :as shape}]
  (let [x-center (+ x1 (/ width 2))
        y-center (+ y1 (/ height 2))
        center (gpt/point x-center y-center)]
    (gmt/rotate* mt rotation center)))

(mf/defc icon-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id content metadata rotation modifier-mtx]} shape

        shape (cond
                (gmt/matrix? modifier-mtx) (geom/transform shape modifier-mtx)
                :else shape)

        {:keys [x1 y1 width height] :as shape} (geom/size shape)

        transform (when (pos? rotation)
                    (str (rotate (gmt/matrix) shape)))

        view-box (apply str (interpose " " (:view-box metadata)))
        moving? (boolean modifier-mtx)
        props {:id (str id)
               :x x1
               :y y1
               :view-box view-box
               :class (classnames :move-cursor moving?)
               :width width
               :height height
               :preserve-aspect-ratio "none"
               :dangerouslySetInnerHTML #js {:__html content}}

        attrs (merge props (attrs/extract-style-attrs shape))]
    [:g {:transform transform}
     [:> :svg (normalize-props attrs) ]]))

;; --- Icon SVG

(mf/defc icon-svg
  [{:keys [shape] :as props}]
  (let [{:keys [content id metadata]} shape
        view-box (apply str (interpose " " (:view-box metadata)))
        props {:view-box view-box
               :id (str "shape-" id)
               :dangerouslySetInnerHTML #js {:__html content}}]
    [:> :svg (normalize-props props)]))
