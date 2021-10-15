;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.team
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.team-form]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [section team] :as props}]
  (let [go-members        (st/emitf (dd/go-to-team-members))
        go-settings       (st/emitf (dd/go-to-team-settings))
        invite-member     (st/emitf (modal/show {:type ::invite-member :team team}))
        members-section?  (= section :dashboard-team-members)
        settings-section? (= section :dashboard-team-settings)
        permissions       (:permissions team)]

    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (cond
             members-section? (tr "labels.members")
             settings-section? (tr "labels.settings")
             :else nil)]]
     [:nav
      [:ul
       [:li {:class (when members-section? "active")}
        [:a {:on-click go-members} (tr "labels.members")]]
       [:li {:class (when settings-section? "active")}
        [:a {:on-click go-settings} (tr "labels.settings")]]]]

     (if (and members-section? (:is-admin permissions))
       [:a.btn-secondary.btn-small {:on-click invite-member}
        (tr "dashboard.invite-profile")]
       [:div])]))

(defn get-available-roles
  [permissions]
  (->> [{:value "editor" :label (tr "labels.editor")}
        (when (:is-admin permissions)
          {:value "admin" :label (tr "labels.admin")})
        ;; Temporarily disabled viewer role
        ;; https://tree.taiga.io/project/uxboxproject/issue/1083
        ;; {:value "viewer" :label (tr "labels.viewer")}
        ]
       (filterv identity)))

(s/def ::email ::us/email)
(s/def ::role  ::us/keyword)
(s/def ::invite-member-form
  (s/keys :req-un [::role ::email]))

