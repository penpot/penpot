;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.shapes.group
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.shapes.common :as common]
            [uxbox.ui.shapes.attrs :as attrs]
            [uxbox.ui.shapes.icon :as icon]
            [uxbox.ui.shapes.rect :as rect]
            [uxbox.ui.shapes.circle :as circle]
            [uxbox.ui.shapes.text :as text]
            [uxbox.ui.shapes.line :as line]
            [uxbox.util.geom :as geom]))

;; --- Helpers

(declare group-component)

(defn render-component
  [{:keys [type] :as shape}]
  (case type
    :group (group-component shape)
    :text (text/text-component shape)
    :line (line/line-component shape)
    :icon (icon/icon-component shape)
    :rect (rect/rect-component shape)
    :circle (circle/circle-component shape)))

;; --- Group Component

(declare group-shape)

(defn- group-component-render
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
      (group-shape shape render-component)])))

(def group-component
  (mx/component
   {:render group-component-render
    :name "group-component"
    :mixins [mx/static rum/reactive]}))

;; --- Group Shape

(defn- group-shape-render
  [own {:keys [items id dx dy rotation] :as shape} factory]
  (let [key (str "group-" id)
        rfm (geom/transformation-matrix shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (attrs/extract-style-attrs shape)
                     (attrs/make-debug-attrs shape))
        shapes-by-id (get @st/state :shapes-by-id)
        xf (comp
            (map #(get shapes-by-id %))
            (remove :hidden)
            (map factory))]
    (html
     [:g attrs
      (for [item (reverse (into [] xf items))]
        (rum/with-key item (str (:id item))))])))
