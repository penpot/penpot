;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-14
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.14"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.14"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues to grow with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.14 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.14-shortcuts.gif" :border "0" :alt "Shortcuts panel"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Shortcuts panel"]]
         [:div.modal-content
          [:p "Shortcuts boost your productivity but are not easy to find and learn. A handy panel at your workspace will help you with that."]
          [:p "Categories and filters will help you to find the shortcut you need. One of the most requested features by the community!"]]
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
         [:img {:src "images/features/1.14-color-group.gif" :border "0" :alt "Colors selection"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Colors selection"]]
         [:div.modal-content
          [:p "All of the colors that are contained within a selection of objects are showcased at the sidebar."]
          [:p "Play with the colors of a group without the hassles of individual selection!"]]
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
         [:img {:src "images/features/1.14-fix-on-scroll.gif" :border "0" :alt "Fix elements at scroll"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Fix elements at scroll"]]
         [:div.modal-content
          [:p "A new option that allows you to fix the position of an object when scrolling at the presentation view."]
          [:p "Ideal for prototyping fixed headers, navbars and floating buttons."]]
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
         [:img {:src "images/features/1.14-group-assets.gif" :border "0" :alt "Group library assets with drag & drop"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Group library assets with drag & drop"]]
         [:div.modal-content
          [:p "We have improved the way to manage asset groups at libraries."]
          [:p "Until now you could only do it by renaming the groups, now with drag & drop it is much more user friendly."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
