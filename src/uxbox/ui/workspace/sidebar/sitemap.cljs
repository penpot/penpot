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
       [:div.tool-window-icon i/project-tree]
       [:span (tr "ds.sitemap")]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:div.project-title
        [:span "Project name"]
        [:div.add-page i/close]]
       [:ul.element-list
        (for [i (range 10)]
          [:li {:key i :class (when (= i 2) "selected")}
           [:div.page-icon i/page]
           [:span (str "Page " i)]
           [:div.page-actions
            [:a i/pencil]
            [:a i/trash]]])
        ]]])))

(def ^:static sitemap-toolbox
  (mx/component
   {:render sitemap-toolbox-render
    :name "sitemap-toolbox"
    :mixins [mx/static rum/reactive]}))
