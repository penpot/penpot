;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.team-choice
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.data.team :as dtm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.i18n :as i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc left-sidebar
  {::mf/props :obj
   ::mf/private true}
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

(def ^:private schema:invite-form
  [:map {:title "InviteForm"}
   [:role :keyword]
   [:emails {:optional true} [::sm/set ::sm/email]]])

(defn- get-available-roles
  []
  [{:value "viewer" :label (tr "labels.viewer")}
   {:value "editor" :label (tr "labels.editor")}
   {:value "admin" :label (tr "labels.admin")}])

(mf/defc team-form-step-2
  {::mf/props :obj}
  [{:keys [name on-back go-to-team?]}]
  (let [initial (mf/with-memo []
                  {:role "editor" :name name})

        form    (fm/use-form :schema schema:invite-form
                             :initial initial)

        roles   (mf/use-memo get-available-roles)
        error*  (mf/use-state nil)

        on-success
        (mf/use-fn
         (fn [response]
           (let [team-id (:id response)]
             (st/emit! (du/update-profile-props {:onboarding-team-id team-id
                                                 :onboarding-viewed true})
                       (when go-to-team?
                         (dcm/go-to-dashboard-recent :team-id team-id))))))

        on-error
        (mf/use-fn
         (fn [cause]
           (let [{:keys [type code] :as error} (ex-data cause)]
             (cond
               (and (= :validation type)
                    (= :profile-is-muted code))
               (swap! error* (tr "errors.profile-is-muted"))

               (and (= :validation type)
                    (= :max-invitations-by-request code))
               (swap! error* (tr "errors.maximum-invitations-by-request-reached" (:threshold error)))

               (and (= :restriction type)
                    (= :max-quote-reached code))
               (swap! error* (tr "errors.max-quote-reached" (:target error)))

               (or (= :member-is-muted code)
                   (= :email-has-permanent-bounces code)
                   (= :email-has-complaints code))
               (swap! error* (tr "errors.email-spam-or-permanent-bounces" (:email error)))

               :else
               (swap! error* (tr "errors.generic"))))))

        on-invite-later
        (mf/use-fn
         (fn [{:keys [name]}]
           (let [mdata  {:on-success on-success
                         :on-error   on-error}
                 params {:name name}]
             (st/emit! (-> (dtm/create-team (with-meta params mdata))
                           (with-meta {::ev/origin :onboarding-without-invitations}))
                       (ptk/data-event ::ev/event
                                       {::ev/name "onboarding-step"
                                        :label "team:create-team-and-invite-later"
                                        :team-name name
                                        :step 8})
                       (ptk/data-event ::ev/event
                                       {::ev/name "onboarding-finish"})))))

        on-invite-now
        (mf/use-fn
         (fn [{:keys [name emails] :as params}]
           (let [mdata  {:on-success on-success
                         :on-error   on-error}]

             (st/emit! (-> (dtm/create-team-with-invitations (with-meta params mdata))
                           (with-meta {::ev/origin :onboarding-with-invitations}))
                       (ptk/data-event ::ev/event
                                       {::ev/name "onboarding-step"
                                        :label "team:create-team-and-invite"
                                        :invites (count emails)
                                        :team-name name
                                        :role (:role params)
                                        :step 8})
                       (ptk/data-event ::ev/event
                                       {::ev/name "onboarding-finish"})))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [params (:clean-data @form)
                 emails (:emails params)]
             (if (> (count emails) 0)
               (on-invite-now params)
               (on-invite-later params)))))]

    [:*
     [:div {:class (stl/css :modal-right-invitations)}
      [:h2 {:class (stl/css :modal-subtitle)} (tr "onboarding.choice.team-up.invite-members")]
      [:p {:class (stl/css :modal-text)} (tr "onboarding.choice.team-up.invite-members-info")]
      [:& fm/form {:form form
                   :class (stl/css :modal-form-invitations)
                   :on-submit on-submit}

       (when-let [content (deref error*)]
         [:& context-notification {:content content :level :error}])

       [:div {:class (stl/css :role-select)}
        [:p {:class (stl/css :role-title)} (tr "onboarding.choice.team-up.roles")]
        [:& fm/select {:name :role :options roles}]]

       [:div {:class (stl/css :invitation-row)}
        [:& fm/multi-input {:type "email"
                            :name :emails
                            :auto-focus? true
                            :trim true
                            :valid-item-fn sm/parse-email
                            :caution-item-fn #{}
                            :label (tr "modals.invite-member.emails")
                            ;; :on-submit on-submit
                            }]]

       [:div {:class (stl/css :action-buttons)}
        [:button {:class (stl/css :back-button)
                  :on-click on-back}
         (tr "labels.back")]

        (let [params (:clean-data @form)
              emails (:emails params)]
          [:> fm/submit-button*
           {:class (stl/css :accept-button)
            :label (if (> (count emails) 0)
                     (tr "onboarding.choice.team-up.create-team-and-invite")
                     (tr "onboarding.choice.team-up.create-team-without-invite"))}])]

       [:div {:class (stl/css :modal-hint)}
        "(" (tr "onboarding.choice.team-up.create-team-and-send-invites-description") ")"]]]


     [:div {:class (stl/css :paginator)} "2/2"]]))

(def ^:private schema:team-form
  [:map {:title "TeamForm"}
   [:name [::sm/text {:max 250}]]])

(mf/defc team-form-step-1
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-submit]}]
  (let [form (fm/use-form :schema schema:team-form
                          :initial {})

        on-submit*
        (mf/use-fn
         (fn [form]
           (let [name (dm/get-in @form [:clean-data :name])]

             (st/emit! (ptk/data-event ::ev/event
                                       {::ev/name "onboarding-step"
                                        :label "team:choice-team-name"
                                        :step 7}))
             (on-submit name))))

        on-skip
        (mf/use-fn
         (fn []
           (st/emit! (du/update-profile-props {:onboarding-viewed true})
                     (ptk/data-event ::ev/event
                                     {::ev/name "onboarding-step"
                                      :label "team:skip-team-creation"
                                      :step 7})
                     (ptk/data-event ::ev/event
                                     {::ev/name "onboarding-finish"}))))]
    [:*
     [:div {:class (stl/css :modal-right)}
      [:div {:class (stl/css :first-block)}
       [:h2 {:class (stl/css :modal-subtitle)}
        (tr "onboarding.team-modal.create-team")]
       [:p {:class (stl/css :modal-text)}
        (tr "onboarding.choice.team-up.create-team-desc")]
       [:& fm/form {:form form
                    :class (stl/css :modal-form)
                    :on-submit on-submit*}

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

     [:div {:class (stl/css :paginator)} "1/2"]]))

(mf/defc onboarding-team-modal
  {::mf/props :obj}
  [{:keys [go-to-team?]}]
  (let [name* (mf/use-state nil)
        name  (deref name*)

        on-submit
        (mf/use-fn
         (fn [tname]
           (swap! name* (constantly tname))))


        on-back
        (mf/use-fn
         (fn []
           (swap! name* (constantly nil))))]

    [:div {:class (stl/css-case
                   :modal-overlay true)}

     [:div.animated.fadeIn {:class (stl/css :modal-container)}
      [:& left-sidebar]
      [:div {:class (stl/css :separator)}]
      (if name
        [:& team-form-step-2 {:name name :on-back on-back :go-to-team? go-to-team?}]
        [:& team-form-step-1 {:on-submit on-submit}])]]))

