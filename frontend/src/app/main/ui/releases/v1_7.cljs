;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-7
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.7"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.7"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Alpha 1.7 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/export.gif" :border "0" :alt "Export & Import"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Export and import Penpot files"]]
         [:div.modal-content
          [:p [:strong "Export files from the dashboard to your computer and import them from your computer to your projects."]
           " This means that Penpot users can freely save and share Penpot files."]
          [:p "Exported files linked to shared libraries provide
          different ways to export their assets. Choose the one that
          suits you better!"]]
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
         [:img {:src "images/features/constraints.gif" :border "0" :alt "Resizing constraints"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Resizing constraints"]]
         [:div.modal-content
          [:p "Constraints allow you to " [:strong "decide how layers will behave when resizing its container"] " being a group or an artboard."]
          [:p "You can manually set horizontal and vertical
          constraints for every layer. This is especially useful to
          control how your designs look when working with responsive
          components."]]
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
         [:img {:src "images/features/group-components.gif" :border "0" :alt "Library assets management improvements"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Library assets management"]]
         [:div.modal-content
          [:p [:strong "Collapse/expand groups"] " at any nesting level, so you don’t have to manage their visibility individually."]
          [:p "Penpot " [:strong "remembers the last library state"] ", so you don’t have to collapse a group you want hidden every time."]
          [:p "Easily " [:strong "rename and ungroup"] " asset groups."]]
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
         [:img {:src "images/features/copy-paste.gif" :border "0" :alt "Paste components from file to file"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Paste components from file to file"]]
         [:div.modal-content
          [:p "Do you sometimes copy and paste component copies that belong to a library already shared by the original and destination files? From now on, those component copies are aware of this and will retain their linkage to the library."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
