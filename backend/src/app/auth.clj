;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth
  (:require
   [app.config :as cf]
   [buddy.hashers :as hashers]
   [cuerdas.core :as str]
   [promesa.exec :as px]))

(def default-params
  {:alg :argon2id
   :memory (* 32768 2)
   :iterations 5
   :parallelism (px/get-available-processors)})

(defn derive-password
  [password]
  (hashers/derive password default-params))

(defn verify-password
  [attempt password]
  (try
    (hashers/verify attempt password)
    (catch Throwable _
      {:update false
       :valid false})))

(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if
  given whitelist is an empty string."
  ([email]
   (let [domains (cf/get :registration-domain-whitelist)]
     (email-domain-in-whitelist? domains email)))
  ([domains email]
   (if (or (nil? domains) (empty? domains))
     true
     (let [[_ candidate] (-> (str/lower email)
                             (str/split #"@" 2))]
       (contains? domains candidate)))))

