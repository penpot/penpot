(ns uxbox.ui.dashboard.icons
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn menu-render
  []
  (let [pcount 20]
    (html
     [:section#dashboard-bar.dashboard-bar
      [:div.dashboard-info
       [:span.dashboard-projects pcount " projects"]
       [:span "Sort by"]]
      [:div.dashboard-search i/search]])))

(def ^:static menu
  (util/component
   {:render menu-render
    :name "icons-menu"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Title
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-title-render
  []
  (html
   [:div.dashboard-title
    [:h2 "Icons library name"]
    [:div.edition
     [:span i/pencil]
     [:span i/trash]]]))

(def ^:static page-title
  (util/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (html
   [:div.dashboard-grid-content
    [:div.grid-item.small-item.add-project
     {on-click #(lightbox/open! :new-icon)}
     [:span "+ New icon"]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/logo-icon]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/pencil]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/trash]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/search]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/image]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/toggle]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/chat]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/close]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/page]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/folder]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/infocard]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/fill]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/stroke]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/action]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/undo]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/redo]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/export]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/exit]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]
    [:div.grid-item.small-item.project-th
     [:span.grid-item-image i/user]
     [:h3 "Custom icon"]
     [:div.project-th-actions
      [:div.project-th-icon.edit i/pencil]
      [:div.project-th-icon.delete i/trash]]]]))

(def grid
  (util/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-icon-lightbox-render
  [own]
  (html
   [:div.lightbox-body
    [:h3 "New icon"]
    [:div.row-flex
     [:div.lightbox-big-btn
      [:span.big-svg i/shapes]
      [:span.text "Go to workspace"]
      ]
     [:div.lightbox-big-btn
      [:span.big-svg.upload i/exit]
      [:span.text "Upload file"]
      ]
     ]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (lightbox/close!))}
     i/close]]))

(def new-icon-lightbox
  (util/component
   {:render new-icon-lightbox-render
    :name "new-icon-lightbox"}))
