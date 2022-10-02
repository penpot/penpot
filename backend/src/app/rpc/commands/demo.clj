;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.demo
  "A demo specific mutations."
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.spec.alpha :as s]))

(s/def ::create-demo-profile any?)

(sv/defmethod ::create-demo-profile
  "A command that is responsible of creating a demo purpose
  profile. It only works if the `demo-users` flag is enabled in the
  configuration."
  {:auth false
   ::doc/added "1.15"
   ::doc/changes ["1.15" "This method is migrated from mutations to commands."]}
  [{:keys [pool] :as cfg} _]
  (let [id       (uuid/next)
        sem      (System/currentTimeMillis)
        email    (str "demo-" sem ".demo@example.com")
        fullname (str "Demo User " sem)
        password (-> (bn/random-bytes 16)
                     (bc/bytes->b64u)
                     (bc/bytes->str))
        params   {:id id
                  :email email
                  :fullname fullname
                  :is-active true
                  :deleted-at (dt/in-future cf/deletion-delay)
                  :password password
                  :props {}
                  }]

    (when-not (contains? cf/flags :demo-users)
      (ex/raise :type :validation
                :code :demo-users-not-allowed
                :hint "Demo users are disabled by config."))

    (db/with-atomic [conn pool]
      (->> (cmd.auth/create-profile conn params)
           (cmd.auth/create-profile-relations conn))

      (with-meta {:email email
                  :password password}
        {::audit/profile-id id}))))
