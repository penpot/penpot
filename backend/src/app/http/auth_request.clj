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

(defn- wrap-authz
  [handler cfg]
  (fn [request]
    ;; Skip when a prior middleware (session or access-token) already
    ;; resolved a profile — we only act as a fallback.
    (if (or (some? (::session/profile-id request))
            (some? (::actoken/profile-id request)))
      (handler request)
      (let [email-claim (yreq/get-header request "x-auth-request-email")]
        (if (str/blank? email-claim)
          (handler request)
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
