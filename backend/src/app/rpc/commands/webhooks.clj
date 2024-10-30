;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.webhooks
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :refer [check-read-permissions!] :as t]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

(defn get-webhooks-permissions
  [conn profile-id team-id creator-id]
  (let [permissions (t/get-permissions conn profile-id team-id)

        can-edit (boolean (or (:can-edit permissions)
                              (= profile-id creator-id)))]
    (assoc permissions :can-edit can-edit)))

(def has-webhook-edit-permissions?
  (perms/make-edition-predicate-fn get-webhooks-permissions))

(def check-webhook-edition-permissions!
  (perms/make-check-fn has-webhook-edit-permissions?))

(defn decode-row
  [{:keys [uri] :as row}]
  (cond-> row
    (string? uri) (assoc :uri (u/uri uri))))

;; --- Mutation: Create Webhook

;; NOTE: for now the quote is hardcoded but this need to be solved in
;; a more universal way for handling properly object quotes
(def max-hooks-for-team 8)

(defn- validate-webhook!
  [cfg whook params]
  (when (not= (:uri whook) (:uri params))
    (let [response (ex/try!
                    (http/req! cfg
                               {:method :head
                                :uri (str (:uri params))
                                :timeout (dt/duration "3s")}
                               {:sync? true}))]
      (if (ex/exception? response)
        (if-let [hint (webhooks/interpret-exception response)]
          (ex/raise :type :validation
                    :code :webhook-validation
                    :hint hint)
          (ex/raise :type :internal
                    :code :webhook-validation
                    :cause response))
        (when-let [hint (webhooks/interpret-response response)]
          (ex/raise :type :validation
                    :code :webhook-validation
                    :hint hint))))))

(defn- validate-quotes!
  [{:keys [::db/pool]} {:keys [team-id]}]
  (let [sql   ["select count(*) as total from webhook where team_id = ?" team-id]
        total (:total (db/exec-one! pool sql))]
    (when (>= total max-hooks-for-team)
      (ex/raise :type :restriction
                :code :webhooks-quote-reached
                :hint (str/ffmt "can't create more than % webhooks per team"
                                max-hooks-for-team)))))

(defn- insert-webhook!
  [{:keys [::db/pool]} {:keys [team-id uri mtype is-active ::rpc/profile-id] :as params}]
  (-> (db/insert! pool :webhook
                  {:id (uuid/next)
                   :team-id team-id
                   :uri (str uri)
                   :profile-id profile-id
                   :is-active is-active
                   :mtype mtype})
      (decode-row)))

(defn- update-webhook!
  [{:keys [::db/pool] :as cfg} {:keys [id] :as wook} {:keys [uri mtype is-active] :as params}]
  (-> (db/update! pool :webhook
                  {:uri (str uri)
                   :is-active is-active
                   :mtype mtype
                   :error-code nil
                   :error-count 0}
                  {:id id}
                  {::db/return-keys true})
      (decode-row)))


(def valid-mtypes
  #{"application/json"
    "application/transit+json"})

(def ^:private schema:create-webhook
  [:map {:title "create-webhook"}
   [:team-id ::sm/uuid]
   [:uri ::sm/uri]
   [:mtype [::sm/one-of {:format "string"} valid-mtypes]]])

(sv/defmethod ::create-webhook
  {::doc/added "1.17"
   ::sm/params schema:create-webhook}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (check-webhook-edition-permissions! pool profile-id team-id profile-id)
  (validate-quotes! cfg params)
  (validate-webhook! cfg nil params)
  (insert-webhook! cfg params))

(def ^:private schema:update-webhook
  [:map {:title "update-webhook"}
   [:id ::sm/uuid]
   [:uri ::sm/uri]
   [:mtype [::sm/one-of {:format "string"} valid-mtypes]]
   [:is-active ::sm/boolean]])

(sv/defmethod ::update-webhook
  {::doc/added "1.17"
   ::sm/params schema:update-webhook}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (let [whook (-> (db/get pool :webhook {:id id}) (decode-row))]
    (check-webhook-edition-permissions! pool profile-id (:team-id whook) (:profile-id whook))
    (validate-webhook! cfg whook params)
    (update-webhook! cfg whook params)))

(def ^:private schema:delete-webhook
  [:map {:title "delete-webhook"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-webhook
  {::doc/added "1.17"
   ::sm/params schema:delete-webhook}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (db/with-atomic [conn pool]
    (let [whook (-> (db/get conn :webhook {:id id}) decode-row)]
      (check-webhook-edition-permissions! conn profile-id (:team-id whook) (:profile-id whook))
      (db/delete! conn :webhook {:id id})
      nil)))

;; --- Query: Webhooks

(def sql:get-webhooks
  "SELECT id, uri, mtype, is_active, error_code, error_count, profile_id 
     FROM webhook 
    WHERE team_id = ? 
    ORDER BY uri")

(def ^:private schema:get-webhooks
  [:map {:title "get-webhooks"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-webhooks
  {::doc/added "1.17"
   ::sm/params schema:get-webhooks}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (->> (db/exec! conn [sql:get-webhooks team-id])
         (mapv decode-row))))
