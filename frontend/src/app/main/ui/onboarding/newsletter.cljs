;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.newsletter
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc onboarding-newsletter-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-newsletter-modal}
  []
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        message (tr "onboarding.newsletter.acceptance-message")
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
                       (dm/success message))
                     (modal/show {:type :onboarding-team})
                     (du/update-profile-props {:newsletter-updates @newsletter-updates :newsletter-news @newsletter-news}))))]

    (if new-css-system
      [:div {:class (stl/css :modal-overlay)}
       [:div.animated.fadeInDown {:class (stl/css :modal-container)}
        [:div {:class (stl/css :modal-header)}
         [:h2 {:class (stl/css :modal-title)
               :data-test "onboarding-newsletter-title"}
          (tr "onboarding.newsletter.title")]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding-v2.newsletter.desc")]]
        [:div {:class (stl/css :modal-content)}
         [:div {:class (stl/css :newsletter-options)}
          [:div {:class (stl/css :input-wrapper)}
           [:label {:for "newsletter-updates"}
            [:span {:class (stl/css-case :global/checked @newsletter-updates)}
             (when @newsletter-updates
               i/status-tick-refactor)]
            (tr "onboarding-v2.newsletter.updates")
            [:input {:type "checkbox"
                     :id "newsletter-updates"
                     :on-change #(toggle newsletter-updates)}]]]

          [:div {:class (stl/css :input-wrapper)}
           [:label {:for "newsletter-news"}
            [:span {:class (stl/css-case :global/checked @newsletter-news)}
             (when @newsletter-news
               i/status-tick-refactor)]
            (tr "onboarding-v2.newsletter.news")
            [:input {:type "checkbox"
                     :id "newsletter-news"
                     :on-change #(toggle newsletter-news)}]]]]

         [:div {:class (stl/css :modal-info)}
          [:p {:class (stl/css :modal-text)}
           (tr "onboarding-v2.newsletter.privacy1")
           [:a {:class (stl/css :modal-link)
                :target "_blank"
                :href "https://penpot.app/privacy"}
            (tr "onboarding.newsletter.policy")]]
          [:p {:class (stl/css :modal-text)}
           (tr "onboarding-v2.newsletter.privacy2")]]]

        [:div {:class (stl/css :modal-footer)}
         [:button {:on-click accept} (tr "labels.continue")]]

        [:img {:class (stl/css-case :deco true
                                    :top true)
               :src "images/deco-newsletter.png" :border "0"}]]]



      [:div.modal-overlay
       [:div.modal-container.onboarding.newsletter.animated.fadeInDown
        [:div.modal-top
         [:h1.newsletter-title {:data-test "onboarding-newsletter-title"} (tr "onboarding.newsletter.title")]
         [:p (tr "onboarding-v2.newsletter.desc")]]
        [:div.modal-bottom
         [:div.newsletter-options
          [:div.input-checkbox.check-primary
           [:input {:type "checkbox"
                    :id "newsletter-updates"
                    :on-change #(toggle newsletter-updates)}]
           [:label {:for "newsletter-updates"} (tr "onboarding-v2.newsletter.updates")]]
          [:div.input-checkbox.check-primary
           [:input {:type "checkbox"
                    :id "newsletter-news"
                    :on-change #(toggle newsletter-news)}]
           [:label {:for "newsletter-news"} (tr "onboarding-v2.newsletter.news")]]]
         [:p (tr "onboarding-v2.newsletter.privacy1")  [:a {:target "_blank" :href "https://penpot.app/privacy"} (tr "onboarding.newsletter.policy")]]
         [:p (tr "onboarding-v2.newsletter.privacy2")]]
        [:div.modal-footer
         [:button.btn-primary {:on-click accept} (tr "labels.continue")]]
        [:img.deco.top {:src "images/deco-newsletter.png" :border "0"}]
        [:img.deco.newsletter-left {:src "images/deco-news-left.png" :border "0"}]
        [:img.deco.newsletter-right {:src "images/deco-news-right.png" :border "0"}]]])
    ))
