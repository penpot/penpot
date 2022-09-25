;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-6
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.6"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.6.0"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Alpha 1.6.0 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/custom-fonts.gif" :border "0" :alt "Upload/use custom fonts"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Upload/use custom fonts"]]
         [:div.modal-content
          [:p "From now on you can upload fonts to a Penpot team and use them across its files. This is one of the most requested features since our first release (we listen!)"]
          [:p "We hope you enjoy having more typography options and our brand new font selector."]]
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
         [:img {:src "images/features/scale-text.gif" :border "0" :alt "Interactively scale text"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Scale text layers at resizing"]]
         [:div.modal-content
          [:p "New main menu option “Scale text (K)” to enable scale text mode."]
          [:p "Disabled by default, this tool is disabled back after being used."]]
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
         [:img {:src "images/features/performance.gif" :border "0" :alt "Performance improvements"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Performance improvements"]]
         [:div.modal-content
          [:p "Penpot brings important improvements handling large files. The performance in managing files in the dashboard has also been improved."]
          [:p "You should have the feeling that files and layers show up a bit faster :)"]]
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
         [:img {:src "images/features/shapes-to-path.gif" :border "0" :alt "Shapes to path"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Shapes to path"]]
         [:div.modal-content
          [:p "Now you can edit basic shapes like rectangles, circles and image containers by double clicking."]
          [:p "An easy way to increase speed by working with vectors!"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
