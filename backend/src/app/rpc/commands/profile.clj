;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.profile
  (:require
   [app.auth :as auth]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- MUTATION: Set profile password

(declare update-profile-password!)

(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)

(s/def ::get-derived-password
  (s/keys :req [::rpc/profile-id]
          :req-un [::password]))

(sv/defmethod ::get-derived-password
  "Get derived password, only ADMINS allowed to call this RPC
  methods. Designed for administration pannel integration."
  {::climit/queue :auth
   ::climit/key-fn ::rpc/profile-id
   ::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [password] :as params}]
  (db/with-atomic [conn pool]
    (let [admins  (cf/get :admins)
          profile (db/get-by-id conn :profile (::rpc/profile-id params))]

      (if (or (:is-admin profile)
              (contains? admins (:email profile)))
        {:password (auth/derive-password password)}
        (ex/raise :type :authentication
                  :code :only-admins-allowed
                  :hint "only admins allowed to call this RPC method")))))

;; --- MUTATION: Check profile password

(s/def ::attempt ::us/not-empty-string)
(s/def ::check-profile-password
  (s/keys :req [::rpc/profile-id]
          :req-un [::profile-id ::password]))

(sv/defmethod ::check-profile-password
  "Check profile password, only ADMINS allowed to call this RPC
  methods. Designed for administration pannel integration."
  {::climit/queue :auth
   ::climit/key-fn ::rpc/profile-id
   ::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [profile-id password] :as params}]
  (db/with-atomic [conn pool]
    (let [admins  (cf/get :admins)
          profile (db/get-by-id pool :profile (::rpc/profile-id params))]

      (if (or (:is-admin profile)
              (contains? admins (:email profile)))
        (let [profile (if (not= (::rpc/profile-id params) profile-id)
                        (db/get-by-id conn :profile profile-id)
                        profile)]
          (auth/verify-password password (:password profile)))
        (ex/raise :type :authentication
                  :code :only-admins-allowed
                  :hint "only admins allowed to call this RPC method")))))
