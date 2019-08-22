;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

;; --- Rect Component

(declare rect-shape)

(mf/defc rect-component
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]
        ;; shape (assoc shape :modifiers modifiers)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& rect-shape {:shape shape}]]))

;; --- Rect Shape

(defn- rotate
  [mt {:keys [x1 y1 x2 y2 width height rotation] :as shape}]
  (let [x-center (+ x1 (/ width 2))
        y-center (+ y1 (/ height 2))
        center (gpt/point x-center y-center)]
    (gmt/rotate* mt rotation center)))

(mf/defc rect-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id rotation modifier-mtx]} shape

        shape (cond
                (gmt/matrix? modifier-mtx) (geom/transform shape modifier-mtx)
                :else shape)

        {:keys [x1 y1 width height] :as shape} (geom/size shape)

        transform (when (pos? rotation)
                    (str (rotate (gmt/matrix) shape)))

        moving? (boolean modifier-mtx)

        props {:x x1 :y y1
               :id (str "shape-" id)
               :class-name (classnames :move-cursor moving?)
               :width width
               :height height
               :transform transform}
        attrs (merge (attrs/extract-style-attrs shape) props)]
    [:> :rect (normalize-props attrs)]))
