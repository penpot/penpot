;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.icon
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.main.ui.mixins :as mx]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.common.geom :as geom]))

;; --- Icon Component

(declare icon-shape)

(defn- icon-component-render
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
      (icon-shape shape identity)])))

(def icon-component
  (mx/component
   {:render icon-component-render
    :name "icon-component"
    :mixins [mx/static rum/reactive]}))

;; --- Icon Shape

(defn- icon-shape-render
  [own {:keys [data id] :as shape} factory]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (attrs/extract-style-attrs shape)
                     (attrs/make-debug-attrs shape))]
    (html
     [:g attrs data])))

(def icon-shape
  (mx/component
   {:render icon-shape-render
    :name "icon-shape"
    :mixins [mx/static]}))

;; --- Icon SVG

(defn- icon-svg-render
  [own {:keys [data id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}]
    (html
     [:svg props data])))

(def icon-svg
  (mx/component
   {:render icon-svg-render
    :name "icon-svg"
    :mixins [mx/static]}))
