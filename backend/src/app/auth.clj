;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth
  (:require
   [buddy.hashers :as hashers]))

(defn derive-password
  [password]
  (hashers/derive password
                  {:alg :argon2id
                   :memory 16384
                   :iterations 20
                   :parallelism 2}))

(defn verify-password
  [attempt password]
  (try
    (hashers/verify attempt password)
    (catch Throwable _
      {:update false
       :valid false})))

