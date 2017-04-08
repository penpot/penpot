;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require [uxbox.main.geom :as geom]
            [uxbox.main.refs :as refs]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.data :refer [classnames]]
            [uxbox.util.dom :as dom]))

;; --- Rect Component

(declare rect-shape)

(mx/defc rect-component
  {:mixins [mx/reactive mx/static]}
  [{:keys [id] :as shape}]
  (let [modifiers (mx/react (refs/selected-modifiers id))
        selected (mx/react refs/selected-shapes)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)
        shape (assoc shape :modifiers modifiers)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (rect-shape shape identity)]))

;; --- Rect Shape

(defn- rotate
  [mt {:keys [x1 y1 x2 y2 width height rotation] :as shape}]
  (let [x-center (+ x1 (/ width 2))
        y-center (+ y1 (/ height 2))
        center (gpt/point x-center y-center)]
    (gmt/rotate* mt rotation center)))

(mx/defc rect-shape
  {:mixins [mx/static]}
  [{:keys [id rotation modifiers] :as shape}]
  (let [{:keys [displacement resize]} modifiers
        xfmt (cond-> (gmt/matrix)
               displacement (gmt/multiply displacement)
               resize (gmt/multiply resize))

        {:keys [x1 y1 width height] :as shape} (-> (geom/transform shape xfmt)
                                                   (geom/size))

        xfmt (cond-> (gmt/matrix)
               (pos? rotation) (rotate shape))

        moving? (boolean displacement)

        props {:x x1 :y y1
               :id (str "shape-" id)
               :class (classnames :move-cursor moving?)
               :width width
               :height height
               :transform (str xfmt)}
        attrs (merge (attrs/extract-style-attrs shape) props)]
    [:rect attrs]))
