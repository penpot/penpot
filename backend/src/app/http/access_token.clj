;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.access-token
  (:require
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
    (tokens/verify cfg {:token token :iss "access-token"})
    (catch Throwable cause
      (l/trc :hint "exception on decoding token"
             :token token
             :cause cause))))

(def sql:get-token-data
  "SELECT perms, profile_id, expires_at
     FROM access_token
    WHERE id = ?
      AND (expires_at IS NULL
           OR (expires_at > now()));")

(defn- get-token-data
  [pool claims]
  (when-not (db/read-only? pool)
    (when-let [token-id (get claims :tid)]
      (some-> (db/exec-one! pool [sql:get-token-data token-id])
              (update :perms db/decode-pgarray #{})))))

(defn- wrap-authz
  [handler {:keys [::db/pool]}]
  (fn [request]
    (let [{:keys [type claims]} (get request ::http/auth-data)]
      (if (= :token type)
        (let [{:keys [perms profile-id expires-at]} (some->> claims (get-token-data pool))]
          ;; FIXME: revisit this, this data looks unused
          (handler (cond-> request
                     (some? perms)
                     (assoc ::perms perms)
                     (some? profile-id)
                     (assoc ::profile-id profile-id)
                     (some? expires-at)
                     (assoc ::expires-at expires-at))))

        (handler request)))))

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-authz))})
