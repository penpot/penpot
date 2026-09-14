;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.organization-team-switch
  "A single two-level dropdown that merges the organization switcher
  and the team switcher: the left column lists the user's
  organizations, the right column lists the teams that belong to the
  organization currently selected on the left."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.organization-avatar :refer [organization-avatar*]]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type
                                               menu-team-icon*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private ^:svg-id penpot-logo-icon-subtle "penpot-logo-subtle")

;; Sentinel used for the "personal projects" bucket, since those teams
;; have no organization (`:organization` is `nil`).
(def ^:private personal-bucket-id :personal)

(defn- organization-bucket-id
  [organization]
  (or (:id organization) personal-bucket-id))

(defn- team-href
  [router team]
  (dm/str (assoc cf/public-uri :fragment
                 (rt/resolve router :dashboard-recent {:team-id (:id team)}))))

(defn- new-tab-click?
  [event]
  (or (kbd/mod? event) (dom/middle-mouse? event)))

(defn- keep-menu-context-menu
  [event]
  (dom/stop-propagation event))

(defn- team-leave-on-error
  [error]
  (let [code (-> error ex-data :code)]
    (condp = code
      :only-owner-can-delete-team
      (rx/of (ntf/error (tr "errors.team-leave.only-owner-can-delete")))

      :no-enough-members-for-leave
      (rx/of (ntf/error (tr "errors.team-leave.insufficient-members")))

      :member-does-not-exist
      (rx/of (ntf/error (tr "errors.team-leave.member-does-not-exists")))

      :owner-cant-leave-team
      (rx/of (ntf/error (tr "errors.team-leave.owner-cant-leave")))

      (rx/throw error))))

(defn- org-leave-on-error
  [error]
  (let [code (-> error ex-data :code)
        error-map {:not-valid-teams "errors.organization-leave.no-valid-teams"
                   :organization-owner-cannot-leave "errors.organization-leave.organization-owner-cannot-leave"
                   :only-owner-can-delete-team "errors.team-leave.only-owner-can-delete"
                   :no-enough-members-for-leave "errors.team-leave.insufficient-members"
                   :member-does-not-exist "errors.team-leave.member-does-not-exists"
                   :owner-cant-leave-team "errors.team-leave.owner-cant-leave"}]
    (if-let [tr-key (get error-map code)]
      (rx/of (dtm/fetch-teams)
             (modal/hide)
             (ntf/error (tr tr-key)))
      (rx/throw error))))

