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
  {::mf/wrap [mf/memo]}
  [{:keys [section team] :as props}]
  (let [go-members           (mf/use-fn #(st/emit! (dd/go-to-team-members)))
        go-settings          (mf/use-fn #(st/emit! (dd/go-to-team-settings)))
        go-invitations       (mf/use-fn #(st/emit! (dd/go-to-team-invitations)))
        go-webhooks          (mf/use-fn #(st/emit! (dd/go-to-team-webhooks)))
        invite-member        (mf/use-fn
                              (mf/deps team)
                              #(st/emit! (modal/show {:type :invite-members
                                                      :team team
                                                      :origin :team})))

        members-section?     (= section :dashboard-team-members)
        settings-section?    (= section :dashboard-team-settings)
        invitations-section? (= section :dashboard-team-invitations)
        webhooks-section?    (= section :dashboard-team-webhooks)
        permissions          (:permissions team)]

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
        [:a {:on-click go-members} (tr "labels.members")]]
       [:li {:class (when invitations-section? "active")}
        [:a {:on-click go-invitations} (tr "labels.invitations")]]
       (when (contains? @cfg/flags :webhooks)
         [:li {:class (when webhooks-section? "active")}
          [:a {:on-click go-webhooks} (tr "labels.webhooks")]])
       [:li {:class (when settings-section? "active")}
        [:a {:on-click go-settings} (tr "labels.settings")]]]]
     [:div.dashboard-buttons
      (if (and (or invitations-section? members-section?) (:is-admin permissions))
        [:a.btn-secondary.btn-small {:on-click invite-member :data-test "invite-member"}
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
   ::mf/register-as :invite-members}
  [{:keys [team origin]}]
  (let [members-map (mf/deref refs/dashboard-team-members)

        perms   (:permissions team)

        roles   (mf/use-memo (mf/deps perms) #(get-available-roles perms))
        initial (mf/use-memo (constantly {:role "editor" :team-id (:id team)}))
        form    (fm/use-form :spec ::invite-member-form
                             :initial initial)
        error-text (mf/use-state  "")

        on-success
        (fn []
          (st/emit! (msg/success (tr "notifications.invitation-email-sent"))
                    (modal/hide)
                    (dd/fetch-team-invitations)))

        current-data-emails (into #{} (dm/get-in @form [:clean-data :emails]))
        current-members-emails (into #{} (map (comp :email second)) members-map)

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
       [:& fm/submit-button {:label (tr "modals.invite-member-confirm.accept")
                             :disabled (and (boolean (some current-data-emails current-members-emails))
                                            (empty? (remove current-members-emails current-data-emails)))}]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MEMBERS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc member-info [{:keys [member profile] :as props}]
  (let [is-you? (= (:id profile) (:id member))]
    [:*
     [:div.member-image
      [:img {:src (cfg/resolve-profile-photo-url member)}]]
     [:div.member-info
      [:div.member-name (:name member)
       (when is-you?
         [:span.you (tr "labels.you")])]
      [:div.member-email (:email member)]]]))

(mf/defc rol-info [{:keys [member team set-admin set-editor set-owner profile] :as props}]
  (let [member-is-owner?  (:is-owner member)
        member-is-admin?  (and (:is-admin member) (not member-is-owner?))
        member-is-editor? (and (:can-edit member) (and (not member-is-admin?) (not member-is-owner?)))
        show?             (mf/use-state false)
        you-owner?        (get-in team [:permissions :is-owner])
        you-admin?        (get-in team [:permissions :is-admin])
        can-change-rol?   (or you-owner? you-admin?)
        not-superior?     (or you-owner? (and can-change-rol? (or member-is-admin? member-is-editor?)))
        role              (cond
                            member-is-owner? "labels.owner"
                            member-is-admin? "labels.admin"
                            member-is-editor? "labels.editor"
                            :else "labels.viewer")
        is-you?             (= (:id profile) (:id member))]
    [:*
     (if (and can-change-rol? not-superior? (not (and is-you? you-owner?)))
       [:div.rol-selector.has-priv  {:on-click #(reset! show? true)}
        [:span.rol-label (tr role)]
        [:span.icon i/arrow-down]]
       [:div.rol-selector
        [:span.rol-label (tr role)]])

     [:& dropdown {:show @show?
                   :on-close #(reset! show? false)}
      [:ul.dropdown.options-dropdown
       [:li {:on-click set-admin} (tr "labels.admin")]
       [:li {:on-click set-editor} (tr "labels.editor")]
        ;; Temporarily disabled viewer role
        ;; https://tree.taiga.io/project/penpot/issue/1083
        ;;  [:li {:on-click set-viewer} (tr "labels.viewer")]
       (when you-owner?
         [:li {:on-click (partial set-owner member)} (tr "labels.owner")])]]]))

(mf/defc member-actions [{:keys [member team delete leave profile] :as props}]
  (let [is-owner? (:is-owner member)
        owner? (get-in team [:permissions :is-owner])
        admin? (get-in team [:permissions :is-admin])
        show?     (mf/use-state false)
        is-you? (= (:id profile) (:id member))
        can-delete? (or owner? admin?)]
    [:*
     (when (or is-you? (and can-delete? (not (and is-owner? (not owner?)))))
       [:span.icon {:on-click #(reset! show? true)} [i/actions]])
     [:& dropdown {:show @show?
                   :on-close #(reset! show? false)}
      [:ul.dropdown.actions-dropdown
       (when is-you?
         [:li {:on-click leave} (tr "dashboard.leave-team")])
       (when (and can-delete? (not is-you?) (not (and is-owner? (not owner?))))
         [:li {:on-click delete} (tr "labels.remove-member")])]]]))

(mf/defc team-member
  {::mf/wrap [mf/memo]}
  [{:keys [team member members profile] :as props}]

  (let [owner? (dm/get-in team [:permissions :is-owner])
        set-role
        (mf/use-fn
         (mf/deps member)
         (fn [role]
           (let [params {:member-id (:id member) :role role}]
             (st/emit! (dd/update-team-member-role params)))))


        set-owner-fn (mf/use-fn (mf/deps set-role) (partial set-role :owner))
        set-admin    (mf/use-fn (mf/deps set-role) (partial set-role :admin))
        set-editor   (mf/use-fn (mf/deps set-role) (partial set-role :editor))
        ;; set-viewer   (partial set-role :viewer)

        set-owner
        (mf/use-fn
         (mf/deps set-owner-fn member)
         (fn [member]
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.promote-owner-confirm.title")
                       :message (tr "modals.promote-owner-confirm.message" (:name member))
                       :scd-message (tr "modals.promote-owner-confirm.hint")
                       :accept-label (tr "modals.promote-owner-confirm.accept")
                       :on-accept set-owner-fn
                       :accept-style :primary}))))

        delete-member-fn
        (mf/use-fn
         (mf/deps member)
         (fn [] (st/emit! (dd/delete-team-member {:member-id (:id member)}))))

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

        delete-fn
        (mf/use-fn
         (mf/deps team on-success on-error)
         (fn []
           (st/emit! (dd/delete-team (with-meta team {:on-success on-success
                                                      :on-error on-error})))))

        leave-fn
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [member-id]
           (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
             (st/emit! (dd/leave-team (with-meta params
                                        {:on-success on-success
                                         :on-error on-error}))))))

        leave-and-close
        (mf/use-fn
         (mf/deps delete-fn)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message  (tr "modals.leave-and-close-confirm.message" (:name team))
                       :scd-message (tr "modals.leave-and-close-confirm.hint")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept delete-fn}))))

        change-owner-and-leave
        (mf/use-fn
         (mf/deps profile team leave-fn)
         (fn []
           (st/emit! (dd/fetch-team-members)
                     (modal/show
                      {:type :leave-and-reassign
                       :profile profile
                       :team team
                       :accept leave-fn}))))

        leave
        (mf/use-fn
         (mf/deps leave-fn)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message  (tr "modals.leave-confirm.message")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept leave-fn}))))

        preset-leave (cond (= 1 (count members)) leave-and-close
                           (= true owner?) change-owner-and-leave
                           :else leave)

        delete
        (mf/use-fn
         (mf/deps delete-member-fn)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-team-member-confirm.title")
                       :message  (tr "modals.delete-team-member-confirm.message")
                       :accept-label (tr "modals.delete-team-member-confirm.accept")
                       :on-accept delete-member-fn}))))]

    [:div.table-row
     [:div.table-field.name
      [:& member-info {:member member :profile profile}]]
     [:div.table-field.roles
      [:& rol-info  {:member member
                     :team team
                     :set-admin set-admin
                     :set-editor set-editor
                     :set-owner set-owner
                     :profile profile}]]
     [:div.table-field.actions
      [:& member-actions {:member member
                          :profile profile
                          :team team
                          :delete delete
                          :leave preset-leave}]]]))

