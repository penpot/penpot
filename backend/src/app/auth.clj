;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.auth
  (:require
   [buddy.hashers :as hashers]))

(def ^:private default-options
  {:alg :argon2id
   :memory 32768 ;; 32 MiB
   :iterations 3
   :parallelism 2})

(def ^:private weak-options
  {:alg :pbkdf2+sha256
   :iterations 100})

(defn derive-password
  [password]
  (hashers/derive password default-options))

(defn derive-password-weak
  "Derives a password with a fast algorithm for demo users."
  [password]
  (hashers/derive password weak-options))

(defn verify-password
  [attempt password]
  (try
    (hashers/verify attempt password default-options)
    (catch Throwable _
      {:update false
       :valid false})))
