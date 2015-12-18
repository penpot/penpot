(ns uxbox.ui.workspace.lateralmenu
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lateral Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- toggle-toolbox
  [state item]
  (update state item (fnil not false)))

(defn lateralmenu-render
  [own]
  (let [workspace (rum/react wb/workspace-state)
        toggle #(rs/emit! (dw/toggle-toolbox %))]
    (html
     [:div#tool-bar.tool-bar
      [:div.tool-bar-inside
       [:ul.main-tools
        [:li.tooltip
         {:alt "Shapes (Ctrl + Shift + F)"
          :class (when (:tools-enabled workspace false) "current")
          :on-click (partial toggle :tools)}
         i/shapes]
        [:li.tooltip
         {:alt "Icons (Ctrl + Shift + I)"
          :class (when (:icons-enabled workspace false) "current")
          :on-click (partial toggle :icons)}
         i/icon-set]
        [:li.tooltip
         {:alt "Elements (Ctrl + Shift + L)"
          :class (when (:layers-enabled workspace false) "current")
          :on-click (partial toggle :layers)}
         i/layers]
        [:li.tooltip
         {:alt "Feedback (Ctrl + Shift + M)"}
         i/chat]]]])))

(def ^:static lateralmenu
  (util/component
   {:render lateralmenu-render
    :name "lateralmenu"
    :mixins [rum/reactive]}))
