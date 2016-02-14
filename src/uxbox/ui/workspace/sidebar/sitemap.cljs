(ns uxbox.ui.workspace.sidebar.sitemap
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
            [uxbox.util.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sitemap-toolbox-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-l)
        close #(rs/emit! (dw/toggle-flag :sitemap))]
    (html
     [:div.sitemap.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span (tr "ds.sitemap")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content {:style {:color "white"}}
       [:div "Project name"]
       [:ul.element-list
        (for [i (range 10)]
          [:li {:key i :class (when (= i 2) "selected")}
           (str "Page " i)])
        ]]])))

(def ^:static sitemap-toolbox
  (mx/component
   {:render sitemap-toolbox-render
    :name "sitemap-toolbox"
    :mixins [mx/static rum/reactive]}))

