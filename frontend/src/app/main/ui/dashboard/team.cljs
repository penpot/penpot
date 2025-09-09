;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.config :as cfg]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.change-owner]
   [app.main.ui.dashboard.subscription :refer [team*
                                               members-cta*
                                               show-subscription-members-banner?]]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.notifications.badge :refer [badge-notification]]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private arrow-icon
  (deprecated-icon/icon-xref :arrow (stl/css :arrow-icon)))

(def ^:private menu-icon
  (deprecated-icon/icon-xref :menu (stl/css :menu-icon)))

(def ^:private warning-icon
  (deprecated-icon/icon-xref :msg-warning (stl/css :warning-icon)))

(def ^:private success-icon
  (deprecated-icon/icon-xref :msg-success (stl/css :success-icon)))

(def ^:private image-icon
  (deprecated-icon/icon-xref :img (stl/css :image-icon)))

(def ^:private user-icon
  (deprecated-icon/icon-xref :user (stl/css :user-icon)))

(def ^:private document-icon
  (deprecated-icon/icon-xref :document (stl/css :document-icon)))

(def ^:private group-icon
  (deprecated-icon/icon-xref :group (stl/css :group-icon)))

(mf/defc header
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [section team]}]
  (let [on-nav-members       (mf/use-fn #(st/emit! (dcm/go-to-dashboard-members)))
        on-nav-settings      (mf/use-fn #(st/emit! (dcm/go-to-dashboard-settings)))
        on-nav-invitations   (mf/use-fn #(st/emit! (dcm/go-to-dashboard-invitations)))
        on-nav-webhooks      (mf/use-fn #(st/emit! (dcm/go-to-dashboard-webhooks)))

        route                (mf/deref refs/route)
        invite-email         (-> route :query-params :invite-email)

        members-section?     (= section :dashboard-team-members)
        settings-section?    (= section :dashboard-team-settings)
        invitations-section? (= section :dashboard-team-invitations)
        webhooks-section?    (= section :dashboard-team-webhooks)
        permissions          (:permissions team)
        invitations          (:invitations team)

        on-invite-member
        (mf/use-fn
         (mf/deps team invite-email)
         (fn []
           (st/emit! (modal/show {:type :invite-members
                                  :team team
                                  :origin :team
                                  :invite-email invite-email}))))]

    (mf/with-effect [team invite-email]
      (when invite-email
        (on-invite-member)))

    [:header {:class (stl/css :dashboard-header :team) :data-testid "dashboard-header"}
     [:div {:class (stl/css :dashboard-title)}
      [:h1 (cond
             members-section? (tr "labels.members")
             settings-section? (tr "labels.settings")
             invitations-section? (tr "labels.invitations")
             webhooks-section? (tr "labels.webhooks")
             :else nil)]]
     [:nav {:class (stl/css :dashboard-header-menu)}
      [:ul {:class (stl/css :dashboard-header-options)}
       [:li {:class (when members-section? (stl/css :active))}
        [:a {:on-click on-nav-members} (tr "labels.members")]]
       [:li {:class (when invitations-section? (stl/css :active))}
        [:a {:on-click on-nav-invitations} (tr "labels.invitations")]]
       (when (contains? cfg/flags :webhooks)
         [:li {:class (when webhooks-section? (stl/css :active))}
          [:a {:on-click on-nav-webhooks} (tr "labels.webhooks")]])
       [:li {:class (when settings-section? (stl/css :active))}
        [:a {:on-click on-nav-settings} (tr "labels.settings")]]]]
     [:div {:class (stl/css :dashboard-buttons)}
      (if (and (or invitations-section? members-section?) (:is-admin permissions) (not-empty invitations))
        [:a
         {:class (stl/css :btn-secondary :btn-small)
          :on-click on-invite-member
          :data-testid "invite-member"}
         (tr "dashboard.invite-profile")]
        [:div {:class (stl/css :blank-space)}])]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INVITATIONS MODAL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-available-roles
  [permissions]
  (->> [{:value "viewer" :label (tr "labels.viewer")}
        {:value "editor" :label (tr "labels.editor")}
        (when (:is-admin permissions)
          {:value "admin" :label (tr "labels.admin")})]
       (filterv identity)))

(def ^:private schema:invite-member-form
  [:map {:title "InviteMemberForm"}
   [:role :keyword]
   [:emails [::sm/set {:min 1} ::sm/email]]
   [:team-id ::sm/uuid]])

(mf/defc invite-members-modal
  {::mf/register modal/components
   ::mf/register-as :invite-members
   ::mf/props :obj}
  [{:keys [team origin invite-email]}]
  (let [members     (get team :members)
        perms       (get team :permissions)
        team-id     (get team :id)

        roles       (mf/with-memo [perms]
                      (get-available-roles perms))

        initial     (mf/with-memo [team-id invite-email]
                      (if invite-email
                        {:role "editor" :team-id team-id :emails #{invite-email}}
                        {:role "editor" :team-id team-id}))

        form        (fm/use-form :schema schema:invite-member-form
                                 :initial initial)
        error-text  (mf/use-state  "")

        current-data-emails (into #{} (dm/get-in @form [:clean-data :emails]))
        current-members-emails (into #{} (map :email) members)

        on-success
        (fn [_form {:keys [total]}]
          (when (pos? total)
            (st/emit! (ntf/success (tr "notifications.invitation-email-sent"))))

          (st/emit! (modal/hide)
                    (dtm/fetch-members)
                    (dtm/fetch-invitations)))

        on-error
        (fn [_form cause]
          (let [{:keys [type code] :as error} (ex-data cause)]
            (cond
              (and (= :validation type)
                   (= :profile-is-muted code))
              (st/emit! (ntf/error (tr "errors.profile-is-muted"))
                        (modal/hide))

              (and (= :validation type)
                   (= :max-invitations-by-request code))
              (swap! error-text (tr "errors.maximum-invitations-by-request-reached" (:threshold error)))

              (and (= :restriction type)
                   (= :max-quote-reached code))
              (swap! error-text (tr "errors.max-quota-reached" (:target error)))

              (or (= :member-is-muted code)
                  (= :email-has-permanent-bounces code)
                  (= :email-has-complaints code))
              (swap! error-text (tr "errors.email-spam-or-permanent-bounces" (:email error)))

              :else
              (st/emit! (ntf/error (tr "errors.generic"))
                        (modal/hide)))))

        on-submit
        (fn [form]
          (let [params (:clean-data @form)
                mdata  {:on-success (partial on-success form)
                        :on-error   (partial on-error form)}]
            (st/emit! (-> (dtm/create-invitations (with-meta params mdata))
                          (with-meta {::ev/origin origin}))
                      ;; FIXME: looks duplicate
                      (dtm/fetch-invitations)
                      (dtm/fetch-members))))]

    [:div {:class (stl/css-case :modal-team-container true
                                :modal-team-container-workspace (= origin :workspace)
                                :hero (= origin :hero))}
     [:& fm/form {:on-submit on-submit :form form}
      [:div {:class (stl/css :modal-title)}
       (tr "modals.invite-team-member.title")]

      (when (= :workspace origin)
        [:div {:class (stl/css :invite-team-member-text)}
         (tr "modals.invite-team-member.text")])

      (when-not (= "" @error-text)
        [:& context-notification {:content  @error-text
                                  :level :error}])

      (when (some current-data-emails current-members-emails)
        [:& context-notification {:content  (tr "modals.invite-member.repeated-invitation")
                                  :level :warning}])

      [:div {:class (stl/css :role-select)}
       [:p {:class (stl/css :role-title)}
        (tr "onboarding.choice.team-up.roles")]
       [:& fm/select {:name :role :options roles}]]

      [:div {:class (stl/css :invitation-row)}
       [:& fm/multi-input {:type "email"
                           :class (stl/css :email-input)
                           :name :emails
                           :auto-focus? true
                           :trim true
                           :valid-item-fn sm/parse-email
                           :caution-item-fn current-members-emails
                           :label (tr "modals.invite-member.emails")}]]

      [:div {:class (stl/css :action-buttons)}
       [:> fm/submit-button*
        {:label (tr "modals.invite-member-confirm.accept")
         :class (stl/css :accept-btn)
         :disabled (and (boolean (some current-data-emails current-members-emails))
                        (empty? (remove current-members-emails current-data-emails)))}]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MEMBERS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc member-info
  {::mf/props :obj}
  [{:keys [member profile]}]
  (let [is-you? (= (:id profile) (:id member))]
    [:*
     [:img  {:class (stl/css :member-image)
             :src (cfg/resolve-profile-photo-url member)}]
     [:div {:class (stl/css :member-info)}
      [:div {:class (stl/css :member-name)} (:name member)
       (when is-you?
         [:span {:class (stl/css :you)} (tr "labels.you")])]
      [:div {:class (stl/css :member-email)} (:email member)]]]))

(mf/defc rol-info*
  [{:keys [member team on-set-admin on-set-editor on-set-owner on-set-viewer profile]}]
  (let [member-is-owner  (:is-owner member)
        member-is-admin  (and (:is-admin member) (not member-is-owner))
        member-is-editor (and (:can-edit member) (and (not member-is-admin) (not member-is-owner)))
        member-is-viewer (and (not member-is-editor) (not member-is-admin) (not member-is-owner))
        show?            (mf/use-state false)

        permissions      (:permissions team)
        is-owner         (:is-owner permissions)
        is-admin         (:is-admin permissions)

        is-you           (= (:id profile) (:id member))

        can-change-rol   (or is-owner is-admin)
        not-superior     (or (and (not member-is-owner) is-admin)
                             (and can-change-rol (or member-is-admin member-is-editor member-is-viewer)))

        role             (cond
                           member-is-owner  "labels.owner"
                           member-is-admin  "labels.admin"
                           member-is-editor "labels.editor"
                           :else            "labels.viewer")

        on-show          (mf/use-fn #(reset! show? true))
        on-hide          (mf/use-fn #(reset! show? false))]
    [:*
     (if (and can-change-rol not-superior (not (and is-you is-owner)))
       [:div {:class (stl/css :rol-selector :has-priv)
              :role "combobox"
              :aria-labelledby "role-label-id"
              :on-click on-show}
        [:span {:class (stl/css :rol-label)
                :id "role-label-id"} (tr role)]
        arrow-icon]
       [:div {:class (stl/css :rol-selector)}
        [:span {:class (stl/css :rol-label)} (tr role)]])

     [:& dropdown {:show @show? :on-close on-hide :dropdown-id (str "member-role-" (:id member))}
      [:ul {:class (stl/css :roles-dropdown)
            :role "listbox"}
       [:li {:on-click on-set-viewer
             :class (stl/css :rol-dropdown-item)}
        (tr "labels.viewer")]
       [:li {:on-click on-set-editor
             :class (stl/css :rol-dropdown-item)}
        (tr "labels.editor")]
       [:li {:on-click on-set-admin
             :class (stl/css :rol-dropdown-item)}
        (tr "labels.admin")]
       (when is-owner
         [:li {:on-click (partial on-set-owner member)
               :class (stl/css :rol-dropdown-item)}
          (tr "labels.owner")])]]]))

(mf/defc member-actions*
  [{:keys [member team on-delete on-leave profile]}]
  (let [is-owner?   (:is-owner member)
        owner?      (dm/get-in team [:permissions :is-owner])
        admin?      (dm/get-in team [:permissions :is-admin])
        show?       (mf/use-state false)
        is-you?     (= (:id profile) (:id member))
        can-delete? (or owner? admin?)

        on-show     (mf/use-fn #(reset! show? true))
        on-hide     (mf/use-fn #(reset! show? false))]


    (when (or is-you? (and can-delete? (not (and is-owner? (not owner?)))))
      [:*
       [:button {:class (stl/css :menu-btn)
                 :on-click on-show}
        menu-icon]

       [:& dropdown {:show @show? :on-close on-hide :dropdown-id (str "member-actions-" (:id member))}
        [:ul {:class (stl/css :actions-dropdown)}
         (when is-you?
           [:li {:on-click on-leave
                 :class (stl/css :action-dropdown-item)
                 :key "is-you-option"}
            (tr "dashboard.leave-team")])
         (when (and can-delete? (not is-you?) (not (and is-owner? (not owner?))))
           [:li {:on-click on-delete
                 :class (stl/css :action-dropdown-item)
                 :key "is-not-you-option"} (tr "labels.remove-member")])]]])))

(defn- set-role! [member-id role]
  (let [params {:member-id member-id :role role}]
    (st/emit! (dtm/update-member-role params))))

(mf/defc team-member*
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [team member total-members profile]}]

  (let [member-id     (:id member)
        on-set-admin  (mf/use-fn (mf/deps member-id) (partial set-role! member-id :admin))
        on-set-editor (mf/use-fn (mf/deps member-id) (partial set-role! member-id :editor))
        on-set-viewer (mf/use-fn (mf/deps member-id) (partial set-role! member-id :viewer))
        owner?        (dm/get-in team [:permissions :is-owner])

        on-set-owner
        (mf/use-fn
         (mf/deps member)
         (fn [member _event]
           (let [params {:type :confirm
                         :title (tr "modals.promote-owner-confirm.title")
                         :message (tr "modals.promote-owner-confirm.message" (:name member))
                         :scd-message (tr "modals.promote-owner-confirm.hint")
                         :accept-label (tr "modals.promote-owner-confirm.accept")
                         :on-accept (partial set-role! member-id :owner)
                         :accept-style :primary}]
             (st/emit! (modal/show params)))))

        on-success
        (mf/use-fn #(rx/of (dcm/go-to-dashboard-recent :team-id :default)))

        on-error
        (mf/use-fn
         (fn [{:keys [code] :as error}]
           (condp = code
             :no-enough-members-for-leave
             (rx/of (ntf/error (tr "errors.team-leave.insufficient-members")))

             :member-does-not-exist
             (rx/of (ntf/error (tr "errors.team-leave.member-does-not-exists")))

             :owner-cant-leave-team
             (rx/of (ntf/error (tr "errors.team-leave.owner-cant-leave")))

             (rx/throw error))))

        on-delete-accepted
        (mf/use-fn
         (mf/deps team on-success on-error)
         (fn []
           (st/emit! (dtm/delete-team (with-meta team {:on-success on-success
                                                       :on-error on-error})))))

        on-leave-accepted
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [member-id]
           (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
             (st/emit! (dtm/leave-current-team (with-meta params
                                                 {:on-success on-success
                                                  :on-error on-error}))))))

        on-leave-and-close
        (mf/use-fn
         (mf/deps on-delete-accepted)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message  (tr "modals.leave-and-close-confirm.message" (:name team))
                       :scd-message (tr "modals.leave-and-close-confirm.hint")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept on-delete-accepted}))))

        on-change-owner-and-leave
        (mf/use-fn
         (mf/deps profile team on-leave-accepted)
         (fn []
           (st/emit! (dtm/fetch-members)
                     (modal/show
                      {:type :leave-and-reassign
                       :profile profile
                       :team team
                       :accept on-leave-accepted}))))

        on-leave
        (mf/use-fn
         (mf/deps on-leave-accepted)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message  (tr "modals.leave-confirm.message")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept on-leave-accepted}))))

        on-delete
        (mf/use-fn
         (mf/deps member-id)
         (fn []
           (let [on-accept #(st/emit! (dtm/delete-member {:member-id member-id}))
                 params    {:type :confirm
                            :title (tr "modals.delete-team-member-confirm.title")
                            :message  (tr "modals.delete-team-member-confirm.message")
                            :accept-label (tr "modals.delete-team-member-confirm.accept")
                            :on-accept on-accept}]
             (st/emit! (modal/show params)))))

        on-leave'
        (cond (= 1 total-members) on-leave-and-close
              (= true owner?)       on-change-owner-and-leave
              :else                 on-leave)]

    [:div {:class (stl/css :table-row)}
     [:div {:class (stl/css :table-field :field-name)}
      [:& member-info {:member member :profile profile}]]

     [:div {:class (stl/css :table-field :field-roles)}
      [:> rol-info*  {:member member
                      :team team
                      :on-set-admin on-set-admin
                      :on-set-editor on-set-editor
                      :on-set-viewer on-set-viewer
                      :on-set-owner on-set-owner
                      :profile profile}]]

     [:div {:class (stl/css :table-field :field-actions)}
      [:> member-actions* {:member member
                           :profile profile
                           :team team
                           :on-delete on-delete
                           :on-leave on-leave'}]]]))

(mf/defc team-members*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [team profile]}]
  (let [members (get team :members)

        total-members
        (count members)

        owner
        (mf/with-memo [members]
          (d/seek :is-owner members))

        members
        (mf/with-memo [team]
          (->> (:members team)
               (sort-by :created-at)
               (remove :is-owner)
               (vec)))]

    [:div {:class (stl/css :dashboard-table :team-members)}
     [:div {:class (stl/css :table-header)}
      [:div {:class (stl/css :table-field :title-field-name)} (tr "labels.member")]
      [:div {:class (stl/css :table-field :title-field-role)} (tr "labels.role")]]

     [:div {:class (stl/css :table-rows)}
      [:> team-member*
       {:member owner
        :team team
        :profile profile
        :total-members total-members}]

      (for [item members]
        [:> team-member*
         {:member item
          :team team
          :profile profile
          :key (dm/str (:id item))
          :total-members total-members}])]]))

(mf/defc team-members-page*
  {::mf/props :obj}
  [{:keys [team profile]}]
  (mf/with-effect [team]
    (dom/set-html-title
     (tr "title.team-members"
         (if (:is-default team)
           (tr "dashboard.your-penpot")
           (:name team)))))

  (mf/with-effect []
    (st/emit! (dtm/fetch-members)))

  [:*
   [:& header {:section :dashboard-team-members :team team}]
   [:section {:class (stl/css :dashboard-container :dashboard-team-members)}

    [:> team-members*
     {:profile profile
      :team team}]

    (when (and (contains? cfg/flags :subscriptions)
               (show-subscription-members-banner? team profile))
      [:> members-cta* {:team team}])]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INVITATIONS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc invitation-role-selector*
  {::mf/props :obj}
  [{:keys [can-invite role status on-change]}]
  (let [show?   (mf/use-state false)
        label   (cond
                  (= role :owner)  (tr "labels.owner")
                  (= role :admin)  (tr "labels.admin")
                  (= role :editor) (tr "labels.editor")
                  :else            (tr "labels.viewer"))

        on-hide (mf/use-fn #(reset! show? false))
        on-show (mf/use-fn #(reset! show? true))

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [role (-> (dom/get-current-target event)
                          (dom/get-data "role")
                          (keyword))]
             (on-change role event))))]

    [:*
     (if (and can-invite (= status :pending))
       [:div {:class (stl/css :rol-selector :has-priv)
              :on-click on-show}
        [:span {:class (stl/css :rol-label)} label]
        arrow-icon]
       [:div {:class (stl/css :rol-selector)}
        [:span {:class (stl/css :rol-label)} label]])

     [:& dropdown {:show @show? :on-close on-hide :dropdown-id "invitation-role-selector"}
      [:ul {:class (stl/css :roles-dropdown)}
       [:li {:data-role "admin"
             :class (stl/css :rol-dropdown-item)
             :on-click on-change'}
        (tr "labels.admin")]
       [:li {:data-role "editor"
             :class (stl/css :rol-dropdown-item)
             :on-click on-change'}
        (tr "labels.editor")]
       [:li {:data-role "viewer"
             :class (stl/css :rol-dropdown-item)
             :on-click on-change'}
        (tr "labels.viewer")]]]]))

(mf/defc invitation-actions*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [invitation team-id]}]
  (let [show?   (mf/use-state false)

        email   (:email invitation)
        role    (:role invitation)

        on-error
        (mf/use-fn
         (mf/deps email)
         (fn [cause]
           (let [{:keys [type code] :as error} (ex-data cause)]
             (cond
               (and (= :validation type)
                    (= :profile-is-muted code))
               (rx/of (ntf/error (tr "errors.profile-is-muted")))

               (and (= :validation type)
                    (= :member-is-muted code))
               (rx/of (ntf/error (tr "errors.member-is-muted")))

               (and (= :restriction type)
                    (or (= :email-has-permanent-bounces code)
                        (= :email-has-complaints code)))
               (rx/of (ntf/error (tr "errors.email-has-permanent-bounces" email)))

               :else
               (rx/throw cause)))))

        on-delete
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params {:email email :team-id team-id}
                 mdata  {:on-success #(st/emit! (dtm/fetch-invitations))}]
             (st/emit! (dtm/delete-invitation (with-meta params mdata))))))

        on-resend-success
        (mf/use-fn
         (fn []
           (st/emit! (ntf/success (tr "notifications.invitation-email-sent"))
                     (modal/hide)
                     (dtm/fetch-invitations))))

        on-resend
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params (with-meta {:emails #{email}
                                    :team-id team-id
                                    :resend? true
                                    :role role}
                          {:on-success on-resend-success
                           :on-error on-error})]
             (st/emit!
              (-> (dtm/create-invitations params)
                  (with-meta {::ev/origin :team}))))))

        on-copy-success
        (mf/use-fn
         (fn []
           (st/emit! (ntf/success (tr "notifications.invitation-link-copied"))
                     (modal/hide))))

        on-copy
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params (with-meta {:email email :team-id team-id}
                          {:on-success on-copy-success
                           :on-error on-error})]
             (st/emit!
              (-> (dtm/copy-invitation-link params)
                  (with-meta {::ev/origin :team}))))))

        on-hide (mf/use-fn #(reset! show? false))
        on-show (mf/use-fn #(reset! show? true))]

    [:*
     [:button {:class (stl/css :menu-btn)
               :on-click on-show}
      menu-icon]

     [:& dropdown {:show @show? :on-close on-hide :dropdown-id "invitation-actions"}
      [:ul {:class (stl/css :actions-dropdown :invitations-dropdown)}
       [:li {:on-click on-copy
             :class (stl/css :action-dropdown-item)}
        (tr "labels.copy-invitation-link")]
       [:li {:on-click on-resend
             :class (stl/css :action-dropdown-item)}
        (tr "labels.resend-invitation")]
       [:li {:on-click on-delete
             :class (stl/css :action-dropdown-item)}
        (tr "labels.delete-invitation")]]]]))

(mf/defc invitation-row*
  {::mf/wrap [mf/memo]
   ::mf/private true
   ::mf/props :obj}
  [{:keys [invitation can-invite team-id]}]

  (let [expired? (:expired invitation)
        email    (:email invitation)
        role     (:role invitation)
        status   (if expired? :expired :pending)
        type     (if expired? :warning :default)

        badge-content
        (if (= status :expired)
          (tr "labels.expired-invitation")
          (tr "labels.pending-invitation"))

        on-change-role
        (mf/use-fn
         (mf/deps email team-id)
         (fn [role _event]
           (let [params {:email email :team-id team-id :role role}
                 mdata  {:on-success #(st/emit! (dtm/fetch-invitations))}]
             (st/emit! (dtm/update-invitation-role (with-meta params mdata))))))]

    [:div {:class (stl/css :table-row :table-row-invitations)}
     [:div {:class (stl/css :table-field :field-email)} email]

     [:div {:class (stl/css :table-field :field-roles)}
      [:> invitation-role-selector*
       {:can-invite can-invite
        :role role
        :status status
        :on-change on-change-role}]]

     [:div {:class (stl/css :table-field :field-status)}
      [:& badge-notification {:type type :content badge-content}]]

     [:div {:class (stl/css :table-field :field-actions)}
      (when ^boolean can-invite
        [:> invitation-actions*
         {:invitation invitation
          :team-id team-id}])]]))

(mf/defc empty-invitation-table*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [can-invite team]}]
  (let
   [route                (mf/deref refs/route)
    invite-email         (-> route :query-params :invite-email)
    on-invite-member     (mf/use-fn
                          (mf/deps team invite-email)
                          (fn []
                            (st/emit! (modal/show {:type :invite-members
                                                   :team team
                                                   :origin :team
                                                   :invite-email invite-email}))))]
    [:div {:class (stl/css :empty-invitations)}
     [:span (tr "labels.no-invitations")]
     (when ^boolean can-invite
       [[:span (tr "labels.no-invitations-gather-people")]
        [:a
         {:class (stl/css :btn-empty-invitations)
          :on-click on-invite-member
          :data-testid "invite-member"}
         (tr "dashboard.invite-profile")]
        [:div {:class (stl/css :blank-space)}]])]))

(mf/defc invitation-section*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [team]}]
  (let [permissions (get team :permissions)
        invitations (get team :invitations)

        team-id     (get team :id)

        owner?      (get permissions :is-owner)
        admin?      (get permissions :is-admin)
        can-invite? (or owner? admin?)]

    [:div {:class (stl/css :invitations)}
     [:div {:class (stl/css :table-header)}
      [:div {:class (stl/css :title-field-name)} (tr "labels.invitations")]
      [:div {:class (stl/css :title-field-role)} (tr "labels.role")]
      [:div {:class (stl/css :title-field-status)} (tr "labels.status")]]
     (if (empty? invitations)
       [:> empty-invitation-table* {:can-invite can-invite? :team team}]
       [:div {:class (stl/css :table-rows)}
        (for [invitation invitations]
          [:> invitation-row*
           {:key (:email invitation)
            :invitation invitation
            :can-invite can-invite?
            :team-id team-id}])])]))

(mf/defc team-invitations-page*
  {::mf/props :obj}
  [{:keys [team profile]}]

  (mf/with-effect [team]
    (dom/set-html-title
     (tr "title.team-invitations"
         (if (:is-default team)
           (tr "dashboard.your-penpot")
           (:name team)))))

  (mf/with-effect []
    (st/emit! (dtm/fetch-invitations)))

  [:*
   [:& header {:section :dashboard-team-invitations
               :team team}]
   [:section {:class (stl/css :dashboard-team-invitations)}

    [:> invitation-section* {:team team}]

    (when (and (contains? cfg/flags :subscriptions)
               (show-subscription-members-banner? team profile))
      [:> members-cta* {:team team}])]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBHOOKS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:webhook-form
  [:map {:title "WebhookForm"}
   [:uri [::sm/uri {:max 4069 :prefix #"^http[s]?://"
                    :error/code "errors.webhooks.invalid-uri"}]]
   [:mtype ::sm/text]])

(def valid-webhook-mtypes
  [{:label "application/json" :value "application/json"}
   {:label "application/transit+json" :value "application/transit+json"}])

(defn- extract-status
  [error-code]
  (-> error-code (str/split #":") second))

(mf/defc webhook-modal
  {::mf/register modal/components
   ::mf/register-as :webhook}
  [{:keys [webhook]}]

  (let [initial (mf/with-memo []
                  (or (some-> webhook (update :uri str))
                      {:is-active false :mtype "application/json"}))
        form    (fm/use-form :schema schema:webhook-form
                             :initial initial)
        on-success
        (mf/use-fn
         (fn [_]
           (let [message (tr "dashboard.webhooks.create.success")]
             (rx/of (ntf/success message)
                    (modal/hide)))))

        on-error
        (mf/use-fn
         (fn [form error]
           (let [{:keys [type code hint]} (ex-data error)]
             (if (and (= type :validation)
                      (= code :webhook-validation))
               (let [message (cond
                               (= hint "unknown")
                               (tr "errors.webhooks.unexpected")
                               (= hint "invalid-uri")
                               (tr "errors.webhooks.invalid-uri")
                               (= hint "ssl-validation-error")
                               (tr "errors.webhooks.ssl-validation")
                               (= hint "timeout")
                               (tr "errors.webhooks.timeout")
                               (= hint "connection-error")
                               (tr "errors.webhooks.connection")
                               (str/starts-with? hint "unexpected-status")
                               (tr "errors.webhooks.unexpected-status" (extract-status hint)))]
                 (swap! form assoc-in [:errors :uri] {:message message}))
               (rx/throw error)))))

        on-create-submit
        (mf/use-fn
         (fn [form]
           (let [cdata  (:clean-data @form)
                 mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:uri        (:uri cdata)
                         :mtype      (:mtype cdata)
                         :is-active  (:is-active cdata)}]
             (st/emit! (dtm/create-webhook
                        (with-meta params mdata))))))

        on-update-submit
        (mf/use-fn
         (fn [form]
           (let [params (:clean-data @form)
                 mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}]
             (st/emit! (dtm/update-webhook
                        (with-meta params mdata))))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [data (:clean-data @form)]
             (if (:id data)
               (on-update-submit form)
               (on-create-submit form)))))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form :on-submit on-submit}
       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)}
         (if webhook
           (tr "modals.edit-webhook.title")
           (tr "modals.create-webhook.title"))]

        [:button {:class (stl/css :modal-close-btn)
                  :on-click modal/hide!} deprecated-icon/close]]

       [:div {:class (stl/css :modal-content)}
        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "text"
                       :auto-focus? true
                       :form form
                       :name :uri
                       :label (tr "modals.create-webhook.url.label")
                       :placeholder (tr "modals.create-webhook.url.placeholder")}]]
        [:div  {:class (stl/css :fields-row)}
         [:div {:class (stl/css :select-title)} (tr "dashboard.webhooks.content-type")]
         [:& fm/select {:options valid-webhook-mtypes
                        :default "application/json"
                        :name :mtype}]]
        [:div  {:class (stl/css :fields-row)}
         [:& fm/input {:type "checkbox"
                       :class (stl/css :custom-input-checkbox)
                       :form form
                       :name :is-active
                       :label (tr "dashboard.webhooks.active")}]
         [:div {:class (stl/css :hint)} (tr "dashboard.webhooks.active.explain")]]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:input {:class (stl/css :cancel-button)
                  :type "button"
                  :value (tr "labels.cancel")
                  :on-click modal/hide!}]
         [:> fm/submit-button*
          {:label (if webhook
                    (tr "modals.edit-webhook.submit-label")
                    (tr "modals.create-webhook.submit-label"))}]]]]]]))

