;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.demo
  "A demo specific mutations."
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]))

(sv/defmethod ::create-demo-profile
  "A command that is responsible of creating a demo purpose
  profile. It only works if the `demo-users` flag is enabled in the
  configuration."
  {::rpc/auth false
   ::doc/added "1.15"
   ::doc/changes ["1.15" "This method is migrated from mutations to commands."]}
  [cfg _]

  (when-not (contains? cf/flags :demo-users)
    (ex/raise :type :validation
              :code :demo-users-not-allowed
              :hint "Demo users are disabled by config."))

  (let [sem      (System/currentTimeMillis)
        email    (str "demo-" sem ".demo@example.com")
        fullname (str "Demo User " sem)

        password (-> (bn/random-bytes 16)
                     (bc/bytes->b64u)
                     (bc/bytes->str))

        params   {:email email
                  :fullname fullname
                  :is-active true
                  :deleted-at (dt/in-future (cf/get-deletion-delay))
                  :password (profile/derive-password cfg password)
                  :props {}}]


    (let [profile (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                                    (->> (auth/create-profile! conn params)
                                         (auth/create-profile-rels! conn))))]
      (with-meta {:email email
                  :password password}
        {::audit/profile-id (:id profile)}))))

