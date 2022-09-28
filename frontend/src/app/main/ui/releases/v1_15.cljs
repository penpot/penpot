;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-15
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.15"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.15"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues to grow with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.15 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.15-nested-boards.gif" :border "0" :alt "Nested boards"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Nested boards"]]
         [:div.modal-content
          [:p "Unlike its predecessors (the artboards) boards can contain other boards and offer the options to clip content and show them or not at the View Mode, opening up a ton of possibilities when creating and organizing your designs."]
          [:p "Say goodbye to Artboards and hello to Boards!"]]
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
         [:img {:src "images/features/1.15-share.gif" :border "0" :alt "Share prototype options"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Share prototype options"]]
         [:div.modal-content
          [:p "Have you ever wanted to share a Penpot file and get feedback from people that are not in your Penpot team?"]
          [:p "Now you can thanks to new permissions that allow you to decide who can comment and/or inspect the code at a shared prototype link."]]
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
         [:img {:src "images/features/1.15-comments.gif" :border "0" :alt "Comments positioning"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Comments poitioning"]]
         [:div.modal-content
          [:p "They live! Now you can move existing comments wherever you want by dragging them."]
          [:p "Also, comments inside boards will be associated with it, so that if you move a board its comments will maintain its place inside it."]]
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
         [:img {:src "images/features/1.15-view-mode.gif" :border "0" :alt "View Mode improvements"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "View Mode improvements"]]
         [:div.modal-content
          [:p "The View Mode, used for presenting designs, is now easier to use thanks to new navigation buttons and microinteractions."]
          [:p "We’ve also made some adjustments to ensure the access to the options from  small screens."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
