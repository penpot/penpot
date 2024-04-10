;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.team-choice
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dmc]
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(mf/defc team-modal-left
  []
  [:div {:class (stl/css :modal-left)}
   [:h1 {:class (stl/css :modal-title)}
    (tr "onboarding-v2.welcome.title")]

   [:h2 {:class (stl/css :modal-subtitle)}
    (tr "onboarding.team-modal.team-definition")]
   [:p {:class (stl/css :modal-text)}
    (tr "onboarding.team-modal.create-team-desc")]
   [:ul {:class (stl/css :team-features)}
    [:li {:class (stl/css :feature)}
     [:span {:class (stl/css :icon)} i/document]
     [:p {:class (stl/css :modal-desc)}
      (tr "onboarding.team-modal.create-team-feature-1")]]
    [:li {:class (stl/css :feature)}
     [:span {:class (stl/css :icon)}  i/move]
     [:p {:class (stl/css :modal-desc)}
      (tr "onboarding.team-modal.create-team-feature-2")]]
    [:li {:class (stl/css :feature)}
     [:span {:class (stl/css :icon)}  i/tree]
     [:p {:class (stl/css :modal-desc)}
      (tr "onboarding.team-modal.create-team-feature-3")]]
    [:li {:class (stl/css :feature)}
     [:span {:class (stl/css :icon)}  i/user]
     [:p {:class (stl/css :modal-desc)}
      (tr "onboarding.team-modal.create-team-feature-4")]]
    [:li {:class (stl/css :feature)}
     [:span {:class (stl/css :icon)}  i/tick]
     [:p {:class (stl/css :modal-desc)}
      (tr "onboarding.team-modal.create-team-feature-5")]]]])

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [form  (fm/use-form :spec ::team-form
                           :initial {}
                           :validators [(fm/validate-not-empty :name (tr "auth.name.not-all-space"))
                                        (fm/validate-length :name fm/max-length-allowed (tr "auth.name.too-long"))])
        on-submit
        (mf/use-fn
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

    (mf/with-effect [teams]
      (when (> (count teams) 1)
        (st/emit! (modal/hide))))

    (when (< (count teams) 2)
      [:div {:class (stl/css :modal-overlay)}
       [:div.animated.fadeIn {:class (stl/css :modal-container)}
        [:& team-modal-left]
        [:div {:class (stl/css :separator)}]
        [:div {:class (stl/css :modal-right)}
         [:div {:class (stl/css :first-block)}
          [:h2 {:class (stl/css :modal-subtitle)}
           (tr "onboarding.team-modal.create-team")]
          [:p {:class (stl/css :modal-text)}
           (tr "onboarding.choice.team-up.create-team-desc")]
          [:& fm/form {:form form
                       :class (stl/css :modal-form)
                       :on-submit on-submit}

           [:& fm/input {:type "text"
                         :class (stl/css :team-name-input)
                         :name :name
                         :placeholder "Team name"
                         :label (tr "onboarding.choice.team-up.create-team-placeholder")}]

           [:div {:class (stl/css :action-buttons)}
            [:> fm/submit-button*
             {:class (stl/css :accept-button)
              :label (tr "onboarding.choice.team-up.continue-creating-team")}]]]]
         [:div {:class (stl/css :second-block)}
          [:h2 {:class (stl/css :modal-subtitle)}
           (tr "onboarding.choice.team-up.start-without-a-team")]
          [:p {:class (stl/css :modal-text)}
           (tr "onboarding.choice.team-up.start-without-a-team-description")]

          [:div {:class (stl/css :action-buttons)}
           [:button {:class (stl/css :accept-button)
                     :on-click on-skip}
            (tr "onboarding.choice.team-up.continue-without-a-team")]]]]

        [:div {:class (stl/css :paginator)} "1/2"]]])))

(defn get-available-roles
  []
  [{:value "editor" :label (tr "labels.editor")}
   {:value "admin" :label (tr "labels.admin")}])

