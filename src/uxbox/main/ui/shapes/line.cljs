;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.line
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]))

;; --- Line Component

(declare line-shape)

(defn- line-component-render
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
      (line-shape shape identity)])))

(def line-component
  (mx/component
   {:render line-component-render
    :name "line-component"
    :mixins [mx/static rum/reactive]}))

;; --- Line Shape

(defn- line-shape-render
  [own {:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        props (select-keys shape [:x1 :x2 :y2 :y1])
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge {:id key :key key})
                  (merge props))]
    (html
     [:line attrs])))

(def line-shape
  (mx/component
   {:render line-shape-render
    :name "line-shape"
    :mixins [mx/static]}))
