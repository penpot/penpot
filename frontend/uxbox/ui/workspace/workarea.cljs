(ns uxbox.ui.workspace.workarea
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]
            [uxbox.ui.workspace.canvas :refer (canvas)]
            [uxbox.ui.workspace.grid :refer (grid)]
            [uxbox.ui.workspace.base :as wb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Viewport
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn viewport-render
  []
  (let [workspace (rum/react wb/workspace-state)
        drawing? (:drawing workspace)
        zoom 1]
    (html
     [:svg.viewport {:width wb/viewport-height
                     :height wb/viewport-width
                     :class (when drawing? "drawing")}
      [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
       (canvas)
       (grid zoom)]])))

(def viewport
  (util/component
   {:render viewport-render
    :name "viewport"
    :mixins [rum/reactive]}))
