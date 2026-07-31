(ns app.main.data.nitrate
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.nitrate-permissions :as nitrate-perms]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate-audit :as nitrate-audit]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dt]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.session-state :as ss]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private nitrate-entry-pending-popup-key ::nitrate-entry-pending-popup)
(defn account-age-days
  [profile]
  (nitrate-audit/age-days (:created-at profile)))

(defn activate-nitrate-entry-popup!
  []
  (binding [storage/*sync* true]
    (swap! storage/storage assoc
           nitrate-entry-pending-popup-key true)))

(defn nitrate-entry-popup-pending?
  []
  (true? (get storage/storage nitrate-entry-pending-popup-key)))

(defn consume-nitrate-entry-popup!
  []
  (binding [storage/*sync* true]
    (swap! storage/storage dissoc
           nitrate-entry-pending-popup-key)))

(defn show-nitrate-popup
  ([popup-type] (show-nitrate-popup popup-type {}))
  ([popup-type extra-props]
   (ptk/reify ::show-nitrate-popup
     ptk/WatchEvent
     (watch [_ _ _]
       (->> (rp/cmd! ::get-nitrate-connectivity {})
            (rx/map (fn [connectivity]
                      (modal/show popup-type (merge (or connectivity {}) extra-props)))))))))

(defn build-admin-console-url
  ([path]
   (build-admin-console-url cf/public-uri path nil))
  ([path query-params]
   (build-admin-console-url cf/public-uri path query-params))
  ([public-uri path query-params]
   (dm/str
    (cond-> (u/join public-uri "admin-console/" path)
      (seq query-params) (assoc :query (u/map->query-string query-params))))))

(defn go-to-nitrate-ac
  ([]
   (st/emit! (rt/nav-raw :href (build-admin-console-url ""))))
  ([{:keys [organization-id organization-slug]}]
   (if (and organization-id organization-slug)
     (let [path (dm/str "organization/"
                        (u/percent-encode organization-slug)
                        "/"
                        (u/percent-encode (str organization-id))
                        "/people/")
           href (build-admin-console-url path)]
       (st/emit! (rt/nav-raw :href href)))
     (st/emit! (rt/nav-raw :href (build-admin-console-url ""))))))

(defn go-to-nitrate-ac-create-organization
  [event-origin]
  (let [href (build-admin-console-url "" {:action "create-organization"
                                          :origin event-origin})]
    (st/emit! (rt/nav-raw :href href))))

(defn can-send-invitations?
  [{:keys [organization profile-id team-permissions]}]
  (nitrate-perms/can-send-invitations?
   {:nitrate-enabled? (contains? cf/flags :nitrate)
    :organization organization
    :profile-id profile-id
    :team-permissions team-permissions}))

(def go-to-subscription-url (dm/str (u/join cf/public-uri "#/settings/subscriptions")))

(def go-to-ac-url (build-admin-console-url ""))

(defn go-to-nitrate-billing
  []
  (let [href (build-admin-console-url "licenses/billing"
                                      {:callback go-to-subscription-url})]
    (st/emit! (rt/nav-raw :href href))))

(def nitrate-checkout-error-token "nitrate-checkout-error")
(def nitrate-checkout-finish-error-token "nitrate-checkout-finish-error")
(def nitrate-checkout-cancelled-token "nitrate-checkout-cancelled")

(defn build-nitrate-callback-urls
  "Build checkout callback URLs from base URLs by appending a `subscription`
  query param identifying the outcome."
  [base-url base-error-url]
  (let [build (fn [url token]
                (dom/append-query-param url :subscription token))]
    {:success-callback      (build base-url "subscribed-to-penpot-nitrate")
     :error-callback        (build base-error-url nitrate-checkout-error-token)
     :finish-error-callback (build base-error-url nitrate-checkout-finish-error-token)
     :cancel-callback       (build base-url nitrate-checkout-cancelled-token)}))

(defn go-to-buy-nitrate-license
  [subscription base-url base-error-url event-origin subscription-mode subscription-start-origin]
  (let [{:keys [success-callback error-callback finish-error-callback cancel-callback]}
        (build-nitrate-callback-urls base-url base-error-url)
        params {:subscription subscription
                :callback success-callback
                :error_callback error-callback
                :finish_error_callback finish-error-callback
                :cancel_callback cancel-callback}
        href   (build-admin-console-url "licenses/start" params)
        event  (ev/event {::ev/name "start-nitrate-checkout"
                          ::ev/origin event-origin
                          :product "nitrate:enterprise"
                          :billing-period subscription
                          :subscription-mode subscription-mode
                          :subscription-start-origin subscription-start-origin})]
    (->> st/stream
         (rx/filter (ptk/type? ::ev/chunk-persisted))
         (rx/take 1)
         (rx/timeout 2000 (rx/of :timeout))
         (rx/subs! (fn [_]
                     (st/emit! (rt/nav-raw :href href)))))
    (st/emit! event (ptk/data-event ::ev/force-persist {}))))

(defn fetch-connectivity
  []
  (rp/cmd! ::get-nitrate-connectivity {}))

(defn fetch-subscription-warning
  []
  (rp/cmd! ::get-subscription-warning {}))

(defn is-valid-license?
  [profile]
  (and (contains? cf/flags :nitrate)
       ;; Possible values: "active" "canceled" "incomplete" "incomplete_expired" "past_due" "paused" "trialing" "unpaid"
       (contains? #{"active" "past_due" "trialing"}
                  (dm/get-in profile [:subscription :status]))))

(defn leave-organization
  [{:keys [id
           name
           default-team-id
           teams-to-delete
           teams-to-leave
           member-added-at
           organization-member-count-before
           on-error]}]

  (ptk/reify ::leave-organization
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id         (dm/get-in state [:profile :id])
            profile-team-id    (dm/get-in state [:profile :default-team-id])
            subscription-status
            (if (= "trialing" (dm/get-in state [:profile :subscription :status]))
              "trial"
              "active")
            audit-event
            (nitrate-audit/delete-organization-member-event
             {:organization-id id
              :user-id profile-id
              :user-who-delete-member profile-id
              :deleted-by-role "organization-member"
              :member-added-at member-added-at
              :organization-member-count-before organization-member-count-before
              :subscription-status subscription-status})]
        (rx/concat
         (rx/of audit-event)
         (->> (rp/cmd! ::leave-organization {:id id
                                             :name name
                                             :default-team-id default-team-id
                                             :teams-to-delete teams-to-delete
                                             :teams-to-leave teams-to-leave})
              (rx/mapcat
               (fn [_]
                 (rx/of
                  (dt/fetch-teams)
                  (dcm/go-to-dashboard-recent :team-id profile-team-id)
                  (modal/hide)
                  (ntf/show {:content (tr "dashboard.leave-organization.toast" name)
                             :type :toast
                             :level :success}))))
              (rx/catch on-error)))))))

(defn show-leave-organization-modal
  [{:keys [organization profile default-team-id leave-fn teams-to-transfer on-error]}]
  (ptk/reify ::show-leave-organization-modal
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-leave-organization-summary {:id (:id organization)
                                                      :default-team-id default-team-id})
           (rx/mapcat
            (fn [summary]
              (let [num-teams-to-delete (:teams-to-delete summary)
                    num-teams-to-transfer (:teams-to-transfer summary)
                    num-teams-to-exit (:teams-to-exit summary)
                    num-teams-to-detach (:teams-to-detach summary)
                    leave-fn
                    (fn [params]
                      (leave-fn
                       (assoc params
                              :member-added-at (:member-added-at summary)
                              :organization-member-count-before
                              (:organization-member-count-before summary))))]
                (cond
                  (pos? num-teams-to-transfer)
                  (rx/of
                   (modal/show
                    {:type :leave-and-reassign-organization
                     :profile profile
                     :teams-to-transfer teams-to-transfer
                     :num-teams-to-delete num-teams-to-delete
                     :accept leave-fn}))

                  (or (pos? num-teams-to-delete)
                      (pos? num-teams-to-exit)
                      (pos? num-teams-to-detach))
                  (rx/of (modal/show
                          {:type :confirm
                           :title (tr "modals.before-leave-organization.title" (:name organization))
                           :message (tr "modals.before-leave-organization.message")
                           :accept-label (tr "modals.leave-organization-confirm.accept")
                           :on-accept leave-fn
                           :error-msg (tr "modals.before-leave-organization.warning")}))

                  :else
                  (rx/of (modal/show
                          {:type :confirm
                           :title (tr "modals.leave-organization-confirm.title" (:name organization))
                           :message (tr "modals.leave-organization-confirm.message")
                           :accept-label (tr "modals.leave-organization-confirm.accept")
                           :on-accept leave-fn}))))))
           (rx/catch on-error)))))


(defn remove-team-from-organization
  [{:keys [team-id organization-id organization-name] :as params}]
  (ptk/reify ::remove-team-from-organization
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::remove-team-from-organization {:team-id team-id :organization-id organization-id :organization-name organization-name})
           (rx/mapcat
            (fn [_]
              (rx/of (dt/fetch-teams)
                     (modal/hide))))
           (rx/catch
            (fn [cause]
              (let [code (-> cause ex-data :code)]
                (if (= code :not-allowed)
                  (rx/of (modal/show :no-permission-modal {:type :no-organizations-change}))
                  (rx/throw cause)))))))))

(defn show-remove-team-from-organization-modal
  "Fetches fresh team/organization data, then shows the remove-from-organization confirmation
  modal or the no-permission modal if the move-team permission blocks it."
  [{:keys [team-id]}]
  (ptk/reify ::show-remove-team-from-organization-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (dt/with-refreshed-team team-id
          (fn [team]
            (let [source-organization (:organization team)
                  can-move?  (nitrate-perms/allowed?
                              :move-team
                              {:organization-perms {:owner-id    (:owner-id source-organization)
                                                    :permissions (:permissions source-organization)}
                               :profile-id profile-id
                               :team-perms (:permissions team)
                               :target-organization-same-owner? false})]
              (rx/of (if can-move?
                       (modal/show
                        {:type :confirm
                         :title (tr "modals.remove-team-organization.title")
                         :message (tr "modals.remove-team-organization.text" (:name team) (:name source-organization))
                         :hint (tr "modals.remove-team-organization.info")
                         :hint-level :default
                         :accept-label (tr "modals.remove-team-organization.accept")
                         :on-accept #(st/emit! (remove-team-from-organization {:team-id team-id
                                                                               :organization-id (:id source-organization)
                                                                               :organization-name (:name source-organization)}))
                         :accept-style :danger})
                       (modal/show :no-permission-modal {:type :no-organizations-change}))))))))))


(defn add-team-to-organization
  "Adds a team to an organization after checking whether the target
  organization requires a fresh Nitrate SSO session. When SSO is required,
  stores the pending action and redirects to the provider so the dashboard
  can resume the operation after the callback."
  [{:keys [team-id organization-id skip-audit?]}]
  (ptk/reify ::add-team-to-organization
    ptk/WatchEvent
    (watch [_ state _]
      (let [team         (dm/get-in state [:teams team-id])
            organization-team-count-before
            (nitrate-audit/organization-team-count
             (vals (:teams state))
             organization-id)
            team-previous-organization-status
            (if (or (:organization-id team)
                    (get-in team [:organization :id]))
              "other-organization"
              "no-organization")
            subscription-status
            (or (dm/get-in state [:profile :subscription :status])
                "active")
            audit-event
            (nitrate-audit/add-team-to-organization-event
             {:team team
              :organization-id organization-id
              :organization-team-count-before organization-team-count-before
              :team-previous-organization-status team-previous-organization-status
              :add-method "move-existing-team-to-organization"
              :subscription-status subscription-status})
            pending-id   (str (uuid/next))
            callback-url (dom/append-query-param (rt/get-current-href)
                                                 :pending-action-id pending-id)]
        (rx/concat
         (when-not skip-audit?
           (rx/of audit-event))
         (->> (rp/cmd! :check-nitrate-sso {:organization-id organization-id :url callback-url})
              (rx/mapcat
               (fn [{:keys [authorized redirect-uri]}]
                 (if authorized
                   (->> (rp/cmd! ::add-team-to-organization {:team-id team-id :organization-id organization-id})
                        (rx/map (fn [_] (modal/hide))))
                   (if redirect-uri
                     (do
                       (ss/save-pending-action! pending-id {:type            :add-team-to-organization
                                                            :team-id         team-id
                                                            :organization-id organization-id})
                       (rx/of (rt/nav-raw :uri (str redirect-uri))))
                     (rx/empty)))))))))))


(defn- fetch-organizations-allowed
  "Returns an rx observable of an `organizations-allowed` map (organization-id -> boolean).
   Organizations where :add-anybody-to-team is permitted are pre-approved;
   the rest are verified via :all-team-members-in-organizations."
  [team-id organizations]
  (let [add-anybody-organizations (filterv #(nitrate-perms/allowed? :add-anybody-to-team {:organization-perms %}) organizations)
        organizations-to-check    (filterv #(not (nitrate-perms/allowed? :add-anybody-to-team {:organization-perms %})) organizations)
        organization-ids-to-check (mapv :id organizations-to-check)]
    (if (empty? organization-ids-to-check)
      (rx/of (into {} (map (fn [organization] [(:id organization) true])) organizations))
      (->> (rp/cmd! :all-team-members-in-organizations {:team-id team-id :organization-ids organization-ids-to-check})
           (rx/map (fn [checked-organizations]
                     (merge (into {} (map (fn [organization] [(:id organization) true])) add-anybody-organizations)
                            checked-organizations)))))))

(defn show-add-team-to-organization-modal
  "Fetches fresh team/organization data, then shows the add-to-organization modal
  restricted to organizations where the user has permission, or the no-permission
  modal if none qualify."
  [{:keys [team-id]}]
  (ptk/reify ::show-add-team-to-organization-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (->> (rp/cmd! :get-teams)
             (rx/mapcat
              (fn [teams]
                (let [all-organizations (map dt/team->organization
                                             (filter #(and (:is-default %) (:organization %)) teams))
                      organizations     (filter (fn [organization]
                                                  (let [perm    (dm/get-in organization [:permissions :create-teams])
                                                        is-own? (= profile-id (:owner-id organization))]
                                                    (or (= perm "any") is-own?))) all-organizations)
                      team     (first (filter #(= (:id %) team-id) teams))
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-organization {:team-id team-id
                                                                        :organization-id organization-id})))
                      show-select-modal
                      (fn [organizations-allowed]
                        (let [has-filtered? (< (count organizations) (count all-organizations))
                              extra-props   (when has-filtered?
                                              {:info-message-key "dashboard.select-organization-modal.permission-info"})]
                          (modal/show :select-organization-modal
                                      (merge {:organizations organizations
                                              :organizations-allowed organizations-allowed
                                              :current-organization-id (dm/get-in team [:organization :id])
                                              :on-confirm on-confirm
                                              :team-id team-id
                                              :title-key "dashboard.select-organization-modal.title"
                                              :choose-key "dashboard.select-organization-modal.choose"
                                              :placeholder-key "dashboard.select-organization-modal.select"
                                              :accept-key "dashboard.select-organization-modal.accept"
                                              :cancel-key "labels.cancel"}
                                             extra-props))))]
                  (if (empty? organizations)
                    (rx/of (dt/teams-fetched teams)
                           (modal/show :no-permission-modal {:type :no-organizations-create}))
                    (->> (fetch-organizations-allowed team-id organizations)
                         (rx/mapcat
                          (fn [organizations-allowed]
                            (let [valid-organizations (filterv #(true? (get organizations-allowed (:id %))) organizations)]
                              (rx/of
                               (dt/teams-fetched teams)
                               (if (empty? valid-organizations)
                                 (modal/show
                                  {:type :alert
                                   :hide-actions? true
                                   :message (tr "dashboard.team-organization.add.no-valid-organizations")
                                   :title (tr "dashboard.select-organization-modal.title")})
                                 (show-select-modal organizations-allowed))))))))))))))))

(defn show-change-team-organization-modal
  "Fetches fresh team/organization data, then shows the change-organization modal
  restricted to organizations where the user has permission, or the no-permission
  modal if none qualify."
  [{:keys [team-id]}]
  (ptk/reify ::show-change-team-organization-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (->> (rp/cmd! :get-teams)
             (rx/mapcat
              (fn [teams]
                (let [all-organizations     (map dt/team->organization
                                                 (filter #(and (:is-default %) (:organization %)) teams))
                      team         (first (filter #(= (:id %) team-id) teams))
                      source-organization   (:organization team)
                      current-organization-id (:id source-organization)
                      move-perm    (dm/get-in source-organization [:permissions :move-teams])
                      source-owner-id (:owner-id source-organization)
                      can-create?  (fn [organization]
                                     (let [perm    (dm/get-in organization [:permissions :create-teams])
                                           is-own? (= profile-id (:owner-id organization))]
                                       (or (= perm "any") is-own?)))
                      organizations-by-move (case move-perm
                                              "never"
                                              []

                                              "myOrganizations"
                                              (filter #(= source-owner-id (:owner-id %)) all-organizations)

                                              ;; Default to always-allowed behavior.
                                              all-organizations)
                      organizations         (filter can-create? organizations-by-move)
                      selectable-organizations (remove #(= current-organization-id (:id %)) organizations)
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-organization {:team-id team-id
                                                                        :organization-id organization-id})))]
                  (if (empty? selectable-organizations)
                    (rx/of (dt/teams-fetched teams)
                           (modal/show :no-permission-modal {:type :no-organizations-change}))
                    (->> (fetch-organizations-allowed team-id selectable-organizations)
                         (rx/mapcat
                          (fn [organizations-allowed]
                            (let [valid-organizations    (filterv #(true? (get organizations-allowed (:id %))) selectable-organizations)
                                  has-filtered? (< (count organizations) (count all-organizations))
                                  extra-props   (when has-filtered?
                                                  {:info-message-key "dashboard.select-organization-modal.permission-info"})]
                              (rx/of
                               (dt/teams-fetched teams)
                               (if (empty? valid-organizations)
                                 (modal/show
                                  {:type :alert
                                   :hide-actions? true
                                   :message (tr "dashboard.team-organization.add.no-valid-organizations")
                                   :title (tr "dashboard.change-organization-modal.title")})
                                 (modal/show :select-organization-modal
                                             (merge {:organizations           selectable-organizations
                                                     :organizations-allowed            organizations-allowed
                                                     :current-organization-id current-organization-id
                                                     :on-confirm              on-confirm
                                                     :team-id                 team-id
                                                     :title-key               "dashboard.change-organization-modal.title"
                                                     :choose-key              "dashboard.change-organization-modal.choose"
                                                     :placeholder-key         "dashboard.change-organization-modal.select"
                                                     :accept-key              "dashboard.change-organization-modal.accept"
                                                     :cancel-key              "labels.cancel"}
                                                    extra-props)))))))))))))))))
