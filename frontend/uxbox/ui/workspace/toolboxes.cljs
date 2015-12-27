(ns uxbox.ui.workspace.toolboxes
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:staric +draw-tools+
  {:rect
   {:icon i/box
    :help "Box (Ctrl + B)"
    :priority 10}
   :circle
   {:icon i/circle
    :help "Circle (Ctrl + E)"
    :priority 20}
   :line
   {:icon i/line
    :help "Line (Ctrl + L)"
    :priority 30}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tool Box
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn draw-tools-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-state)
        close #(rs/emit! (dw/toggle-toolbox :draw))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))]
    (html
     [:div#form-tools.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span "Tools"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       (for [[key props] tools]
         [:div.tool-btn.tooltip.tooltip-hover
          {:alt (:help props)
           :key (name key)
           :on-click (constantly nil)}
          (:icon props)])]])))

(def ^:static draw-tools
  (util/component
   {:render draw-tools-render
    :name "draw-tools"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn layers-render
  []
  (let [workspace (rum/react wb/workspace-state)
        close #(rs/emit! (dw/toggle-toolbox :layers))]
    (html
     [:div#layers.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/layers]
       [:span "Layers"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.element-list
        #_(for [shape (reverse shapes)]
          (let [{shape-id :shape/uuid
                 selected? :shape/selected?
                 locked? :shape/locked?
                 visible? :shape/visible?
                 raw-shape :shape/data} shape]
            [:li {:key shape-id
                :class (when selected? "selected")}
             [:div.toggle-element
              {:class (when visible? "selected")
               :on-click #(actions/toggle-shape-visibility conn shape-id)} icons/eye]
             [:div.block-element
              {:class (when locked? "selected")
               :on-click #(actions/toggle-shape-lock conn shape-id)} icons/lock]
             [:div.element-icon
              (shapes/icon raw-shape)]
             [:span (shapes/name raw-shape)]]))]]])))

(def ^:static layers
  (util/component
   {:render layers-render
    :name "layers"
    :mixins [rum/reactive]}))

