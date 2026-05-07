(ns app.main.data.nitrate
  (:require
   [app.common.data.macros :as dm]
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

(defn go-to-nitrate-cc
  ([]
   (st/emit! (rt/nav-raw :href "/control-center/")))
  ([{:keys [organization-id organization-slug]}]
   (if (and organization-id organization-slug)
     (let [href (dm/str "/control-center/org/"
                        (u/percent-encode organization-slug)
                        "/"
                        (u/percent-encode (str organization-id))
                        "/people/")]
       (st/emit! (rt/nav-raw :href href)))
     (st/emit! (rt/nav-raw :href "/control-center/")))))

(defn go-to-nitrate-cc-create-org
  []
  (st/emit! (rt/nav-raw :href "/control-center/?action=create-org")))

(def go-to-subscription-url (u/join cf/public-uri "#/settings/subscriptions"))

(defn go-to-nitrate-billing
  []
  (let [href (dm/str "/control-center/licenses/billing?callback=" (js/encodeURIComponent go-to-subscription-url))]
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
        href   (dm/str "/control-center/licenses/start?" (u/map->query-string params))]
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


(defn remove-team-from-org
  [{:keys [team-id organization-id organization-name] :as params}]
  (ptk/reify ::remove-team-from-org
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::remove-team-from-org {:team-id team-id :organization-id organization-id :organization-name organization-name})
           (rx/mapcat
            (fn [_]
              (rx/of (modal/hide))))))))


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
                                    (filter #(and (:is-default %) (:organization-id %)) teams))
                      orgs     (filter (fn [org]
                                         (let [perm    (get-in org [:permissions :create-teams])
                                               is-own? (= profile-id (:owner-id org))]
                                           (or (= perm "any") is-own?))) all-orgs)
                      team     (first (filter #(= (:id %) team-id) teams))
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-org {:team-id team-id
                                                               :organization-id organization-id})))]
                  (rx/of (dt/teams-fetched teams)
                         (if (empty? orgs)
                           (modal/show :no-permission-modal {:type :no-orgs-create})
                           (let [has-filtered? (< (count orgs) (count all-orgs))
                                 extra-props   (when has-filtered?
                                                 {:info-message-key "dashboard.select-org-modal.permission-info"})]
                             (modal/show :select-organization-modal
                                         (merge {:organizations           orgs
                                                 :current-organization-id (dm/get-in team [:organization :id])
                                                 :on-confirm              on-confirm
                                                 :title-key               "dashboard.select-org-modal.title"
                                                 :choose-key              "dashboard.select-org-modal.choose"
                                                 :placeholder-key         "dashboard.select-org-modal.select"
                                                 :accept-key              "dashboard.select-org-modal.accept"
                                                 :cancel-key              "labels.cancel"}
                                                extra-props)))))))))))))

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
                (let [all-orgs (map dt/team->organization
                                    (filter #(and (:is-default %) (:organization %)) teams))
                      orgs     (filter (fn [org]
                                         (let [perm    (get-in org [:permissions :create-teams])
                                               is-own? (= profile-id (:owner-id org))]
                                           (or (= perm "any") is-own?))) all-orgs)
                      team     (first (filter #(= (:id %) team-id) teams))
                      on-confirm (fn [organization-id]
                                   (st/emit! (add-team-to-org {:team-id team-id
                                                               :organization-id organization-id})))]
                  (rx/of (dt/teams-fetched teams)
                         (if (empty? orgs)
                           (modal/show :no-permission-modal {:type :no-orgs-change})
                           (let [has-filtered? (< (count orgs) (count all-orgs))
                                 extra-props   (when has-filtered?
                                                 {:info-message-key "dashboard.select-org-modal.permission-info"})]
                             (modal/show :select-organization-modal
                                         (merge {:organizations           orgs
                                                 :current-organization-id (dm/get-in team [:organization :id])
                                                 :on-confirm              on-confirm
                                                 :title-key               "dashboard.change-org-modal.title"
                                                 :choose-key              "dashboard.change-org-modal.choose"
                                                 :placeholder-key         "dashboard.change-org-modal.select"
                                                 :accept-key              "dashboard.change-org-modal.accept"
                                                 :cancel-key              "labels.cancel"}
                                                extra-props)))))))))))))
