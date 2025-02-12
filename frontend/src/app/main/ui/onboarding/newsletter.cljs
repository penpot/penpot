;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.newsletter
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as-alias ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc onboarding-newsletter
  []
  (let [state* (mf/use-state #(do {:newsletter-updates false
                                   :newsletter-news false}))
        state  (deref state*)
        team (mf/deref refs/team)

        on-change
        (mf/use-fn
         (fn [event]
           (let [attr (-> (dom/get-current-target event)
                          (dom/get-data "attr")
                          (keyword))]
             (swap! state* update attr not))))

        on-next
        (mf/use-fn
         (mf/deps state team)
         (fn []
           (when (or (:newsletter-updates state)
                     (:newsletter-news state))
             (st/emit! (ntf/success (tr "onboarding.newsletter.acceptance-message"))))

           (let [params (-> state
                            (assoc ::ev/name "onboarding-step")
                            (assoc :label "newsletter:subscriptions")
                            (assoc :step 6))]
             (st/emit! (ptk/data-event ::ev/event params)
                       (du/update-profile-props
                        (cond-> state
                          (not (:is-default team))
                          (assoc :onboarding-viewed true)))))))]

    [:div {:class (stl/css-case
                   :modal-overlay true)}

     [:div.animated.fadeInDown {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-left)}
       [:img {:src "images/deco-newsletter.png"
              :border "0"}]]

      [:div {:class (stl/css :modal-right)}
       [:h2 {:class (stl/css :modal-title)
             :data-testid "onboarding-newsletter-title"}
        (tr "onboarding.newsletter.title")]

       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.desc")]

       [:div {:class (stl/css :newsletter-options)}
        [:div {:class (stl/css :input-wrapper)}
         [:label {:for "newsletter-updates"}
          [:span {:class (stl/css-case :global/checked (:newsletter-updates state))}
           i/status-tick]

          (tr "onboarding-v2.newsletter.updates")
          [:input {:type "checkbox"
                   :id "newsletter-updates"
                   :data-attr "newsletter-updates"
                   :value (:newsletter-updates state)
                   :on-change on-change}]]]

        [:div {:class (stl/css :input-wrapper)}
         [:label {:for "newsletter-news"}
          [:span {:class (stl/css-case :global/checked (:newsletter-news state))}
           i/status-tick]

          (tr "onboarding-v2.newsletter.news")
          [:input {:type "checkbox"
                   :id "newsletter-news"
                   :data-attr "newsletter-news"
                   :value (:newsletter-news state)
                   :on-change on-change}]]]]

       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.privacy1")
        [:a {:class (stl/css :modal-link)
             :target "_blank"
             :href "https://penpot.app/privacy"}
         (tr "onboarding.newsletter.policy")]]
       [:p {:class (stl/css :modal-text)}
        (tr "onboarding-v2.newsletter.privacy2")]

       [:button {:on-click on-next
                 :class (stl/css :accept-btn)}
        (tr "labels.continue")]]]]))
