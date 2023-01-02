;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.access-token
  (:refer-clojure :exclude [read])
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [app.worker :as wrk]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]))

(defn- get-token
  [request]
  (let [hauth (str/lower (yrq/get-header request "authorization"))]
    (when (str/starts-with? hauth "Token")
      (let [[_ token] (str/split #"\s+" 2)]
        token))))

(defn decode-token
  [props token]
  (tokens/verify props {:token token :iss "access-token"}))

(defn- get-token-claims
  [pool request]
  (when-not (db/read-only? pool)
    (when-let [token (::token request)]
      (some-> (db/get* pool :access-token {:id token})
              (update :perms db/decode-pgarray #{})))))

(defn- wrap-soft-auth
  [handler {:keys [::wrk/executor ::main/props]}]
  (fn [request respond raise]
    (let [token (get-token request)]
      (->> (px/submit! executor (partial decode-token props token))
           (p/fnly (fn [claims _]
                     (cond-> request
                       (map? claims) (-> (assoc ::token-claims claims)
                                         (assoc ::token token))
                       :always       (handler respond raise))))))))

(defn- wrap-authz
  [handler {:keys [::db/pool ::wrk/executor] :as cfg}]
  (fn [request respond raise]
    (if-let [token (::token request)]
      (->> (px/submit! executor (partial get-token-claims pool token))
           (p/fnly (fn [token cause]
                     (cond
                       (some? cause)
                       (raise cause)

                       (nil? token)
                       (handler request respond raise)

                       :else
                       (let [request (-> request
                                         (assoc ::profile-id (:profile-id token))
                                         (assoc ::token-id (:id token))
                                         (assoc ::perms (:perms token)))]
                         (handler request respond raise))))))
      (handler request respond raise))))

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
