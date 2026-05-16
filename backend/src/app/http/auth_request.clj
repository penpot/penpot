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
  that are not yet registered. After resolving a profile (new or existing), users
  with no membership in any non-default team are joined to the shared team matching
  PENPOT_SMB_DEFAULT_WORKSPACE_NAME (team.name); no fallback to another team."

  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as-alias actoken]
   [app.http.session :as session]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [cuerdas.core :as str]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- valid-email?
  [s]
  (boolean (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" s)))

(defn- resolve-email
  "If the claim is already a valid email, return it as-is.
  Otherwise treat it as a bare username and append @<default-email-domain>."
  [email-claim]
  (if (valid-email? email-claim)
    email-claim
    (let [domain (or (cf/get :default-email-domain) "askii.ai")]
      (l/wrn :hint "x-auth-request: email claim is not a valid address, constructing from default-email-domain"
             :claim email-claim
             :domain domain)
      (str (first (str/split email-claim #"@")) "@" domain))))

(defn- auto-join-team!
  "_auto_join_workspace: ensure a ``team_profile_rel`` row for
  the non-default team whose ``name`` matches PENPOT_SMB_DEFAULT_WORKSPACE_NAME
  (:smb-default-workspace-name). Runs even when the profile already belongs to another
  shared team (multi-team parity with Plane workspaces).

  If config is unset or no such team exists, does nothing — no fallback. Idempotent
  INSERT ON CONFLICT DO NOTHING."

  [conn {:keys [id] :as _profile}]
  (let [preferred (some-> (cf/get :smb-default-workspace-name) str/trim not-empty)]
    (when-not (str/blank? preferred)
      (when-let [team (db/exec-one! conn
                                    ["SELECT id FROM team
                                      WHERE is_default = false
                                        AND deleted_at IS NULL
                                        AND name = ?
                                      LIMIT 1"
                                     preferred])]
        (db/insert! conn :team-profile-rel
                     {:team-id    (:id team)
                      :profile-id id
                      :is-owner   false
                      :is-admin   false
                      :can-edit   true}
                     {::db/on-conflict-do-nothing? true})
        (l/inf :hint "x-auth-request: ensured SMB shared team membership"
               :profile-id (str id)
               :team-id    (str (:id team)))))))

(defn- get-or-register-profile
  "Looks up a profile by email. If not found and the
  :x-auth-request-auto-register flag is enabled, creates a new active
  profile with a default team. Returns nil when the profile does not
  exist and auto-registration is disabled."
  [cfg email fullname]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [profile (or (profile/get-profile-by-email conn email)
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
                                      (auth/create-profile-rels conn profile))))]
                  ;; Same semantics as Plane: join only provisioned SMB team by name — no fallback.
                  ;; Never fail auth if join fails (e.g. quotas, constraints).
                  (when profile
                    (try
                      (auto-join-team! conn profile)
                      (catch Throwable cause
                        (l/err :hint "x-auth-request: auto-join to shared team failed"
                               :profile-id (:id profile)
                               :cause cause))))
                  profile))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MIDDLEWARE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Perf note: this middleware removes the previous fast-path that
;; short-circuited whenever wrap-session had already set
;; ::session/profile-id. With the fix in place, every authenticated
;; SSO request resolves the header email's profile (a transaction
;; with get-profile-by-email + an idempotent auto-join check). The
;; cost is intentional — correctness over throughput on the steady-
;; state path. If profiling shows this is hot, a follow-up can
;; reintroduce a fast-path by pre-loading the session profile's
;; email (so we can compare without get-or-register-profile) or by
;; caching email→profile-id in a short-lived in-memory map.

(defn- wrap-authz
  [handler cfg]
  (fn [request]
    (let [atoken-pid  (::actoken/profile-id request)
          session-pid (::session/profile-id request)
          email-claim (yreq/get-header request "x-auth-request-email")]
      (cond
        ;; Access-token (API key) — programmatic identity issued out-of-band
        ;; by the user. Not a browser SSO session, so the header is not
        ;; meaningful here. Pass through unconditionally.
        (some? atoken-pid)
        (handler request)

        ;; No proxy header — trust whatever wrap-session decided. Without a
        ;; header we have no upstream identity to compare against.
        (str/blank? email-claim)
        (handler request)

        :else
        (let [local-part (first (str/split email-claim #"@"))
              email      (resolve-email email-claim)
              fullname   (or (not-empty (yreq/get-header request "x-auth-request-user"))
                             local-part)
              profile    (try
                           (get-or-register-profile cfg email fullname)
                           (catch Throwable cause
                             (l/err :hint "x-auth-request: error resolving profile"
                                    :email email
                                    :cause cause)
                             nil))]
          (cond
            (nil? profile)
            ;; Header email doesn't resolve to a profile (and auto-register
            ;; is off). No identity to switch *to* — pass through with
            ;; whatever wrap-session set.
            (do
              (l/wrn :hint "x-auth-request: no profile found for email, passing through unauthenticated"
                     :email email)
              (handler request))

            (:is-blocked profile)
            (do
              (l/wrn :hint "x-auth-request: profile is blocked, denying access"
                     :email email
                     :profile-id (str (:id profile)))
              {::yres/status 403})

            (not (:is-active profile))
            (do
              (l/wrn :hint "x-auth-request: profile is not active, denying access"
                     :email email
                     :profile-id (str (:id profile)))
              {::yres/status 403})

            ;; Steady state — existing browser session matches the proxy-
            ;; asserted identity. No work to do.
            (and session-pid (= session-pid (:id profile)))
            (handler request)

            ;; Either no existing session, or the session points at a
            ;; *different* profile than oauth2-proxy is asserting. Re-key.
            ;;
            ;; This is the fix for the session-sharing bug: portal "log out
            ;; of all apps" clears the shared _oauth2_proxy cookie + Cognito
            ;; session but NOT Penpot's auth-token cookie on its subdomain.
            ;; Without this branch, wrap-session resolves the previous
            ;; user's profile-id from the stale cookie and this middleware
            ;; (under the previous always-skip-when-session rule) never
            ;; overrode it.
            :else
            (do
              (when session-pid
                (l/inf :hint "x-auth-request: proxy identity differs from existing session — re-keying"
                       :session-profile-id (str session-pid)
                       :header-profile-id  (str (:id profile))))
              (l/dbg :hint "x-auth-request: authenticating via forwarded header"
                     :email email
                     :profile-id (str (:id profile)))
              (let [create-session! (session/create-fn cfg profile)
                    response        (-> request
                                        (assoc ::session/profile-id (:id profile))
                                        ;; Drop stale identity-carrying keys
                                        ;; so downstream code does not see the
                                        ;; previous user's data after re-key.
                                        ;;
                                        ;; ::http/auth-data — errors.clj logs
                                        ;; auth-data.claims.uid as
                                        ;; :request/profile-id; rpc/helpers
                                        ;; exposes the map to RPC handlers via
                                        ;; get-auth-data.
                                        ;;
                                        ;; ::session/session — read indirectly
                                        ;; by session/get-session, which is
                                        ;; called in update-profile-password's
                                        ;; invalidate-others path. Leaving
                                        ;; alice's session here means a
                                        ;; password-change RPC made on the
                                        ;; re-keyed request would invalidate
                                        ;; alice's sessions instead of bob's.
                                        (dissoc ::http/auth-data ::session/session)
                                        handler)]
                ;; Fresh auth-token cookie; replaces the stale one the
                ;; browser still has (if any).
                (create-session! request response)))))))))

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :x-auth-request-headers)
                wrap-authz))})
