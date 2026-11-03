;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.access-token
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]))

(def mcp-token-issuer "urn:penpot:mcp-token")

(def ^:private valid-issuers
  #{"access-token" mcp-token-issuer})

(defn decode-token
  [cfg token]
  (try
    (let [claims (tokens/verify cfg {:token token})]
      (when (contains? valid-issuers (:iss claims))
        claims))
    ;; The cause is not logged because its data carries the token
    (catch Throwable _
      (l/trc :hint "exception on decoding token"))))

(def sql:get-token-data
  "SELECT perms, profile_id, expires_at, type
     FROM access_token
    WHERE id = ?
      AND (expires_at IS NULL
           OR (expires_at > now()));")

(defn- get-token-data
  [pool claims]
  (when-not (db/read-only? pool)
    (when-let [token-id (get claims :tid)]
      (let [token       (db/exec-one! pool [sql:get-token-data token-id])
            mcp-claims? (= mcp-token-issuer (:iss claims))
            mcp-token?  (= "mcp" (:type token))]
        (when (and (= (:uid claims) (:profile-id token))
                   (= mcp-claims? mcp-token?))
          (update token :perms db/decode-pgarray #{}))))))

(defn- wrap-authz
  "Authenticate requests carrying an access token. Personal tokens need the
  `access-tokens` flag. MCP tokens are restricted to specific commands, so
  only RPC routes opt in (the RPC wrapper enforces those restrictions); they
  need either the `mcp` or the `access-tokens` flag."
  [handler {:keys [::db/pool ::allow-mcp]}]
  (let [personal-enabled? (contains? cf/flags :access-tokens)
        mcp-enabled?      (and allow-mcp
                               (or personal-enabled?
                                   (contains? cf/flags :mcp)))]
    (fn [request]
      (let [{:keys [type claims]} (get request ::http/auth-data)]
        (if (and (= :token type)
                 (if (= mcp-token-issuer (:iss claims))
                   mcp-enabled?
                   personal-enabled?))
          (let [{:keys [perms profile-id expires-at type]} (some->> claims (get-token-data pool))
                token-id (get claims :tid)]
            (handler (cond-> request
                       (some? perms)
                       (assoc ::perms perms)
                       (some? profile-id)
                       (assoc ::profile-id profile-id)
                       (some? expires-at)
                       (assoc ::expires-at expires-at)
                       (some? token-id)
                       (assoc ::id token-id)
                       (some? type)
                       (assoc ::type type))))

          (handler request))))))

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (or (contains? cf/flags :access-tokens)
                        (contains? cf/flags :mcp))
                wrap-authz))})
