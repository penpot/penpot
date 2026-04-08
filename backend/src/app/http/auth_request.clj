;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.auth-request
  "Middleware that trusts X-Auth-Request-* headers set by a forward-auth
  proxy (e.g. oauth2-proxy, Authelia, Traefik ForwardAuth).

  Enabled via PENPOT_FLAGS: enable-x-auth-request-headers (parsed as :x-auth-request-headers).
  Any request carrying an X-Auth-Request-Email header is treated as pre-authenticated.
  A Penpot session cookie is created on the response so that the browser
  does not need to visit the login screen.

  Optional: enable-x-auth-request-auto-register (parsed as :x-auth-request-auto-register)
  automatically creates a Penpot profile (with a default team) for email addresses
  that are not yet registered."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.http.access-token :as-alias actoken]
   [app.http.session :as session]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [cuerdas.core :as str]
   [yetti.request :as yreq]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-or-register-profile
  "Looks up a profile by email. If not found and the
  :x-auth-request-auto-register flag is enabled, creates a new active
  profile with a default team. Returns nil when the profile does not
  exist and auto-registration is disabled."
  [cfg email fullname]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (or (profile/get-profile-by-email conn email)
                    (when (contains? cf/flags :x-auth-request-auto-register)
                      (let [display-name (or (not-empty fullname)
                                             (first (str/split email #"@")))
                            profile      (auth/create-profile cfg
                                                              {:email    email
                                                               :fullname display-name
                                                               :backend  "x-auth-request"
                                                               :is-active true})]
                        (l/inf :hint "x-auth-request: auto-registered profile"
                               :email email
                               :profile-id (str (:id profile)))
                        (auth/create-profile-rels cfg profile)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MIDDLEWARE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wrap-authz
  [handler cfg]
  (fn [request]
    ;; Skip when a prior middleware (session or access-token) already
    ;; resolved a profile — we only act as a fallback.
    (if (or (some? (::session/profile-id request))
            (some? (::actoken/profile-id request)))
      (handler request)
      (let [email (yreq/get-header request "x-auth-request-email")]
        (if (str/blank? email)
          (handler request)
          (let [fullname (yreq/get-header request "x-auth-request-user")
                profile  (ex/ignoring (get-or-register-profile cfg email fullname))]
            (cond
              (nil? profile)
              (do
                (l/wrn :hint "x-auth-request: no profile found for email, passing through unauthenticated"
                       :email email)
                (handler request))

              (:is-blocked profile)
              (do
                (l/wrn :hint "x-auth-request: profile is blocked"
                       :email email
                       :profile-id (str (:id profile)))
                (handler request))

              (not (:is-active profile))
              (do
                (l/wrn :hint "x-auth-request: profile is not active"
                       :email email
                       :profile-id (str (:id profile)))
                (handler request))

              :else
              (do
                (l/dbg :hint "x-auth-request: authenticating via forwarded header"
                       :email email
                       :profile-id (str (:id profile)))
                (let [create-session! (session/create-fn cfg profile)
                      ;; Inject profile-id into the request so this very
                      ;; request is also treated as authenticated downstream.
                      response        (-> request
                                          (assoc ::session/profile-id (:id profile))
                                          handler)]
                  ;; Attach a session cookie so the browser is authenticated
                  ;; for all subsequent requests without needing to log in.
                  (create-session! request response))))))))))

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :x-auth-request-headers)
                wrap-authz))})
