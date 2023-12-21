;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.team-choice
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
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

(mf/defc team-modal-right
  []
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)]
    (if new-css-system

      [:div {:class (stl/css :modal-right)}
       [:h2 {:class (stl/css :modal-subtitle)}
        (tr "onboarding.team-modal.create-team")]
       [:p {:class (stl/css :modal-text)}
        (tr "onboarding.team-modal.create-team-desc")]
       [:ul {:class (stl/css :team-features)}
        [:li {:class (stl/css :feature)}
         [:span {:class (stl/css :icon)} i/document-refactor]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding.team-modal.create-team-feature-1")]]
        [:li {:class (stl/css :feature)}
         [:span {:class (stl/css :icon)}  i/move-refactor]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding.team-modal.create-team-feature-2")]]
        [:li {:class (stl/css :feature)}
         [:span {:class (stl/css :icon)}  i/tree-refactor]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding.team-modal.create-team-feature-3")]]
        [:li {:class (stl/css :feature)}
         [:span {:class (stl/css :icon)}  i/user-refactor]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding.team-modal.create-team-feature-4")]]
        [:li {:class (stl/css :feature)}
         [:span {:class (stl/css :icon)}  i/tick-refactor]
         [:p {:class (stl/css :modal-text)}
          (tr "onboarding.team-modal.create-team-feature-5")]]]]



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
         [:p.feature-txt (tr "onboarding.team-modal.create-team-feature-5")]]]])))

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        form  (fm/use-form :spec ::team-form
                           :initial {}
                           :validators [(fm/validate-not-empty :name (tr "auth.name.not-all-space"))
                                        (fm/validate-length :name fm/max-length-allowed (tr "auth.name.too-long"))])
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

    (if new-css-system
      (if (< (count teams) 2)
        [:div {:class (stl/css :modal-overlay)}
         [:div.animated.fadeIn {:class (stl/css :modal-container)}
          [:div {:class (stl/css :modal-left)}
           [:div {:class (stl/css :first-block)}
            [:h2 {:class (stl/css :modal-title)}
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
              [:& fm/submit-button*
               {:className (stl/css :accept-button)
                :label (tr "onboarding.choice.team-up.continue-creating-team")}]]]]
           [:div {:class (stl/css :second-block)}
            [:h2 {:class (stl/css :modal-title)}
             (tr "onboarding.choice.team-up.start-without-a-team")]
            [:p {:class (stl/css :modal-text)}
             (tr "onboarding.choice.team-up.start-without-a-team-description")]

            [:div {:class (stl/css :action-buttons)}
             [:button {:class (stl/css :accept-button)
                       :on-click on-skip}
              (tr "onboarding.choice.team-up.continue-without-a-team")]]]]
          [:& team-modal-right]
          [:div {:class (stl/css :paginator)} "1/2"]]]

        (st/emit! (modal/hide)))


      (if (< (count teams) 2)

        [:div.modal-overlay
         [:div.modal-container.onboarding-team.animated.fadeIn
          [:div.team-left
           [:h2.title (tr "onboarding.team-modal.create-team")]
           [:p.info (tr "onboarding.choice.team-up.create-team-desc")]
           [:& fm/form {:form form
                        :on-submit on-submit}
            [:& fm/input {:type "text"
                          :name :name
                          :label (tr "onboarding.choice.team-up.create-team-placeholder")}]

            [:& fm/submit-button*
             {:label (tr "onboarding.choice.team-up.continue-creating-team")}]]

           [:h2.title (tr "onboarding.choice.team-up.start-without-a-team")]
           [:p.info (tr "onboarding.choice.team-up.start-without-a-team-description")]

           [:div
            [:button.btn-primary.btn-large {:on-click on-skip} (tr "onboarding.choice.team-up.continue-without-a-team")]]]
          [:& team-modal-right]
          [:div.paginator "1/2"]

          [:img.deco.square {:src "images/deco-square.svg" :border "0"}]
          [:img.deco.circle {:src "images/deco-circle.svg" :border "0"}]
          [:img.deco.line1 {:src "images/deco-line1.svg" :border "0"}]
          [:img.deco.line2 {:src "images/deco-line2.svg" :border "0"}]]]

        (st/emit! (modal/hide))))))

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
   ::mf/register-as :onboarding-team-invitations}
  [{:keys [name] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        initial (mf/use-memo (constantly
                              {:role "editor"
                               :name name}))
        form    (fm/use-form :spec ::invite-form
                             :initial initial)
        params  (:clean-data @form)
        emails  (:emails params)

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
        on-invite-later
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
        on-invite-now
        (mf/use-callback
         (fn [_]
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
        (mf/use-callback
         (fn [_]
           (let [params (:clean-data @form)
                 emails (:emails params)]
             (if (> (count emails) 0)
               (on-invite-now form)
               (on-invite-later form)))))]

    (if new-css-system
      [:div {:class (stl/css :modal-overlay)}
       [:div.animated.fadeIn {:class (stl/css :modal-container)}
        [:div {:class (stl/css :modal-left)}
         [:h2 {:class (stl/css :modal-title)} (tr "onboarding.choice.team-up.invite-members")]
         [:p {:class (stl/css :modal-text)} (tr "onboarding.choice.team-up.invite-members-info")]

         [:div {:class (stl/css :modal-form)}
          [:& fm/form {:form form
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
                                :on-submit  on-submit}]]]

          [:div {:class (stl/css :action-buttons)}
           [:button {:class (stl/css :back-button)
                     :on-click #(st/emit! (modal/show {:type :onboarding-team})
                                          (ptk/event ::ev/event {::ev/name "invite-members-back"
                                                                 ::ev/origin "onboarding"
                                                                 :name name
                                                                 :step 2}))}
            (tr "labels.back")]

           [:& fm/submit-button*
            {:className (stl/css :accept-button)
             :label
             (if (> (count emails) 0)
               (tr "onboarding.choice.team-up.create-team-and-invite")
               (tr "onboarding.choice.team-up.create-team-without-invite"))}]]
          [:div {:class (stl/css :modal-hint)}
           (tr "onboarding.choice.team-up.create-team-and-send-invites-description")]]]

        [:& team-modal-right]
        [:div {:class (stl/css :paginator)} "2/2"]]]



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
                               :caution-item-fn #{}
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
           [:& fm/submit-button*
            {:label
             (if (> (count emails) 0)
               (tr "onboarding.choice.team-up.create-team-and-send-invites")
               (tr "onboarding.choice.team-up.create-team-without-inviting"))}]]
          [:div.skip-action
           (tr "onboarding.choice.team-up.create-team-and-send-invites-description")]]]
        [:& team-modal-right]
        [:div.paginator "2/2"]

        [:img.deco.square {:src "images/deco-square.svg" :border "0"}]
        [:img.deco.circle {:src "images/deco-circle.svg" :border "0"}]
        [:img.deco.line1 {:src "images/deco-line1.svg" :border "0"}]
        [:img.deco.line2 {:src "images/deco-line2.svg" :border "0"}]]])))


