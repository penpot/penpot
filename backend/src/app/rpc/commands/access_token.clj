;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.access-token
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.quotes :as quotes]
   [app.rpc.climit :as climit]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(defn- create-access-token
  [{:keys [::conn ::main/props]} profile-id name perms]
  (let [created-at (dt/now)
        token-id   (tokens/generate props {:iss "access-token"
                                           :iat created-at})]
    (db/insert! conn :access-token
                {:name name
                 :created-at created-at
                 :updated-at created-at
                 :perms (db/create-array conn "text" perms)})))

(defn repl-create-access-token
  [{:keys [::db/pool] :as system} profile-id name perms]
  (db/with-atomic [conn pool]
    (let [props (:app.setup/props system)]
      (create-access-token {::conn conn ::main/props props}
                           profile-id
                           name
                           perms))))

(s/def ::name ::us/not-empty-string)
(s/def ::perms ::us/set-of-strings)

(s/def ::create-access-token
  (s/keys :req [::rpc/profile-id]
          :req-un [::name ::perms]))

(sv/defmethod ::create-access-token
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id name perms]}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg ::conn conn)]
      (quotes/check-quote! conn
                           {::quotes/id ::quotes/access-tokens-per-profile
                            ::quotes/profile-id profile-id})
      (create-access-token cfg profile-id name perms))))



