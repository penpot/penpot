(ns uxbox.ui.elements
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.header :as ui.header]
            [uxbox.ui.library-bar :as ui.library-bar]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.dom :as dom]
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
            i/search]]
      (ui.library-bar/library-bar)
      [:section.dashboard-grid.library
        [:div.dashboard-title
          [:h2 "Element library name"]
          [:div.edition
            [:span i/pencil]
            [:span i/trash]
          ]
        ]
        [:div.dashboard-grid-content
          [:div.grid-item.add-project
            {on-click #(lightbox/set! :new-element)}
            [:span "+ New element"]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.project-th
            [:span.grid-item-image i/image]
            [:h3 "Custom element"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
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
   [:main.dashboard-main
    (ui.header/header)
    [:section.dashboard-content
      [:section#dashboard-bar.dashboard-bar.library-gap
        [:div.dashboard-info
          [:span.dashboard-projects "20 icons"]
          [:span "Sort by"]
            #_(project-sort-selector (atom :name))]
          [:div.dashboard-search
            i/search]]
      (ui.library-bar/library-bar)
      [:section.dashboard-grid.library
        [:div.dashboard-title
          [:h2 "Icon library name"]
          [:div.edition
            [:span i/pencil]
            [:span i/trash]
          ]
        ]
        [:div.dashboard-grid-content
          [:div.grid-item.small-item.add-project
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
              [:div.project-th-icon.delete i/trash]]]
        ]
      ]
    ]
   ]))

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
   [:main.dashboard-main
    (ui.header/header)
    [:section.dashboard-content
      [:section#dashboard-bar.dashboard-bar.library-gap
        [:div.dashboard-info
          [:span.dashboard-projects "20 colors"]
          [:span "Sort by"]
            #_(project-sort-selector (atom :name))]
          [:div.dashboard-search
            i/search]]
      (ui.library-bar/library-bar)
      [:section.dashboard-grid.library
        [:div.dashboard-title
          [:h2 "Colors library name"]
          [:div.edition
            [:span i/pencil]
            [:span i/trash]
          ]
        ]
        [:div.dashboard-grid-content
          [:div.grid-item.small-item.add-project
            [:span "+ New color"]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#81dadd"}}]
            [:span.color-data "#00f9ff"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#6eafd6"}}]
            [:span.color-data "#009fff"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#0078ff"}}]
            [:span.color-data "#0078ff"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#005eff"}}]
            [:span.color-data "#005eff"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#0900ff"}}]
            [:span.color-data "#0900ff"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#7502f1"}}]
            [:span.color-data "#7502f1"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#ffe705"}}]
            [:span.color-data "#ffe705"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#00ffab"}}]
            [:span.color-data "#00ffab"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#d56c5e"}}]
            [:span.color-data "#f52105"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#ae80df"}}]
            [:span.color-data "#7502f1"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#e7ba64"}}]
            [:span.color-data "#ffe705"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#73c2a8"}}]
            [:span.color-data "#00ffab"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
          [:div.grid-item.small-item.project-th
            [:span.color-swatch {:style {:background-color "#f52105"}}]
            [:span.color-data "#f52105"]
            [:span.color-data "RGB 31,31,31"]
            [:div.project-th-actions
              [:div.project-th-icon.edit i/pencil]
              [:div.project-th-icon.delete i/trash]]]
        ]
      ]
    ]
   ]))

(def colors
  (util/component
   {:render colors-render
    :name "colors"
    :mixins [rum/static]}))

;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;
(defn- new-element-lightbox-render
  [own]

   (html
    [:div.lightbox-body
      [:h3 "New element"]
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

(def new-element-lightbox
  (util/component
   {:render new-element-lightbox-render
    :name "new-element-lightbox"}))

(defmethod lightbox/render-lightbox :new-element
  [_]
  (new-element-lightbox))
