;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.webhooks
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :refer [check-edition-permissions! check-read-permissions!]]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(defn decode-row
  [{:keys [uri] :as row}]
  (cond-> row
    (string? uri) (assoc :uri (u/uri uri))))

;; --- Mutation: Create Webhook

(s/def ::team-id ::us/uuid)
(s/def ::uri ::us/uri)
(s/def ::is-active ::us/boolean)
(s/def ::mtype
  #{"application/json"
    "application/transit+json"})

(s/def ::create-webhook
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::uri ::mtype]
          :opt-un [::is-active]))

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
  [{:keys [::db/pool]} {:keys [team-id uri mtype is-active] :as params}]
  (-> (db/insert! pool :webhook
                  {:id (uuid/next)
                   :team-id team-id
                   :uri (str uri)
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
                  {:id id})
      (decode-row)))

(sv/defmethod ::create-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (check-edition-permissions! pool profile-id team-id)
  (validate-quotes! cfg params)
  (validate-webhook! cfg nil params)
  (insert-webhook! cfg params))

(s/def ::update-webhook
  (s/keys :req-un [::id ::uri ::mtype ::is-active]))

(sv/defmethod ::update-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (let [whook (-> (db/get pool :webhook {:id id}) (decode-row))]
    (check-edition-permissions! pool profile-id (:team-id whook))
    (validate-webhook! cfg whook params)
    (update-webhook! cfg whook params)))

(s/def ::delete-webhook
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

(sv/defmethod ::delete-webhook
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (db/with-atomic [conn pool]
    (let [whook (-> (db/get conn :webhook {:id id}) decode-row)]
      (check-edition-permissions! conn profile-id (:team-id whook))
      (db/delete! conn :webhook {:id id})
      nil)))

;; --- Query: Webhooks

(s/def ::team-id ::us/uuid)
(s/def ::get-webhooks
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(def sql:get-webhooks
  "select id, uri, mtype, is_active, error_code, error_count
   from webhook where team_id = ? order by uri")

(sv/defmethod ::get-webhooks
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (->> (db/exec! conn [sql:get-webhooks team-id])
         (mapv decode-row))))
