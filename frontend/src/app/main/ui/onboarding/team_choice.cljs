;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.onboarding.team-choice
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.common.types.team :as ctt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.data.team :as dtm]
   [app.main.store :as st]
   [app.main.ui.components.link :refer [link*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.forms :as fc]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc team-info-feature*
  {::mf/private true}
  [{:keys [icon-id text]}]
  [:li {:class (stl/css :modal-info-item)}
   [:div {:class (stl/css :modal-info-icon)}
    [:> icon* {:icon-id icon-id :size "m"}]]
   [:> text* {:as "div"
              :typography t/body-medium
              :class (stl/css :color-light)}
    text]])

(mf/defc team-info*
  {::mf/private true}
  []
  [:div {:class (stl/css :modal-info)}
   [:img {:src "images/form/slide-final-team.svg"}]

   [:> heading* {:level 2
                 :typography t/title-medium
                 :class (stl/css :color-light)}
    (tr "onboarding.team-modal.team-definition")]

   [:> text* {:as "div"
              :typography t/body-medium
              :class (stl/css :color-dimmed :margin-bottom)}
    (tr "onboarding.team-modal.create-team-desc")]

   [:ul {:class (stl/css :modal-info-features)}
    [:> team-info-feature* {:icon-id i/document
                            :text (tr "onboarding.team-modal.create-team-feature-1")}]
    [:> team-info-feature* {:icon-id i/move
                            :text (tr "onboarding.team-modal.create-team-feature-2")}]
    [:> team-info-feature* {:icon-id i/tree
                            :text (tr "onboarding.team-modal.create-team-feature-3")}]
    [:> team-info-feature* {:icon-id i/user
                            :text (tr "onboarding.team-modal.create-team-feature-4")}]
    [:> team-info-feature* {:icon-id i/tick
                            :text (tr "onboarding.team-modal.create-team-feature-5")}]]])

(defn- get-available-roles
  []
  [{:id "viewer" :value "viewer" :label (tr "labels.viewer")}
   {:id "editor" :value "editor" :label (tr "labels.editor")}
   {:id "admin"  :value "admin"  :label (tr "labels.admin")}])

(def ^:private schema:team-form
  [:map {:title "TeamForm"}
   [:name ctt/schema:team-name]
   [:role :keyword]
   [:emails {:optional true} [::sm/set ::sm/email]]])

