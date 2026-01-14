;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.cli
  "PREPL API for external usage (CLI or ADMIN)"
  (:require
   [app.auth :refer [derive-password]]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.profile :as cmd.profile]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [cuerdas.core :as str]))

(defn coercer
  [schema & {:as opts}]
  (let [decode-fn (sm/decoder schema sm/json-transformer)
        check-fn (sm/check-fn schema opts)]
    (fn [data]
      (-> data decode-fn check-fn))))

(defn- get-current-system
  []
  (or (deref (requiring-resolve 'app.main/system))
      (deref (requiring-resolve 'user/system))))

(defmulti ^:private exec-command ::cmd)

(defmethod exec-command :default
  [{:keys [::cmd]}]
  (ex/raise :type :internal
            :code :not-implemented
            :hint (str/ffmt "command '%' not implemented" cmd)))

(defn exec
  "Entry point with external tools integrations that uses PREPL
  interface for interacting with running penpot backend."
  [data]
  (-> {::cmd (get data :cmd)}
      (merge (:params data))
      (exec-command)))

(defmethod exec-command "create-profile"
  [{:keys [fullname email password is-active]
    :or {is-active true}}]
  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [password (derive-password password)
                   params   {:id (uuid/next)
                             :email email
                             :fullname fullname
                             :is-active is-active
                             :password password
                             :props {}}]
               (->> (cmd.auth/create-profile system params)
                    (cmd.auth/create-profile-rels conn)))))))

(defmethod exec-command "update-profile"
  [{:keys [fullname email password is-active]}]
  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [params (cond-> {}
                            (some? fullname)
                            (assoc :fullname fullname)

                            (some? password)
                            (assoc :password (derive-password password))

                            (some? is-active)
                            (assoc :is-active is-active))]
               (when (seq params)
                 (let [res (db/update! conn :profile
                                       params
                                       {:email email
                                        :deleted-at nil})]
                   (pos? (db/get-update-count res)))))))))

(defmethod exec-command "echo"
  [params]
  params)


(defmethod exec-command "delete-profile"
  [{:keys [email soft]}]
  (when-not email
    (ex/raise :type :assertion
              :code :invalid-arguments
              :hint "email should be provided"))

  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [res (if soft
                         (db/update! conn :profile
                                     {:deleted-at (ct/now)}
                                     {:email email :deleted-at nil})
                         (db/delete! conn :profile
                                     {:email email}))]
               (pos? (db/get-update-count res)))))))

(defmethod exec-command "search-profile"
  [{:keys [email]}]
  (when-not email
    (ex/raise :type :assertion
              :code :invalid-arguments
              :hint "email should be provided"))

  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [sql (str "select email, fullname, created_at, deleted_at from profile "
                            " where email similar to ? order by created_at desc limit 100")]
               (db/exec! conn [sql email]))))))

(defmethod exec-command "derive-password"
  [{:keys [password]}]
  (derive-password password))

(defmethod exec-command "authenticate"
  [{:keys [token]}]
  (when-let [system (get-current-system)]
    (tokens/verify system {:token token :iss "authentication"})))

(def ^:private schema:get-customer
  [:map [:id ::sm/uuid]])

(def coerce-get-customer-params
  (coercer schema:get-customer
           :type :validation
           :hint "invalid data provided for `get-customer` rpc call"))

(def sql:get-customer-slots
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
  [system profile-id]
  (let [result (db/exec-one! system [sql:get-customer-slots profile-id])]
    (:total result)))

(defmethod exec-command "get-customer"
  [params]
  (when-let [system (get-current-system)]
    (let [{:keys [id] :as params} (coerce-get-customer-params params)
          {:keys [props] :as profile} (cmd.profile/get-profile system id)]
      {:id (get profile :id)
       :name (get profile :fullname)
       :email (get profile :email)
       :num-editors (get-customer-slots system id)
       :subscription (get props :subscription)})))

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

(def ^:private schema:customer-subscription
  [:map {:title "CustomerSubscription"}
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

(def ^:private schema:update-customer-subscription
  [:map
   [:id ::sm/uuid]
   [:subscription [:maybe schema:customer-subscription]]])

(def coerce-update-customer-subscription-params
  (coercer schema:update-customer-subscription
           :type :validation
           :hint "invalid data provided for `update-customer-subscription` rpc call"))

(defmethod exec-command "update-customer-subscription"
  [params]
  (when-let [system (get-current-system)]
    (let [{:keys [id subscription]} (coerce-update-customer-subscription-params params)
          ;; FIXME: locking
          {:keys [props] :as profile} (cmd.profile/get-profile system id)
          props (assoc props :subscription subscription)]

      (db/update! system :profile
                  {:props (db/tjson props)}
                  {:id id}
                  {::db/return-keys false})
      true)))
