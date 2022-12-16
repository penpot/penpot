;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.session
  (:refer-clojure :exclude [read])
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A default cookie name for storing the session.
(def default-auth-token-cookie-name "auth-token")

;; A cookie that we can use to check from other sites of the same
;; domain if a user is authenticated.
(def default-authenticated-cookie-name "authenticated")

;; Default value for cookie max-age
(def default-cookie-max-age (dt/duration {:days 7}))

;; Default age for automatic session renewal
(def default-renewal-max-age (dt/duration {:hours 6}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROTOCOLS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISessionManager
  (read [_ key])
  (decode [_ key])
  (write! [_ key data])
  (update! [_ data])
  (delete! [_ key]))

(s/def ::session #(satisfies? ISessionManager %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- prepare-session-params
  [props data]
  (let [profile-id (:profile-id data)
        user-agent (:user-agent data)
        created-at (or (:created-at data) (dt/now))
        token      (tokens/generate props {:iss "authentication"
                                           :iat created-at
                                           :uid profile-id})]
    {:user-agent user-agent
     :profile-id profile-id
     :created-at created-at
     :updated-at created-at
     :id token}))

(defn- database-manager
  [{:keys [::db/pool ::wrk/executor ::main/props]}]
  (reify ISessionManager
    (read [_ token]
      (px/with-dispatch executor
        (db/exec-one! pool (sql/select :http-session {:id token}))))

    (decode [_ token]
      (px/with-dispatch executor
        (tokens/verify props {:token token :iss "authentication"})))

    (write! [_ _ data]
      (px/with-dispatch executor
        (let [params (prepare-session-params props data)]
          (db/insert! pool :http-session params)
          params)))

    (update! [_ data]
      (let [updated-at (dt/now)]
        (px/with-dispatch executor
          (db/update! pool :http-session
                      {:updated-at updated-at}
                      {:id (:id data)})
          (assoc data :updated-at updated-at))))

    (delete! [_ token]
      (px/with-dispatch executor
        (db/delete! pool :http-session {:id token})
        nil))))

(defn inmemory-manager
  [{:keys [::wrk/executor ::main/props]}]
  (let [cache (atom {})]
    (reify ISessionManager
      (read [_ token]
        (p/do (get @cache token)))

      (decode [_ token]
        (px/with-dispatch executor
          (tokens/verify props {:token token :iss "authentication"})))

      (write! [_ _ data]
        (p/do
          (let [{:keys [token] :as params} (prepare-session-params props data)]
            (swap! cache assoc token params)
            params)))

      (update! [_ data]
        (p/do
          (let [updated-at (dt/now)]
            (swap! cache update (:id data) assoc :updated-at updated-at)
            (assoc data :updated-at updated-at))))

      (delete! [_ token]
        (p/do
          (swap! cache dissoc token)
          nil)))))

(defmethod ig/pre-init-spec ::manager [_]
  (s/keys :req [::db/pool ::wrk/executor ::main/props]))

(defmethod ig/init-key ::manager
  [_ {:keys [::db/pool] :as cfg}]
  (if (db/read-only? pool)
    (inmemory-manager cfg)
    (database-manager cfg)))

(defmethod ig/halt-key! ::manager
  [_ _])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MANAGER IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare assign-auth-token-cookie)
(declare assign-authenticated-cookie)
(declare clear-auth-token-cookie)
(declare clear-authenticated-cookie)

(defn create-fn
  [manager profile-id]
  (fn [request response]
    (let [uagent  (yrq/get-header request "user-agent")
          params  {:profile-id profile-id
                   :user-agent uagent}]
      (-> (write! manager nil params)
          (p/then (fn [session]
                    (l/trace :hint "create" :profile-id profile-id)
                    (-> response
                        (assign-auth-token-cookie session)
                        (assign-authenticated-cookie session))))))))
