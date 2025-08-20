;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.management
  "Internal mangement HTTP API"
  (:require
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
   [app.db :as db]
   [app.main :as-alias main]
   [app.rpc.commands.profile :as cmd.profile]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

;; ---- ROUTES

(declare ^:private authenticate)
(declare ^:private get-customer)
(declare ^:private update-customer)

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::routes
  [_ cfg]
  [["/authenticate"
    {:handler (partial authenticate cfg)
     :allowed-methods #{:post}}]

   ["/get-customer"
    {:handler (partial get-customer cfg)
     :allowed-methods #{:post}}]

   ["/update-customer"
    {:handler (partial update-customer cfg)
     :allowed-methods #{:post}}]])

;; ---- HELPERS

(defn- coercer
  [schema & {:as opts}]
  (let [decode-fn (sm/decoder schema sm/json-transformer)
        check-fn  (sm/check-fn schema opts)]
    (fn [data]
      (-> data decode-fn check-fn))))

;; ---- API: AUTHENTICATE

(defn- authenticate
  [cfg request]
  (let [token  (-> request :params :token)
        props  (get cfg ::setup/props)
        result (tokens/verify props {:token token :iss "authentication"})]
    {::yres/status 200
     ::yres/body result}))

;; ---- API: GET-CUSTOMER

(def ^:private schema:get-customer
  [:map [:id ::sm/uuid]])

(def ^:private coerce-get-customer-params
  (coercer schema:get-customer
           :type :validation
           :hint "invalid data provided for `get-customer` rpc call"))

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

(defn- get-customer
  [cfg request]
  (let [profile-id (-> request :params coerce-get-customer-params :id)
        profile    (cmd.profile/get-profile cfg profile-id)
        result     {:id (get profile :id)
                    :name (get profile :fullname)
                    :email (get profile :email)
                    :num-editors (get-customer-slots cfg profile-id)
                    :subscription (-> profile :props :subscription)}]
    {::yres/status 200
     ::yres/body result}))


;; ---- API: UPDATE-CUSTOMER

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
     :decode/string ct/inst
     :encode/string inst-ms
     :decode/json ct/inst
     :encode/json inst-ms}}))

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

(def ^:private schema:update-customer
  [:map
   [:id ::sm/uuid]
   [:subscription [:maybe schema:subscription]]])

(def ^:private coerce-update-customer-params
  (coercer schema:update-customer
           :type :validation
           :hint "invalid data provided for `update-customer` rpc call"))

(defn- update-customer
  [cfg request]
  (let [{:keys [id subscription]}
        (-> request :params coerce-update-customer-params)

        {:keys [props] :as profile}
        (cmd.profile/get-profile cfg id)

        props
        (assoc props :subscription subscription)]

    (db/update! cfg :profile
                {:props (db/tjson props)}
                {:id id}
                {::db/return-keys false})

    {::yres/status 201
     ::yres/body nil}))
