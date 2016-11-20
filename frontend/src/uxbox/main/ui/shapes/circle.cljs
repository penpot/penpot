;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.circle
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]))

;; --- Circle Component

(declare circle-shape)

(defn- circle-component-render
  [own shape]
  (let [{:keys [id x y width height group]} shape
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    (html
     [:g.shape {:class (when selected? "selected")
                :on-mouse-down on-mouse-down}
      (circle-shape shape identity)])))

(def circle-component
  (mx/component
   {:render circle-component-render
    :name "circle-component"
    :mixins [mx/static mx/reactive]}))

;; --- Circle Shape

(defn- circle-shape-render
  [own {:keys [id] :as shape}]
  (let [key (str "shape-" id)
        rfm (geom/transformation-matrix shape)
        props (select-keys shape [:cx :cy :rx :ry])
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge {:id key :key key :transform (str rfm)})
                  (merge props))]
    (html
     [:ellipse attrs])))

(def circle-shape
  (mx/component
   {:render circle-shape-render
    :name "circle-shape"
    :mixins [mx/static]}))