(mf/defc invite-member-modal
  {::mf/register modal/components
   ::mf/register-as ::invite-member}
  [{:keys [team]}]
  (let [perms   (:permissions team)
        roles   (mf/use-memo (mf/deps perms) #(get-available-roles perms))
        initial (mf/use-memo (constantly {:role "editor"}))
        form    (fm/use-form :spec ::invite-member-form
                             :initial initial)
        on-success
        (st/emitf (dm/success (tr "notifications.invitation-email-sent"))
                  (modal/hide))

        on-error
        (fn [form {:keys [type code] :as error}]
          (let [email (get @form [:data :email])]
            (cond
              (and (= :validation type)
                   (= :profile-is-muted code))
              (dm/error (tr "errors.profile-is-muted"))

              (and (= :validation type)
                   (= :member-is-muted code))
              (dm/error (tr "errors.member-is-muted"))

              (and (= :validation type)
                   (= :email-has-permanent-bounces code))
              (dm/error (tr "errors.email-has-permanent-bounces" email))

              :else
              (dm/error (tr "errors.generic")))))

        on-submit
        (fn [form]
          (let [params (:clean-data @form)
                mdata  {:on-success (partial on-success form)
                        :on-error   (partial on-error form)}]
            (st/emit! (dd/invite-team-member (with-meta params mdata)))))]

    [:div.modal.dashboard-invite-modal.form-container
     [:& fm/form {:on-submit on-submit :form form}
      [:div.title
       [:span.text (tr "modals.invite-member.title")]]

      [:div.form-row
       [:& fm/input {:name :email
                     :label (tr "labels.email")}]
       [:& fm/select {:name :role
                      :options roles}]]

      [:div.action-buttons
       [:& fm/submit-button {:label (tr "modals.invite-member-confirm.accept")}]]]]))

(mf/defc team-member
  {::mf/wrap [mf/memo]}
  [{:keys [team member profile] :as props}]
  (let [show? (mf/use-state false)

        set-role
        (fn [role]
          (let [params {:member-id (:id member) :role role}]
            (st/emit! (dd/update-team-member-role params))))

        set-owner-fn (partial set-role :owner)
        set-admin    (partial set-role :admin)
        set-editor   (partial set-role :editor)
        ;; set-viewer   (partial set-role :viewer)

        set-owner
        (st/emitf (modal/show
                   {:type :confirm
                    :title (tr "modals.promote-owner-confirm.title")
                    :message (tr "modals.promote-owner-confirm.message")
                    :accept-label (tr "modals.promote-owner-confirm.accept")
                    :on-accept set-owner-fn}))

        delete-fn
        (st/emitf (dd/delete-team-member {:member-id (:id member)}))

        delete
        (st/emitf (modal/show
                   {:type :confirm
                    :title (tr "modals.delete-team-member-confirm.title")
                    :message  (tr "modals.delete-team-member-confirm.message")
                    :accept-label (tr "modals.delete-team-member-confirm.accept")
                    :on-accept delete-fn}))]

    [:div.table-row
     [:div.table-field.name (:name member)]
     [:div.table-field.email (:email member)]
     [:div.table-field.permissions
      [:*
       (cond
         (:is-owner member)
         [:span.label (tr "labels.owner")]

         (:is-admin member)
         [:span.label (tr "labels.admin")]

         (:can-edit member)
         [:span.label (tr "labels.editor")]

         :else
         [:span.label (tr "labels.viewer")])

       (when (and (not (:is-owner member))
                  (or (:is-admin team)
                      (:is-owner team)))
         [:span.icon {:on-click #(reset! show? true)} i/arrow-down])]

      [:& dropdown {:show @show?
                    :on-close #(reset! show? false)}
       [:ul.dropdown.options-dropdown
        [:li {:on-click set-admin} (tr "labels.admin")]
        [:li {:on-click set-editor} (tr "labels.editor")]
        ;; Temporarily disabled viewer role
        ;; https://tree.taiga.io/project/uxboxproject/issue/1083
        ;; [:li {:on-click set-viewer} (tr "labels.viewer")]
        (when (:is-owner team)
          [:*
           [:hr]
           [:li {:on-click set-owner} (tr "dashboard.promote-to-owner")]])
        [:hr]
        (when (and (or (:is-owner team)
                       (:is-admin team))
                   (not= (:id profile)
                         (:id member)))
          [:li {:on-click delete} (tr "labels.remove")])]]]]))


(mf/defc team-members
  [{:keys [members-map team profile] :as props}]
  (let [members (->> (vals members-map)
                     (sort-by :created-at)
                     (remove :is-owner))
        owner   (->> (vals members-map)
                     (d/seek :is-owner))]
    [:div.dashboard-table
     [:div.table-header
      [:div.table-field.name (tr "labels.name")]
      [:div.table-field.email (tr "labels.email")]
      [:div.table-field.permissions (tr "labels.permissions")]]
     [:div.table-rows
      [:& team-member {:member owner :team team :profile profile}]
      (for [item members]
        [:& team-member {:member item :team team :profile profile :key (:id item)}])]]))

(mf/defc team-members-page
  [{:keys [team profile] :as props}]
  (let [members-map (mf/deref refs/dashboard-team-members)]

    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title
        (tr "title.team-members"
            (if (:is-default team)
              (tr "dashboard.your-penpot")
              (:name team))))))

    (mf/use-effect
     (st/emitf (dd/fetch-team-members)))

    [:*
     [:& header {:section :dashboard-team-members
                 :team team}]
     [:section.dashboard-container.dashboard-team-members
      [:& team-members {:profile profile
                        :team team
                        :members-map members-map}]]]))

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
     (st/emitf (dd/fetch-team-members)
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
          [:span.update-overlay {:on-click on-image-click} i/exit]
          [:img {:src (cfg/resolve-team-photo-url team)}]
          [:& file-uploader {:accept "image/jpeg,image/png"
                             :multi false
                             :ref finput
                             :on-selected on-file-selected}]]]

        [:div.block.owner-block
         [:div.label (tr "dashboard.team-members")]
         [:div.owner
          [:span.icon [:img {:src (cfg/resolve-profile-photo-url owner)}]]
          [:span.text (str (:name owner) " ("  (tr "labels.owner") ")") ]]
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
