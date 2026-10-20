;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.organization-team-switch-menus
  "The team-options dropdown and the organization right-click menu
  that `organization-team-switch*` opens, split out of
  `app.main.ui.dashboard.organization-team-switch` to keep that file
  focused on the switcher's own two-column dropdown. Not meant to be
  reused outside that component."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.data.team :as dtm]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- use-organization-leave
  "The default team id and `:leave-fn` needed to leave `organization`.
  Fetches nothing: fetching team members would hit the SSO gate. Pass
  `enabled?` false when leaving isn't offered."
  [organization teams ^boolean enabled?]
  (let [org-teams
        (mf/with-memo [teams organization enabled?]
          (when enabled?
            (dnt/organization-teams teams (:id organization))))

        {default-team-id :default-team-id
         not-owned-teams :not-owned-teams}
        (mf/with-memo [org-teams]
          (when org-teams
            (dnt/organization-leave-info org-teams)))

        leave-fn
        (mf/use-fn
         (mf/deps organization default-team-id not-owned-teams)
         (dnt/leave-organization-fn {:organization organization
                                     :default-team-id default-team-id
                                     :not-owned-teams not-owned-teams
                                     :on-error dnt/org-leave-on-error}))]

    {:default-team-id default-team-id
     :leave-fn leave-fn}))

