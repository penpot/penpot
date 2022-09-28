;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.team-choice
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(mf/defc team-modal-right
  []
  [:div.team-right
   [:h2.subtitle (tr "onboarding.team-modal.create-team")]
   [:p.info (tr "onboarding.team-modal.create-team-desc")]
   [:ul.team-features
    [:li.feature
     [:span.icon i/file-html]
     [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-1")]]
    [:li.feature
     [:span.icon i/pointer-inner]
     [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-2")]]
    [:li.feature
     [:span.icon i/tree]
     [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-3")]]
    [:li.feature
     [:span.icon i/user]
     [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-4")]]
    [:li.feature
     [:span.icon i/tick]
     [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-5")]]]])

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [form  (fm/use-form :spec ::team-form
                           :initial {})
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [tname (get-in @form [:clean-data :name])]
             (st/emit! (modal/show {:type :onboarding-team-invitations :name tname})
                       (ptk/event ::ev/event {::ev/name "choose-team-name"
                                              ::ev/origin "onboarding"
                                              :name tname
                                              :step 1})))))
        on-skip
        (fn []
          (tm/schedule 400  #(st/emit! (modal/hide)
                                       (ptk/event ::ev/event {::ev/name "create-team-later"
                                                              ::ev/origin "onboarding"
                                                              :step 1}))))

        teams (mf/deref refs/teams)]
    (if (< (count teams) 2)

      [:div.modal-overlay
       [:div.modal-container.onboarding-team.animated.fadeIn
        [:div.team-left
         [:h2.title (tr "onboarding.choice.team-up.create-team")]
         [:p.info (tr "onboarding.choice.team-up.create-team-desc")]
         [:& fm/form {:form form
                      :on-submit on-submit}
          [:& fm/input {:type "text"
                        :name :name
                        :label (tr "onboarding.choice.team-up.create-team-placeholder")}]

          [:& fm/submit-button
           {:label (tr "labels.continue")}]]

         [:button.skip-action {:on-click on-skip} (tr "onboarding.choice.team-up.create-later")]]
        [:& team-modal-right]
        [:div.paginator "1/2"]

        [:img.deco.square {:src "images/deco-square.svg" :border "0"}]
        [:img.deco.circle {:src "images/deco-circle.svg" :border "0"}]
        [:img.deco.line1 {:src "images/deco-line1.svg" :border "0"}]
        [:img.deco.line2 {:src "images/deco-line2.svg" :border "0"}]]]

      (st/emit! (modal/hide)))))

(defn get-available-roles
  []
  [{:value "editor" :label (tr "labels.editor")}
   {:value "admin" :label (tr "labels.admin")}])

(s/def ::emails (s/and ::us/set-of-valid-emails d/not-empty?))
(s/def ::role  ::us/keyword)
(s/def ::invite-form
  (s/keys :req-un [::role ::emails]))

;; This is the final step of team creation, consists in provide a
;; shortcut for invite users.

(mf/defc onboarding-team-invitations-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team-invitations}
  [{:keys [name] :as props}]
  (let [initial (mf/use-memo (constantly
                              {:role "editor"
                               :name name}))
        form    (fm/use-form :spec ::invite-form
                             :initial initial)

        roles   (mf/use-memo #(get-available-roles))

        on-success
        (mf/use-callback
         (fn [_form response]
           (let [team-id    (:id response)]
             (st/emit!
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id team-id}))
             (tm/schedule 400 #(st/emit!
                                (modal/hide))))))

        on-error
        (mf/use-callback
         (fn [_form _response]
           (st/emit! (dm/error "Error on creating team."))))

        ;; The SKIP branch only creates the team, without invitations
        on-skip
        (mf/use-callback
         (fn [_]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name name}]
             (st/emit! (dd/create-team (with-meta params mdata))
                       (ptk/event ::ev/event {::ev/name "create-team-and-invite-later"
                                              ::ev/origin "onboarding"
                                              :name name
                                              :step 2})))))

        ;; The SUBMIT branch creates the team with the invitations
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params (:clean-data @form)]
             (st/emit! (dd/create-team-with-invitations (with-meta params mdata))
                       (ptk/event ::ev/event {::ev/name "create-team-and-send-invitations"
                                              ::ev/origin "onboarding"
                                              :invites (count (:emails params))
                                              :role (:role params)
                                              :name name
                                              :step 2})))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding-team-members.animated.fadeIn
      [:div.team-left
       [:h2.title (tr "onboarding.choice.team-up.invite-members")]
       [:p.info (tr "onboarding.choice.team-up.invite-members-info")]

       [:& fm/form {:form form
                    :on-submit on-submit}
        [:div.invite-row
         [:div.role-wrapper
          [:span.rol (tr "onboarding.choice.team-up.roles")]
          [:& fm/select {:name :role :options roles}]]

         [:& fm/multi-input {:type "email"
                             :name :emails
                             :auto-focus? true
                             :trim true
                             :valid-item-fn us/parse-email
                             :on-submit  on-submit
                             :label (tr "modals.invite-member.emails")}]]

        [:div.buttons
         [:button.btn-secondary.btn-large
          {:on-click #(st/emit! (modal/show {:type :onboarding-team})
                                (ptk/event ::ev/event {::ev/name "invite-members-back"
                                                       ::ev/origin "onboarding"
                                                       :name name
                                                       :step 2}))}
          (tr "labels.back")]
         [:& fm/submit-button
          {:label (tr "onboarding.choice.team-up.invite-members-submit")}]]
        [:div.skip-action
         {:on-click on-skip}
         [:div.action (tr "onboarding.choice.team-up.invite-members-skip")]]]]
      [:& team-modal-right]
      [:div.paginator "2/2"]

      [:img.deco.square {:src "images/deco-square.svg" :border "0"}]
      [:img.deco.circle {:src "images/deco-circle.svg" :border "0"}]
      [:img.deco.line1 {:src "images/deco-line1.svg" :border "0"}]
      [:img.deco.line2 {:src "images/deco-line2.svg" :border "0"}]]]))


