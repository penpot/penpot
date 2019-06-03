;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.auth
  (:require [clojure.spec.alpha :as s]
            [suricatta.core :as sc]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
            [buddy.core.hash :as hash]
            [uxbox.config :as cfg]
            [uxbox.util.spec :as us]
            [uxbox.db :as db]
            [uxbox.services.core :as core]
            [uxbox.services.users :as users]
            [uxbox.util.exceptions :as ex]))

(def auth-opts
  {:alg :a256kw :enc :a128cbc-hs256})

;; --- Login

(defn- check-user-password
  [user password]
  (hashers/check password (:password user)))

(defn generate-token
  [user]
  (let [data {:id (:id user) :scope :auth}]
    (jwt/encrypt data cfg/secret auth-opts)))

(s/def ::scope string?)
(s/def ::login
  (s/keys :req-un [::us/username ::us/password ::scope]))

(defmethod core/novelty :login
  [{:keys [username password scope] :as params}]
  (s/assert ::login params)
  (with-open [conn (db/connection)]
    (let [user (users/find-user-by-username-or-email conn username)]
      (when-not user
        (ex/raise :type :validation
                  :code ::wrong-credentials))
      (if (check-user-password user password)
        {:token (generate-token user)}
        (ex/raise :type :validation
                  :code ::wrong-credentials)))))
