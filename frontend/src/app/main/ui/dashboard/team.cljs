;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.change-owner]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [section team]}]
  (let [on-nav-members           (mf/use-fn #(st/emit! (dd/go-to-team-members)))
        on-nav-settings          (mf/use-fn #(st/emit! (dd/go-to-team-settings)))
        on-nav-invitations       (mf/use-fn #(st/emit! (dd/go-to-team-invitations)))
        on-nav-webhooks          (mf/use-fn #(st/emit! (dd/go-to-team-webhooks)))

        members-section?     (= section :dashboard-team-members)
        settings-section?    (= section :dashboard-team-settings)
        invitations-section? (= section :dashboard-team-invitations)
        webhooks-section?    (= section :dashboard-team-webhooks)
        permissions          (:permissions team)

        on-invite-member
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (modal/show {:type :invite-members
                                  :team team
                                  :origin :team}))))]

    [:header.dashboard-header.team
     [:div.dashboard-title
      [:h1 (cond
             members-section? (tr "labels.members")
             settings-section? (tr "labels.settings")
             invitations-section? (tr "labels.invitations")
             webhooks-section? (tr "labels.webhooks")
             :else nil)]]
     [:nav.dashboard-header-menu
      [:ul.dashboard-header-options
       [:li {:class (when members-section? "active")}
        [:a {:on-click on-nav-members} (tr "labels.members")]]
       [:li {:class (when invitations-section? "active")}
        [:a {:on-click on-nav-invitations} (tr "labels.invitations")]]
       (when (contains? cfg/flags :webhooks)
         [:li {:class (when webhooks-section? "active")}
          [:a {:on-click on-nav-webhooks} (tr "labels.webhooks")]])
       [:li {:class (when settings-section? "active")}
        [:a {:on-click on-nav-settings} (tr "labels.settings")]]]]
     [:div.dashboard-buttons
      (if (and (or invitations-section? members-section?) (:is-admin permissions))
        [:a.btn-secondary.btn-small
         {:on-click on-invite-member
          :data-test "invite-member"}
         (tr "dashboard.invite-profile")]
        [:div.blank-space])]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INVITATIONS MODAL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-available-roles
  [permissions]
  (->> [{:value "editor" :label (tr "labels.editor")}
        (when (:is-admin permissions)
          {:value "admin" :label (tr "labels.admin")})
        ;; Temporarily disabled viewer roles
        ;; https://tree.taiga.io/project/penpot/issue/1083
        ;; {:value "viewer" :label (tr "labels.viewer")}
        ]
       (filterv identity)))

(s/def ::emails (s/and ::us/set-of-valid-emails d/not-empty?))
(s/def ::role  ::us/keyword)
(s/def ::team-id ::us/uuid)

(s/def ::invite-member-form
  (s/keys :req-un [::role ::emails ::team-id]))

(mf/defc invite-members-modal
  {::mf/register modal/components
   ::mf/register-as :invite-members
   ::mf/wrap-props false}
  [{:keys [team origin]}]
  (let [members-map (mf/deref refs/dashboard-team-members)
        perms       (:permissions team)

        roles       (mf/use-memo (mf/deps perms) #(get-available-roles perms))
        initial     (mf/use-memo (constantly {:role "editor" :team-id (:id team)}))
        form        (fm/use-form :spec ::invite-member-form
                                 :initial initial)
        error-text  (mf/use-state  "")

        current-data-emails (into #{} (dm/get-in @form [:clean-data :emails]))
        current-members-emails (into #{} (map (comp :email second)) members-map)

        on-success
        (fn [_form {:keys [total]}]
          (when (pos? total)
            (st/emit! (msg/success (tr "notifications.invitation-email-sent"))))

          (st/emit! (modal/hide)
                    (dd/fetch-team-invitations)))

        on-error
        (fn [{:keys [type code] :as error}]
          (cond
            (and (= :validation type)
                 (= :profile-is-muted code))
            (st/emit! (msg/error (tr "errors.profile-is-muted"))
                      (modal/hide))

            (and (= :validation type)
                 (or (= :member-is-muted code)
                     (= :email-has-permanent-bounces code)))
            (swap! error-text (tr "errors.email-spam-or-permanent-bounces" (:email error)))

            :else
            (st/emit! (msg/error (tr "errors.generic"))
                      (modal/hide))))

        on-submit
        (fn [form]
          (let [params (:clean-data @form)
                mdata  {:on-success (partial on-success form)
                        :on-error   (partial on-error form)}]
            (st/emit! (-> (dd/invite-team-members (with-meta params mdata))
                          (with-meta {::ev/origin origin}))
                      (dd/fetch-team-invitations))))]

    [:div.modal.dashboard-invite-modal.form-container
     {:class (dom/classnames
              :hero (= origin :hero))}
     [:& fm/form {:on-submit on-submit :form form}
      [:div.title
       [:span.text (tr "modals.invite-team-member.title")]]

      (when-not (= "" @error-text)
        [:div.error
         [:span.icon i/msg-error]
         [:span.text @error-text]])

      (when (some current-data-emails current-members-emails)
        [:div.warning
         [:span.icon i/msg-warning]
         [:span.text (tr "modals.invite-member.repeated-invitation")]])

      [:div.form-row
       [:p.label (tr "onboarding.choice.team-up.roles")]
       [:& fm/select {:name :role :options roles}]]

      [:div.form-row
       [:& fm/multi-input {:type "email"
                           :name :emails
                           :auto-focus? true
                           :trim true
                           :valid-item-fn us/parse-email
                           :caution-item-fn current-members-emails
                           :label (tr "modals.invite-member.emails")
                           :on-submit  on-submit}]]

      [:div.action-buttons
       [:> fm/submit-button*
        {:label (tr "modals.invite-member-confirm.accept")
         :disabled (and (boolean (some current-data-emails current-members-emails))
                        (empty? (remove current-members-emails current-data-emails)))}]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MEMBERS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc member-info
  {::mf/wrap-props false}
  [{:keys [member profile]}]
  (let [is-you? (= (:id profile) (:id member))]
    [:*
     [:div.member-image
      [:img {:src (cfg/resolve-profile-photo-url member)}]]
     [:div.member-info
      [:div.member-name (:name member)
       (when is-you?
         [:span.you (tr "labels.you")])]
      [:div.member-email (:email member)]]]))

(mf/defc rol-info
  {::mf/wrap-props false}
  [{:keys [member team on-set-admin on-set-editor on-set-owner profile]}]
  (let [member-is-owner?  (:is-owner member)
        member-is-admin?  (and (:is-admin member) (not member-is-owner?))
        member-is-editor? (and (:can-edit member) (and (not member-is-admin?) (not member-is-owner?)))
        show?             (mf/use-state false)

        you-owner?        (dm/get-in team [:permissions :is-owner])
        you-admin?        (dm/get-in team [:permissions :is-admin])
        is-you?           (= (:id profile) (:id member))

        can-change-rol?   (or you-owner? you-admin?)
        not-superior?     (or you-owner? (and can-change-rol? (or member-is-admin? member-is-editor?)))

        role              (cond
                            member-is-owner?  "labels.owner"
                            member-is-admin?  "labels.admin"
                            member-is-editor? "labels.editor"
                            :else             "labels.viewer")

        on-show           (mf/use-fn #(reset! show? true))
        on-hide           (mf/use-fn #(reset! show? false))]
    [:*
     (if (and can-change-rol? not-superior? (not (and is-you? you-owner?)))
       [:div.rol-selector.has-priv  {:on-click on-show}
        [:span.rol-label (tr role)]
        [:span.icon i/arrow-down]]
       [:div.rol-selector
        [:span.rol-label (tr role)]])

     [:& dropdown {:show @show? :on-close on-hide}
      [:ul.dropdown.options-dropdown
       [:li {:on-click on-set-admin} (tr "labels.admin")]
       [:li {:on-click on-set-editor} (tr "labels.editor")]
        ;; Temporarily disabled viewer role
        ;; https://tree.taiga.io/project/penpot/issue/1083
        ;;  [:li {:on-click set-viewer} (tr "labels.viewer")]
       (when you-owner?
         [:li {:on-click (partial on-set-owner member)} (tr "labels.owner")])]]]))

(mf/defc member-actions
  {::mf/wrap-props false}
  [{:keys [member team on-delete on-leave profile]}]
  (let [is-owner?   (:is-owner member)
        owner?      (dm/get-in team [:permissions :is-owner])
        admin?      (dm/get-in team [:permissions :is-admin])
        show?       (mf/use-state false)
        is-you?     (= (:id profile) (:id member))
        can-delete? (or owner? admin?)

        on-show     (mf/use-fn #(reset! show? true))
        on-hide     (mf/use-fn #(reset! show? false))]

    [:*
     (when (or is-you? (and can-delete? (not (and is-owner? (not owner?)))))
       [:span.icon {:on-click on-show} [i/actions]])

     [:& dropdown {:show @show? :on-close on-hide}
      [:ul.dropdown.actions-dropdown
       (when is-you?
         [:li {:on-click on-leave} (tr "dashboard.leave-team")])
       (when (and can-delete? (not is-you?) (not (and is-owner? (not owner?))))
         [:li {:on-click on-delete} (tr "labels.remove-member")])]]]))

(defn- set-role! [member-id role]
  (let [params {:member-id member-id :role role}]
    (st/emit! (dd/update-team-member-role params))))

(mf/defc team-member
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [team member members profile]}]

  (let [member-id  (:id member)
        on-set-admin  (mf/use-fn (mf/deps member-id) (partial set-role! member-id :admin))
        on-set-editor (mf/use-fn (mf/deps member-id) (partial set-role! member-id :editor))
        owner?     (dm/get-in team [:permissions :is-owner])

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
        (mf/use-fn
         (mf/deps profile)
         (fn []
           (st/emit! (dd/go-to-projects (:default-team-id profile))
                     (modal/hide)
                     (du/fetch-teams))))

        on-error
        (mf/use-fn
         (fn [{:keys [code] :as error}]
           (condp = code

             :no-enough-members-for-leave
             (rx/of (msg/error (tr "errors.team-leave.insufficient-members")))

             :member-does-not-exist
             (rx/of (msg/error (tr "errors.team-leave.member-does-not-exists")))

             :owner-cant-leave-team
             (rx/of (msg/error (tr "errors.team-leave.owner-cant-leave")))

             (rx/throw error))))

        on-delete-accepted
        (mf/use-fn
         (mf/deps team on-success on-error)
         (fn []
           (st/emit! (dd/delete-team (with-meta team {:on-success on-success
                                                      :on-error on-error})))))

        on-leave-accepted
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [member-id]
           (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
             (st/emit! (dd/leave-team (with-meta params
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
           (st/emit! (dd/fetch-team-members (:id team))
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
           (let [on-accept #(st/emit! (dd/delete-team-member {:member-id member-id}))
                 params    {:type :confirm
                            :title (tr "modals.delete-team-member-confirm.title")
                            :message  (tr "modals.delete-team-member-confirm.message")
                            :accept-label (tr "modals.delete-team-member-confirm.accept")
                            :on-accept on-accept}]
             (st/emit! (modal/show params)))))

        on-leave'
        (cond (= 1 (count members)) on-leave-and-close
              (= true owner?)       on-change-owner-and-leave
              :else                 on-leave)]

    [:div.table-row
     [:div.table-field.name
      [:& member-info {:member member :profile profile}]]

     [:div.table-field.roles
      [:& rol-info  {:member member
                     :team team
                     :on-set-admin on-set-admin
                     :on-set-editor on-set-editor
                     :on-set-owner on-set-owner
                     :profile profile}]]

     [:div.table-field.actions
      [:& member-actions {:member member
                          :profile profile
                          :team team
                          :on-delete on-delete
                          :on-leave on-leave'}]]]))

(mf/defc team-members
  {::mf/wrap-props false}
  [{:keys [members-map team profile]}]
  (let [members (mf/with-memo [members-map]
                  (->> (vals members-map)
                       (sort-by :created-at)
                       (remove :is-owner)))
        owner   (mf/with-memo [members-map]
                  (->> (vals members-map)
                       (d/seek :is-owner)))]

    [:div.dashboard-table.team-members
     [:div.table-header
      [:div.table-field.name (tr "labels.member")]
      [:div.table-field.role (tr "labels.role")]]

     [:div.table-rows
      [:& team-member
       {:member owner
        :team team
        :profile profile
        :members members-map}]

      (for [item members]
        [:& team-member
         {:member item
          :team team
          :profile profile
          :key (:id item)
          :members members-map}])]]))

(mf/defc team-members-page
  {::mf/wrap-props false}
  [{:keys [team profile]}]
  (let [members-map (mf/deref refs/dashboard-team-members)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-members"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect [team]
      (st/emit! (dd/fetch-team-members (:id team))))

    [:*
     [:& header {:section :dashboard-team-members :team team}]
     [:section.dashboard-container.dashboard-team-members
      [:& team-members
       {:profile profile
        :team team
        :members-map members-map}]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INVITATIONS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc invitation-role-selector
  {::mf/wrap-props false}
  [{:keys [can-invite? role status on-change]}]
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
     (if (and can-invite? (= status :pending))
       [:div.rol-selector.has-priv {:on-click on-show}
        [:span.rol-label label]
        [:span.icon i/arrow-down]]
       [:div.rol-selector
        [:span.rol-label label]])

     [:& dropdown {:show @show? :on-close on-hide}
      [:ul.dropdown.options-dropdown
       [:li {:data-role "admin" :on-click on-change'} (tr "labels.admin")]
       [:li {:data-role "editor" :on-click on-change'} (tr "labels.editor")]]]]))

(mf/defc invitation-status-badge
  {::mf/wrap-props false}
  [{:keys [status]}]
  [:div.status-badge
   {:class (dom/classnames
            :expired (= status :expired)
            :pending (= status :pending))}
   [:span.status-label
    (if (= status :expired)
      (tr "labels.expired-invitation")
      (tr "labels.pending-invitation"))]])

(mf/defc invitation-actions
  {::mf/wrap-props false}
  [{:keys [invitation team-id]}]
  (let [show?   (mf/use-state false)

        email   (:email invitation)
        role    (:role invitation)

        on-error
        (mf/use-fn
         (mf/deps email)
         (fn [{:keys [type code] :as error}]
           (cond
             (and (= :validation type)
                  (= :profile-is-muted code))
             (rx/of (msg/error (tr "errors.profile-is-muted")))

             (and (= :validation type)
                  (= :member-is-muted code))
             (rx/of (msg/error (tr "errors.member-is-muted")))

             (and (= :validation type)
                  (= :email-has-permanent-bounces code))
             (rx/of (msg/error (tr "errors.email-has-permanent-bounces" email)))

             :else
             (rx/throw error))))

        on-delete
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params {:email email :team-id team-id}
                 mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
             (st/emit! (dd/delete-team-invitation (with-meta params mdata))))))


        on-resend-success
        (mf/use-fn
         (fn []
           (st/emit! (msg/success (tr "notifications.invitation-email-sent"))
                     (modal/hide)
                     (dd/fetch-team-invitations))))

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
              (-> (dd/invite-team-members params)
                  (with-meta {::ev/origin :team}))))))

        on-copy-success
        (mf/use-fn
         (fn []
           (st/emit! (msg/success (tr "notifications.invitation-link-copied"))
                     (modal/hide))))

        on-copy
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params (with-meta {:email email :team-id team-id}
                          {:on-success on-copy-success
                           :on-error on-error})]
             (st/emit!
              (-> (dd/copy-invitation-link params)
                  (with-meta {::ev/origin :team}))))))

        on-hide (mf/use-fn #(reset! show? false))
        on-show (mf/use-fn #(reset! show? true))]

    [:*
     [:span.icon {:on-click on-show} [i/actions]]
     [:& dropdown {:show @show? :on-close on-hide}
      [:ul.dropdown.actions-dropdown
       [:li {:on-click on-copy}   (tr "labels.copy-invitation-link")]
       [:li {:on-click on-resend} (tr "labels.resend-invitation")]
       [:li {:on-click on-delete} (tr "labels.delete-invitation")]]]]))

(mf/defc invitation-row
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [invitation can-invite? team-id] :as props}]

  (let [expired? (:expired invitation)
        email    (:email invitation)
        role     (:role invitation)
        status   (if expired? :expired :pending)

        on-change-role
        (mf/use-fn
         (mf/deps email team-id)
         (fn [role _event]
           (let [params {:email email :team-id team-id :role role}
                 mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
             (st/emit! (dd/update-team-invitation-role (with-meta params mdata))))))]

    [:div.table-row
     [:div.table-field.mail email]

     [:div.table-field.roles
      [:& invitation-role-selector
       {:can-invite? can-invite?
        :role role
        :status status
        :on-change on-change-role}]]

     [:div.table-field.status
      [:& invitation-status-badge {:status status}]]

     [:div.table-field.actions
      (when can-invite?
        [:& invitation-actions
         {:invitation invitation
          :team-id team-id}])]]))

(mf/defc empty-invitation-table
  [{:keys [can-invite?] :as props}]
  [:div.empty-invitations
   [:span (tr "labels.no-invitations")]
   (when can-invite?
     [:& i18n/tr-html {:label "labels.no-invitations-hint"
                       :tag-name "span"}])])

(mf/defc invitation-section
  [{:keys [team invitations] :as props}]
  (let [owner?      (dm/get-in team [:permissions :is-owner])
        admin?      (dm/get-in team [:permissions :is-admin])
        can-invite? (or owner? admin?)
        team-id     (:id team)]

    [:div.dashboard-table.invitations
     [:div.table-header
      [:div.table-field.name (tr "labels.invitations")]
      [:div.table-field.role (tr "labels.role")]
      [:div.table-field.status (tr "labels.status")]]
     (if (empty? invitations)
       [:& empty-invitation-table {:can-invite? can-invite?}]
       [:div.table-rows
        (for [invitation invitations]
          [:& invitation-row
           {:key (:email invitation)
            :invitation invitation
            :can-invite? can-invite?
            :team-id team-id}])])]))

(mf/defc team-invitations-page
  [{:keys [team] :as props}]
  (let [invitations (mf/deref refs/dashboard-team-invitations)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-invitations"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect []
      (st/emit! (dd/fetch-team-invitations)))

    [:*
     [:& header {:section :dashboard-team-invitations
                 :team team}]
     [:section.dashboard-container.dashboard-team-invitations
      [:& invitation-section {:team team
                              :invitations invitations}]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBHOOKS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::uri ::us/uri)
(s/def ::mtype ::us/not-empty-string)
(s/def ::webhook-form
  (s/keys :req-un [::uri ::mtype]))

(def valid-webhook-mtypes
  [{:label "application/json" :value "application/json"}
   {:label "application/transit+json" :value "application/transit+json"}])

(defn- extract-status
  [error-code]
  (-> error-code (str/split #":") second))

(mf/defc webhook-modal
  {::mf/register modal/components
   ::mf/register-as :webhook}
  [{:keys [webhook] :as props}]
  ;; FIXME: this is a workaround because input fields do not support rendering hooks
  (let [initial (mf/use-memo (fn [] (or (some-> webhook (update :uri str))
                                        {:is-active false :mtype "application/json"})))
        form    (fm/use-form :spec ::webhook-form
                             :initial initial
                             :validators [(fm/validate-length :uri fm/max-uri-length-allowed (tr "team.webhooks.max-length"))])
        on-success
        (mf/use-fn
         (fn [_]
           (let [message (tr "dashboard.webhooks.create.success")]
             (st/emit! (dd/fetch-team-webhooks)
                       (msg/success message)
                       (modal/hide)))))

        on-error
        (mf/use-fn
         (fn [form {:keys [type code hint] :as error}]
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
             (rx/throw error))))

        on-create-submit
        (mf/use-fn
         (fn [form]
           (let [cdata  (:clean-data @form)
                 mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:uri        (:uri cdata)
                         :mtype      (:mtype cdata)
                         :is-active  (:is-active cdata)}]
             (st/emit! (dd/create-team-webhook
                        (with-meta params mdata))))))

        on-update-submit
        (mf/use-fn
         (fn [form]
           (let [params (:clean-data @form)
                 mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}]
             (st/emit! (dd/update-team-webhook
                        (with-meta params mdata))))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [data (:clean-data @form)]
             (if (:id data)
               (on-update-submit form)
               (on-create-submit form)))))]

    [:div.modal-overlay
     [:div.modal-container.webhooks-modal
      [:& fm/form {:form form :on-submit on-submit}

       [:div.modal-header
        [:div.modal-header-title
         (if webhook
           [:h2 (tr "modals.edit-webhook.title")]
           [:h2 (tr "modals.create-webhook.title")])]

        [:div.modal-close-button
         {:on-click #(st/emit! (modal/hide))} i/close]]

       [:div.modal-content.generic-form
        [:div.fields-container
         [:div.fields-row
          [:& fm/input {:type "text"
                        :auto-focus? true
                        :form form
                        :name :uri
                        :label (tr "modals.create-webhook.url.label")
                        :placeholder (tr "modals.create-webhook.url.placeholder")}]]

         [:div.fields-row
          [:& fm/select {:options valid-webhook-mtypes
                         :label (tr "dashboard.webhooks.content-type")
                         :default "application/json"
                         :name :mtype}]]]
        [:div.fields-row
         [:div.input-checkbox.check-primary
          [:& fm/input {:type "checkbox"
                        :form form
                        :name :is-active
                        :label (tr "dashboard.webhooks.active")}]]
         [:div.explain (tr "dashboard.webhooks.active.explain")]]]

       [:div.modal-footer
        [:div.action-buttons
         [:input.cancel-button
          {:type "button"
           :value (tr "labels.cancel")
           :on-click #(modal/hide!)}]
         [:> fm/submit-button*
          {:label (if webhook
                    (tr "modals.edit-webhook.submit-label")
                    (tr "modals.create-webhook.submit-label"))}]]]]]]))


(mf/defc webhooks-hero
  {::mf/wrap-props false}
  []
  [:div.banner
   [:div.title (tr "labels.webhooks")
    [:div.description (tr "dashboard.webhooks.description")]]
   [:div.create-container
    [:div.create (tr "dashboard.webhooks.create")]]]

  [:div.webhooks-hero-container
   [:div.webhooks-hero
    [:div.desc
     [:h2 (tr "labels.webhooks")]
     [:& i18n/tr-html {:label "dashboard.webhooks.description"}]]

    [:div.btn-primary
     {:on-click #(st/emit! (modal/show :webhook {}))}
     [:span (tr "dashboard.webhooks.create")]]]])

(mf/defc webhook-actions
  {::mf/wrap-props false}
  [{:keys [on-edit on-delete]}]
  (let [show?   (mf/use-state false)
        on-show (mf/use-fn #(reset! show? true))
        on-hide (mf/use-fn #(reset! show? false))]

    [:*
     [:span.icon {:on-click on-show} [i/actions]]
     [:& dropdown {:show @show? :on-close on-hide}
      [:ul.dropdown.actions-dropdown
       [:li {:on-click on-edit} (tr "labels.edit")]
       [:li {:on-click on-delete} (tr "labels.delete")]]]]))

(mf/defc last-delivery-icon
  {::mf/wrap-props false}
  [{:keys [success? text]}]
  [:div.last-delivery-icon
   [:div.tooltip
    [:div.label text]
    [:div.arrow-down]]
   (if success?
     [:span.icon.success i/msg-success]
     [:span.icon.failure i/msg-warning])])

(mf/defc webhook-item
  {::mf/wrap [mf/memo]}
  [{:keys [webhook] :as props}]
  (let [error-code (:error-code webhook)
        id         (:id webhook)

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
                 mdata  {:on-success #(st/emit! (dd/fetch-team-webhooks))}]
             (st/emit! (dd/delete-team-webhook (with-meta params mdata))))))

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

    [:div.table-row
     [:div.table-field.last-delivery
      [:div.icon-container
       [:& last-delivery-icon
        {:success? (nil? error-code)
         :text last-delivery-text}]]]
     [:div.table-field.uri
      [:div (dm/str (:uri webhook))]]
     [:div.table-field.active
      [:div (if (:is-active webhook)
              (tr "labels.active")
              (tr "labels.inactive"))]]
     [:div.table-field.actions
      [:& webhook-actions
       {:on-edit on-edit
        :on-delete on-delete}]]]))

(mf/defc webhooks-list
  {::mf/wrap-props false}
  [{:keys [webhooks]}]
  [:div.dashboard-table
   [:div.table-rows
    (for [webhook webhooks]
      [:& webhook-item {:webhook webhook :key (:id webhook)}])]])

(mf/defc team-webhooks-page
  {::mf/wrap-props false}
  [{:keys [team]}]
  (let [webhooks (mf/deref refs/dashboard-team-webhooks)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-webhooks"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect [team]
      (st/emit! (dd/fetch-team-webhooks)))

    [:*
     [:& header {:team team :section :dashboard-team-webhooks}]
     [:section.dashboard-container.dashboard-team-webhooks
      [:*
       [:& webhooks-hero]
       (if (empty? webhooks)
         [:div.webhooks-empty
          [:div (tr "dashboard.webhooks.empty.no-webhooks")]
          [:div (tr "dashboard.webhooks.empty.add-one")]]
         [:& webhooks-list {:webhooks webhooks}])]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SETTINGS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc team-settings-page
  {::mf/wrap-props false}
  [{:keys [team]}]
  (let [finput      (mf/use-ref)

        members-map (mf/deref refs/dashboard-team-members)
        owner       (->> (vals members-map)
                         (d/seek :is-owner))

        stats       (mf/deref refs/dashboard-team-stats)

        you-owner?  (get-in team [:permissions :is-owner])
        you-admin?  (get-in team [:permissions :is-admin])
        can-edit?   (or you-owner? you-admin?)

        on-image-click
        (mf/use-callback #(dom/click (mf/ref-val finput)))

        on-file-selected
        (fn [file]
          (st/emit! (dd/update-team-photo file)))]


    (mf/with-effect [team]
      (dom/set-html-title (tr "title.team-settings"
                              (if (:is-default team)
                                (tr "dashboard.your-penpot")
                                (:name team)))))


    (mf/with-effect [team]
      (let [team-id (:id team)]
        (st/emit! (dd/fetch-team-members team-id)
                  (dd/fetch-team-stats team-id))))

    [:*
     [:& header {:section :dashboard-team-settings :team team}]
     [:section.dashboard-container.dashboard-team-settings
      [:div.team-settings
       [:div.horizontal-blocks
        [:div.block.info-block
         [:div.label (tr "dashboard.team-info")]
         [:div.name (:name team)]
         [:div.icon
          (when can-edit?
            [:span.update-overlay {:on-click on-image-click} i/image])
          [:img {:src (cfg/resolve-team-photo-url team)}]
          (when can-edit?
            [:& file-uploader {:accept "image/jpeg,image/png"
                               :multi false
                               :ref finput
                               :on-selected on-file-selected}])]]

        [:div.block.owner-block
         [:div.label (tr "dashboard.team-members")]
         [:div.owner
          [:span.icon [:img {:src (cfg/resolve-profile-photo-url owner)}]]
          [:span.text (str (:name owner) " ("  (tr "labels.owner") ")")]]
         [:div.summary
          [:span.icon i/user]
          [:span.text (tr "dashboard.num-of-members" (count members-map))]]]

        [:div.block.stats-block
         [:div.label (tr "dashboard.team-projects")]
         [:div.projects
          [:span.icon i/folder]
          [:span.text (tr "labels.num-of-projects" (i18n/c (dec (:projects stats))))]]
         [:div.files
          [:span.icon i/file-html]
          [:span.text (tr "labels.num-of-files" (i18n/c (:files stats)))]]]]]]]))
