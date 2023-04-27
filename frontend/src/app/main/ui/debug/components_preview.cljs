;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.debug.components-preview
  (:require-macros [app.main.style :refer [css styles]])
  (:require [app.common.data :as d]
            [app.main.data.users :as du]
            [app.main.refs :as refs]
            [app.main.store :as st]
            [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
            [app.main.ui.components.search-bar :refer [search-bar]]
            [app.main.ui.components.tab-container :refer [tab-container tab-element]]
            [app.main.ui.components.title-bar :refer [title-bar]]
            [app.main.ui.icons :as i]
            [app.util.dom :as dom]
            [rumext.v2 :as mf]))

(mf/defc component-wrapper
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        title    (unchecked-get props "title")]
    [:div {:class (dom/classnames (css :component) true)}
     [:h4 {:class (dom/classnames (css :component-name) true)} title]
     children]))

(mf/defc components-preview
  {::mf/wrap-props false}
  []
  (let [profile (mf/deref refs/profile)
        initial (mf/with-memo [profile]
                  (update profile :lang #(or % "")))
        initial-theme (:theme initial)
        on-change (fn [event]
                    (let [theme (dom/event->value event)
                          data (assoc initial :theme theme)]
                      (st/emit! (du/update-profile data))))
        colors [:bg-primary
                :bg-secondary
                :bg-tertiary
                :bg-cuaternary
                :fg-primary
                :fg-secondary
                :acc
                :acc-muted
                :acc-secondary
                :acc-tertiary]

        ;; COMPONENTS FNs
        state* (mf/use-state {:collapsed? true
                              :tab-selected :first
                              :input-value ""
                              :radio-selected "first"})
        state  (deref state*)

        collapsed? (:collapsed? state)
        toggle-collapsed
        (mf/use-fn #(swap! state* update :collapsed? not))

        tab-selected (:tab-selected state)
        set-tab      (mf/use-fn #(swap! state* assoc :tab-selected %))

        input-value  (:input-value state)
        radio-selected (:radio-selected state)

        set-radio-selected (mf/use-fn #(swap! state* assoc :radio-selected %))

        update-search
        (mf/use-fn
         (fn [value _event]
           (swap! state* assoc :input-value value)))


        on-btn-click (mf/use-fn #(prn "eyy"))]

    [:section.debug-components-preview
     [:div {:class (dom/classnames (css :themes-row) true)}
      [:h2 "Themes"]
      [:select {:label "Select theme color"
                :name :theme
                :default "default"
                :value initial-theme
                :on-change on-change}
       [:option {:label "Penpot Dark (default)" :value "default"}]
       [:option  {:label "Penpot Light" :value "light"}]]
      [:div {:class (dom/classnames (css :wrapper) true)}
       (let [css (styles)]
         (for [color colors]
           [:div {:key color
                  :class (dom/classnames (get css color) true
                                         (get css :rect) true)}
            (d/name color)]))]]
     [:div {:class (dom/classnames (css :components-row) true)}
      [:h2 {:class (dom/classnames (css :title) true)} "Components"]
      [:div {:class (dom/classnames (css :components-wrapper) true)}
       [:div {:class (dom/classnames (css :component-group) true)}
        [:h3 "Titles"]
        [:& component-wrapper
         {:title "Title"}
         [:& title-bar {:collapsable? false
                        :title        "Title"}]]
        [:& component-wrapper
         {:title  "Title and action button"}
         [:& title-bar {:collapsable? false
                        :title        "Title"
                        :on-btn-click on-btn-click
                        :btn-children i/add-refactor}]]
        [:& component-wrapper
         {:title "Collapsed title and action button"}
         [:& title-bar {:collapsable? true
                        :collapsed?   collapsed?
                        :on-collapsed  toggle-collapsed
                        :title        "Title"
                        :on-btn-click on-btn-click
                        :btn-children i/add-refactor}]]
        [:& component-wrapper
         {:title "Collapsed title and children"}
         [:& title-bar {:collapsable? true
                        :collapsed?   collapsed?
                        :on-collapsed  toggle-collapsed
                        :title        "Title"}
          [:& tab-container {:on-change-tab set-tab
                             :selected tab-selected}
           [:& tab-element {:id :first
                            :title "A tab"}]
           [:& tab-element {:id :second
                            :title "B tab"}]]]]]

       [:div {:class (dom/classnames (css :component-group) true)}
        [:h3 "Tabs component"]
        [:& component-wrapper
         {:title "2 tab component"}
         [:& tab-container {:on-change-tab set-tab
                            :selected tab-selected}
          [:& tab-element {:id :first :title "First tab"}
           [:div "This is first tab content"]]

          [:& tab-element {:id :second :title "Second tab"}
           [:div "This is second tab content"]]]]
        [:& component-wrapper
         {:title "3 tab component"}
         [:& tab-container {:on-change-tab set-tab
                            :selected tab-selected}
          [:& tab-element {:id :first :title "First tab"}
           [:div "This is first tab content"]]

          [:& tab-element {:id :second
                           :title "Second tab"}
           [:div "This is second tab content"]]
          [:& tab-element {:id :third
                           :title "Third tab"}
           [:div "This is third tab content"]]]]]

       [:div {:class (dom/classnames (css :component-group) true)}
        [:h3 "Search bar"]
        [:& component-wrapper
         {:title "Search bar only"}
         [:& search-bar {:on-change update-search
                         :value input-value
                         :placeholder "Test value"}]]
        [:& component-wrapper
         {:title "Search and button"}
         [:& search-bar {:on-change update-search
                         :value input-value
                         :placeholder "Test value"}
          [:button {:class (dom/classnames (css :test-button) true)
                    :on-click on-btn-click}
           "X"]]]]

       [:div {:class (dom/classnames (css :component-group) true)}
        [:h3 "Radio buttons"]
        [:& component-wrapper
         {:title "Two radio buttons (toggle)"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon (mf/html i/view-as-list-refactor)
                            :value "first"
                            :id :list}]
          [:& radio-button {:icon (mf/html i/flex-grid-refactor)
                            :value "second"
                            :id :grid}]]]
        [:& component-wrapper
         {:title "Three radio buttons"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon (mf/html i/view-as-list-refactor)
                            :value "first"
                            :id :first}]
          [:& radio-button {:icon (mf/html i/flex-grid-refactor)
                            :value "second"
                            :id :second}]

          [:& radio-button {:icon (mf/html i/add-refactor)
                            :value "third"
                            :id :third}]]]

        [:& component-wrapper
         {:title "Four radio buttons"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon (mf/html i/view-as-list-refactor)
                            :value "first"
                            :id :first}]
          [:& radio-button {:icon (mf/html i/flex-grid-refactor)
                            :value "second"
                            :id :second}]

          [:& radio-button {:icon (mf/html i/add-refactor)
                            :value "third"
                            :id :third}]

          [:& radio-button {:icon (mf/html i/board-refactor)
                            :value "forth"
                            :id :forth}]]]]]]]))
