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
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.organization-avatar :refer [organization-avatar*]]
   [app.main.ui.dashboard.organization-team-switch-menus :refer [options-dropdown*
                                                                 organization-context-menu*]]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type
                                               menu-team-icon*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private ^:svg-id penpot-logo-icon-subtle "penpot-logo-subtle")

;; Sentinel used for the "personal projects" bucket, since those teams
;; have no organization (`:organization` is `nil`).
(def ^:private personal-bucket-id :personal)

(defn organization-bucket-id
  [organization]
  (or (:id organization) personal-bucket-id))

(defn team-href
  [router team]
  (rt/resolve-uri router :dashboard-recent {:team-id (:id team)}))

(defn- team-display-name
  [team]
  (if (:is-default team) (tr "dashboard.personal-projects") (:name team)))

(defn show-subscription-badge?
  "Whether `team`'s U/E subscription badge should render: never on a
  default team (\"Personal projects\" has no billing of its own), and
  never on a team that belongs to an organization, since a team's
  `:subscription` reflects its owner's own plan, not any
  organization-level billing. Pending a real editor/seat billing
  model for organizations, only a standalone team outside every
  organization shows its own plan."
  [team]
  (and (not (:is-default team))
       (nil? (dm/get-in team [:organization :id]))
       (contains? #{"unlimited" "enterprise"} (get-subscription-type (:subscription team)))))

(defn sort-organization-teams
  "Orders the teams of a single organization for the dropdown's second
  column: alphabetical, with the organization's default team
  (\"Personal projects\") always last. `boolean` collapses a missing
  `:is-default` and an explicit `false` into one comparator value."
  [teams]
  (sort-by (juxt (fn [team] (boolean (:is-default team)))
                 (fn [team] (str/lower (:name team ""))))
           teams))

(defn sort-all-teams
  "Orders every team for the simplified single-column dropdown:
  alphabetical by display name, with \"Personal projects\" always
  last. Takes the display-name fn as a parameter so it stays pure;
  the component passes `team-display-name`, which translates the
  default team's label."
  ([teams]
   (sort-all-teams teams team-display-name))
  ([teams display-name]
   (sort-by (juxt (fn [team] (boolean (:is-default team)))
                  (comp str/lower display-name))
            teams)))

(defn teams-for-organization
  "Teams to preview in the dropdown's second column for
  `organization-id`: teams with no organization of their own when
  `organization-id` is the personal bucket, otherwise the teams
  belonging to that organization, sorted for display."
  [teams organization-id]
  (->> teams
       vals
       (filter (fn [candidate-team]
                 (if (= organization-id personal-bucket-id)
                   (nil? (dm/get-in candidate-team [:organization :id]))
                   (= organization-id (dm/get-in candidate-team [:organization :id])))))
       sort-organization-teams))

(defn sort-organizations
  "Orders the organizations for the dropdown's first column:
  alphabetical, with the \"Other teams\" bucket (no id) always last;
  organizations sharing a name tie-break on id so the order is
  stable."
  [organizations]
  (sort-by (juxt (fn [organization] (if (nil? (:id organization)) 1 0))
                 (fn [organization] (str/lower (:name organization "")))
                 :id)
           organizations))

(defn closed-control-line-2
  "The organization name for the second line of the closed switcher
  control, or nil when the line must not be rendered at all: the
  profile belongs to no organization, or the current destination
  (personal projects, or a team under the \"Other teams\" bucket) has
  no organization of its own."
  [has-organizations? current-organization]
  (when has-organizations?
    (:name current-organization)))

(defn create-team-target-id
  "The organization default team id that \"Create new team\" targets,
  or nil when the new team belongs under \"Other teams\". Creation
  targets the organization currently previewed in the switcher's left
  column, i.e. the one the user triggers the action from, not
  necessarily the open dashboard's own organization."
  [organizations organization-id]
  (when-not (= organization-id personal-bucket-id)
    (:default-team-id (get organizations organization-id))))

(defn team-select-target
  "The team id to navigate to when a team row is selected, or nil
  when selecting is a no-op beyond closing the switcher because the
  user is already in that team."
  [selected-team-id current-team]
  (when (not= selected-team-id (:id current-team))
    selected-team-id))

(defn show-create-organization-in-teams-column?
  "Whether the simplified single-column dropdown should offer its own
  \"Create new organization\" fallback action: only on deployments
  where the admin-console (Nitrate) feature exists at all. Mirrors
  the pre-merge sidebar's top-level `(when nitrate? ...)` gate, which
  used to hide the whole organization-switcher UI (including this
  action) on installs without the flag."
  [flags]
  (contains? flags :admin-console))

(defn resolve-admin-console-href
  "The admin-console link for the pinned action at the bottom of the
  organizations column: the organization-specific page when `profile`
  owns `organization`, the generic admin-console page otherwise —
  including when no organization is previewed at all (e.g. \"Other
  teams\"/personal projects is selected)."
  [organization profile]
  (if (and (:id organization) (= (:id profile) (:owner-id organization)))
    (dnt/build-admin-console-href {:organization-id (:id organization)
                                   :organization-slug (:slug organization)})
    (dnt/build-admin-console-href)))

(defn- new-tab-click?
  [event]
  (or (kbd/mod? event) (dom/middle-mouse? event)))

(mf/defc organizations-column*
  {::mf/private true}
  [{:keys [organizations current-id ^boolean has-organizations? on-select on-create-organization
           admin-console-href ^boolean valid-license on-context-menu on-dismiss-context-menu]}]
  [:li {:role "presentation" :class (stl/css :organizations-column)}
   [:div {:class (stl/css :column-label)}
    (tr "dashboard.section.organizations")]

   [:ul {:class (stl/css :column-list)}
    (when-not has-organizations?
      [:li {:class (stl/css :empty-state)}
       (tr "dashboard.no-organizations-yet")])

    (for [organization organizations]
      (let [bucket-id (organization-bucket-id organization)
            personal? (= bucket-id personal-bucket-id)]
        [:* {:key (str bucket-id)}
         ;; "Other teams" is always last (see `sort-organizations`),
         ;; set apart from the real organizations above it the same
         ;; way `.column-separator` sets the pinned actions apart
         ;; from the list.
         (when personal?
           [:li {:role "separator" :class (stl/css :column-separator)}])
         [:> dropdown-menu-item* {:data-value (str bucket-id)
                                  :class (stl/css-case :organization-item true
                                                       :selected (= bucket-id current-id))
                                  :on-click on-select
                                  :on-context-menu #(on-context-menu % organization)}
          (if personal?
            [:span {:class (stl/css :my-teams-icon)}
             [:> raw-svg* {:id penpot-logo-icon-subtle}]]
            [:> organization-avatar* {:organization organization :size "xxl"}])
          [:span {:class (stl/css :organization-text-group)}
           [:span {:class (stl/css :organization-text)
                   :title (if personal? (tr "dashboard.other-teams") (:name organization))}
            (if personal? (tr "dashboard.other-teams") (:name organization))]
           (when (= bucket-id current-id)
             [:span {:class (stl/css :tick-icon)}
              [:> icon* {:icon-id i/tick :size "s"}]])]
          [:span {:class (stl/css :chevron-icon)}
           [:> icon* {:icon-id i/arrow-right :size "s"}]]]]))]

   [:ul {:class (stl/css :column-actions)}
    [:li {:role "separator" :class (stl/css :column-separator)}]

    [:> dropdown-menu-item* {:on-click on-create-organization
                             :class (stl/css :organization-item :action)}
     [:span {:class (stl/css :icon-wrapper)}
      [:> icon* {:icon-id i/add :class (stl/css :action-icon)}]]
     [:span {:class (stl/css :organization-text)} (tr "dashboard.create-new-organization")]]

    (when valid-license
      [:> dropdown-menu-item* {:class (stl/css :organization-item :action :with-link)
                               :on-click (fn [event]
                                           (if (new-tab-click? event)
                                             (dom/stop-propagation event)
                                             (do
                                               (dom/prevent-default event)
                                               (dom/stop-propagation event)
                                               (st/emit! (rt/nav-raw :href admin-console-href)))))
                               :on-context-menu on-dismiss-context-menu}
       [:a {:class (stl/css :item-link)
            :href admin-console-href
            :tab-index "-1"}
        [:span {:class (stl/css :icon-wrapper)}
         [:> icon* {:icon-id i/arrow-up-right :class (stl/css :action-icon)}]]
        [:span {:class (stl/css :organization-text)} (tr "dashboard.go-to-admin-console")]]])]])

(mf/defc teams-column*
  {::mf/private true}
  [{:keys [teams selected-team-id on-select on-context-menu on-create-team
           on-create-organization]}]
  (let [router (mf/deref refs/router)]
    [:li {:role "presentation" :class (stl/css :teams-column)}
     [:div {:class (stl/css :column-label)}
      (tr "dashboard.section.teams")]

     [:ul {:class (stl/css :column-list)}
      (for [team teams]
        (let [subscription-type (get-subscription-type (:subscription team))
              show-badge? (show-subscription-badge? team)]
          [:* {:key (str (:id team))}
           ;; "Personal projects" is always last (see
           ;; `sort-organization-teams`/`sort-all-teams`), set apart
           ;; from the real teams above it the same way
           ;; `.column-separator` sets the pinned actions apart from
           ;; the list. Skipped when it's the only team: there's
           ;; nothing above it to separate from.
           (when (and (:is-default team) (> (count teams) 1))
             [:li {:role "separator" :class (stl/css :column-separator)}])
           [:> dropdown-menu-item* {:data-value (str (:id team))
                                    :class (stl/css-case :team-item true
                                                         :with-link true)
                                    :on-click on-select
                                    :on-context-menu on-context-menu}
            [:a {:class (stl/css :item-link)
                 :href (team-href router team)
                 :tab-index "-1"}
             (if (:is-default team)
               [:span {:class (stl/css :team-item-personal-icon)}
                [:> icon* {:icon-id i/files}]]
               [:img {:src (cf/resolve-team-photo-url team)
                      :class (stl/css :team-item-picture)
                      :alt (:name team)}])
             [:span {:class (stl/css :team-text-group)}
              [:span {:class (stl/css :team-text)
                      :title (team-display-name team)}
               (team-display-name team)]
              (when show-badge?
                [:> menu-team-icon* {:subscription-type subscription-type}])
              (when (= (:id team) selected-team-id)
                [:span {:class (stl/css :tick-icon)}
                 [:> icon* {:icon-id i/tick :size "s"}]])]]]]))]

     [:ul {:class (stl/css :column-actions)}
      [:li {:role "separator" :class (stl/css :column-separator)}]

      [:> dropdown-menu-item* {:on-click on-create-team
                               :class (stl/css :team-item :action)}
       [:span {:class (stl/css :icon-wrapper)}
        [:> icon* {:icon-id i/add :class (stl/css :action-icon)}]]
       [:span {:class (stl/css :team-text)} (tr "dashboard.create-new-team")]]

      ;; Only present when there is no separate organizations column to
      ;; host it (no valid license and no organization membership).
      (when on-create-organization
        [:> dropdown-menu-item* {:on-click on-create-organization
                                 :class (stl/css :team-item :action)}
         [:span {:class (stl/css :icon-wrapper)}
          [:> icon* {:icon-id i/add :class (stl/css :action-icon)}]]
         [:span {:class (stl/css :team-text)} (tr "dashboard.create-new-organization")]])]]))

(mf/defc organization-team-switch*
  [{:keys [team profile]}]
  (let [teams (mf/deref refs/teams)

        current-organization (dtm/team->organization team)
        current-organization-id (organization-bucket-id current-organization)

        ;; The organization owner cannot leave their own organization
        ;; from the team options menu.
        can-leave-organization?
        (and (:id current-organization)
             (not= (:id profile) (:owner-id current-organization)))

        ;; A default team (the personal "my teams" bucket, or an
        ;; organization's own default team) has no options of its own
        ;; to manage; the "..." button is only worth showing when
        ;; there is at least a "leave organization" action behind it.
        show-team-options-button?
        (or (not (:is-default team)) can-leave-organization?)

        subscription-type (get-subscription-type (-> profile :props :subscription))
        current-team-subscription-type (get-subscription-type (:subscription team))
        team-count (count teams)
        account-age-days (dnt/account-age-days profile)
        is-valid-license? (dnt/is-valid-license? profile)

        ;; Keyed by `organization-bucket-id`, not raw `:id`: every
        ;; personal default team maps to a nil organization, so
        ;; indexing by `:id` would key them all under a bare `nil`
        ;; that happens to collide with the map's own "no value"
        ;; result. Keying by the bucket id instead makes the
        ;; "personal/other-teams" entry explicit and gives it a
        ;; lookup key consistent with `selected-organization-id` and
        ;; `create-team-target-id`, which already reason in terms of
        ;; `personal-bucket-id`.
        organizations
        (mf/with-memo [teams current-organization]
          (cond-> (->> teams
                       vals
                       (filter :is-default)
                       (map dtm/team->organization)
                       (d/index-by organization-bucket-id))
            (:id current-organization)
            (assoc (:id current-organization) current-organization)))

        has-organizations?
        (some #(not= personal-bucket-id %) (keys organizations))

        ;; Without a valid license and without belonging to any real
        ;; organization, there is nothing to put in an organizations
        ;; column at all: fall back to a single teams-only column,
        ;; with "create organization" folded into it (see
        ;; `teams-column*`'s `on-create-organization`).
        simplified-mode?
        (and (not is-valid-license?) (not has-organizations?))

        all-teams-sorted
        (mf/with-memo [teams]
          (sort-all-teams (vals teams)))

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
          (teams-for-organization teams selected-organization-id))

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
         (mf/deps team)
         (fn [event]
           (let [team-id (-> (dom/get-current-target event)
                             (dom/get-data "value")
                             (uuid/parse))]
             (if (new-tab-click? event)
               (dom/stop-propagation event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (reset! show-menu* false)
                 (when-let [target-team-id (team-select-target team-id team)]
                   (st/emit! (dcm/go-to-dashboard-recent :team-id target-team-id))))))))

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

        ;; "Go to admin console" targets the organization currently
        ;; previewed on the left column (not necessarily the active
        ;; team's organization), and only owners of that organization
        ;; get the option at all.
        selected-organization
        (get organizations selected-organization-id)

        admin-console-href
        (mf/with-memo [selected-organization profile]
          (resolve-admin-console-href selected-organization profile))

        ;; "Create new team" targets the organization previewed on the
        ;; left column (`selected-organization-id`), i.e. wherever the
        ;; user triggers the action from, or "Other teams" when
        ;; that's the previewed group.
        on-create-team
        (mf/use-fn
         (mf/deps organizations selected-organization-id)
         (fn []
           (reset! show-menu* false)
           (if (contains? cf/flags :admin-console)
             (st/emit! (dtm/check-and-create-team
                        (create-team-target-id organizations selected-organization-id)))
             (st/emit! (modal/show :team-form {})))))

        on-close-context-menu
        (mf/use-fn #(reset! context-menu* nil))

        ;; The organization owner cannot leave their own organization,
        ;; which is the only action offered here, so right-clicking an
        ;; owned organization (or the personal-projects bucket, which
        ;; has none) never opens a menu; it just dismisses whichever
        ;; one might already be open, same as clicking anywhere else.
        on-organization-context-menu
        (mf/use-fn
         (mf/deps profile on-close-context-menu)
         (fn [event organization]
           (dom/stop-propagation event)
           (if (and (:id organization)
                    (not= (:id profile) (:owner-id organization)))
             (do
               (dom/prevent-default event)
               (reset! context-menu* {:organization organization
                                      :x (.-clientX event)
                                      :y (.-clientY event)}))
             (on-close-context-menu))))

        ;; Right-clicking a row with no context menu of its own (team
        ;; rows, the admin-console link) still needs to dismiss any
        ;; already-open organization context menu: `stop-propagation`
        ;; here keeps the two-column dropdown itself open, but it also
        ;; prevents that click from ever reaching the document-level
        ;; listener the organization context menu relies on to close
        ;; itself, so it has to be closed explicitly instead.
        on-dismiss-context-menu
        (mf/use-fn
         (mf/deps on-close-context-menu)
         (fn [event]
           (dom/stop-propagation event)
           (on-close-context-menu)))

        on-organization-leave-requested
        (mf/use-fn #(do (reset! context-menu* nil) (reset! show-menu* false)))]

    [:div {:class (stl/css :organization-team-switch)}
     [:div {:class (stl/css :switch-button-row)}
      [:button {:class (stl/css-case :current-selection true
                                     :no-options-button (not show-team-options-button?))
                :on-click on-open-click
                :aria-expanded show-menu?
                :aria-haspopup "menu"}
       (if (:is-default team)
         [:div {:class (stl/css :personal-projects-icon)}
          [:> icon* {:icon-id i/files}]]

         [:img {:src (cf/resolve-team-photo-url team)
                :class (stl/css :team-picture)
                :alt (:name team)}])
       [:div {:class (stl/css :current-selection-text)}
        [:div {:class (stl/css :current-team-name-group)}
         [:span {:class (stl/css :current-team-name)
                 :title (team-display-name team)}
          (team-display-name team)]
         (when (show-subscription-badge? team)
           [:> menu-team-icon* {:subscription-type current-team-subscription-type}])]
        (when-let [organization-name (closed-control-line-2 has-organizations? current-organization)]
          [:span {:class (stl/css :current-organization-name)
                  :title organization-name}
           organization-name])]]

      (when show-team-options-button?
        [:button {:class (stl/css :options-button)
                  :on-click on-show-options-click
                  :aria-expanded show-options-menu?
                  :aria-haspopup "menu"
                  :aria-label (tr "labels.team-management")
                  :data-testid "team-options-button"}
         [:> icon* {:icon-id i/menu :class (stl/css :options-icon)}]])

      (if simplified-mode?
        [:> dropdown-menu* {:show show-menu?
                            :on-close on-close
                            :id "organization-team-switch"
                            :class (stl/css :organization-team-dropdown :single-column)}
         [:> teams-column* {:teams all-teams-sorted
                            :selected-team-id (:id team)
                            :on-select on-team-select
                            :on-context-menu on-dismiss-context-menu
                            :on-create-team on-create-team
                            :on-create-organization (when (show-create-organization-in-teams-column? cf/flags)
                                                      on-create-organization)}]]
        [:> dropdown-menu* {:show show-menu?
                            :on-close on-close
                            :id "organization-team-switch"
                            :class (stl/css :organization-team-dropdown)}
         [:> organizations-column* {:organizations (sort-organizations (vals organizations))
                                    :current-id current-organization-id
                                    :has-organizations? has-organizations?
                                    :on-select on-organization-select
                                    :on-create-organization on-create-organization
                                    :admin-console-href admin-console-href
                                    :valid-license is-valid-license?
                                    :on-context-menu on-organization-context-menu
                                    :on-dismiss-context-menu on-dismiss-context-menu}]
         [:> teams-column* {:teams selected-organization-teams
                            :selected-team-id (:id team)
                            :on-select on-team-select
                            :on-context-menu on-dismiss-context-menu
                            :on-create-team on-create-team}]])]

     [:> options-dropdown* {:show show-options-menu?
                            :on-close on-close-options
                            :id "team-options"
                            :class (stl/css :options-dropdown)
                            :team team
                            :profile profile
                            :current-organization current-organization
                            :can-leave-organization can-leave-organization?
                            :teams teams}]

     ;; Context menu for leave organization
     (when context-menu
       [:> organization-context-menu* {:organization (:organization context-menu)
                                       :teams teams
                                       :profile profile
                                       :x (:x context-menu)
                                       :y (:y context-menu)
                                       :on-close on-close-context-menu
                                       :on-leave-requested on-organization-leave-requested}])]))
