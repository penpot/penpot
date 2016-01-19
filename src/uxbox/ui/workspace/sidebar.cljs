(ns uxbox.ui.workspace.sidebar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as st]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.toolboxes.layers :refer (layers-toolbox)]
            [uxbox.ui.workspace.toolboxes.icons :refer (icons-toolbox)]
            [uxbox.ui.workspace.toolboxes.drawtools :refer (draw-toolbox)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aside
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn aside-render
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

(def aside
  (mx/component
   {:render aside-render
    :name "aside"
    :mixins [rum/reactive mx/static]}))

