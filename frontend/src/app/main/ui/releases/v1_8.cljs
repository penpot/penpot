;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-8
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.8"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Alpha release 1.8"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Alpha version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Alpha 1.8 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/share-viewer.gif" :border "0" :alt "Share options and pages at view mode"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Share options and pages at view mode"]]
         [:div.modal-content
          [:p "Now you can navigate through prototype pages of the same file at the view mode."]
          [:p "You can also create a shareable link deciding which pages will be available for the visitors. Sharing is caring!"]]
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
         [:img {:src "images/features/stroke-caps.gif" :border "0" :alt "Path stroke caps"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Path stroke caps"]]
         [:div.modal-content
          [:p "Ever needed an arrow to point something? Style the ends of any open paths."]
          [:p "You can select different styles for each end of an open path: arrows, square, circle, diamond or just a round ending are the available options."]]
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
         [:img {:src "images/features/navigate-history.gif" :border "0" :alt "Navigable history"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Navigable history"]]
         [:div.modal-content
          [:p "Click on a change of the history of a file to get the file to this very point without ctrl+z all the way."]
          [:p "Quick and easy :)"]]
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
         [:img {:src "images/features/export-artboards.gif" :border "0" :alt "Export artboards PDF"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Export artboards PDF"]]
         [:div.modal-content
          [:p "If you have a presentation made at Penpot you might want to create a document that can be shared with anyone, regardless of having a Penpot account, or just to be able to use your presentation offline (essential for talks and classes)."]
          [:p "Now you can easily export all the artboards of a page to a single pdf file."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
