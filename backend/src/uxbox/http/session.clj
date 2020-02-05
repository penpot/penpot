;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.session
  (:require
   [promesa.core :as p]
   [sieppari.context :as spx]
   [vertx.core :as vc]
   [uxbox.db :as db]
   [uxbox.util.uuid :as uuid]))

;; --- Main API

(defn retrieve
  "Retrieves a user id associated with the provided auth token."
  [token]
  (when token
    (let [sql "select user_id from sessions where id = $1"]
      (-> (db/query-one db/pool [sql token])
          (p/then' (fn [row] (when row (:user-id row))))))))

(defn create
  [user-id user-agent]
  (let [id  (uuid/random)
        sql "insert into sessions (id, user_id, user_agent) values ($1, $2, $3)"]
    (-> (db/query-one db/pool [sql id user-id user-agent])
        (p/then (constantly (str id))))))

(defn delete
  [token]
  (let [sql "delete from sessions where id = $1"]
    (-> (db/query-one db/pool [sql token])
        (p/then' (constantly nil)))))

;; --- Interceptor

(defn parse-token
  [request]
  (try
    (when-let [token (get-in request [:cookies "auth-token"])]
      (uuid/from-string token))
    (catch java.lang.IllegalArgumentException e
      nil)))

(defn auth
  []
  {:enter (fn [data]
            (let [token (parse-token (:request data))]
              (-> (retrieve token)
                  (p/then' (fn [user-id]
                             (if user-id
                               (update data :request assoc :user user-id)
                               data))))))})
