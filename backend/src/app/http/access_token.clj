;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.access-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]))

(defn decode-token
  [cfg token]
  (try
    (let [claims (tokens/verify cfg {:token token})]
      (when-not (contains? #{"access-token" "urn:penpot:mcp-token"} (:iss claims))
        (ex/raise :type :validation
                  :code :invalid-token
                  :reason :invalid-issuer))
      claims)
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
      (let [token (db/exec-one! pool [sql:get-token-data token-id])]
        (when (and (= (:uid claims) (:profile-id token))
                   (= (= "urn:penpot:mcp-token" (:iss claims))
                      (= "mcp" (:type token))))
          (update token :perms db/decode-pgarray #{}))))))

(defn- wrap-authz
  [handler {:keys [::db/pool ::allow-mcp]}]
  (fn [request]
    (let [{:keys [type claims]} (get request ::http/auth-data)]
      ;; MCP tokens are restricted to specific commands. Only RPC routes opt in,
      ;; because the RPC wrapper enforces those command-level restrictions.
      (if (and (= :token type)
               (or allow-mcp (not= "urn:penpot:mcp-token" (:iss claims))))
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

        (handler request)))))

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-authz))})
