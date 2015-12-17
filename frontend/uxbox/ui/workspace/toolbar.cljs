(ns uxbox.ui.workspace.toolbar
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.ui.icons :as i]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Toolbar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static toolbar-state
  (as-> (l/in [:workspace :toolbars]) $
    (l/focus-atom $ s/state)))

(defn- toggle-toolbox
  [state item]
  (update state item (fnil not false)))

(defn toolbar-render
  [own]
  (let [state (rum/react toolbar-state)]
    (html
     [:div#tool-bar.tool-bar
      [:div.tool-bar-inside
       [:ul.main-tools
        [:li.tooltip
         {:alt "Shapes (Ctrl + Shift + F)"
          :class (when (:tools state) "current")
          :on-click #(swap! toolbar-state toggle-toolbox :tools)}
         i/shapes]
        [:li.tooltip
         {:alt "Icons (Ctrl + Shift + I)"
          :class (when (:icons state) "current")
          :on-click #(swap! toolbar-state toggle-toolbox :icons)}
         i/icon-set]
        [:li.tooltip
         {:alt "Elements (Ctrl + Shift + L)"
          :class (when (:layers state)
                   "current")
          :on-click #(swap! toolbar-state toggle-toolbox :layers)}
         i/layers]
        [:li.tooltip
         {:alt "Feedback (Ctrl + Shift + M)"}
         i/chat]]]])))

(def ^:static toolbar
  (util/component
   {:render toolbar-render
    :name "toolbar"
    :mixins [rum/reactive]}))
