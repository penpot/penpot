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
   [rumext.v2 :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [section team] :as props}]
  (let [go-members           (mf/use-fn #(st/emit! (dd/go-to-team-members)))
        go-settings          (mf/use-fn #(st/emit! (dd/go-to-team-settings)))
        go-invitations       (mf/use-fn #(st/emit! (dd/go-to-team-invitations)))
        invite-member        (mf/use-fn 
                              (mf/deps team)
                              #(st/emit! (modal/show {:type :invite-members :team team :origin :team})))

        members-section?     (= section :dashboard-team-members)
        settings-section?    (= section :dashboard-team-settings)
        invitations-section? (= section :dashboard-team-invitations)
        permissions          (:permissions team)]

    [:header.dashboard-header.team
     [:div.dashboard-title
      [:h1 (cond
             members-section? (tr "labels.members")
             settings-section? (tr "labels.settings")
             invitations-section? (tr "labels.invitations")
             :else nil)]]
     [:nav.dashboard-header-menu
      [:ul.dashboard-header-options
       [:li {:class (when members-section? "active")}
        [:a {:on-click go-members} (tr "labels.members")]]
       [:li {:class (when invitations-section? "active")}
        [:a {:on-click go-invitations} (tr "labels.invitations")]]
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
        ;; https://tree.taiga.io/project/uxboxproject/issue/1083
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
  (let [perms   (:permissions team)
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
      [:div.form-row
       [:p.label (tr "onboarding.choice.team-up.roles")]
       [:& fm/select {:name :role :options roles}]]
      [:div.form-row


       [:& fm/multi-input {:type "email"
                           :name :emails
                           :auto-focus? true
                           :trim true
                           :valid-item-fn us/parse-email
                           :label (tr "modals.invite-member.emails")
                           :on-submit  on-submit}]]

      [:div.action-buttons
       [:& fm/submit-button {:label (tr "modals.invite-member-confirm.accept")}]]]]))

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
        ;; https://tree.taiga.io/project/uxboxproject/issue/1083
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

(mf/defc invitation-actions [{:keys [can-modify? delete resend] :as props}]
  (let [show? (mf/use-state false)]
    (when can-modify?
      [:*
       [:span.icon {:on-click #(reset! show? true)} [i/actions]]
       [:& dropdown {:show @show?
                     :on-close #(reset! show? false)}
        [:ul.dropdown.actions-dropdown
         [:li {:on-click resend} (tr "labels.resend-invitation")]
         [:li {:on-click delete} (tr "labels.delete-invitation")]]]])))

(mf/defc invitation-row
  {::mf/wrap [mf/memo]}
  [{:keys [invitation can-invite? team] :as props}]

  (let [expired?          (:expired invitation)
        email             (:email invitation)
        invitation-role   (:role invitation)
        status            (if expired?
                            :expired
                            :pending)

        on-success
        #(st/emit! (msg/success (tr "notifications.invitation-email-sent"))
                   (modal/hide)
                   (dd/fetch-team-invitations))


        on-error
        (fn [email {:keys [type code] :as error}]
          (cond
            (and (= :validation type)
                 (= :profile-is-muted code))
            (msg/error (tr "errors.profile-is-muted"))

            (and (= :validation type)
                 (= :member-is-muted code))
            (msg/error (tr "errors.member-is-muted"))

            (and (= :validation type)
                 (= :email-has-permanent-bounces code))
            (msg/error (tr "errors.email-has-permanent-bounces" email))

            :else
            (msg/error (tr "errors.generic"))))

        change-rol
        (fn [role]
          (let [params {:email email :team-id (:id team) :role role}
                mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
            (st/emit! (dd/update-team-invitation-role (with-meta params mdata)))))

        delete-invitation
        (fn []
          (let [params {:email email :team-id (:id team)}
                mdata  {:on-success #(st/emit! (dd/fetch-team-invitations))}]
            (st/emit! (dd/delete-team-invitation (with-meta params mdata)))))

        resend-invitation
        (fn []
          (let [params {:email email
                        :team-id (:id team)
                        :resend? true
                        :role invitation-role}
                mdata  {:on-success on-success
                        :on-error (partial on-error email)}]
            (st/emit! (-> (dd/invite-team-members (with-meta params mdata))
                          (with-meta {::ev/origin :team}))
                      (dd/fetch-team-invitations))))]
    [:div.table-row
     [:div.table-field.mail email]
     [:div.table-field.roles
      [:& invitation-role-selector
       {:can-invite? can-invite?
        :role invitation-role
        :status status
        :change-to-editor (partial change-rol :editor)
        :change-to-admin (partial change-rol :admin)}]]

     [:div.table-field.status
      [:& invitation-status-badge {:status status}]]
     [:div.table-field.actions
      [:& invitation-actions
       {:can-modify? can-invite?
        :delete delete-invitation
        :resend resend-invitation}]]]))

(mf/defc empty-invitation-table [can-invite?]
  [:div.empty-invitations
   [:span (tr "labels.no-invitations")]
   (when (:can-invite? can-invite?) [:span (tr "labels.no-invitations-hint")])])

(mf/defc invitation-section
  [{:keys [team invitations] :as props}]
  (let [owner? (get-in team [:permissions :is-owner])
        admin? (get-in team [:permissions :is-admin])
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
          [:& invitation-row {:key (:email invitation) :invitation invitation :can-invite? can-invite? :team team}])])]))

(mf/defc team-invitations-page
  [{:keys [team] :as props}]
  (let [invitations (mf/deref refs/dashboard-team-invitations)]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-invitations"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect
      (st/emit! (dd/fetch-team-invitations)))

    [:*
     [:& header {:section :dashboard-team-invitations
                 :team team}]
     [:section.dashboard-container.dashboard-team-invitations
      [:& invitation-section {:team team
                              :invitations invitations}]]]))

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

        on-image-click
        (mf/use-callback #(dom/click (mf/ref-val finput)))

        on-file-selected
        (fn [file]
          (st/emit! (dd/update-team-photo {:file file})))]


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
          [:span.update-overlay {:on-click on-image-click} i/image]
          [:img {:src (cfg/resolve-team-photo-url team)}]
          [:& file-uploader {:accept "image/jpeg,image/png"
                             :multi false
                             :ref finput
                             :on-selected on-file-selected}]]]

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
