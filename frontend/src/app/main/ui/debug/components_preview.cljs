;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.debug.components-preview
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
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
    [:div {:class (stl/css :component)}
     [:h4 {:class (stl/css :component-name)} title]
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
        colors ["var(--color-background-primary)"
                "var(--color-background-secondary)"
                "var(--color-background-tertiary)"
                "var(--color-background-quaternary)"
                "var(--color-foreground-primary)"
                "var(--color-foreground-secondary)"
                "var(--color-accent-primary)"
                "var(--color-accent-primary-muted)"
                "var(--color-accent-secondary)"
                "var(--color-accent-tertiary)"]

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
     [:div {:class (stl/css :themes-row)}
      [:h2 "Themes"]
      [:select {:label "Select theme color"
                :name :theme
                :default "default"
                :value initial-theme
                :on-change on-change}
       [:option {:label "Penpot Dark (default)" :value "default"}]
       [:option  {:label "Penpot Light" :value "light"}]]
      [:div {:class (stl/css :wrapper)}
       (for [color colors]
         [:div {:class (stl/css :color-wrapper)}
          [:span (d/name color)]
          [:div {:key color
                 :style {:background color}
                 :class (stl/css :rect)}]])]]

     [:div {:class (stl/css :components-row)}
      [:h2 {:class (stl/css :title)} "Components"]
      [:div {:class (stl/css :components-wrapper)}
       [:div {:class (stl/css :components-group)}
        [:h3 "Titles"]
        [:& component-wrapper
         {:title "Title"}
         [:& title-bar {:collapsable false
                        :title       "Title"}]]
        [:& component-wrapper
         {:title  "Title and action button"}
         [:& title-bar {:collapsable  false
                        :title        "Title"
                        :on-btn-click on-btn-click
                        :btn-children i/add}]]
        [:& component-wrapper
         {:title "Collapsed title and action button"}
         [:& title-bar {:collapsable  true
                        :collapsed    collapsed?
                        :on-collapsed  toggle-collapsed
                        :title        "Title"
                        :on-btn-click on-btn-click
                        :btn-children i/add}]]
        [:& component-wrapper
         {:title "Collapsed title and children"}
         [:& title-bar {:collapsable  true
                        :collapsed    collapsed?
                        :on-collapsed  toggle-collapsed
                        :title        "Title"}
          [:& tab-container {:on-change-tab set-tab
                             :selected tab-selected}
           [:& tab-element {:id :first
                            :title "A tab"}]
           [:& tab-element {:id :second
                            :title "B tab"}]]]]]

       [:div {:class (stl/css :components-group)}
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

       [:div {:class (stl/css :components-group)}
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
          [:button {:class (stl/css :button-secondary)
                    :on-click on-btn-click}
           "X"]]]]

       [:div {:class (stl/css :components-group)}
        [:h3 "Radio buttons"]
        [:& component-wrapper
         {:title "Two radio buttons (toggle)"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon i/view-as-list
                            :value "first"
                            :id :list}]
          [:& radio-button {:icon i/flex-grid
                            :value "second"
                            :id :grid}]]]
        [:& component-wrapper
         {:title "Three radio buttons"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon i/view-as-list
                            :value "first"
                            :id :first}]
          [:& radio-button {:icon i/flex-grid
                            :value "second"
                            :id :second}]

          [:& radio-button {:icon i/add
                            :value "third"
                            :id :third}]]]

        [:& component-wrapper
         {:title "Four radio buttons"}
         [:& radio-buttons {:selected radio-selected
                            :on-change set-radio-selected
                            :name "listing-style"}
          [:& radio-button {:icon i/view-as-list
                            :value "first"
                            :id :first}]
          [:& radio-button {:icon i/flex-grid
                            :value "second"
                            :id :second}]

          [:& radio-button {:icon i/add
                            :value "third"
                            :id :third}]

          [:& radio-button {:icon i/board
                            :value "forth"
                            :id :forth}]]]]
       [:div {:class (stl/css :components-group)}
        [:h3 "Buttons"]
        [:& component-wrapper
         {:title "Button primary"}
         [:button  {:class (stl/css :button-primary)}
          "Primary"]]
        [:& component-wrapper
         {:title "Button primary with icon"}
         [:button  {:class (stl/css :button-primary)}
          i/add]]

        [:& component-wrapper
         {:title "Button secondary"}
         [:button  {:class (stl/css :button-secondary)}
          "secondary"]]
        [:& component-wrapper
         {:title "Button secondary with icon"}
         [:button  {:class (stl/css :button-secondary)}
          i/add]]

        [:& component-wrapper
         {:title "Button tertiary"}
         [:button  {:class (stl/css :button-tertiary)}
          "tertiary"]]
        [:& component-wrapper
         {:title "Button tertiary with icon"}
         [:button  {:class (stl/css :button-tertiary)}
          i/add]]]
       [:div {:class (stl/css :components-group)}
        [:h3 "Inputs"]
        [:& component-wrapper
         {:title "Only input"}
         [:div {:class (stl/css :input-wrapper)}
          [:input  {:class (stl/css :basic-input)
                    :placeholder "----"}]]]
        [:& component-wrapper
         {:title "Input with label"}
         [:div {:class (stl/css :input-wrapper)}
          [:span {:class (stl/css :input-label)} "label"]
          [:input  {:class (stl/css :basic-input)
                    :placeholder "----"}]]]
        [:& component-wrapper
         {:title "Input with icon"}
         [:div {:class (stl/css :input-wrapper)}
          [:span {:class (stl/css :input-label)}
           i/add]
          [:input  {:class (stl/css :basic-input)
                    :placeholder "----"}]]]]]]]))
