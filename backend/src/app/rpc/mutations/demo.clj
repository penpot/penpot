;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.demo
  "A demo specific mutations."
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.mutations.profile :as profile]
   [app.setup.initial-data :as sid]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.spec.alpha :as s]))

(s/def ::create-demo-profile any?)

(sv/defmethod ::create-demo-profile {:auth false}
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
                  :is-demo true
                  :deleted-at (dt/in-future cf/deletion-delay)
                  :password password
                  :props {:onboarding-viewed true}}]

    (when-not (contains? cf/flags :demo-users)
      (ex/raise :type :validation
                :code :demo-users-not-allowed
                :hint "Demo users are disabled by config."))

    (db/with-atomic [conn pool]
      (->> (#'profile/create-profile conn params)
           (#'profile/create-profile-relations conn)
           (sid/load-initial-project! conn))

      (with-meta {:email email
                  :password password}
        {::audit/profile-id id}))))
