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
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn show-nitrate-popup
  [popup-type]
  (ptk/reify ::show-nitrate-popup
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-nitrate-connectivity {})
           (rx/map (fn [connectivity]
                     (modal/show popup-type (or connectivity {}))))))))

(defn go-to-nitrate-cc
  ([]
   (st/emit! (rt/nav-raw :href "/control-center/")))
  ([{:keys [organization-id organization-slug]}]
   (let [href (dm/str "/control-center/org/"
                      (u/percent-encode organization-slug)
                      "/"
                      (u/percent-encode (str organization-id)))]
     (st/emit! (rt/nav-raw :href href)))))

(defn go-to-nitrate-cc-create-org
  []
  (st/emit! (rt/nav-raw :href "/control-center/?action=create-org")))

(defn go-to-nitrate-billing
  []
  (st/emit! (rt/nav-raw :href "/control-center/licenses/billing")))

(defn go-to-buy-nitrate-license
  ([subscription]
   (go-to-buy-nitrate-license subscription nil))
  ([subscription callback]
   (let [params (cond-> {:subscription subscription}
                  callback (assoc :callback callback))
         href   (dm/str "/control-center/licenses/start?" (u/map->query-string params))]
     (st/emit! (rt/nav-raw :href href)))))

(def go-to-subscription-url (u/join cf/public-uri "#/settings/subscriptions"))

(defn is-valid-license?
  [profile]
  (and (contains? cf/flags :nitrate)
       ;; Possible values: "active" "canceled" "incomplete" "incomplete_expired" "past_due" "paused" "trialing" "unpaid"
       (contains? #{"active" "past_due" "trialing"}
                  (dm/get-in profile [:subscription :status]))))

(defn leave-org
  [{:keys [org-id org-name default-team-id teams-to-delete teams-to-leave on-error] :as params}]

  (ptk/reify ::leave-org
    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-team-id (dm/get-in state [:profile :default-team-id])]
        (->> (rp/cmd! ::leave-org {:org-id org-id
                                   :org-name org-name
                                   :default-team-id default-team-id
                                   :teams-to-delete teams-to-delete
                                   :teams-to-leave teams-to-leave})
             (rx/mapcat
              (fn [_]
                (rx/of
                 (dt/fetch-teams)
                 (dcm/go-to-dashboard-recent :team-id profile-team-id)
                 (modal/hide)
                 (ntf/show {:content (tr "dasboard.leave-org.toast" org-name)
                            :type :toast
                            :level :success}))))
             (rx/catch on-error))))))
