;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-19
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.19"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/onboarding-version.jpg"
                :border "0"
                :alt "What's new release 1.19"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Version " version]
         [:div.modal-content
          [:p
           "On this 1.19 release, we bring Access Tokens, which "
           "will enable Penpot to connect with other services, "
           "another gateway to community creativity!"]
          [:p
           "We’ve also published performance improvements and tons "
           "of enhancements, a lot of them from our beloved community "
           "contributors <3"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]

        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.19-contributions.png"
                :border "0"
                :alt "Community code contributions"}]]

        [:div.modal-right
         [:div.modal-title
          [:h2 "Community code contributions"]]
         [:div.modal-content
          [:p
           "By far, this is the Penpot release that has the most "
           "code contributions. We cannot emphasize enough how happy "
           "we are to see how Penpot is more and more  a product of "
           "the community."]
          [:p
           "Let’s give kudos to "
           [:a {:href "https://github.com/astudentinearth" :target "_blank" :rel "noopener noreferrer"} "@astudentinearth"]
           ", "
           [:a {:href "https://github.com/dfelinto" :target "_blank" :rel "noopener noreferrer"} "@dfelinto"]
           ", "
           [:a {:href "https://github.com/akshay-gupta7" :target "_blank" :rel "noopener noreferrer"} "@akshay-gupta7"]
           ", "
           [:a {:href "https://github.com/ondrejkonec" :target "_blank" :rel "noopener noreferrer"} "@ondrejkonec"]
           " and "
           [:a {:href "https://github.com/ryanbreen" :target "_blank" :rel "noopener noreferrer"} "@ryanbreen"]
           " in particular and the Penpot community as a whole!"]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 2}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.19-tokens.gif"
                :border "0"
                :alt "Access Tokens"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Access Tokens"]]
         [:div.modal-content
          [:p
           "Personal access tokens function like an alternative to "
           "our login/password authentication system and can be used "
           "to allow an application to access the internal Penpot API."]
          [:p
           "This opens up a wide range of possibilities in terms of "
           "integrations and is an important step on the critical path "
           "to the Penpot’s plugins system."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 2}]]]]]])))

