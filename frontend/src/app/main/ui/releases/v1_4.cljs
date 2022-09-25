;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-4
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.4"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.4.0"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Alpha 1.4.0 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/select-files.gif" :border "0" :alt "New file selection"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New file selection and open files"]]
         [:div.modal-content
          [:p "Now you can select files with left click and make multi-selections holding down the shift + left click."]
          [:p "To open a file you just have to double click it. You can also open a file in a new tab with right click."]]
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
         [:img {:src "images/features/manage-files.gif" :border "0" :alt "Manage files"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New files/projects management"]]
         [:div.modal-content
          [:p "Penpot now allows to duplicate and move files and projects."]
          [:p "Also, now you have an easy way to manage files and projects between teams."]]
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
         [:img {:src "images/features/rtl.gif" :border "0" :alt "RTL support"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "RTL support is now available!"]]
         [:div.modal-content
          [:p "Diversity and inclusion is one major Penpot concern and that's why we love to give support to RTL languages, unlike in most of design tools."]
          [:p "If you write in arabic, hebrew or other RTL language text direction will be automatically detected in text layers."]]
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
         [:img {:src "images/features/blend-modes.gif" :border "0" :alt "Blend modes"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "New layer opacity and blend modes"]]
         [:div.modal-content
          [:p "Combining elements visually is an important part of the design process."]
          [:p "This is why the standard blend modes and opacity level are now available for each element."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))

