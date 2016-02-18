(ns uxbox.ui.workspace.sidebar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.sidebar.options :refer (options-toolbox)]
            [uxbox.ui.workspace.sidebar.layers :refer (layers-toolbox)]
            [uxbox.ui.workspace.sidebar.sitemap :refer (sitemap-toolbox)]
            [uxbox.ui.workspace.sidebar.icons :refer (icons-toolbox)]
            [uxbox.ui.workspace.sidebar.drawtools :refer (draw-toolbox)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Left Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn left-sidebar-render
  [own]
  (let [flags (rum/react wb/flags-l)]
    (html
     [:aside#settings-bar.settings-bar.settings-bar-left
      [:div.settings-bar-inside
       (when (contains? flags :sitemap)
         (sitemap-toolbox))
       (when (contains? flags :layers)
         (layers-toolbox))]])))

(def left-sidebar
  (mx/component
   {:render left-sidebar-render
    :name "left-sidebar"
    :mixins [rum/reactive mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Right Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn right-sidebar-render
  [own]
  (let [flags (rum/react wb/flags-l)]
    (html
     [:aside#settings-bar.settings-bar
      [:div.settings-bar-inside
       (when (contains? flags :drawtools)
         (draw-toolbox))
       (when (contains? flags :element-options)
         (options-toolbox))
       (when (contains? flags :icons)
         (icons-toolbox))]])))

(def right-sidebar
  (mx/component
   {:render right-sidebar-render
    :name "right-sidebar"
    :mixins [rum/reactive mx/static]}))
