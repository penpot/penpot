;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.session
  (:require
   [promesa.core :as p]
   [vertx.core :as vc]
   [uxbox.db :as db]
   [uxbox.common.uuid :as uuid]))

;; --- Main API

(defn retrieve
  "Retrieves a user id associated with the provided auth token."
  [token]
  (when token
    (let [sql "select profile_id from session where id = $1"]
      (-> (db/query-one db/pool [sql token])
          (p/then' (fn [row] (when row (:profile-id row))))))))

(defn create
  [user-id user-agent]
  (let [id  (uuid/random)
        sql "insert into session (id, profile_id, user_agent) values ($1, $2, $3)"]
    (-> (db/query-one db/pool [sql id user-id user-agent])
        (p/then (constantly (str id))))))

(defn delete
  [token]
  (let [sql "delete from session where id = $1"]
    (-> (db/query-one db/pool [sql token])
        (p/then' (constantly nil)))))

;; --- Interceptor

(defn- parse-token
  [request]
  (try
    (when-let [token (get-in request [:cookies "auth-token"])]
      (uuid/uuid token))
    (catch java.lang.IllegalArgumentException e
      nil)))

(defn- wrap-auth
  [handler]
  (fn [request]
    (let [token (parse-token request)]
      (-> (p/do! (retrieve token))
          (p/then (fn [profile-id]
                    (if profile-id
                      (handler (assoc request :profile-id profile-id))
                      (handler request))))))))

(def auth
  {:nane ::auth
   :compile (constantly wrap-auth)})