(defn delete-fn
  [manager]
  (letfn [(delete [{:keys [profile-id] :as request}]
            (let [cname   (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
                  cookie  (yrq/get-cookie request cname)]
              (l/trace :hint "delete" :profile-id profile-id)
              (some->> (:value cookie) (delete! manager))))]
    (fn [request response]
      (p/do
        (delete request)
        (-> response
            (assoc :status 204)
            (assoc :body nil)
            (clear-auth-token-cookie)
            (clear-authenticated-cookie))))))

(def middleware-1
  (letfn [(wrap-handler [manager handler request respond raise]
            (when-let [cookie (some->> (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
                                       (yrq/get-cookie request))]
              (->> (decode manager (:value cookie))
                   (p/fnly (fn [claims _]
                             (cond-> request
                               (some? claims) (assoc :session-token-claims claims)
                               :always        (handler respond raise)))))))]
    {:name :session-1
     :compile (fn [& _]
                (fn [handler manager]
                  (partial wrap-handler manager handler)))}))

(def middleware-2
  (letfn [(wrap-handler [manager handler request respond raise]
            (-> (retrieve-session manager request)
                (p/finally (fn [session cause]
                             (cond
                               (some? cause)
                               (raise cause)

                               (nil? session)
                               (handler request respond raise)

                               :else
                               (let [request (-> request
                                                 (assoc :profile-id (:profile-id session))
                                                 (assoc :session-id (:id session)))
                                     respond (cond-> respond
                                               (renew-session? session)
                                               (wrap-respond manager session))]
                                 (handler request respond raise)))))))

          (retrieve-session [manager request]
            (let [cname  (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
                  cookie (yrq/get-cookie request cname)]
              (some->> (:value cookie) (read manager))))

          (renew-session? [{:keys [updated-at] :as session}]
            (and (dt/instant? updated-at)
                 (let [elapsed (dt/diff updated-at (dt/now))]
                   (neg? (compare default-renewal-max-age elapsed)))))

          ;; Wrap respond with session renewal code
          (wrap-respond [respond manager session]
            (fn [response]
              (p/let [session (update! manager session)]
                (-> response
                    (assign-auth-token-cookie session)
                    (assign-authenticated-cookie session)
                    (respond)))))]

    {:name :session-2
     :compile (fn [& _]
                (fn [handler manager]
                  (partial wrap-handler manager handler)))}))

;; --- IMPL

(defn- assign-auth-token-cookie
  [response {token :id updated-at :updated-at}]
  (let [max-age    (cf/get :auth-token-cookie-max-age default-cookie-max-age)
        created-at (or updated-at (dt/now))
        renewal    (dt/plus created-at default-renewal-max-age)
        expires    (dt/plus created-at max-age)
        secure?    (contains? cf/flags :secure-session-cookies)
        cors?      (contains? cf/flags :cors)
        name       (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
        comment    (str "Renewal at: " (dt/format-instant renewal :rfc1123))
        cookie     {:path "/"
                    :http-only true
                    :expires expires
                    :value token
                    :comment comment
                    :same-site (if cors? :none :lax)
                    :secure secure?}]
    (update response :cookies assoc name cookie)))

(defn- assign-authenticated-cookie
  [response {updated-at :updated-at}]
  (let [max-age    (cf/get :auth-token-cookie-max-age default-cookie-max-age)
        created-at (or updated-at (dt/now))
        renewal    (dt/plus created-at default-renewal-max-age)
        expires    (dt/plus created-at max-age)
        comment    (str "Renewal at: " (dt/format-instant renewal :rfc1123))
        secure?    (contains? cf/flags :secure-session-cookies)
        domain     (cf/get :authenticated-cookie-domain)
        name       (cf/get :authenticated-cookie-name "authenticated")
        cookie     {:domain domain
                    :expires expires
                    :path "/"
                    :comment comment
                    :value true
                    :same-site :strict
                    :secure secure?}]
    (cond-> response
      (string? domain)
      (update :cookies assoc name cookie))))

(defn- clear-auth-token-cookie
  [response]
  (let [cname (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (update response :cookies assoc cname {:path "/" :value "" :max-age -1})))

(defn- clear-authenticated-cookie
  [response]
  (let [cname   (cf/get :authenticated-cookie-name default-authenticated-cookie-name)
        domain (cf/get :authenticated-cookie-domain)]
    (cond-> response
      (string? domain)
      (update :cookies assoc cname {:domain domain :path "/" :value "" :max-age -1}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: SESSION GC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare sql:delete-expired)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-task [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::max-age]))

(defmethod ig/prep-key ::gc-task
  [_ cfg]
  (merge {:max-age default-cookie-max-age}
         (d/without-nils cfg)))

(defmethod ig/init-key ::gc-task
  [_ {:keys [pool max-age] :as cfg}]
  (l/debug :hint "initializing session gc task" :max-age max-age)
  (fn [_]
    (db/with-atomic [conn pool]
      (let [interval (db/interval max-age)
            result   (db/exec-one! conn [sql:delete-expired interval interval])
            result   (:next.jdbc/update-count result)]
        (l/debug :task "gc"
                 :hint "clean http sessions"
                 :deleted result)
        result))))

(def ^:private
  sql:delete-expired
  "delete from http_session
    where updated_at < now() - ?::interval
       or (updated_at is null and
           created_at < now() - ?::interval)")