(defn- organization-teams
  "Teams belonging to `organization-id`, out of the full team map."
  [teams organization-id]
  (->> teams
       vals
       (filter #(= (dm/get-in % [:organization :id]) organization-id))))

(defn- organization-leave-info
  "Splits the teams of an organization into what is needed to leave it:
  the organization's own default team id, the teams owned by the
  current user (whose membership decides whether they get deleted or
  offered for transfer), and the teams the user does not own (which
  are simply left)."
  [org-teams]
  (let [non-default-teams (remove :is-default org-teams)]
    {:default-team-id (->> org-teams (filter :is-default) first :id)
     :owned-teams (filter #(dm/get-in % [:permissions :is-owner]) non-default-teams)
     :not-owned-teams (remove #(dm/get-in % [:permissions :is-owner]) non-default-teams)}))

(defn- organization-leave-params
  "Builds the :teams-to-leave/:teams-to-delete payload for
  `dnt/leave-organization`, folding in any teams the user chose to
  transfer to another owner instead of leaving/deleting them."
  [owned-teams not-owned-teams teams-to-transfer]
  {:teams-to-leave (cond->> not-owned-teams
                     :always (map #(select-keys % [:id]))
                     (seq teams-to-transfer) (concat teams-to-transfer))
   :teams-to-delete (->> owned-teams
                         (filter #(= (count (:members %)) 1))
                         (map :id))})

(mf/defc organizations-column*
  {::mf/private true}
  [{:keys [organizations selected-id on-select on-create-organization admin-console-href
           is-valid-license? on-context-menu]}]
  [:ul {:class (stl/css :organizations-column)}
   [:li {:role "presentation" :class (stl/css :column-label)}
    (tr "dashboard.section.organizations")]

   (for [organization organizations]
     (let [bucket-id (organization-bucket-id organization)
           personal? (= bucket-id personal-bucket-id)]
       [:> dropdown-menu-item* {:key (str bucket-id)
                                :data-value (str bucket-id)
                                :class (stl/css-case :organization-item true
                                                     :selected (= bucket-id selected-id))
                                :on-click on-select
                                :on-context-menu #(on-context-menu % organization)}
        (if personal?
          [:span {:class (stl/css :my-teams-icon)}
           [:> raw-svg* {:id penpot-logo-icon-subtle}]]
          [:> organization-avatar* {:organization organization :size "xxl"}])
        [:span {:class (stl/css :organization-text)
                :title (if personal? (tr "dashboard.other-teams") (:name organization))}
         (if personal? (tr "dashboard.other-teams") (:name organization))]
        (when (= bucket-id selected-id)
          [:> icon* {:icon-id i/tick :class (stl/css :tick-icon)}])]))

   [:hr {:role "separator" :class (stl/css :column-separator)}]

   [:> dropdown-menu-item* {:on-click on-create-organization
                            :class (stl/css :organization-item :action)}
    [:span {:class (stl/css :icon-wrapper)}
     [:> icon* {:icon-id i/add :class (stl/css :action-icon)}]]
    [:span {:class (stl/css :organization-text)} (tr "dashboard.create-new-organization")]]

   (when is-valid-license?
     [:> dropdown-menu-item* {:class (stl/css :organization-item :action :with-link)
                              :on-context-menu keep-menu-context-menu}
      [:a {:class (stl/css :item-link)
           :href admin-console-href
           :tab-index "-1"}
       [:span {:class (stl/css :icon-wrapper)}
        [:> icon* {:icon-id i/arrow-up-right :class (stl/css :action-icon)}]]
       [:span {:class (stl/css :organization-text)} (tr "dashboard.go-to-admin-console")]]])])

(mf/defc teams-column*
  {::mf/private true}
  [{:keys [teams selected-team-id on-select on-context-menu on-create-team]}]
  (let [router (mf/deref refs/router)]
    [:ul {:class (stl/css :teams-column)}
     [:li {:role "presentation" :class (stl/css :column-label)}
      (tr "dashboard.section.teams")]

     (for [team teams]
       (let [subscription-type (get-subscription-type (:subscription team))]
         [:> dropdown-menu-item* {:key (str (:id team))
                                  :data-value (str (:id team))
                                  :class (stl/css-case :team-item true
                                                       :with-link true
                                                       :selected (= (:id team) selected-team-id))
                                  :on-click on-select
                                  :on-context-menu on-context-menu}
          [:a {:class (stl/css :item-link)
               :href (team-href router team)
               :tab-index "-1"}
           (if (:is-default team)
             [:span {:class (stl/css :my-teams-icon)}
              [:> raw-svg* {:id penpot-logo-icon-subtle}]]
             [:img {:src (cf/resolve-team-photo-url team)
                    :class (stl/css :team-picture)
                    :alt (:name team)}])
           [:span {:class (stl/css :team-text)
                   :title (if (:is-default team) (tr "dashboard.personal-projects") (:name team))}
            (if (:is-default team) (tr "dashboard.personal-projects") (:name team))]
           (when (#{"unlimited" "enterprise"} subscription-type)
             [:> menu-team-icon* {:subscription-type subscription-type}])
           (when (= (:id team) selected-team-id)
             [:> icon* {:icon-id i/tick :class (stl/css :tick-icon)}])]]))

     [:hr {:role "separator" :class (stl/css :column-separator)}]

     [:> dropdown-menu-item* {:on-click on-create-team
                              :class (stl/css :team-item :action)}
      [:span {:class (stl/css :icon-wrapper)}
       [:> icon* {:icon-id i/add :class (stl/css :action-icon)}]]
      [:span {:class (stl/css :team-text)} (tr "dashboard.create-new-team")]]]))

(mf/defc options-dropdown*
  {::mf/private true}
  [{:keys [show id class team profile current-organization teams on-close]}]
  (let [members         (get team :members)
        permissions     (get team :permissions)
        can-rename?     (or (:is-owner permissions) (:is-admin permissions))
        default-team-id (:default-team-id profile)

        org-teams
        (mf/with-memo [teams current-organization]
          (organization-teams teams (:id current-organization)))

        {org-default-team-id :default-team-id
         owned-teams :owned-teams
         not-owned-teams :not-owned-teams}
        (mf/with-memo [org-teams]
          (organization-leave-info org-teams))

        teams-to-transfer
        (mf/with-memo [owned-teams]
          (filter #(> (count (:members %)) 1) owned-teams))

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
                                                  :on-error team-leave-on-error}))))))

        delete-fn
        (mf/use-fn
         (mf/deps team on-success)
         (fn []
           (st/emit! (dtm/delete-team (with-meta team {:on-success on-success
                                                       :on-error team-leave-on-error})))))

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

        org-leave-fn
        (mf/use-fn
         (mf/deps current-organization org-default-team-id owned-teams not-owned-teams)
         (fn [{:keys [teams-to-transfer
                      member-added-at
                      organization-member-count-before]}]
           (let [{:keys [teams-to-leave teams-to-delete]}
                 (organization-leave-params owned-teams not-owned-teams teams-to-transfer)]
             (st/emit! (dnt/leave-organization {:id (:id current-organization)
                                                :name (:name current-organization)
                                                :default-team-id org-default-team-id
                                                :teams-to-delete teams-to-delete
                                                :teams-to-leave teams-to-leave
                                                :member-added-at member-added-at
                                                :organization-member-count-before organization-member-count-before
                                                :on-error org-leave-on-error})))))

        on-leave-organization-clicked
        (mf/use-fn
         (mf/deps profile current-organization org-default-team-id teams-to-transfer org-leave-fn)
         (fn []
           (on-close)
           (st/emit! (dnt/show-leave-organization-modal
                      {:organization current-organization
                       :profile profile
                       :default-team-id org-default-team-id
                       :leave-fn org-leave-fn
                       :teams-to-transfer teams-to-transfer
                       :on-error org-leave-on-error}))))]

    (mf/use-effect
     (mf/deps owned-teams)
     (fn []
       (doseq [owned-team owned-teams
               :when (not (contains? owned-team :members))]
         (st/emit! (dtm/fetch-members (:id owned-team))))))

    [:> dropdown-menu* {:show show
                        :on-close on-close
                        :id id
                        :class class}
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
        (tr "dashboard.delete-team")])

     (when (:id current-organization)
       [:*
        [:hr {:class (stl/css :options-separator)}]
        [:> dropdown-menu-item* {:on-click on-leave-organization-clicked
                                 :class (stl/css :options-item)}
         (tr "dashboard.leave-organization")]])]))

(mf/defc organization-team-switch*
  [{:keys [team profile]}]
  (let [teams (mf/deref refs/teams)

        current-organization (dtm/team->organization team)
        current-organization-id (organization-bucket-id current-organization)

        subscription-type (get-subscription-type (-> profile :props :subscription))
        team-count (count teams)
        account-age-days (dnt/account-age-days profile)
        is-valid-license? (dnt/is-valid-license? profile)

        organizations
        (mf/with-memo [teams current-organization]
          (cond-> (->> teams
                       vals
                       (filter :is-default)
                       (map dtm/team->organization)
                       (d/index-by :id))
            (:id current-organization)
            (assoc (:id current-organization) current-organization)))

        show-menu*
        (mf/use-state false)

        show-menu?
        (deref show-menu*)

        show-options-menu*
        (mf/use-state false)

        show-options-menu?
        (deref show-options-menu*)

        ;; Which organization is previewed on the right column; reset
        ;; to the current one every time the menu is (re)opened.
        selected-organization-id*
        (mf/use-state current-organization-id)

        selected-organization-id
        (deref selected-organization-id*)

        ;; Context menu state for right-click on organizations
        context-menu*
        (mf/use-state nil)

        context-menu
        (deref context-menu*)

        selected-organization-teams
        (mf/with-memo [teams selected-organization-id]
          (->> teams
               vals
               (filter (fn [candidate-team]
                         (if (= selected-organization-id personal-bucket-id)
                           (and (:is-default candidate-team)
                                (nil? (dm/get-in candidate-team [:organization :id])))
                           (= selected-organization-id (dm/get-in candidate-team [:organization :id])))))
               (sort-by (juxt (complement :is-default) :name))))

        on-open-click
        (mf/use-fn
         (mf/deps current-organization-id)
         (fn [event]
           (dom/stop-propagation event)
           (reset! selected-organization-id* current-organization-id)
           (swap! show-menu* not)))

        on-close
        (mf/use-fn #(do (reset! show-menu* false) (reset! context-menu* nil)))

        on-close-options
        (mf/use-fn #(reset! show-options-menu* false))

        on-show-options-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-options-menu* not)))

        on-organization-select
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [value (-> (dom/get-current-target event) (dom/get-data "value"))]
             (reset! selected-organization-id*
                     (if (= value (str personal-bucket-id))
                       personal-bucket-id
                       (uuid/parse value))))))

        on-team-select
        (mf/use-fn
         (fn [event]
           (let [team-id (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (uuid/parse))]
             (if (new-tab-click? event)
               (dom/stop-propagation event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (st/emit! (dcm/go-to-dashboard-recent :team-id team-id))
                 (reset! show-menu* false))))))

        on-create-organization
        (mf/use-fn
         (mf/deps account-age-days profile subscription-type team-count)
         (fn []
           (reset! show-menu* false)
           (if (and (not= subscription-type "unlimited") is-valid-license?)
             (dnt/go-to-nitrate-ac-create-organization
              "dashboard:organization-switcher")
             (st/emit!
              (ev/event
               (cond-> {::ev/name "open-subscription-modal"
                        ::ev/origin "dashboard:organization-switcher"
                        :product "nitrate:enterprise"
                        :source "frontend"
                        :has-teams (pos? team-count)
                        :team-count team-count}
                 (some? account-age-days)
                 (assoc :account-age-days account-age-days)))
              (dnt/show-nitrate-popup
               :nitrate-form
               (cond-> {:subscription-start-origin "dashboard:organization-switcher"}
                 (= subscription-type "unlimited")
                 (assoc :show-contact-sales-option true)))))))

        admin-console-href
        (mf/with-memo [current-organization profile]
          (if (and (:id current-organization)
                   (= (:id profile) (:owner-id current-organization)))
            (dnt/build-admin-console-href {:organization-id (:id current-organization)
                                           :organization-slug (:slug current-organization)})
            (dnt/build-admin-console-href)))

        on-create-team
        (mf/use-fn
         (mf/deps selected-organization-id)
         (fn []
           (reset! show-menu* false)
           (if (contains? cf/flags :admin-console)
             (st/emit! (dtm/check-and-create-team
                        (when-not (= selected-organization-id personal-bucket-id)
                          (:default-team-id (get organizations selected-organization-id)))))
             (st/emit! (modal/show :team-form {})))))

        ;; Context menu computed values
        context-org (:organization context-menu)
        context-teams (when context-org (organization-teams teams (:id context-org)))

        {context-default-team-id :default-team-id
         context-owned-teams :owned-teams
         context-not-owned-teams :not-owned-teams}
        (organization-leave-info context-teams)

        context-owned-teams-members-loaded? (every? #(contains? % :members) context-owned-teams)
        context-teams-to-transfer (filter #(> (count (:members %)) 1) context-owned-teams)

        leave-fn
        (mf/use-fn
         (mf/deps context-org context-default-team-id context-not-owned-teams context-owned-teams)
         (fn [{:keys [teams-to-transfer
                      member-added-at
                      organization-member-count-before]}]
           (let [{:keys [teams-to-leave teams-to-delete]}
                 (organization-leave-params context-owned-teams context-not-owned-teams teams-to-transfer)]
             (st/emit! (dnt/leave-organization {:id (:id context-org)
                                                :name (:name context-org)
                                                :default-team-id context-default-team-id
                                                :teams-to-delete teams-to-delete
                                                :teams-to-leave teams-to-leave
                                                :member-added-at member-added-at
                                                :organization-member-count-before organization-member-count-before
                                                :on-error org-leave-on-error})))))

        on-leave-clicked
        (mf/use-fn
         (mf/deps leave-fn
                  profile
                  context-org
                  context-default-team-id
                  context-teams-to-transfer
                  context-owned-teams-members-loaded?)
         (fn []
           (when (and context-org context-owned-teams-members-loaded?)
             (reset! context-menu* nil)
             (reset! show-menu* false)
             (st/emit! (dnt/show-leave-organization-modal {:organization context-org
                                                           :profile profile
                                                           :default-team-id context-default-team-id
                                                           :leave-fn leave-fn
                                                           :teams-to-transfer context-teams-to-transfer
                                                           :on-error org-leave-on-error})))))

        on-organization-context-menu
        (mf/use-fn
         (fn [event organization]
           (dom/stop-propagation event)
           (when (:id organization)
             (dom/prevent-default event)
             (reset! context-menu* {:organization organization
                                    :x (.-clientX event)
                                    :y (.-clientY event)}))))]

    ;; Fetch members for owned teams when context menu opens
    (mf/use-effect
     (mf/deps context-menu context-owned-teams)
     (fn []
       (when context-menu
         (doseq [team context-owned-teams
                 :when (not (contains? team :members))]
           (st/emit! (dtm/fetch-members (:id team)))))))

    [:div {:class (stl/css :organization-team-switch)}
     [:div {:class (stl/css :switch-button-row)}
      [:button {:class (stl/css :current-selection)
                :on-click on-open-click
                :aria-expanded show-menu?
                :aria-haspopup "menu"}
       (if (:id team)
         [:img {:src (cf/resolve-team-photo-url team)
                :class (stl/css :team-picture)
                :alt (:name team)}]
         [:span {:class (stl/css :my-teams-icon)}
          [:> raw-svg* {:id penpot-logo-icon-subtle}]])
       [:div {:class (stl/css :current-selection-text)}
        [:span {:class (stl/css :current-team-name)}
         (if (:is-default team) (tr "dashboard.personal-projects") (:name team))]
        (when (:id current-organization)
          [:span {:class (stl/css :current-organization-name)}
           (:name current-organization)])]]

      [:button {:class (stl/css :options-button)
                :on-click on-show-options-click
                :aria-expanded show-options-menu?
                :aria-haspopup "menu"
                :aria-label (tr "labels.team-management")}
       [:> icon* {:icon-id i/menu :class (stl/css :options-icon)}]]

      [:> dropdown-menu* {:show show-menu?
                          :on-close on-close
                          :id "organization-team-switch"
                          :class (stl/css :organization-team-dropdown)}
       [:> organizations-column* {:organizations (->> (vals organizations)
                                                      (sort-by (juxt (fn [o] (if (nil? (:id o)) 1 0))
                                                                     (fn [o] (str/lower (:name o "")))
                                                                     :id)))
                                  :selected-id selected-organization-id
                                  :on-select on-organization-select
                                  :on-create-organization on-create-organization
                                  :admin-console-href admin-console-href
                                  :is-valid-license? is-valid-license?
                                  :on-context-menu on-organization-context-menu}]
       [:> teams-column* {:teams selected-organization-teams
                          :selected-team-id (:id team)
                          :on-select on-team-select
                          :on-context-menu keep-menu-context-menu
                          :on-create-team on-create-team}]]]

     [:> options-dropdown* {:show show-options-menu?
                            :on-close on-close-options
                            :id "team-options"
                            :class (stl/css :options-dropdown)
                            :team team
                            :profile profile
                            :current-organization current-organization
                            :teams teams}]

     ;; Context menu for leave organization
     (when context-menu
       [:> dropdown-menu* {:show true
                           :on-close #(reset! context-menu* nil)
                           :class (stl/css :organization-context-menu)
                           :style {:position "fixed"
                                   :top (str (:y context-menu) "px")
                                   :left (str (:x context-menu) "px")}}
        [:> dropdown-menu-item* {:on-click on-leave-clicked
                                 :class (stl/css :context-menu-item)}
         (tr "dashboard.leave-organization")]])]))
