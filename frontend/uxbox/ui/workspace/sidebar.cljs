(ns uxbox.ui.workspace.sidebar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.toolboxes :as toolboxes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aside
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn aside-render
  [own]
  (let [workspace (rum/react wb/workspace-state)]
    (html
     [:aside#settings-bar.settings-bar
      [:div.settings-bar-inside
       (when (:draw-toolbox-enabled workspace false)
         (toolboxes/draw-tools))
       (when (:icons-toolbox-enabled workspace false)
         (toolboxes/icons))
       (when (:layers-toolbox-enabled workspace false)
         (toolboxes/layers))]])))

(def aside
  (util/component
   {:render aside-render
    :name "aside"
    :mixins [rum/reactive]}))

