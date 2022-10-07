;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-16
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.16"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.16"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues to grow with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.16 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.16-dashboard.gif" :border "0" :alt "Dashboard refreshed look & feel"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Dashboard refreshed look & feel"]]
         [:div.modal-content
          [:p "It’s been some time since we designed the project's dashboard and we felt that we could improve it in terms of accessibility, content hierarchy and aesthetics."]
          [:p "We heard the users before refreshing the interface, simplifying it to give prominence to the content. And yes, now that you ask, the dark theme is coming soon."]]
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
         [:img {:src "images/features/1.16-slider.gif" :border "0" :alt "Libraries & templates module"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Libraries & templates module"]]
         [:div.modal-content
          [:p "This new module will allow you to import a curated selection of the files that are available at the Libraries & Templates page directly from your projects dashboard."] 
          [:p "You no longer need to to download most of them to the computer before importing."]]
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
         [:img {:src "images/features/1.16-onboarding.gif" :border "0" :alt "Improved onboarding"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Improved onboarding"]]
         [:div.modal-content
          [:p "We’ve done a ton of improvements to the onboarding experience."]
          [:p "More relevant info and better explanations, a refined new team and invitation flow, a beginners tutorial and a walkthrough file that will help newcomers learn how to use and start designing with Penpot faster."]]
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
         [:img {:src "images/features/1.16-click-zoom.gif" :border "0" :alt "Zoom to shape with double click"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Zoom to shape with double click"]]
         [:div.modal-content
          [:p "The devil is in the details. At the layers panel, double clicking to the icon of a layer will zoom to it, making the layers navigation and selection easier."]
          [:p "This was a contribution by our community member @andrewzhurov <3"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
