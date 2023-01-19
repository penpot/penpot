;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.ext
  "PREPL API for external usage (CLI or ADMIN)"
  (:require
   [app.auth :as auth]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.auth :as cmd.auth]))

(defn- get-current-system
  []
  (or (deref (requiring-resolve 'app.main/system))
      (deref (requiring-resolve 'user/system))))

(defn derive-password
  [password]
  (auth/derive-password password))

(defn create-profile
  [fullname, email, password]
  (when-let [system (get-current-system)]
    (db/with-atomic [conn (:app.db/pool system)]
      (let [params  {:id (uuid/next)
                     :email email
                     :fullname fullname
                     :is-active true
                     :password (derive-password password)
                     :props {}}
            profile (->> (cmd.auth/create-profile! conn params)
                         (cmd.auth/create-profile-rels! conn))]
        (str (:id profile))))))






