;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.team
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.constants :as c]
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
   [app.util.router :as rt]
   [app.util.time :as dt]
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [section team] :as props}]
  (let [go-members
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-members {:team-id (:id team)})))

        go-settings
        (mf/use-callback
         (mf/deps team)
         (st/emitf (rt/nav :dashboard-team-settings {:team-id (:id team)})))

        invite-member
        (mf/use-callback
         (mf/deps team)
         (st/emitf (modal/show {:type ::invite-member
                                :team team})))

        members-section?  (= section :dashboard-team-members)
        settings-section? (= section :dashboard-team-settings)]

    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (cond
             members-section? (tr "labels.members")
             settings-section? (tr "labels.settings")
             nil)]]
     [:nav
      [:ul
       [:li {:class (when members-section? "active")}
        [:a {:on-click go-members} (tr "labels.members")]]
       [:li {:class (when settings-section? "active")}
        [:a {:on-click go-settings} (tr "labels.settings")]]]]

     (if members-section?
       [:a.btn-secondary.btn-small {:on-click invite-member}
        (tr "dashboard.invite-profile")]
       [:div])]))

(s/def ::email ::us/email)
(s/def ::role  ::us/keyword)
(s/def ::invite-member-form
  (s/keys :req-un [::role ::email]))

(mf/defc invite-member-modal
  {::mf/register modal/components
   ::mf/register-as ::invite-member}
  [{:keys [team] :as props}]
  (let [roles   [{:value "" :label (tr "labels.role")}
                 {:value "admin" :label (tr "labels.admin")}
                 {:value "editor" :label (tr "labels.editor")}]
                 ;; Temporarily disabled viewer role
                 ;; https://tree.taiga.io/project/uxboxproject/issue/1083
                 ;; {:value "viewer" :label (tr "labels.viewer")}]

        initial (mf/use-memo (mf/deps team) (constantly {:team-id (:id team)}))
        form    (fm/use-form :spec ::invite-member-form
                             :initial initial)
        on-success
        (mf/use-callback
         (mf/deps team)
         (st/emitf (dm/success (tr "notifications.invitation-email-sent"))
                   (modal/hide)))

        on-error
        (mf/use-callback
         (mf/deps team)
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
                    (= :email-has-permanent-bounces))
               (dm/error (tr "errors.email-has-permanent-bounces" email))

               :else
               (dm/error (tr "errors.generic"))))))

        on-submit
        (mf/use-callback
         (mf/deps team)
         (fn [form]
           (let [params (:clean-data @form)
                 mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}]
             (st/emit! (dd/invite-team-member (with-meta params mdata))))))]

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
  [{:keys [team member profile] :as props}]
  (let [show? (mf/use-state false)

        set-role
        #(st/emit! (dd/update-team-member-role {:team-id (:id team)
                                                :member-id (:id member)
                                                :role %}))
        set-owner-fn
        (partial set-role :owner)

        set-admin
        (mf/use-callback (mf/deps team member) (partial set-role :admin))

        set-editor
        (mf/use-callback (mf/deps team member) (partial set-role :editor))

        set-viewer
        (mf/use-callback (mf/deps team member) (partial set-role :viewer))

        set-owner
        (mf/use-callback
         (mf/deps team member)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (tr "modals.promote-owner-confirm.title")
                     :message (tr "modals.promote-owner-confirm.message")
                     :accept-label (tr "modals.promote-owner-confirm.accept")
                     :on-accept set-owner-fn})))

        delete-fn
        (st/emitf (dd/delete-team-member {:team-id (:id team) :member-id (:id member)}))

        delete
        (mf/use-callback
         (mf/deps team member)
         (st/emitf (modal/show
                    {:type :confirm
                     :title (tr "modals.delete-team-member-confirm.title")
                     :message  (tr "modals.delete-team-member-confirm.message")
                     :accept-label (tr "modals.delete-team-member-confirm.accept")
                     :on-accept delete-fn})))]


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

(defn- members-ref
  [{:keys [id] :as team}]
  (l/derived (l/in [:team-members id]) st/state))

(mf/defc team-members-page
  [{:keys [team profile] :as props}]
  (let [members-ref (mf/use-memo (mf/deps team) #(members-ref team))
        members-map (mf/deref members-ref)]

    (mf/use-effect
     (mf/deps team)
     (fn []
       (dom/set-html-title (tr "title.team-members"
                               (if (:is-default team)
                                 (tr "dashboard.your-penpot")
                                 (:name team))))
       (st/emit! (dd/fetch-team-members team))))

    [:*
     [:& header {:section :dashboard-team-members
                 :team team}]
     [:section.dashboard-container.dashboard-team-members
      [:& team-members {:profile profile
                        :team team
                        :members-map members-map}]]]))

(defn- stats-ref
  [{:keys [id] :as team}]
  (l/derived (l/in [:team-stats id]) st/state))

(mf/defc team-settings-page
  [{:keys [team profile] :as props}]
  (let [finput      (mf/use-ref)

        members-ref (mf/use-memo (mf/deps team) #(members-ref team))
        members-map (mf/deref members-ref)

        owner       (->> (vals members-map)
                         (d/seek :is-owner))

        stats-ref   (mf/use-memo (mf/deps team) #(stats-ref team))
        stats       (mf/deref stats-ref)

        on-image-click
        (mf/use-callback #(dom/click (mf/ref-val finput)))

        on-file-selected
        (mf/use-callback
         (mf/deps team)
         (fn [file]
           (st/emit! (dd/update-team-photo {:file file
                                            :team-id (:id team)}))))]

    (mf/use-effect
      (mf/deps team)
      (fn []
        (dom/set-html-title (tr "title.team-settings"
                                (if (:is-default team)
                                  (tr "dashboard.your-penpot")
                                  (:name team))))
        (st/emit! (dd/fetch-team-members team)
                  (dd/fetch-team-stats team))))

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
                             :input-ref finput
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
