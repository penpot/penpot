;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.group
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.shapes.rect :as rect]
            [uxbox.main.ui.shapes.circle :as circle]
            [uxbox.main.ui.shapes.text :as text]
            [uxbox.main.ui.shapes.line :as line]
            [uxbox.main.ui.shapes.path :as path]
            [uxbox.main.geom :as geom]))

;; --- Helpers

(declare group-component)

(defn render-component
  [{:keys [type] :as shape}]
  ;; (println "render-component" (pr-str shape))
  (case type
    :group (group-component shape)
    :text (text/text-component shape)
    :line (line/line-component shape)
    :icon (icon/icon-component shape)
    :rect (rect/rect-component shape)
    :path (path/path-component shape)
    :circle (circle/circle-component shape)))

;; --- Group Component

(declare group-shape)

(defn- group-component-render
  [own shape]
  (let [{:keys [id x y width height group]} shape
        selected (mx/react common/selected-shapes-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    (html
     [:g.shape.group-shape
      {:class (when selected? "selected")
       :on-mouse-down on-mouse-down}
      (group-shape shape render-component)])))

(def group-component
  (mx/component
   {:render group-component-render
    :name "group-component"
    :mixins [mx/static mx/reactive]}))

;; --- Group Shape

(defn- group-shape-render
  [own {:keys [items id dx dy rotation] :as shape} factory]
  (let [key (str "shape-" id)
        rfm (geom/transformation-matrix shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (attrs/extract-style-attrs shape)
                     (attrs/make-debug-attrs shape))
        shapes-by-id (get @st/state :shapes-by-id)
        xf (comp
            (map #(get shapes-by-id %))
            (remove :hidden))]
    (html
     [:g attrs
      (for [item (reverse (into [] xf items))
            :let [key (str (:id item))]]
        (-> (factory item)
            (rum/with-key key)))])))

(def group-shape
  (mx/component
   {:render group-shape-render
    :name "group-shape"
    :mixins [mx/static]}))