(mf/defc webhooks-hero*
  {::mf/props :obj}
  []
  [:div {:class (stl/css :webhooks-hero-container)}
   [:h2 {:class (stl/css :hero-title)}
    (tr "labels.webhooks")]
   [:> i18n/tr-html* {:class (stl/css :hero-desc)
                      :content (tr "dashboard.webhooks.description")}]
   [:button {:class (stl/css :hero-btn)
             :on-click #(st/emit! (modal/show :webhook {}))}
    (tr "dashboard.webhooks.create")]])

(mf/defc webhook-actions*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-edit on-delete can-edit]}]
  (let [show?   (mf/use-state false)
        on-show (mf/use-fn #(reset! show? true))
        on-hide (mf/use-fn #(reset! show? false))]
    (if can-edit
      [:*
       [:button {:class (stl/css :menu-btn)
                 :on-click on-show}
        menu-icon]
       [:& dropdown {:show @show? :on-close on-hide :dropdown-id "webhook-actions"}
        [:ul {:class (stl/css :webhook-actions-dropdown)}
         [:li {:on-click on-edit
               :class (stl/css :webhook-dropdown-item)} (tr "labels.edit")]
         [:li {:on-click on-delete
               :class (stl/css :webhook-dropdown-item)} (tr "labels.delete")]]]]

      [:span {:title (tr "dashboard.webhooks.cant-edit")
              :class (stl/css :menu-disabled)}
       [:> icon* {:icon-id i/menu}]])))

(mf/defc webhook-item*
  {::mf/wrap [mf/memo]
   ::mf/props :obj
   ::mf/private true}
  [{:keys [webhook permissions]}]
  (let [error-code (:error-code webhook)
        id         (:id webhook)
        creator-id (:profile-id webhook)
        profile    (mf/deref refs/profile)
        user-id    (:id profile)
        can-edit   (or (:can-edit permissions)
                       (= creator-id user-id))
        on-edit
        (mf/use-fn
         (mf/deps webhook)
         (fn []
           (st/emit! (modal/show :webhook {:webhook webhook}))))

        on-delete-accepted
        (mf/use-fn
         (mf/deps id)
         (fn []
           (let [params {:id id}
                 mdata  {:on-success #(st/emit! (dtm/fetch-webhooks))}]
             (st/emit! (dtm/delete-webhook (with-meta params mdata))))))

        on-delete
        (mf/use-fn
         (mf/deps on-delete-accepted)
         (fn []
           (let [params {:type :confirm
                         :title (tr "modals.delete-webhook.title")
                         :message (tr "modals.delete-webhook.message")
                         :accept-label (tr "modals.delete-webhook.accept")
                         :on-accept on-delete-accepted}]
             (st/emit! (modal/show params)))))

        last-delivery-text
        (if (nil? error-code)
          (tr "webhooks.last-delivery.success")
          (dm/str (tr "errors.webhooks.last-delivery")
                  (cond
                    (= error-code "ssl-validation-error")
                    (dm/str " " (tr "errors.webhooks.ssl-validation"))

                    (str/starts-with? error-code "unexpected-status")
                    (dm/str " " (tr "errors.webhooks.unexpected-status" (extract-status error-code))))))]


    [:div {:class (stl/css :table-row :webhook-row)}
     [:div {:class (stl/css :table-field :last-delivery)
            :title last-delivery-text}
      (if (nil? error-code)
        success-icon
        warning-icon)]
     [:div {:class (stl/css :table-field :uri)}
      [:div (dm/str (:uri webhook))]]
     [:div {:class (stl/css :table-field :active)}
      [:div (if (:is-active webhook)
              (tr "labels.active")
              (tr "labels.inactive"))]]
     [:div {:class (stl/css :table-field :actions)}
      [:> webhook-actions*
       {:on-edit on-edit
        :on-delete on-delete
        :can-edit can-edit}]]]))

(mf/defc webhooks-list*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [webhooks permissions]}]
  [:div {:class (stl/css :table-rows :webhook-table)}
   (for [webhook webhooks]
     [:> webhook-item*
      {:webhook webhook
       :key (dm/str (:id webhook))
       :permissions permissions}])])

(mf/defc webhooks-page*
  {::mf/props :obj}
  [{:keys [team]}]
  (let [webhooks (:webhooks team)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-webhooks"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect []
      (st/emit! (dtm/fetch-webhooks)))

    [:*
     [:& header {:team team :section :dashboard-team-webhooks}]
     [:section {:class (stl/css :dashboard-container :dashboard-team-webhooks)}
      [:*
       [:> webhooks-hero* {}]
       (if (empty? webhooks)
         [:div {:class (stl/css :webhooks-empty)}
          [:div (tr "dashboard.webhooks.empty.no-webhooks")]
          [:div (tr "dashboard.webhooks.empty.add-one")]]
         [:> webhooks-list*
          {:webhooks webhooks
           :permissions (:permissions team)}])]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SETTINGS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc team-settings-page*
  {::mf/props :obj}
  [{:keys [team]}]
  (let [finput      (mf/use-ref)

        members     (get team :members)
        stats       (get team :stats)

        owner       (d/seek :is-owner members)

        permissions (:permissions team)
        can-edit    (or (:is-owner permissions)
                        (:is-admin permissions))

        on-image-click
        (mf/use-fn #(dom/click (mf/ref-val finput)))

        on-file-selected
        (fn [file]
          (st/emit! (dtm/update-team-photo file)))]

    (mf/with-effect [team]
      (dom/set-html-title (tr "title.team-settings"
                              (if (:is-default team)
                                (tr "dashboard.your-penpot")
                                (:name team)))))

    (mf/with-effect []
      (st/emit! (dtm/fetch-members)
                (dtm/fetch-stats)))

    [:*
     [:& header {:section :dashboard-team-settings :team team}]
     [:section {:class (stl/css :dashboard-team-settings)}
      [:div {:class (stl/css :settings-container)}
       [:div {:class (stl/css :block :info-block)}
        [:div {:class (stl/css :team-icon)}
         (when can-edit
           [:button {:class (stl/css :update-overlay)
                     :on-click on-image-click}
            image-icon])
         [:img {:class (stl/css :team-image)
                :src (cfg/resolve-team-photo-url team)}]
         (when can-edit
           [:& file-uploader {:accept "image/jpeg,image/png"
                              :multi false
                              :ref finput
                              :on-selected on-file-selected}])]
        [:div {:class (stl/css :block-label)}
         (tr "dashboard.team-info")]
        [:div {:class (stl/css :block-text)}
         (:name team)]]

       [:div {:class (stl/css :block)}
        [:div {:class (stl/css :block-label)}
         (tr "dashboard.team-members")]

        [:div {:class (stl/css :block-content)}
         [:img {:class (stl/css :owner-icon)
                :src (cfg/resolve-profile-photo-url owner)}]
         [:span {:class (stl/css :block-text)}
          (str (:name owner) " ("  (tr "labels.owner") ")")]]

        [:div {:class (stl/css :block-content)}
         user-icon
         [:span {:class (stl/css :block-text)}
          (tr "dashboard.num-of-members" (count members))]]]

       [:div {:class (stl/css :block)}
        [:div {:class (stl/css :block-label)}
         (tr "dashboard.team-projects")]

        [:div {:class (stl/css :block-content)}
         group-icon
         [:span {:class (stl/css :block-text)}
          (tr "labels.num-of-projects" (i18n/c (dec (:projects stats))))]]

        [:div {:class (stl/css :block-content)}
         document-icon
         [:span {:class (stl/css :block-text)}
          (tr "labels.num-of-files" (i18n/c (:files stats)))]]]

       (when (contains? cfg/flags :subscriptions)
         [:> team* {:is-owner (:is-owner permissions) :team team}])]]]))

