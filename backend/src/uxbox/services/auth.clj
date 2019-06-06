;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.auth
  (:require [clojure.spec.alpha :as s]
            [suricatta.core :as sc]
            [buddy.hashers :as hashers]
            [uxbox.config :as cfg]
            [uxbox.db :as db]
            [uxbox.services.core :as core]
            [uxbox.services.users :as users]
            [uxbox.util.exceptions :as ex]))

(defn- check-user-password
  [user password]
  (hashers/check password (:password user)))

(defmethod core/novelty :login
  [{:keys [username password scope] :as params}]
  (with-open [conn (db/connection)]
    (let [user (users/find-user-by-username-or-email conn username)]
      (when-not user
        (ex/raise :type :validation
                  :code ::wrong-credentials))
      (when-not (check-user-password user password)
        (ex/raise :type :validation
                  :code ::wrong-credentials))
      user)))
