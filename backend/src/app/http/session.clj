;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.session
  (:require
   [app.db :as db]
   [app.services.tokens :as tokens]))

(defn extract-auth-token
  [request]
  (get-in request [:cookies "auth-token" :value]))

(defn retrieve
  [conn token]
  (when token
    (-> (db/exec-one! conn ["select profile_id from http_session where id = ?" token])
        (:profile-id))))

(defn retrieve-from-request
  [conn request]
  (->> (extract-auth-token request)
       (retrieve conn)))

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

(defn wrap-session
  [handler]
  (fn [request]
    (if-let [profile-id (retrieve-from-request db/pool request)]
      (handler (assoc request :profile-id profile-id))
      (handler request))))

(def middleware
  {:nane ::middleware
   :compile (constantly wrap-session)})
