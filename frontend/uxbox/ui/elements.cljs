(ns uxbox.ui.elements
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.header :as ui.header]
            [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.icons :as icons]
            [uxbox.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Elements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elements-render
  [own]
  (html
   [:main.dashboard-main
    (ui.header/header)
    [:section.dashboard-content
      [:section#dashboard-bar.dashboard-bar.library-gap
        [:div.dashboard-info
          [:span.dashboard-projects "20 elements"]
          [:span "Sort by"]
            #_(project-sort-selector (atom :name))]
          [:div.dashboard-search
            icons/search]]
      (ui.library-bar/library-bar)
      [:section.dashboard-grid.library
        [:div.dashboard-title
          [:h2 "Library name"]
          [:div.edition
            [:span icons/pencil]
            [:span icons/trash]
          ]
        ]
        [:div.dashboard-grid-content
          [:div.grid-item.add-project
            [:span "+ New element"]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image icons/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit icons/pencil]
              [:div.project-th-icon.delete icons/trash]]]
        ]
      ]
    ]
   ]))

(def elements
  (util/component
   {:render elements-render
    :name "elements"
    :mixins [rum/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn icons-render
  [own]
  (html
   [:p "hello icons"]))

(def icons
  (util/component
   {:render icons-render
    :name "icons"
    :mixins [rum/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Colors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn colors-render
  [own]
  (html
   [:p "hello colors"]))

(def colors
  (util/component
   {:render colors-render
    :name "colors"
    :mixins [rum/static]}))
