;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.auth
  (:require
   [app.config :as cf]
   [app.db :as db]
   [buddy.hashers :as hashers]
   [integrant.core :as ig]))

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
  "Derives a password using a fast algorithm (pbkdf2+sha256, 100 iterations).
   Intended for demo users only — they are already gated behind the
   `demo-users` config flag which is disabled in production."
  [password]
  (hashers/derive password weak-options))

(defn verify-password
  [attempt password]
  (try
    (hashers/verify attempt password default-options)
    (catch Throwable _
      {:update false
       :valid false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Superuser registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Resolves the configured `:superusers` emails to profile ids once, at
;; startup. What does not exist then is simply not in the set: a profile
;; registered afterwards is recognized after the next restart. Callers
;; check membership with a plain `contains?` on the set found under
;; `::superusers` in their config map, so the check costs no query.
(defmethod ig/init-key ::superusers
  [_ {:keys [::db/pool]}]
  (into #{}
        (keep (fn [email]
                (:id (db/get* pool :profile {:email email}))))
        (or (cf/get :superusers) #{})))

(defn superuser-allowed?
  "True when the caller satisfies the superuser rule shared by the
  admin RPC wrapper and the admin HTTP transfer routes: registry
  membership (any auth type), a token carrying the operator-granted
  `\"superuser\"` scope, or any authenticated profile on a `devenv`
  host."
  [cfg profile-id token-perms]
  (boolean
   (or (and (= "devenv" (cf/get :host))
            (uuid? profile-id))
       (contains? (::superusers cfg) profile-id)
       (contains? (set token-perms) "superuser"))))