(mf/defc team-members
  [{:keys [members-map team profile] :as props}]
  (let [members (->> (vals members-map)
                     (sort-by :created-at)
                     (remove :is-owner))
        owner   (->> (vals members-map)
                     (d/seek :is-owner))]
    [:div.dashboard-table.team-members
     [:div.table-header
      [:div.table-field.name (tr "labels.member")]
      [:div.table-field.role (tr "labels.role")]]
     [:div.table-rows
      [:& team-member {:member owner :team team :profile profile :members members-map}]
      (for [item members]
        [:& team-member {:member item :team team :profile profile :key (:id item) :members members-map}])]]))

(mf/defc team-members-page
  [{:keys [team profile] :as props}]
  (let [members-map (mf/deref refs/dashboard-team-members)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-members"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect
      (st/emit! (dd/fetch-team-members)))

    [:*
     [:& header {:section :dashboard-team-members
                 :team team}]
     [:section.dashboard-container.dashboard-team-members
      [:& team-members {:profile profile
                        :team team
                        :members-map members-map}]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INVITATIONS SECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc invitation-role-selector
  [{:keys [can-invite? role status change-to-admin change-to-editor] :as props}]
  (let [show? (mf/use-state false)
        role-label (cond
                     (= role :owner) "labels.owner"
                     (= role :admin) "labels.admin"
                     (= role :editor) "labels.editor"
                     :else "labels.viewer")]
    [:*
     (if (and can-invite? (= status :pending))
       [:div.rol-selector.has-priv {:on-click #(reset! show? true)}
        [:span.rol-label (tr role-label)]
        [:span.icon i/arrow-down]]
       [:div.rol-selector
        [:span.rol-label (tr role-label)]])

     [:& dropdown {:show @show?
                   :on-close #(reset! show? false)}
      [:ul.dropdown.options-dropdown
       [:li {:on-click change-to-admin} (tr "labels.admin")]
       [:li {:on-click change-to-editor} (tr "labels.editor")]]]]))

(mf/defc invitation-status-badge
  [{:keys [status] :as props}]
  (let [status-label (if (= status :expired)
                       (tr "labels.expired-invitation")
                       (tr "labels.pending-invitation"))]
    [:div.status-badge {:class (dom/classnames
                                :expired (= status :expired)
                                :pending (= status :pending))}
     [:span.status-label (tr status-label)]]))

(mf/defc invitation-actions
  [{:keys [invitation team] :as props}]
  (let [show?   (mf/use-state false)

        team-id (:id team)
        email   (:email invitation)
        role    (:role invitation)

        on-resend-success
        (mf/use-fn
         (fn []
           (st/emit! (msg/success (tr "notifications.invitation-email-sent"))
                     (modal/hide)
                     (dd/fetch-team-invitations))))

        on-copy-success
        (mf/use-fn
         (fn []
           (st/emit! (msg/success (tr "notifications.invitation-link-copied"))
                     (modal/hide))))

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

        delete-fn
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params {:email email :team-id team-id}
                 mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
             (st/emit! (dd/delete-team-invitation (with-meta params mdata))))))

        resend-fn
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params (with-meta {:emails [email]
                                    :team-id team-id
                                    :resend? true
                                    :role role}
                          {:on-success on-resend-success
                           :on-error on-error})]
             (st/emit!
              (-> (dd/invite-team-members params)
                  (with-meta {::ev/origin :team}))))))

        copy-fn
        (mf/use-fn
         (mf/deps email team-id)
         (fn []
           (let [params (with-meta {:email email :team-id team-id}
                          {:on-success on-copy-success
                           :on-error on-error})]
             (st/emit!
              (-> (dd/copy-invitation-link params)
                  (with-meta {::ev/origin :team}))))))]


    [:*
     [:span.icon {:on-click #(reset! show? true)} [i/actions]]
     [:& dropdown {:show @show?
                   :on-close #(reset! show? false)}
      [:ul.dropdown.actions-dropdown
       [:li {:on-click copy-fn}   (tr "labels.copy-invitation-link")]
       [:li {:on-click resend-fn} (tr "labels.resend-invitation")]
       [:li {:on-click delete-fn} (tr "labels.delete-invitation")]]]]))

(mf/defc invitation-row
  {::mf/wrap [mf/memo]}
  [{:keys [invitation can-invite? team] :as props}]

  (let [expired? (:expired invitation)
        email    (:email invitation)
        role     (:role invitation)
        status   (if expired? :expired :pending)

        change-rol
        (mf/use-fn
         (mf/deps team email)
         (fn [role]
           (let [params {:email email :team-id (:id team) :role role}
                 mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
             (st/emit! (dd/update-team-invitation-role (with-meta params mdata))))))]

    [:div.table-row
     [:div.table-field.mail email]
     [:div.table-field.roles
      [:& invitation-role-selector
       {:can-invite? can-invite?
        :role role
        :status status
        :change-to-editor (partial change-rol :editor)
        :change-to-admin (partial change-rol :admin)}]]

     [:div.table-field.status
      [:& invitation-status-badge {:status status}]]
     [:div.table-field.actions
      (when can-invite?
        [:& invitation-actions
         {:invitation invitation
          :team team}])]]))

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
        can-invite? (or owner? admin?)]

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
            :team team}])])]))

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
                             :initial initial)
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
                        :label (tr "dashboard.webhooks.active")}]
          ]
         [:div.explain (tr "dashboard.webhooks.active.explain")]]]

       [:div.modal-footer
        [:div.action-buttons
         [:input.cancel-button
          {:type "button"
           :value (tr "labels.cancel")
           :on-click #(modal/hide!)}]
         [:& fm/submit-button
          {:label (if webhook
                    (tr "modals.edit-webhook.submit-label")
                    (tr "modals.create-webhook.submit-label"))}]]]]]]))


