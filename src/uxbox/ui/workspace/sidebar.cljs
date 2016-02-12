(ns uxbox.ui.workspace.sidebar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.sidebar.layers :refer (layers-toolbox)]
            [uxbox.ui.workspace.sidebar.icons :refer (icons-toolbox)]
            [uxbox.ui.workspace.sidebar.drawtools :refer (draw-toolbox)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Right Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn right-sidebar-render
  [own]
  (let [toolboxes (rum/react wb/toolboxes-l)]
    (html
     [:aside#settings-bar.settings-bar
      [:div.settings-bar-inside
       (when (contains? toolboxes :draw)
         (draw-toolbox))
       (when (contains? toolboxes :icons)
         (icons-toolbox))
       (when (contains? toolboxes :layers)
         (layers-toolbox))]])))

(def right-sidebar
  (mx/component
   {:render right-sidebar-render
    :name "aside"
    :mixins [rum/reactive mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Left Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn left-sidebar-render
  [own]
  (let [toolboxes (rum/react wb/toolboxes-l)]
    (html
     [:aside#settings-bar.settings-bar.settings-bar-left
      [:div.settings-bar-inside
       (when (contains? toolboxes :draw)
         (draw-toolbox))
       (when (contains? toolboxes :icons)
         (icons-toolbox))
       (when (contains? toolboxes :layers)
         (layers-toolbox))]])))

(def left-sidebar
  (mx/component
   {:render left-sidebar-render
    :name "aside"
    :mixins [rum/reactive mx/static]}))
