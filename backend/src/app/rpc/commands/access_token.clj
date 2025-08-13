;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.access-token
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.services :as sv]))

(defn- decode-row
  [row]
  (dissoc row :perms))

(defn create-access-token
  [{:keys [::db/conn ::setup/props]} profile-id name expiration]
  (let [created-at (ct/now)
        token-id   (uuid/next)
        token      (tokens/generate props {:iss "access-token"
                                           :tid token-id
                                           :iat created-at})

        expires-at (some-> expiration ct/in-future)
        token      (db/insert! conn :access-token
                               {:id token-id
                                :name name
                                :token token
                                :profile-id profile-id
                                :created-at created-at
                                :updated-at created-at
                                :expires-at expires-at
                                :perms (db/create-array conn "text" [])})]
    (decode-row token)))

(defn repl:create-access-token
  [cfg profile-id name expiration]
  (db/tx-run! cfg create-access-token profile-id name expiration))

(def ^:private schema:create-access-token
  [:map {:title "create-access-token"}
   [:name [:string {:max 250 :min 1}]]
   [:expiration {:optional true} ::ct/duration]])

(sv/defmethod ::create-access-token
  {::doc/added "1.18"
   ::sm/params schema:create-access-token}
  [cfg {:keys [::rpc/profile-id name expiration]}]

  (quotes/check! cfg {::quotes/id ::quotes/access-tokens-per-profile
                      ::quotes/profile-id profile-id})

  (db/tx-run! cfg create-access-token profile-id name expiration))

(def ^:private schema:delete-access-token
  [:map {:title "delete-access-token"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-access-token
  {::doc/added "1.18"
   ::sm/params schema:delete-access-token}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (db/delete! pool :access-token {:id id :profile-id profile-id})
  nil)

(def ^:private schema:get-access-tokens
  [:map {:title "get-access-tokens"}])

(sv/defmethod ::get-access-tokens
  {::doc/added "1.18"
   ::sm/params schema:get-access-tokens}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (->> (db/query pool :access-token
                 {:profile-id profile-id}
                 {:order-by [[:expires-at :asc] [:created-at :asc]]
                  :columns [:id :name :perms :created-at :updated-at :expires-at]})
       (mapv decode-row)))
