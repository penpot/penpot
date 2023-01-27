;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-17
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.17"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/onboarding-version.jpg" :border "0" :alt "What's new release 1.17"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Version " version]
         [:div.modal-content
          [:p "This is the first release in which Penpot is no longer Beta (hooray!) and it comes with very special features, starring the long awaited Flex Layout."]
          [:p "On this 1.17 release, you’ll also be able to inspect the code and properties of your designs right from the workspace and to manage webhooks. We’ve also implemented a lot of accessibility improvements."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.17-flex-layout.gif" :border "0" :alt "Flex-Layout"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Flex-Layout"]]
         [:div.modal-content
          [:p "The Flex Layout allows you to automatically adapt your designs. Resize, fit, and fill content and containers without the need to do it manually."]
          [:p "Penpot brings a layout system like no other. As described by one of our beta testers: 'I love the fact that Penpot is following the CSS FlexBox, which is making UI Design a step closer to the logic and behavior behind how things will be actually built after design.'"]]
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
         [:img {:src "images/features/1.17-inspect.gif" :border "0" :alt "Inspect at the workspace"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Inspect at the workspace"]]
         [:div.modal-content
          [:p "Now you can inspect designs to get measures, properties and production-ready code right at the workspace, so designers and developers can share the same space while working."] 
          [:p "Also, inspect mode provides a safer view-only mode and other improvements."]]
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
         [:img {:src "images/features/1.17-webhook.gif" :border "0" :alt "Webhooks"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Webhooks"]]
         [:div.modal-content
          [:p "Webhooks allow other websites and apps to be notified when certain events happen at Penpot, ensuring to create integrations with other services."]
          [:p "While we are still working on a plugin system, this is a great and simple way to create integrations with other services."]]
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
         [:img {:src "images/features/1.17-ally.gif" :border "0" :alt "Accessibility improvements"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Accessibility improvements"]]
         [:div.modal-content
          [:p "We're working to ensure that people with visual or physical impairments can use Penpot in the same conditions."]
          [:p "This release comes with improvements on color contrasts, alt texts, semantic labels, focusable items and keyboard navigation at login and dashboard, but more will come."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