(mf/defc options-dropdown*
  "The \"...\" team-management menu opened from the switcher's closed
  control: team members/invitations/webhooks/settings/rename/leave/
  delete, plus \"leave organization\" when `can-leave-organization`."
  [{:keys [show id class team profile current-organization teams ^boolean can-leave-organization on-close]}]
  (let [members         (get team :members)
        permissions     (get team :permissions)
        can-rename?     (or (:is-owner permissions) (:is-admin permissions))
        default-team-id (:default-team-id profile)

        ;; A default team (the personal "my teams" bucket, or an
        ;; organization's own default team) isn't a real, manageable
        ;; team: it has no separate members/settings/etc. of its own,
        ;; so none of those options apply to it.
        show-team-management?
        (not (:is-default team))

        {org-default-team-id :default-team-id
         org-leave-fn        :leave-fn}
        (use-organization-leave current-organization teams can-leave-organization)

        on-success
        (fn []
          (rx/of (dcm/go-to-dashboard-recent :team-id default-team-id)
                 (modal/hide)))

        leave-fn
        (mf/use-fn
         (mf/deps on-success)
         (fn [member-id]
           (let [params (cond-> {} (uuid? member-id) (assoc :reassign-to member-id))]
             (st/emit! (dtm/leave-current-team (with-meta params
                                                 {:on-success on-success
                                                  :on-error dnt/team-leave-on-error}))))))

        delete-fn
        (mf/use-fn
         (mf/deps team on-success)
         (fn []
           (st/emit! (dtm/delete-team (with-meta team {:on-success on-success
                                                       :on-error dnt/team-leave-on-error})))))

        on-members-click
        (mf/use-fn #(do (on-close) (st/emit! (dcm/go-to-dashboard-members))))

        on-invitations-click
        (mf/use-fn #(do (on-close) (st/emit! (dcm/go-to-dashboard-invitations))))

        on-webhooks-click
        (mf/use-fn #(do (on-close) (st/emit! (dcm/go-to-dashboard-webhooks))))

        on-settings-click
        (mf/use-fn #(do (on-close) (st/emit! (dcm/go-to-dashboard-settings))))

        on-rename-clicked
        (mf/use-fn
         (mf/deps team)
         (fn []
           (on-close)
           (st/emit! (modal/show :team-form {:team team}))))

        on-leave-clicked
        (mf/use-fn
         (mf/deps leave-fn)
         (fn []
           (on-close)
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message (tr "modals.leave-confirm.message")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept leave-fn}))))

        on-leave-as-owner-clicked
        (mf/use-fn
         (mf/deps profile team leave-fn)
         (fn []
           (on-close)
           (st/emit! (dtm/fetch-members)
                     (modal/show
                      {:type :leave-and-reassign
                       :profile profile
                       :team team
                       :accept leave-fn}))))

        leave-and-close
        (mf/use-fn
         (mf/deps team delete-fn)
         (fn []
           (on-close)
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.leave-confirm.title")
                       :message (tr "modals.leave-and-close-confirm.message" (:name team))
                       :scd-message (tr "modals.leave-and-close-confirm.hint")
                       :accept-label (tr "modals.leave-confirm.accept")
                       :on-accept delete-fn}))))

        on-delete-clicked
        (mf/use-fn
         (mf/deps team delete-fn)
         (fn []
           (on-close)
           (st/emit! (dtm/check-and-delete-team {:team-id (:id team)
                                                 :delete-fn delete-fn}))))

        on-leave-organization-clicked
        (mf/use-fn
         (mf/deps profile current-organization org-default-team-id org-leave-fn)
         (fn []
           (on-close)
           (st/emit! (dnt/show-leave-organization-modal
                      {:organization current-organization
                       :profile profile
                       :default-team-id org-default-team-id
                       :leave-fn org-leave-fn
                       :on-error dnt/org-leave-on-error}))))]

    [:> dropdown-menu* {:show show
                        :on-close on-close
                        :id id
                        :class class}
     (when show-team-management?
       [:*
        [:> dropdown-menu-item* {:on-click on-members-click
                                 :class (stl/css :options-item)
                                 :data-testid "team-members"}
         (tr "labels.members")]

        [:> dropdown-menu-item* {:on-click on-invitations-click
                                 :class (stl/css :options-item)
                                 :data-testid "team-invitations"}
         (tr "labels.invitations")]

        (when (contains? cf/flags :webhooks)
          [:> dropdown-menu-item* {:on-click on-webhooks-click
                                   :class (stl/css :options-item)}
           (tr "labels.webhooks")])

        [:> dropdown-menu-item* {:on-click on-settings-click
                                 :class (stl/css :options-item)
                                 :data-testid "team-settings"}
         (tr "labels.settings")]

        [:hr {:class (stl/css :options-separator)}]

        (when can-rename?
          [:> dropdown-menu-item* {:on-click on-rename-clicked
                                   :class (stl/css :options-item)
                                   :data-testid "rename-team"}
           (tr "labels.rename")])

        (cond
          (= (count members) 1)
          [:> dropdown-menu-item* {:on-click leave-and-close
                                   :class (stl/css :options-item)}
           (tr "dashboard.leave-team")]

          (:is-owner permissions)
          [:> dropdown-menu-item* {:on-click on-leave-as-owner-clicked
                                   :class (stl/css :options-item)
                                   :data-testid "leave-team"}
           (tr "dashboard.leave-team")]

          (> (count members) 1)
          [:> dropdown-menu-item* {:on-click on-leave-clicked
                                   :class (stl/css :options-item)}
           (tr "dashboard.leave-team")])

        (when (:is-owner permissions)
          [:> dropdown-menu-item* {:on-click on-delete-clicked
                                   :class (stl/css :options-item :warning)
                                   :data-testid "delete-team"}
           (tr "dashboard.delete-team")])])

     (when can-leave-organization
       [:*
        (when show-team-management?
          [:hr {:class (stl/css :options-separator)}])
        [:> dropdown-menu-item* {:on-click on-leave-organization-clicked
                                 :class (stl/css :options-item)}
         (tr "dashboard.leave-organization")]])]))

(mf/defc organization-context-menu*
  "Right-click menu on an organization in `organizations-column*`,
  currently offering just \"leave organization\". Split out of
  `organization-team-switch*` so its leave-organization data stays
  local instead of being computed on every render of the switcher."
  [{:keys [organization teams profile x y on-close on-leave-requested]}]
  (let [{default-team-id :default-team-id
         leave-fn :leave-fn}
        (use-organization-leave organization teams true)

        on-leave-clicked
        (mf/use-fn
         (mf/deps leave-fn profile organization default-team-id on-leave-requested)
         (fn []
           (on-leave-requested)
           (st/emit! (dnt/show-leave-organization-modal {:organization organization
                                                         :profile profile
                                                         :default-team-id default-team-id
                                                         :leave-fn leave-fn
                                                         :on-error dnt/org-leave-on-error}))))]

    [:> dropdown-menu* {:show true
                        :on-close on-close
                        :class (stl/css :organization-context-menu)
                        :style {:position "fixed"
                                :top (str y "px")
                                :left (str x "px")}}
     [:> dropdown-menu-item* {:on-click on-leave-clicked
                              :class (stl/css :context-menu-item)}
      (tr "dashboard.leave-organization")]]))
