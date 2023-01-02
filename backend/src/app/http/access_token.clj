;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.access-token
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]))


(s/def ::manager
  (s/keys :req [::db/pool ::wrk/executor ::main/props]))

(defmethod ig/pre-init-spec ::manager [_] ::manager)
(defmethod ig/init-key ::manager [_ cfg] cfg)
(defmethod ig/halt-key! ::manager [_ _])

(def header-re #"^Token\s+(.*)")

(defn- get-token
  [request]
  (some->> (yrq/get-header request "authorization")
           (re-matches header-re)
           (second)))

(defn- decode-token
  [props token]
  (when token
    (tokens/verify props {:token token :iss "access-token"})))

(defn- get-token-perms
  [pool token-id]
  (when-not (db/read-only? pool)
    (when-let [token (db/get* pool :access-token {:id token-id} {:columns [:perms]})]
      (some-> (:perms token)
              (db/decode-pgarray #{})))))

(defn- wrap-soft-auth
  [handler {:keys [::manager]}]
  (us/assert! ::manager manager)

  (let [{:keys [::wrk/executor ::main/props]} manager]
    (fn [request respond raise]
      (let [token (get-token request)]
        (->> (px/submit! executor (partial decode-token props token))
             (p/fnly (fn [claims cause]
                       (when cause
                         (l/trace :hint "exception on decoding malformed token" :cause cause))
                       (let [request (cond-> request
                                       (map? claims)
                                       (assoc ::id (:tid claims)))]
                         (handler request respond raise)))))))))

(defn- wrap-authz
  [handler {:keys [::manager]}]
  (us/assert! ::manager manager)
  (let [{:keys [::wrk/executor ::db/pool]} manager]
    (fn [request respond raise]
      (if-let [token-id (::id request)]
        (->> (px/submit! executor (partial get-token-perms pool token-id))
             (p/fnly (fn [perms cause]
                       (cond
                         (some? cause)
                         (raise cause)

                         (nil? perms)
                         (handler request respond raise)

                         :else
                         (let [request (assoc request ::perms perms)]
                           (handler request respond raise))))))
        (handler request respond raise)))))

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
