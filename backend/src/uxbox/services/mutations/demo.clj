;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.demo
  "A demo specific mutations."
  (:require
   [clojure.spec.alpha :as s]
   [sodi.prng]
   [sodi.util]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.profile :as profile]
   [uxbox.tasks :as tasks]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.time :as tm]))

(sm/defmutation ::create-demo-profile
  [_]
  (let [id (uuid/next)
        sem (System/currentTimeMillis)
        email    (str "demo-" sem ".demo@nodomain.com")
        fullname (str "Demo User " sem)
        password (-> (sodi.prng/random-bytes 12)
                     (sodi.util/bytes->b64s))]
    (db/with-atomic [conn db/pool]
      (#'profile/register-profile conn {:id id
                                        :email email
                                        :fullname fullname
                                        :demo? true
                                        :password password})

      ;; Schedule deletion of the demo profile
      (tasks/submit! conn {:name "delete-profile"
                           :delay cfg/default-deletion-delay
                           :props {:profile-id id}})
      {:email email
       :password password})))
