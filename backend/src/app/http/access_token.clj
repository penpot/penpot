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
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [ring.request :as rreq]))

(def header-re #"^Token\s+(.*)")

(defn- get-token
  [request]
  (some->> (rreq/get-header request "authorization")
           (re-matches header-re)
           (second)))

(defn- decode-token
  [props token]
  (when token
    (tokens/verify props {:token token :iss "access-token"})))

(def sql:get-token-data
  "SELECT perms, profile_id, expires_at
     FROM access_token
    WHERE id = ?
      AND (expires_at IS NULL
           OR (expires_at > now()));")

(defn- get-token-data
  [pool token-id]
  (when-not (db/read-only? pool)
    (some-> (db/exec-one! pool [sql:get-token-data token-id])
            (update :perms db/decode-pgarray #{}))))

(defn- wrap-soft-auth
  "Soft Authentication, will be executed synchronously on the undertow
  worker thread."
  [handler {:keys [::main/props]}]
  (letfn [(handle-request [request]
            (try
              (let [token  (get-token request)
                    claims (decode-token props token)]
                (cond-> request
                  (map? claims)
                  (assoc ::id (:tid claims))))
              (catch Throwable cause
                (l/trace :hint "exception on decoding malformed token" :cause cause)
                request)))]

    (fn [request]
      (handler (handle-request request)))))

(defn- wrap-authz
  "Authorization middleware, will be executed synchronously on vthread."
  [handler {:keys [::db/pool]}]
  (fn [request]
    (let [{:keys [perms profile-id expires-at]} (some->> (::id request) (get-token-data pool))]
      (handler (cond-> request
                 (some? perms)
                 (assoc ::perms perms)
                 (some? profile-id)
                 (assoc ::profile-id profile-id)
                 (some? expires-at)
                 (assoc ::expires-at expires-at))))))

(def soft-auth
  {:name ::soft-auth
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-soft-auth))})

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-authz))})
