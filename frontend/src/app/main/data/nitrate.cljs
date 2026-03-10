(ns app.main.data.nitrate
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn show-nitrate-popup
  [popup-type]
  (ptk/reify ::show-nitrate-popup
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-nitrate-connectivity {})
           (rx/map (fn [connectivity]
                     (prn "connectivity" connectivity)
                     (modal/show popup-type (or connectivity {}))))))))

(defn go-to-nitrate-cc
  []
  (st/emit! (rt/nav-raw :href "/control-center/")))

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


