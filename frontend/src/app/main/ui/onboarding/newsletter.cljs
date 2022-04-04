;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding.newsletter
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc onboarding-newsletter-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-newsletter-modal}
  []
  (let [message (tr "onboarding.newsletter.acceptance-message")
        accept
        (mf/use-callback
         (fn []
           (st/emit! (dm/success message)
                     (modal/show {:type :onboarding-choice})
                     (du/update-profile-props {:newsletter-subscribed true}))))
        
        decline
        (mf/use-callback
         (fn []
           (st/emit! (modal/show {:type :onboarding-choice})
                     (du/update-profile-props {:newsletter-subscribed false}))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding.newsletter.animated.fadeInUp
      [:div.modal-top
       [:h1.newsletter-title {:data-test "onboarding-newsletter-title"} (tr "onboarding.newsletter.title")]
       [:p (tr "onboarding.newsletter.desc")]]
      [:div.modal-bottom
       [:p (tr "onboarding.newsletter.privacy1")  [:a {:target "_blank" :href "https://penpot.app/privacy.html"} (tr "onboarding.newsletter.policy")]]
       [:p (tr "onboarding.newsletter.privacy2")]]
      [:div.modal-footer
       [:button.btn-secondary {:on-click decline} (tr "onboarding.newsletter.decline")]
       [:button.btn-primary {:on-click accept} (tr "onboarding.newsletter.accept")]]
      [:img.deco.top {:src "images/deco-newsletter.png" :border "0"}]
      [:img.deco.newsletter-left {:src "images/deco-news-left.png" :border "0"}]
      [:img.deco.newsletter-right {:src "images/deco-news-right.png" :border "0"}]]]))
