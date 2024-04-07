;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.newsletter
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc onboarding-newsletter
  {::mf/register modal/components
   ::mf/register-as :onboarding-newsletter}
  []
  (let [message (tr "onboarding.newsletter.acceptance-message")
        newsletter-updates   (mf/use-state false)
        newsletter-news      (mf/use-state false)
        toggle
        (mf/use-callback
         (fn [option]
           (swap! option not)))


        accept
        (mf/use-callback
         (mf/deps @newsletter-updates @newsletter-news)
         (fn []
           (st/emit! (when (or @newsletter-updates @newsletter-news)
                       (msg/success message))
                     (modal/show {:type :onboarding-team})
                     (du/update-profile-props {:newsletter-updates @newsletter-updates :newsletter-news @newsletter-news}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div.animated.fadeInDown {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-left)}
       [:img {:src "images/deco-newsletter.png"
              :border "0"}]]

      [:div {:class (stl/css :modal-right)}
       [:h2 {:class (stl/css :modal-title)
             :data-test "onboarding-newsletter-title"}
        (tr "onboarding.newsletter.title")]
       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.desc")]


       [:div {:class (stl/css :newsletter-options)}
        [:div {:class (stl/css :input-wrapper)}
         [:label {:for "newsletter-updates"}
          [:span {:class (stl/css-case :global/checked @newsletter-updates)}
           (when @newsletter-updates
             i/status-tick)]
          (tr "onboarding-v2.newsletter.updates")
          [:input {:type "checkbox"
                   :id "newsletter-updates"
                   :on-change #(toggle newsletter-updates)}]]]

        [:div {:class (stl/css :input-wrapper)}
         [:label {:for "newsletter-news"}
          [:span {:class (stl/css-case :global/checked @newsletter-news)}
           (when @newsletter-news
             i/status-tick)]
          (tr "onboarding-v2.newsletter.news")
          [:input {:type "checkbox"
                   :id "newsletter-news"
                   :on-change #(toggle newsletter-news)}]]]]

       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.privacy1")
        [:a {:class (stl/css :modal-link)
             :target "_blank"
             :href "https://penpot.app/privacy"}
         (tr "onboarding.newsletter.policy")]]
       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.privacy2")]

       [:button {:on-click accept
                 :class (stl/css :accept-btn)} (tr "labels.continue")]]]]))
