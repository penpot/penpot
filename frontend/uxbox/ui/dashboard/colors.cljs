(ns uxbox.ui.dashboard.colors
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]
            [uxbox.data.dashboard :as dd]
            [uxbox.ui.dashboard.builtins :as builtins]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static dashboard-state
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ s/state)))

(def ^:static colors-state
  (as-> (l/in [:colors-by-id]) $
    (l/focus-atom $ s/state)))

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
    [:h2 "Colors library name"]
    [:div.edition
     [:span i/pencil]
     [:span i/trash]]]))

(def ^:static page-title
  (util/component
   {:render page-title-render
    :name "page-title"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nav
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nav-render
  [own]
  (let [dashboard (rum/react dashboard-state)
        colors (rum/react colors-state)
        collid (:collection-id dashboard)
        own? (= (:collection-type dashboard) :own)
        builtin? (= (:collection-type dashboard) :builtin)
        collections (if own?
                      (sort-by :id (vals colors))
                      builtins/+colors+)]
    (html
     [:div.library-bar
      [:div.library-bar-inside
       [:ul.library-tabs
        [:li (when builtin? {:class-name "current"})
         "STANDARD"]
        [:li (when own? {:class-name "current"})
         "YOUR LIBRARIES"]]
       [:ul.library-elements
        ;; (when own?
        ;;   [:li
        ;;    [:a.btn-primary {:href "#"} "+ New library"]])
        (for [props collections]
          [:li {:key (str (:id props))
                :on-click #(rs/emit! (dd/set-collection (:id props)))
                :class-name (when (= (:id props) collid) "current")}
           [:span.element-title (:name props)]
           [:span.element-subtitle
            (str (count (:colors props)) " elements")]])]]])))

(def ^:static nav
  (util/component
   {:render nav-render
    :name "nav"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Nav
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own]
  (html
   [:div.dashboard-grid-content
    [:div.grid-item.small-item.add-project
     {on-click #(lightbox/set! :new-color)}
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
      [:div.project-th-icon.delete i/trash]]]]))

(def grid
  (util/component
   {:render grid-render
    :name "colors"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lightbox
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-color-lightbox-render
  [own]
   (html
    [:div.lightbox-body
      [:h3 "New color"]
      [:form
        [:div.row-flex
          [:input#color-hex.input-text
            {:placeholder "#"
            :type "text"}]
          [:input#color-rgb.input-text
            {:placeholder "RGB"
            :type "text"}]]
        [:input#project-btn.btn-primary {:value "+ Add color" :type "submit"}]]
      [:a.close {:href "#"
                :on-click #(do (dom/prevent-default %)
                               (lightbox/close!))}
      i/close]]))

(def new-color-lightbox
  (util/component
   {:render new-color-lightbox-render
    :name "new-color-lightbox"}))

(defmethod lightbox/render-lightbox :new-color
  [_]
  (new-color-lightbox))
