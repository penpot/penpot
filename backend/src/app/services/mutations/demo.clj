;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.mutations.demo
  "A demo specific mutations."
  (:require
   [clojure.spec.alpha :as s]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.services.mutations :as sm]
   [app.services.mutations.profile :as profile]
   [app.tasks :as tasks]
   [app.common.uuid :as uuid]
   [app.util.time :as tm]))

(sm/defmutation ::create-demo-profile
  [_]
  (let [id       (uuid/next)
        sem      (System/currentTimeMillis)
        email    (str "demo-" sem ".demo@nodomain.com")
        fullname (str "Demo User " sem)
        password (-> (bn/random-bytes 16)
                     (bc/bytes->b64u)
                     (bc/bytes->str))
        params   {:id id
                  :email email
                  :fullname fullname
                  :demo? true
                  :password password}]
    (db/with-atomic [conn db/pool]
      (->> (#'profile/create-profile conn params)
           (#'profile/create-profile-relations conn))

      ;; Schedule deletion of the demo profile
      (tasks/submit! conn {:name "delete-profile"
                           :delay cfg/default-deletion-delay
                           :props {:profile-id id}})
      {:email email
       :password password})))
