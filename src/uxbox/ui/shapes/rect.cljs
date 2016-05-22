;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.shapes.rect
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.shapes.common :as common]
            [uxbox.ui.shapes.attrs :as attrs]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Rect Component

(declare rect-shape)

(defn- rect-component-render
  [own shape]
  (let [{:keys [id x y width height group]} shape
        selected (rum/react common/selected-shapes-l)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)
        on-mouse-up #(common/on-mouse-up % shape)]
    (html
     [:g.shape {:class (when selected? "selected")
                :on-mouse-down on-mouse-down
                :on-mouse-up on-mouse-up}
      (rect-shape shape identity)])))

(def rect-component
  (mx/component
   {:render rect-component-render
    :name "rect-component"
    :mixins [mx/static rum/reactive]}))

;; --- Rect Shape

(defn- rect-shape-render
  [own {:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1 :id key :key key :transform (str rfm)}
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge props size))]
    (html
     [:rect attrs])))

(def rect-shape
  (mx/component
   {:render rect-shape-render
    :name "rect-shape"
    :mixins [mx/static]}))
