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
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

;; --- Rect Wrapper

(declare rect-shape)

(mf/defc rect-wrapper
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& rect-shape {:shape shape}]]))

;; --- Rect Shape

(mf/defc rect-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id rotation modifier-mtx]} shape

        shape (cond
                (gmt/matrix? modifier-mtx) (geom/transform shape modifier-mtx)
                :else shape)

        {:keys [x y width height]} shape

        transform (when (and rotation (pos? rotation))
                    (str/format "rotate(%s %s %s)"
                                rotation
                                (+ x (/ width 2))
                                (+ y (/ height 2))))

        props (-> (attrs/extract-style-attrs shape)
                  (assoc :x x
                         :y y
                         :transform transform
                         :id (str "shape-" id)
                         :width width
                         :height height
                         ))]
    [:& "rect" props]))
