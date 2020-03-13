;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.library
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.main.ui.dashboard.components.context-menu :refer [context-menu]]))

(mf/defc library-header
  [{:keys [profile] :as props}]
  (let [locale (i18n/use-locale)]
    [:header#main-bar.main-bar
     [:h1.dashboard-title "Libraries"]
     [:nav.library-header-navigation
      [:a.library-header-navigation-item "Icons"]
      [:a.library-header-navigation-item "Images"]
      [:a.library-header-navigation-item "Colors"]]]))

(mf/defc library-sidebar
  []
  [:aside.library-sidebar
   [:button.library-sidebar-add-item
    {:type "button"}
    "+ New icon library"]
   [:ul.library-sidebar-list
    [:li.library-sidebar-list-element [:a "Ecometer"]]
    [:li.library-sidebar-list-element [:a "Tipi"]]
    [:li.library-sidebar-list-element [:a "Taiga (inspirational)"]]
    [:li.library-sidebar-list-element [:a "DKT photo assets"]]]])

(mf/defc library-top-menu
  []
  (let [state (mf/use-state {:is-open false})]
    [:header.library-top-menu
     [:div.library-top-menu-current-element
      [:h2.library-top-menu-current-element-name "UXBOX"]
      [:a.library-top-menu-current-action
       { :on-click #(swap! state update :is-open not)}
       [:span i/arrow-down]]
      [:& context-menu {:is-open (:is-open @state)
                        :options [["Rename" #(println "Rename")]
                                  ["Delete" #(println "Delete")]]}]]

     [:div.library-top-menu-actions
      [:a i/trash]
      [:a.btn-dashboard "+ New icon"]]]))

(mf/defc library-icon-card []
  (let [state (mf/use-state {:is-open false})]
    [:div.library-card.library-icon
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id "card"
               :on-change #(println "toggle-selection")
               #_(:checked false)}]
      [:label {:for "card"}]]
     [:div.library-card-image i/trash]
     [:div.library-card-footer
      [:div.library-card-footer-name "my-dear-icon.svg"]
      [:div.library-card-footer-timestamp "Less than 5 seconds ago"]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu {:is-open (:is-open @state)
                        :options [["Delete" #(println "Delete")]]}]]]))

(mf/defc library-image-card []
  (let [state (mf/use-state {:is-open false})]
    [:div.library-card.library-image
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id "card"
               :on-change #(println "toggle-selection")
               #_(:checked false)}]
      [:label {:for "card"}]]
     [:div.library-card-image
      [:img {:src "https://www.placecage.com/200/200"}]]
     [:div.library-card-footer
      [:div.library-card-footer-name "my-dear-icon.svg"]
      [:div.library-card-footer-timestamp "Less than 5 seconds ago"]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu {:is-open (:is-open @state)
                        :options [["Delete" #(println "Delete")]]}]]]))

(mf/defc library-color-card []
  (let [state (mf/use-state {:is-open false})]
    [:div.library-card.library-color
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id "card"
               :on-change #(println "toggle-selection")
               #_(:checked false)}]
      [:label {:for "card"}]]
     [:div.library-card-image
      { :style { :background-color "#9B78FF" }}]
     [:div.library-card-footer
      #_[:*
       [:div.library-card-footer-name "my-dear-icon.svg"]
       [:div.library-card-footer-timestamp "Less than 5 seconds ago"]]
      [:*
       [:div.library-card-footer-name "#9B78FF"]
       [:div.library-card-footer-color
        [:span.library-card-footer-color-label "RGB"]
        [:span.library-card-footer-color-rgb "128, 128, 128"]]]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu {:is-open (:is-open @state)
                        :options [["Delete" #(println "Delete")]]}]]]))

(mf/defc library-page
  [{:keys [team-id]}]
  [:div.library-page
   [:& library-header]
   [:& library-sidebar]
   [:section.library-content
    [:& library-top-menu]
    [:div.library-page-cards-container
     (for [_ (range 0 10)]
       #_[:& library-icon-card]
       #_[:& library-image-card]
       [:& library-color-card])]]])
