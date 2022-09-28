;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-13
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.13"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.13"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues to grow with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.13 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.13-multi-export.gif" :border "0" :alt "Multiple exports"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Multiple exports"]]
         [:div.modal-content
          [:p "Speed up your workflow exporting multiple elements simultaneously."]
          [:p "Use the export window to manage your multiple exports and be informed about the download progress. Big exports will happen in the background so you can keep designing in the meantime ;)"]]
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
         [:img {:src "images/features/1.13-multiple-fills.gif" :border "0" :alt "Multiple fills and strokes"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Multiple fills and strokes"]]
         [:div.modal-content
          [:p "Now you can add multiple color fills and strokes to a single element, including shapes and texts."]
          [:p "This opens endless graphic possibilities such as combining gradients and blending modes in the same element to create sophisticated visual effects."]]
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
         [:img {:src "images/features/1.13-members.gif" :border "0" :alt "Members area redesign"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Members area redesign"]]
         [:div.modal-content
          [:p "Penpot is meant for teams, that’s why we decided to give some love to the members area."]
          [:p "A refreshed interface and two new features! The Invitations section allows you to check the status of current team invites plus you now have the ability to invite multiple members at the same time."]]
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
         [:img {:src "images/features/1.13-focus.gif" :border "0" :alt "Focus mode"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Focus mode"]]
         [:div.modal-content
          [:p "Enjoy a distraction-less design mode by selecting the elements of a page that matter to you and temporarily hiding the rest."]
          [:p "As a side effect, this can give you a performance boost in massive designs."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
