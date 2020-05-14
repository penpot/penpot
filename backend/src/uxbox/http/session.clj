;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.session
  (:require
   [uxbox.db :as db]
   [uxbox.common.uuid :as uuid]))

;; --- Main API

(defn retrieve
  "Retrieves a user id associated with the provided auth token."
  [token]
  (when token
    (let [row (db/get-by-params db/pool :session {:id token})]
      (:profile-id row))))

(defn create
  [user-id user-agent]
  (let [id  (uuid/random)]
    (db/insert! db/pool :session {:id id
                                  :profile-id user-id
                                  :user-agent user-agent})
    (str id)))

(defn delete
  [token]
  (db/delete! db/pool :session {:id token})
  nil)

;; --- Interceptor

(defn- parse-token
  [request]
  (try
    (when-let [token (get-in request [:cookies "auth-token"])]
      (uuid/uuid (:value token)))
    (catch java.lang.IllegalArgumentException e
      nil)))

(defn wrap-auth
  [handler]
  (fn [request]
    (let [token (parse-token request)
          profile-id (retrieve token)]
      (if profile-id
        (handler (assoc request :profile-id profile-id))
        (handler request)))))

(def auth
  {:nane ::auth
   :compile (constantly wrap-auth)})
