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
   [app.util.log4j :refer [update-thread-context!]]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

;; --- IMPL

(defn- next-session-id
  ([] (next-session-id 96))
  ([n]
   (-> (bn/random-nonce n)
       (bc/bytes->b64u)
       (bc/bytes->str))))

(defn- create
  [{:keys [conn] :as cfg} {:keys [profile-id user-agent]}]
  (let [id (next-session-id)]
    (db/insert! conn :http-session {:id id
                                    :profile-id profile-id
                                    :user-agent user-agent})
    id))

(defn- delete
  [{:keys [conn cookie-name] :as cfg} {:keys [cookies] :as request}]
  (when-let [token (get-in cookies [cookie-name :value])]
    (db/delete! conn :http-session {:id token}))
  nil)

(defn- retrieve
  [{:keys [conn] :as cfg} token]
  (when token
    (-> (db/exec-one! conn ["select profile_id from http_session where id = ?" token])
        (:profile-id))))

(defn- retrieve-from-request
  [{:keys [cookie-name] :as cfg} {:keys [cookies] :as request}]
  (->> (get-in cookies [cookie-name :value])
       (retrieve cfg)))

(defn- cookies
  [{:keys [cookie-name] :as cfg} vals]
  {cookie-name (merge vals {:path "/" :http-only true})})

(defn- middleware
  [cfg handler]
  (fn [request]
    (if-let [profile-id (retrieve-from-request cfg request)]
      (do
        (update-thread-context! {:profile-id profile-id})
        (handler (assoc request :profile-id profile-id)))
      (handler request))))

;; --- STATE INIT

(defmethod ig/pre-init-spec ::session [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/prep-key ::session
  [_ cfg]
  (merge {:cookie-name "auth-token"} cfg))

(defmethod ig/init-key ::session
  [_ {:keys [pool] :as cfg}]
  (let [cfg (assoc cfg :conn pool)]
    (-> cfg
        (assoc :middleware #(middleware cfg %))
        (assoc :create (fn [profile-id]
                         (fn [request response]
                           (let [uagent (get-in request [:headers "user-agent"])
                                 value  (create cfg {:profile-id profile-id :user-agent uagent})]
                             (assoc response :cookies (cookies cfg {:value value}))))))
        (assoc :delete (fn [request response]
                         (delete cfg request)
                         (assoc response
                                :status 204
                                :body ""
                                :cookies (cookies cfg {:value "" :max-age -1})))))))

