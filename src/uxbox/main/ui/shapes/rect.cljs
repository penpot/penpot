;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Rect Component

(declare rect-shape)

(mx/defc rect-component
  {:mixins [mx/reactive mx/static]}
  [shape]
  (let [{:keys [id x y width height group]} shape
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (rect-shape shape identity)]))

;; --- Rect Shape

(mx/defc rect-shape
  {:mixins [mx/static]}
  [{:keys [id x1 y1] :as shape}]
  (let [key (str "shape-" id)
        rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1 :id key :key key :transform (str rfm)}
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge props size))]
    [:rect attrs]))
