(ns app.main.data.nitrate
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.nitrate-permissions :as nitrate-perms]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dt]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def ^:private nitrate-entry-active-key ::nitrate-entry-active)
(def ^:private nitrate-entry-pending-popup-key ::nitrate-entry-pending-popup)

(defn activate-nitrate-entry-popup!
  []
  (binding [storage/*sync* true]
    (swap! storage/storage assoc
           nitrate-entry-active-key true
           nitrate-entry-pending-popup-key true)))

(defn nitrate-entry-active?
  []
  (true? (get storage/storage nitrate-entry-active-key)))

(defn nitrate-entry-popup-pending?
  []
  (true? (get storage/storage nitrate-entry-pending-popup-key)))

(defn consume-nitrate-entry-popup!
  []
  (binding [storage/*sync* true]
    (swap! storage/storage dissoc
           nitrate-entry-active-key
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

(defn go-to-nitrate-ac
  ([]
   (st/emit! (rt/nav-raw :href "/admin-console/")))
  ([{:keys [organization-id organization-slug]}]
   (if (and organization-id organization-slug)
     (let [href (dm/str "/admin-console/org/"
                        (u/percent-encode organization-slug)
                        "/"
                        (u/percent-encode (str organization-id))
                        "/people/")]
       (st/emit! (rt/nav-raw :href href)))
     (st/emit! (rt/nav-raw :href "/admin-console/")))))

(defn go-to-nitrate-ac-create-org
  []
  (st/emit! (rt/nav-raw :href "/admin-console/?action=create-org")))

(defn can-send-invitations?
  [{:keys [organization profile-id team-permissions]}]
  (nitrate-perms/can-send-invitations?
   {:nitrate-enabled? (contains? cf/flags :nitrate)
    :organization organization
    :profile-id profile-id
    :team-permissions team-permissions}))

(def go-to-subscription-url (u/join cf/public-uri "#/settings/subscriptions"))

(def go-to-ac-url "/admin-console/")

(defn go-to-nitrate-billing
  []
  (let [href (dm/str "/admin-console/licenses/billing?callback=" (js/encodeURIComponent go-to-subscription-url))]
    (st/emit! (rt/nav-raw :href href))))

(def nitrate-checkout-error-token "nitrate-checkout-error")
(def nitrate-checkout-finish-error-token "nitrate-checkout-finish-error")
(def nitrate-checkout-cancelled-token "nitrate-checkout-cancelled")

(defn- append-query-param
  [url key value]
  (let [assoc-q  (fn [u]
                   (update u :query
                           (fn [q]
                             (-> (u/query-string->map (or q ""))
                                 (assoc (name key) value)
                                 u/map->query-string))))
        parsed   (u/uri url)
        fragment (:fragment parsed)]
    (if (str/blank? fragment)
      (str (assoc-q parsed))
      (-> parsed
          (assoc :fragment (str (assoc-q (u/parse fragment))))
          str))))

(defn build-nitrate-callback-urls
  "Build the success/error/cancel callback URLs from a base URL by appending
  a `subscription` query param identifying the outcome."
  [base-url]
  (let [build (fn [token]
                (append-query-param base-url :subscription token))]
    {:success-callback      (build "subscribed-to-penpot-nitrate")
     :error-callback        (build nitrate-checkout-error-token)
     :finish-error-callback (build nitrate-checkout-finish-error-token)
     :cancel-callback       (build nitrate-checkout-cancelled-token)}))

(defn go-to-buy-nitrate-license
  [subscription base-url]
  (let [{:keys [success-callback error-callback finish-error-callback cancel-callback]}
        (build-nitrate-callback-urls base-url)
        params {:subscription subscription
                :callback success-callback
                :error_callback error-callback
                :finish_error_callback finish-error-callback
                :cancel_callback cancel-callback}
        href   (dm/str "/admin-console/licenses/start?" (u/map->query-string params))]
    (st/emit! (rt/nav-raw :href href))))

(defn fetch-connectivity
  []
  (rp/cmd! ::get-nitrate-connectivity {}))

(defn is-valid-license?
  [profile]
  (and (contains? cf/flags :nitrate)
       ;; Possible values: "active" "canceled" "incomplete" "incomplete_expired" "past_due" "paused" "trialing" "unpaid"
       (contains? #{"active" "past_due" "trialing"}
                  (dm/get-in profile [:subscription :status]))))

(defn leave-org
  [{:keys [id name default-team-id teams-to-delete teams-to-leave on-error] :as params}]

  (ptk/reify ::leave-org
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-team-id (dm/get-in state [:profile :default-team-id])]
        (->> (rp/cmd! ::leave-org {:id id
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
                 (ntf/show {:content (tr "dasboard.leave-org.toast" name)
                            :type :toast
                            :level :success}))))
             (rx/catch on-error))))))

(defn show-leave-org-modal
  [{:keys [organization profile default-team-id leave-fn teams-to-transfer on-error]}]
  (ptk/reify ::show-leave-org-modal
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-leave-org-summary {:id (:id organization)
                                             :default-team-id default-team-id})
           (rx/mapcat
            (fn [summary]
              (let [num-teams-to-delete (:teams-to-delete summary)
                    num-teams-to-transfer (:teams-to-transfer summary)
                    num-teams-to-exit (:teams-to-exit summary)
                    num-teams-to-detach (:teams-to-detach summary)]
                (cond
                  (pos? num-teams-to-transfer)
                  (rx/of
                   (modal/show
                    {:type :leave-and-reassign-org
                     :profile profile
                     :teams-to-transfer teams-to-transfer
                     :num-teams-to-delete num-teams-to-delete
                     :accept leave-fn}))

                  (or (pos? num-teams-to-delete)
                      (pos? num-teams-to-exit)
                      (pos? num-teams-to-detach))
                  (rx/of (modal/show
                          {:type :confirm
                           :title (tr "modals.before-leave-org.title" (:name organization))
                           :message (tr "modals.before-leave-org.message")
                           :accept-label (tr "modals.leave-org-confirm.accept")
                           :on-accept leave-fn
                           :error-msg (tr "modals.before-leave-org.warning")}))

                  :else
                  (rx/of (modal/show
                          {:type :confirm
                           :title (tr "modals.leave-org-confirm.title" (:name organization))
                           :message (tr "modals.leave-org-confirm.message")
                           :accept-label (tr "modals.leave-org-confirm.accept")
                           :on-accept leave-fn}))))))
           (rx/catch on-error)))))


(defn remove-team-from-org
  [{:keys [team-id organization-id organization-name] :as params}]
  (ptk/reify ::remove-team-from-org
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::remove-team-from-org {:team-id team-id :organization-id organization-id :organization-name organization-name})
           (rx/mapcat
            (fn [_]
              (rx/of (dt/fetch-teams)
                     (modal/hide))))
           (rx/catch
            (fn [cause]
              (let [code (-> cause ex-data :code)]
                (if (= code :not-allowed)
                  (rx/of (modal/show :no-permission-modal {:type :no-orgs-change}))
                  (rx/throw cause)))))))))

(defn show-remove-team-from-org-modal
  "Fetches fresh team/org data, then shows the remove-from-org confirmation
  modal or the no-permission modal if the move-team permission blocks it."
  [{:keys [team-id]}]
  (ptk/reify ::show-remove-team-from-org-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (dt/with-refreshed-team team-id
          (fn [team]
            (let [source-org (:organization team)
                  can-move?  (nitrate-perms/allowed?
                              :move-team
                              {:org-perms {:owner-id    (:owner-id source-org)
                                           :permissions (:permissions source-org)}
                               :profile-id profile-id
                               :team-perms (:permissions team)
                               :target-org-same-owner? false})]
              (rx/of (if can-move?
                       (modal/show
                        {:type :confirm
                         :title (tr "modals.remove-team-org.title")
                         :message (tr "modals.remove-team-org.text" (:name team) (:name source-org))
                         :hint (tr "modals.remove-team-org.info")
                         :hint-level :default
                         :accept-label (tr "modals.remove-team-org.accept")
                         :on-accept #(st/emit! (remove-team-from-org {:team-id team-id
                                                                      :organization-id (:id source-org)
                                                                      :organization-name (:name source-org)}))
                         :accept-style :danger})
                       (modal/show :no-permission-modal {:type :no-orgs-change}))))))))))


(defn add-team-to-org
  [{:keys [team-id organization-id] :as params}]
  (ptk/reify ::add-team-to-org
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::add-team-to-organization {:team-id team-id :organization-id organization-id})
           (rx/mapcat
            (fn [_]
              (rx/of (modal/hide))))))))

(defn show-add-team-to-org-modal
  "Fetches fresh team/org data, then shows the add-to-org modal
  restricted to orgs where the user has permission, or the no-permission
  modal if none qualify."
  [{:keys [team-id]}]
  (ptk/reify ::show-add-team-to-org-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (->> (rp/cmd! :get-teams)
             (rx/mapcat
              (fn [teams]
                (let [all-orgs (map dt/team->organization
                                    (filter #(and (:is-default %) (:organization %)) teams))
                      orgs     (filter (fn [org]
                                         (let [perm    (dm/get-in org [:permissions :create-teams])
                                               is-own? (= profile-id (:owner-id org))]
                                           (or (= perm "any") is-own?))) all-orgs)
                      team     (first (filter #(= (:id %) team-id) teams))
                      add-anybody-to-team-orgs
                      (filterv #(nitrate-perms/allowed? :add-anybody-to-team
                                                        {:org-perms %})
                               orgs)
                      orgs-to-check
                      (filterv #(not (nitrate-perms/allowed? :add-anybody-to-team
                                                             {:org-perms %}))
                               orgs)
                      org-ids-to-check (mapv :id orgs-to-check)
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-org {:team-id team-id
                                                               :organization-id organization-id})))
                      show-select-modal
                      (fn [orgs-allowed]
                        (let [has-filtered? (< (count orgs) (count all-orgs))
                              extra-props   (when has-filtered?
                                              {:info-message-key "dashboard.select-org-modal.permission-info"})]
                          (modal/show :select-organization-modal
                                      (merge {:organizations orgs
                                              :orgs-allowed orgs-allowed
                                              :current-organization-id (dm/get-in team [:organization :id])
                                              :on-confirm on-confirm
                                              :title-key "dashboard.select-org-modal.title"
                                              :choose-key "dashboard.select-org-modal.choose"
                                              :placeholder-key "dashboard.select-org-modal.select"
                                              :accept-key "dashboard.select-org-modal.accept"
                                              :cancel-key "labels.cancel"}
                                             extra-props))))]
                  (cond
                    (empty? orgs)
                    (rx/of (dt/teams-fetched teams)
                           (modal/show :no-permission-modal {:type :no-orgs-create}))

                    (empty? org-ids-to-check)
                    (let [orgs-allowed (into {}
                                             (map (fn [org] [(:id org) true]))
                                             orgs)]
                      (rx/of (dt/teams-fetched teams)
                             (show-select-modal orgs-allowed)))

                    :else
                    (->> (rp/cmd! :all-team-members-in-orgs
                                  {:team-id team-id
                                   :organization-ids org-ids-to-check})
                         (rx/mapcat
                          (fn [checked-orgs]
                            (let [orgs-allowed
                                  (merge (into {}
                                               (map (fn [org] [(:id org) true]))
                                               add-anybody-to-team-orgs)
                                         checked-orgs)
                                  valid-orgs
                                  (filterv #(true? (get orgs-allowed (:id %))) orgs)]
                              (rx/of
                               (dt/teams-fetched teams)
                               (if (empty? valid-orgs)
                                 (modal/show
                                  {:type :alert
                                   :message (tr "dashboard.team-organization.add.no-valid-orgs")
                                   :accept-label (tr "labels.accept")
                                   :accept-style :primary
                                   :title (tr "dashboard.select-org-modal.title")})
                                 (show-select-modal orgs-allowed))))))))))))))))

(defn show-change-team-org-modal
  "Fetches fresh team/org data, then shows the change-org modal
  restricted to orgs where the user has permission, or the no-permission
  modal if none qualify."
  [{:keys [team-id]}]
  (ptk/reify ::show-change-team-org-modal
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (dm/get-in state [:profile :id])]
        (->> (rp/cmd! :get-teams)
             (rx/mapcat
              (fn [teams]
                (let [all-orgs     (map dt/team->organization
                                        (filter #(and (:is-default %) (:organization %)) teams))
                      team         (first (filter #(= (:id %) team-id) teams))
                      source-org   (:organization team)
                      current-org-id (:id source-org)
                      move-perm    (dm/get-in source-org [:permissions :move-teams])
                      source-owner-id (:owner-id source-org)
                      can-create?  (fn [org]
                                     (let [perm    (dm/get-in org [:permissions :create-teams])
                                           is-own? (= profile-id (:owner-id org))]
                                       (or (= perm "any") is-own?)))
                      orgs-by-move (case move-perm
                                     "never"
                                     []

                                     "myOrganizations"
                                     (filter #(= source-owner-id (:owner-id %)) all-orgs)

                                     ;; Default to always-allowed behavior.
                                     all-orgs)
                      orgs         (filter can-create? orgs-by-move)
                      selectable-orgs (remove #(= current-org-id (:id %)) orgs)
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-org {:team-id team-id
                                                               :organization-id organization-id})))]
                  (rx/of (dt/teams-fetched teams)
                         (if (empty? selectable-orgs)
                           (modal/show :no-permission-modal {:type :no-orgs-change})
                           (let [has-filtered? (< (count orgs) (count all-orgs))
                                 extra-props   (when has-filtered?
                                                 {:info-message-key "dashboard.select-org-modal.permission-info"})]
                             (modal/show :select-organization-modal
                                         (merge {:organizations           selectable-orgs
                                                 :current-organization-id current-org-id
                                                 :on-confirm              on-confirm
                                                 :title-key               "dashboard.change-org-modal.title"
                                                 :choose-key              "dashboard.change-org-modal.choose"
                                                 :placeholder-key         "dashboard.change-org-modal.select"
                                                 :accept-key              "dashboard.change-org-modal.accept"
                                                 :cancel-key              "labels.cancel"}
                                                extra-props)))))))))))))
