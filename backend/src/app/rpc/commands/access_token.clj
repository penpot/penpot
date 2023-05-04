;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.access-token
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

(defn- decode-row
  [row]
  (dissoc row :perms))

(defn create-access-token
  [{:keys [::db/conn ::main/props]} profile-id name expiration]
  (let [created-at (dt/now)
        token-id   (uuid/next)
        token      (tokens/generate props {:iss "access-token"
                                           :tid token-id
                                           :iat created-at})

        expires-at (some-> expiration dt/in-future)]

    (db/insert! conn :access-token
                {:id token-id
                 :name name
                 :token token
                 :profile-id profile-id
                 :created-at created-at
                 :updated-at created-at
                 :expires-at expires-at
                 :perms (db/create-array conn "text" [])})))


(defn repl-create-access-token
  [{:keys [::db/pool] :as system} profile-id name expiration]
  (db/with-atomic [conn pool]
    (let [props (:app.setup/props system)]
      (create-access-token {::db/conn conn ::main/props props}
                           profile-id
                           name
                           expiration))))

(s/def ::name ::us/not-empty-string)
(s/def ::expiration ::dt/duration)

(s/def ::create-access-token
  (s/keys :req [::rpc/profile-id]
          :req-un [::name]
          :opt-un [::expiration]))

(sv/defmethod ::create-access-token
  {::doc/added "1.18"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id name expiration]}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg ::db/conn conn)]
      (quotes/check-quote! conn
                           {::quotes/id ::quotes/access-tokens-per-profile
                            ::quotes/profile-id profile-id})
      (-> (create-access-token cfg profile-id name expiration)
          (decode-row)))))

(s/def ::delete-access-token
  (s/keys :req [::rpc/profile-id]
          :req-un [::us/id]))

(sv/defmethod ::delete-access-token
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (db/delete! pool :access-token {:id id :profile-id profile-id})
  nil)

(s/def ::get-access-tokens
  (s/keys :req [::rpc/profile-id]))

(sv/defmethod ::get-access-tokens
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (->> (db/query pool :access-token
                 {:profile-id profile-id}
                 {:order-by [[:expires-at :asc] [:created-at :asc]]
                  :columns [:id :name :perms :created-at :updated-at :expires-at]})
       (mapv decode-row)))
