;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-9
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.9"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.9"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Alpha 1.9 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/advanced-proto.gif" :border "0" :alt "Advanced interactions"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Prototyping triggers and actions"]]
         [:div.modal-content
          [:p "Prototyping options at last! Different triggers (like mouse events or time delays) and actions allow you to add complexity to the interactions of your prototypes."]
          [:p "Create overlays, back buttons or links to URLs to mimic the behavior of the product you’re designing."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/flows-proto.gif" :border "0" :alt "Multiple flows"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Multiple flows"]]
         [:div.modal-content
          [:p "Design projects usually need to define multiple casuistics for different devices and user journeys."]
          [:p "Flows allow you to define multiple starting points within the same page so you can better organize and present your prototypes."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/booleans.gif" :border "0" :alt "Boolean shapes"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Boolean operations"]]
         [:div.modal-content
          [:p "Now in Penpot you can combine shapes in different ways. There are five options: Union, difference, intersection, exclusion and flatten."]
          [:p "Using boolean operations will lead to countless graphic possibilities for your designs."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     3
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/libraries-feature.gif" :border "0" :alt "Libraries & templates"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Libraries & templates"]]
         [:div.modal-content
          [:p "We’ve created a new space on Penpot where you can share your libraries and templates and download the ones you like.   Material Design, Cocomaterial or Penpot’s Design System are among them (and a lot more to come!)."]
          [:p [:a {:alt "Explore libraries & templates" :target "_blank" :href "https://penpot.app/libraries-templates.html"} "Explore libraries & templates"]]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