(mf/defc team-form*
  {::mf/private true}
  [{:keys [go-to-team]}]
  (let [initial (mf/with-memo []
                  {:role "editor"})

        form    (fm/use-form :schema schema:team-form
                             :initial initial)

        roles   (mf/use-memo get-available-roles)

        error*  (mf/use-state nil)

        on-success
        (mf/use-fn
         (fn [response]
           (let [team-id (:id response)]
             (st/emit! (du/update-profile-props {:onboarding-team-id team-id
                                                 :onboarding-viewed true}))
             (when go-to-team
               (st/emit! (dcm/go-to-dashboard-recent :team-id team-id))))))

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
               (swap! error* (tr "errors.max-quota-reached" (:target error)))

               (or (= :member-is-muted code)
                   (= :email-has-permanent-bounces code)
                   (= :email-has-complaints code))
               (swap! error* (tr "errors.email-spam-or-permanent-bounces" (:email error)))

               :else
               (swap! error* (tr "errors.generic"))))))

        on-create-without-invitations
        (mf/use-fn
         (fn [{:keys [name]}]
           (let [mdata  {:on-success on-success
                         :on-error   on-error}
                 params {:name name}]
             (st/emit! (-> (dtm/create-team (with-meta params mdata))
                           (with-meta {::ev/origin :onboarding-without-invitations}))
                       (ev/event {::ev/name "onboarding-step"
                                  :label "team:create-team-and-invite-later"
                                  :team-name name
                                  :step 8})
                       (ev/event {::ev/name "onboarding-finish"})))))

        on-create-with-invitations
        (mf/use-fn
         (fn [{:keys [name emails] :as params}]
           (let [mdata  {:on-success on-success
                         :on-error   on-error}]
             (st/emit! (-> (dtm/create-team-with-invitations (with-meta params mdata))
                           (with-meta {::ev/origin :onboarding-with-invitations}))
                       (ev/event {::ev/name "onboarding-step"
                                  :label "team:create-team-and-invite"
                                  :invites (count emails)
                                  :team-name name
                                  :role (:role params)
                                  :step 8})
                       (ev/event {::ev/name "onboarding-finish"})))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [params (:clean-data @form)
                 emails (:emails params)]
             (if (> (count emails) 0)
               (on-create-with-invitations params)
               (on-create-without-invitations params)))))

        on-skip
        (mf/use-fn
         (fn []
           (st/emit! (du/update-profile-props {:onboarding-viewed true})
                     (ev/event {::ev/name "onboarding-step"
                                :label "team:skip-team-creation"
                                :step 7})
                     (ev/event {::ev/name "onboarding-finish"}))))]

    [:div {:class (stl/css :modal-team)}
     [:> fc/form* {:form form
                   :class (stl/css :modal-team-form)
                   :on-submit on-submit}

      [:div {:class (stl/css :modal-team-block)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-light)}
        (tr "onboarding.team-modal.create-team")]

       [:> fc/form-input* {:type "text"
                           :name :name
                           :auto-focus true
                           :auto-complete "off"
                           :placeholder (tr "onboarding.choice.team-up.create-team-placeholder")}]

       [:> text* {:as "div"
                  :typography t/body-small
                  :class (stl/css :color-dimmed)}
        (tr "onboarding.choice.team-up.create-team-desc")]]

      [:div {:class (stl/css :modal-team-block)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-light)}
        (tr "onboarding.choice.team-up.invite-members")]

       (when-let [content (deref error*)]
         [:> context-notification* {:level :error}
          content])

       [:div {:class (stl/css :modal-team-sub-block)}
        [:> fc/form-select* {:name :role
                             :options roles}]
        [:> fc/form-multi-input* {:type "email"
                                  :name :emails
                                  :trim true
                                  :valid-item-fn sm/parse-email
                                  :caution-item-fn #{}
                                  :auto-complete "off"
                                  :placeholder (tr "modals.invite-member.emails")}]]
       [:> text* {:as "div"
                  :typography t/body-small
                  :class (stl/css :color-dimmed)}
        (tr "onboarding.choice.team-up.invite-members-info")]

       (let [params     (:clean-data @form)
             emails     (:emails params)
             num-emails (count emails)]
         [:*
          [:div {:class (stl/css :flex-align-right)}
           [:> fc/form-submit* {:variant "primary"}
            (if (> num-emails 0)
              (tr "onboarding.choice.team-up.create-team-and-invite")
              (tr "onboarding.choice.team-up.create-team-without-invite"))]]

          (when (= num-emails 0)
            [:> text* {:as "div"
                       :typography t/body-small
                       :class (stl/css :color-dimmed :text-align-right)}
             "(" (tr "onboarding.choice.team-up.create-team-and-send-invites-description") ")"])])]]

     [:div {:class (stl/css :link-wrapper)}
      [:> link* {:class (stl/css :link)
                 :action on-skip}
       (tr "onboarding.choice.team-up.continue-without-a-team")]]]))

(mf/defc onboarding-team-modal*
  [{:keys [go-to-team]}]
  [:div {:class (stl/css-case :modal-overlay true)}
   [:div.animated.fade-in {:class (stl/css :modal-container)}
    [:> heading* {:level 1
                  :typography t/title-large
                  :class (stl/css :color-light)}
     (tr "onboarding-v2.welcome.title")]
    [:div {:class (stl/css :modal-sections)}
     [:> team-info*]
     [:div {:class (stl/css :modal-separator)}
      [:div {:class (stl/css :modal-separator-line)}]]
     [:> team-form* {:go-to-team go-to-team}]]]])
