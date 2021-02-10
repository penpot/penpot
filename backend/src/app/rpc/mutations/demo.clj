;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.mutations.demo
  "A demo specific mutations."
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.profile-initial-data :refer [create-profile-initial-data]]
   [app.rpc.mutations.profile :as profile]
   [app.tasks :as tasks]
   [app.util.services :as sv]
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
                  :demo? true
                  :password password
                  :props {:onboarding-viewed true}}]

    (when-not (:allow-demo-users cfg/config)
      (ex/raise :type :validation
                :code :demo-users-not-allowed
                :hint "Demo users are disabled by config."))

    (db/with-atomic [conn pool]
      (->> (#'profile/create-profile conn params)
           (#'profile/create-profile-relations conn)
           (create-profile-initial-data conn))

      ;; Schedule deletion of the demo profile
      (tasks/submit! conn {:name "delete-profile"
                           :delay cfg/deletion-delay
                           :props {:profile-id id}})

      {:email email
       :password password})))
