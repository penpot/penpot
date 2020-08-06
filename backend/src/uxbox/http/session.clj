;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http.session
  (:require
   [uxbox.db :as db]
   [uxbox.services.tokens :as tokens]
   [uxbox.common.uuid :as uuid]))

(defn retrieve
  "Retrieves a user id associated with the provided auth token."
  [token]
  (when token
    (-> (db/query db/pool :http-session {:id token})
        (first)
        (:profile-id))))

(defn create
  [profile-id user-agent]
  (let [id (tokens/next-token)]
    (db/insert! db/pool :http-session {:id id
                                       :profile-id profile-id
                                       :user-agent user-agent})
    id))

(defn delete
  [token]
  (db/delete! db/pool :http-session {:id token})
  nil)

(defn cookies
  ([id] (cookies id {}))
  ([id opts]
   {"auth-token" (merge opts {:value id :path "/" :http-only true})}))

(defn extract-auth-token
  [req]
  (get-in req [:cookies "auth-token" :value]))

(defn wrap-auth
  [handler]
  (fn [request]
    (let [token (get-in request [:cookies "auth-token" :value])
          profile-id (retrieve token)]
      (if profile-id
        (handler (assoc request :profile-id profile-id))
        (handler request)))))

(def auth
  {:nane ::auth
   :compile (constantly wrap-auth)})