(s/def ::emails (s/and ::us/set-of-valid-emails))
(s/def ::role  ::us/keyword)
(s/def ::invite-form
  (s/keys :req-un [::role ::emails]))

;; This is the final step of team creation, consists in provide a
;; shortcut for invite users.

(mf/defc onboarding-team-invitations-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team-invitations
   ::mf/props :obj}
  [{:keys [name]}]
  (let [initial (mf/use-memo (constantly
                              {:role "editor"
                               :name name}))
        form    (fm/use-form :spec ::invite-form
                             :initial initial)
        params  (:clean-data @form)
        emails  (:emails params)

        roles   (mf/use-memo get-available-roles)

        on-success
        (mf/use-fn
         (fn [_form response]
           (let [team-id    (:id response)]
             (st/emit!
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id team-id}))
             (tm/schedule 400 #(st/emit!
                                (modal/hide))))))

        on-error
        (mf/use-fn
         (fn [_form _cause]
           (st/emit! (msg/error "Error on creating team."))))

        ;; The SKIP branch only creates the team, without invitations
        on-invite-later
        (mf/use-fn
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
        on-invite-now
        (mf/use-fn
         (fn [form]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params (:clean-data @form)
                 emails (:emails params)]

             (st/emit! (if (> (count emails) 0)
                         ;; If the user is only inviting to itself we don't call to create-team-with-invitations
                         (dd/create-team-with-invitations (with-meta params mdata))
                         (dd/create-team (with-meta {:name name} mdata)))
                       (ptk/event ::ev/event {::ev/name "create-team-and-send-invitations"
                                              ::ev/origin "onboarding"
                                              :invites (count emails)
                                              :role (:role params)
                                              :name name
                                              :step 2})))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [params (:clean-data @form)
                 emails (:emails params)]
             (if (> (count emails) 0)
               (on-invite-now form)
               (on-invite-later form))
             (modal/hide!))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div.animated.fadeIn {:class (stl/css :modal-container)}
      [:& team-modal-left]

      [:div {:class (stl/css :separator)}]
      [:div {:class (stl/css :modal-right-invitations)}
       [:h2 {:class (stl/css :modal-subtitle)} (tr "onboarding.choice.team-up.invite-members")]
       [:p {:class (stl/css :modal-text)} (tr "onboarding.choice.team-up.invite-members-info")]
       [:& fm/form {:form form
                    :class (stl/css :modal-form-invitations)
                    :on-submit on-submit}
        [:div {:class (stl/css :role-select)}
         [:p {:class (stl/css :role-title)} (tr "onboarding.choice.team-up.roles")]
         [:& fm/select {:name :role :options roles}]]

        [:div {:class (stl/css :invitation-row)}
         [:& fm/multi-input {:type "email"
                             :name :emails
                             :auto-focus? true
                             :trim true
                             :valid-item-fn us/parse-email
                             :caution-item-fn #{}
                             :label (tr "modals.invite-member.emails")
                             :on-submit  on-submit}]]

        [:div {:class (stl/css :action-buttons)}
         [:button {:class (stl/css :back-button)
                   :on-click #(st/emit! (modal/show {:type :onboarding-team})
                                        (ptk/event ::ev/event {::ev/name "invite-members-back"
                                                               ::ev/origin "onboarding"
                                                               :name name
                                                               :step 2}))}
          (tr "labels.back")]

         [:> fm/submit-button*
          {:class (stl/css :accept-button)
           :label (if (> (count emails) 0)
                    (tr "onboarding.choice.team-up.create-team-and-invite")
                    (tr "onboarding.choice.team-up.create-team-without-invite"))}]]
        [:div {:class (stl/css :modal-hint)}
         (dmc/str "(" (tr "onboarding.choice.team-up.create-team-and-send-invites-description") ")")]]]


      [:div {:class (stl/css :paginator)} "2/2"]]]))


