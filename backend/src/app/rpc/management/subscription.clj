;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.management.subscription
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as doc]
   [app.util.services :as sv]))

;; ---- RPC METHOD: AUTHENTICATE

(def ^:private
  schema:authenticate-params
  [:map {:title "authenticate-params"}])

(def ^:private
  schema:authenticate-result
  [:map {:title "authenticate-result"}
   [:profile-id ::sm/uuid]])

(sv/defmethod ::auth
  {::doc/added "2.12"
   ::sm/params schema:authenticate-params
   ::sm/result schema:authenticate-result}
  [_ {:keys [::rpc/profile-id]}]
  {:profile-id profile-id})

;; ---- RPC METHOD: GET-CUSTOMER

;; FIXME: move to app.common.time
(def ^:private schema:timestamp
  (sm/type-schema
   {:type ::timestamp
    :pred ct/inst?
    :type-properties
    {:title "inst"
     :description "The same as :app.common.time/inst but encodes to epoch"
     :error/message "should be an instant"
     :gen/gen (->> (sg/small-int)
                   (sg/fmap (fn [v] (ct/inst v))))
     :decode/string #(some-> % ct/inst)
     :encode/string #(some-> % inst-ms)
     :decode/json #(some-> % ct/inst)
     :encode/json #(some-> % inst-ms)}}))

(def ^:private schema:subscription
  [:map {:title "Subscription"}
   [:id ::sm/text]
   [:customer-id ::sm/text]
   [:type [:enum
           "unlimited"
           "professional"
           "enterprise"]]
   [:status [:enum
             "active"
             "canceled"
             "incomplete"
             "incomplete_expired"
             "past_due"
             "paused"
             "trialing"
             "unpaid"]]

   [:billing-period [:enum
                     "month"
                     "day"
                     "week"
                     "year"]]
   [:quantity :int]
   [:description [:maybe ::sm/text]]
   [:created-at schema:timestamp]
   [:start-date [:maybe schema:timestamp]]
   [:ended-at [:maybe schema:timestamp]]
   [:trial-end [:maybe schema:timestamp]]
   [:trial-start [:maybe schema:timestamp]]
   [:cancel-at [:maybe schema:timestamp]]
   [:canceled-at [:maybe schema:timestamp]]
   [:current-period-end [:maybe schema:timestamp]]
   [:current-period-start [:maybe schema:timestamp]]
   [:cancel-at-period-end :boolean]

   [:cancellation-details
    [:map {:title "CancellationDetails"}
     [:comment [:maybe ::sm/text]]
     [:reason [:maybe ::sm/text]]
     [:feedback [:maybe
                 [:enum
                  "customer_service"
                  "low_quality"
                  "missing_feature"
                  "other"
                  "switched_service"
                  "too_complex"
                  "too_expensive"
                  "unused"]]]]]])

(def ^:private sql:get-customer-slots
  "WITH teams AS (
    SELECT tpr.team_id AS id,
           tpr.profile_id AS profile_id
      FROM team_profile_rel AS tpr
     WHERE tpr.is_owner IS true
       AND tpr.profile_id = ?
  ), teams_with_slots AS (
    SELECT tpr.team_id AS id,
           count(*) AS total
      FROM team_profile_rel AS tpr
     WHERE tpr.team_id IN (SELECT id FROM teams)
       AND tpr.can_edit IS true
     GROUP BY 1
     ORDER BY 2
  )
  SELECT max(total) AS total FROM teams_with_slots;")

(defn- get-customer-slots
  [cfg profile-id]
  (let [result (db/exec-one! cfg [sql:get-customer-slots profile-id])]
    (:total result)))

(def ^:private schema:get-customer-params
  [:map])

(def ^:private schema:get-customer-result
  [:map
   [:id ::sm/uuid]
   [:name :string]
   [:num-editors ::sm/int]
   [:subscription {:optional true} schema:subscription]])

(sv/defmethod ::get-customer
  {::doc/added "2.12"
   ::sm/params schema:get-customer-params
   ::sm/result schema:get-customer-result}
  [cfg {:keys [::rpc/profile-id]}]
  (let [profile (profile/get-profile cfg profile-id)]
    {:id (get profile :id)
     :name (get profile :fullname)
     :email (get profile :email)
     :num-editors (get-customer-slots cfg profile-id)
     :subscription (-> profile :props :subscription)}))


;; ---- RPC METHOD: GET-CUSTOMER

(def ^:private schema:update-customer-params
  [:map
   [:subscription [:maybe schema:subscription]]])

(def ^:private schema:update-customer-result
  [:map])

(sv/defmethod ::update-customer
  {::doc/added "2.12"
   ::sm/params schema:update-customer-params
   ::sm/result schema:update-customer-result}
  [cfg {:keys [::rpc/profile-id subscription]}]
  (let [{:keys [props] :as profile}
        (profile/get-profile cfg profile-id ::db/for-update true)

        props
        (assoc props :subscription subscription)]

    (l/dbg :hint "update customer"
           :profile-id (str profile-id)
           :subscription-type (get subscription :type)
           :subscription-status (get subscription :status)
           :subscription-quantity (get subscription :quantity))

    (db/update! cfg :profile
                {:props (db/tjson props)}
                {:id profile-id}
                {::db/return-keys false})

    nil))
