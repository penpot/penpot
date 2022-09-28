;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-11
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.11"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.11"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.11 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.11-animations.gif" :border "0" :alt "Animations"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Prototype animations"]]
         [:div.modal-content
          [:p "Bring your prototypes to life with animations! With animations now you can define the transition between artboards when an interaction is triggered."]
          [:p "Use dissolve, slide and push animations to fade screens and imitate gestures like swipe."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.11-bg-export.gif" :border "0" :alt "Ignore background on export"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Ignore artboard background on export"]]
         [:div.modal-content
          [:p "Sometimes you don’t need the artboards to be part of your designs, but only their support to work on them."]
          [:p "Now you can decide to include their backgrounds on your exports or leave them out."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.11-zoom-widget.gif" :border "0" :alt "New zoom widget"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New zoom widget"]]
         [:div.modal-content
          [:p "We’ve redesigned zooming menus to improve their usability and the consistency between zooming in the design workspace and in the view mode."]
          [:p "We’ve also added two new options to scale your designs at the view mode that might help you to make your presentations look better."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 3}]]]]]])))
