;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.auth
  (:require
   [clojure.spec.alpha :as s]
   [sodi.pwhash :as pwhash]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]))

(def ^:private user-by-username-sql
  "select id, password
     from users
    where username=$1 or email=$1
      and deleted_at is null")

(s/def ::username ::us/string)
(s/def ::password ::us/string)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::username ::password]
          :opt-un [::scope]))

(sm/defmutation ::login
  [{:keys [username password scope] :as params}]
  (letfn [(check-password [user password]
            (let [result (pwhash/verify password (:password user))]
              (:valid result)))

          (check-user [user]
            (when-not user
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            (when-not (check-password user password)
              (ex/raise :type :validation
                        :code ::wrong-credentials))

            {:id (:id user)})]
    (-> (db/query-one db/pool [user-by-username-sql username])
        (p/then' check-user))))
