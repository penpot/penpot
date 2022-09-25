;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-12
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.12"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/login-on.jpg" :border "0" :alt "What's new Beta release 1.12"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Beta version " version]
         [:div.modal-content
          [:p "Penpot continues growing with new features that improve performance, user experience and visual design."]
          [:p "We are happy to show you a sneak peek of the most important stuff that the Beta 1.12 version brings."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.12-ui.gif" :border "0" :alt "Adjustable UI"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Adjustable UI"]]
         [:div.modal-content
          [:p "Adjust the workspace interface to your unique experience. Resize the sidebar, the layers panel  or hide the whole UI to have maximum space."]
          [:p "Along with a better organization of panels (say hello to typography toolbar!) and new shortcuts that will speed your workflow."]]
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
         [:img {:src "images/features/1.12-guides.gif" :border "0" :alt "Guides"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Guides"]]
         [:div.modal-content
          [:p "One of our most requested features! It’s hard to believe how far Penpot has come without guides, but they are here at last."]
          [:p "And they don’t come alone, but with some nice improvements to the rulers."]]
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
         [:img {:src "images/features/1.12-scrollbars.gif" :border "0" :alt "Scrollbars"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Scrollbars"]]
         [:div.modal-content
          [:p "Scrollbars at the design workspace will make it more obvious how to navigate it and easier for some users, for instance those who love using graphic tablets, from now on, will feel just as comfortable as those who use a mouseAnd they don’t come alone, but with some nice improvements to the rulers."]]
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
         [:img {:src "images/features/1.12-nudge.gif" :border "0" :alt "Nudge amount"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Nudge amount"]]
         [:div.modal-content
          [:p "Set your desired distance to move objects using the keyboard."]
          [:p "This is a must if you’re working with grids (if you’re not, you should ;)), being able to adjust the movement to your baseline grid (8px? 5px?) is a huge timesaver that will improve your quality of life while designing."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
