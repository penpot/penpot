(ns uxbox.ui.workspace.workarea
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.canvas :refer (canvas)]
            [uxbox.ui.workspace.grid :refer (grid)]
            [uxbox.ui.workspace.base :as wb]))

;; TODO: implement as streams

(defn- on-click
  [event wstate]
  (let [mousepos @wb/mouse-position
        shape (:drawing wstate)]
    (when shape
      (let [props {:x (first mousepos)
                   :y (second mousepos)
                   :width 100
                   :height 100}]
        (rs/emit!
         (dw/add-shape shape props)
         (dw/select-for-drawing nil))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Viewport Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn viewport-render
  [own]
  (let [workspace (rum/react wb/workspace-state)
        page (rum/react wb/page-state)
        drawing? (:drawing workspace)
        zoom 1]
    (html
     [:svg.viewport {:width wb/viewport-height
                     :height wb/viewport-width
                     :on-click #(on-click % workspace)
                     :class (when drawing? "drawing")}
      [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
       (if page
         (canvas page))]])))

(def viewport
  (util/component
   {:render viewport-render
    :name "viewport"
    :mixins [rum/reactive]}))
