;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]))

;; --- Rect Component

(declare rect-shape)

(mx/defc rect-component
  {:mixins [mx/reactive mx/static]}
  [{:keys [id] :as shape}]
  (let [modifiers (mx/react (common/modifiers-ref id))
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)
        shape (assoc shape :modifiers modifiers)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (rect-shape shape identity)]))

;; --- Rect Shape

(defn- rotate
  ;; TODO: revisit, i'm not sure if this function is duplicated.
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

        props {:x x1 :y y1
               :id (str "shape-" id)
               :width width
               :height height
               :transform (str xfmt)}

        attrs (merge (attrs/extract-style-attrs shape) props)]
    [:rect attrs]))
