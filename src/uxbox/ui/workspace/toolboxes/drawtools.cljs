(ns uxbox.ui.workspace.toolboxes.drawtools
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:static drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (as-> (l/in [:workspace :drawing]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:staric +draw-tools+
  {:rect
   {:icon i/box
    :help (tr "ds.help.rect")
    :shape {:type :builtin/rect
            :name "Rect"
            :width 20
            :height 20
            :stroke "#000000"
            :stroke-width "1"
            :view-box [0 0 20 20]}
    :priority 10}
   :circle
   {:icon i/circle
    :help (tr "ds.help.circle")
    :shape {:type :builtin/circle
            :name "Circle"
            :width 20
            :height 20
            :stroke "#000000"
            :stroke-width "1"
            :view-box [0 0 20 20]}
    :priority 20}
   :line
   {:icon i/line
    :help (tr "ds.help.line")
    :shape {:type :builtin/line
            :width 20
            :height 20
            :view-box [0 0 20 20]}
    :priority 30}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tool Box
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-for-draw
  [icon]
  (if (= (:drawing @wb/workspace-l) icon)
    (rs/emit! (dw/select-for-drawing nil))
    (rs/emit! (dw/select-for-drawing icon))))

(defn draw-tools-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-l)
        drawing (rum/react drawing-shape)
        close #(rs/emit! (dw/toggle-toolbox :draw))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))]
    (html
     [:div#form-tools.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span (tr "ds.tools")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       (for [[key props] tools
             :let [selected? (= drawing (:shape props))]]
         [:div.tool-btn.tooltip.tooltip-hover
          {:alt (:help props)
           :class (when selected? "selected")
           :key (name key)
           :on-click (partial select-for-draw (:shape props))}
          (:icon props)])]])))

(def ^:static draw-toolbox
  (mx/component
   {:render draw-tools-render
    :name "draw-tools"
    :mixins [mx/static rum/reactive]}))