(mf/defc webhooks-hero
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
  [{:keys [on-edit on-delete] :as props}]
  (let [show? (mf/use-state false)]
    [:*
     [:span.icon {:on-click #(reset! show? true)} [i/actions]]
     [:& dropdown {:show @show?
                   :on-close #(reset! show? false)}
      [:ul.dropdown.actions-dropdown
       [:li {:on-click on-edit} (tr "labels.edit")]
       [:li {:on-click on-delete} (tr "labels.delete")]]]]))

(mf/defc last-delivery-icon
  [{:keys [success? text] :as props}]
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
  (let [on-edit #(st/emit! (modal/show :webhook {:webhook webhook}))
        error-code (:error-code webhook)

        delete-fn
        (fn []
          (let [params {:id (:id webhook)}
                mdata  {:on-success #(st/emit! (dd/fetch-team-webhooks))}]
            (st/emit! (dd/delete-team-webhook (with-meta params mdata)))))

        on-delete
        (fn []
          (st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-webhook.title")
                      :message (tr "modals.delete-webhook.message")
                      :accept-label (tr "modals.delete-webhook.accept")
                      :on-accept delete-fn})))

        last-delivery-text
        (if (nil? error-code)
          (tr "webhooks.last-delivery.success")
          (str (tr "errors.webhooks.last-delivery")
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
  [{:keys [webhooks] :as props}]
  [:div.dashboard-table
   [:div.table-rows
    (for [webhook webhooks]
      [:& webhook-item {:webhook webhook :key (:id webhook)}])]])

(mf/defc team-webhooks-page
  [{:keys [team] :as props}]
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
      [:div
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
  [{:keys [team] :as props}]
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


    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title (tr "title.team-settings"
                               (if (:is-default team)
                                 (tr "dashboard.your-penpot")
                                 (:name team))))))


    (mf/use-effect
     #(st/emit! (dd/fetch-team-members)
                (dd/fetch-team-stats)))

    [:*
     [:& header {:section :dashboard-team-settings
                 :team team}]
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
