;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.session
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]))

;; A default cookie name for storing the session.
(def default-auth-token-cookie-name "auth-token")

;; A cookie that we can use to check from other sites of the same
;; domain if a user is authenticated.
(def default-authenticated-cookie-name "authenticated")

;; Default value for cookie max-age
(def default-cookie-max-age (dt/duration {:days 7}))

;; Default age for automatic session renewal
(def default-renewal-max-age (dt/duration {:hours 6}))

(defprotocol ISessionStore
  (read-session [store key])
  (write-session [store key data])
  (update-session [store data])
  (delete-session [store key]))

(defn- make-database-store
  [{:keys [pool sprops executor]}]
  (reify ISessionStore
    (read-session [_ token]
      (px/with-dispatch executor
        (db/exec-one! pool (sql/select :http-session {:id token}))))

    (write-session [_ _ data]
      (px/with-dispatch executor
        (let [profile-id (:profile-id data)
              user-agent (:user-agent data)
              created-at (or (:created-at data) (dt/now))
              token      (tokens/generate sprops {:iss "authentication"
                                                  :iat created-at
                                                  :uid profile-id})
              params     {:user-agent user-agent
                          :profile-id profile-id
                          :created-at created-at
                          :updated-at created-at
                          :id token}]
          (db/insert! pool :http-session params))))

    (update-session [_ data]
      (let [updated-at (dt/now)]
        (px/with-dispatch executor
          (db/update! pool :http-session
                      {:updated-at updated-at}
                      {:id (:id data)})
          (assoc data :updated-at updated-at))))

    (delete-session [_ token]
      (px/with-dispatch executor
        (db/delete! pool :http-session {:id token})
        nil))))

(defn make-inmemory-store
  [{:keys [sprops]}]
  (let [cache (atom {})]
    (reify ISessionStore
      (read-session [_ token]
        (p/do (get @cache token)))

      (write-session [_ _ data]
        (p/do
          (let [profile-id (:profile-id data)
                user-agent (:user-agent data)
                created-at (or (:created-at data) (dt/now))
                token      (tokens/generate sprops {:iss "authentication"
                                                    :iat created-at
                                                    :uid profile-id})
                params     {:user-agent user-agent
                            :created-at created-at
                            :updated-at created-at
                            :profile-id profile-id
                            :id token}]

            (swap! cache assoc token params)
            params)))

      (update-session [_ data]
        (let [updated-at (dt/now)]
          (swap! cache update (:id data) assoc :updated-at updated-at)
          (assoc data :updated-at updated-at)))

      (delete-session [_ token]
        (p/do
          (swap! cache dissoc token)
          nil)))))

(s/def ::sprops map?)
(defmethod ig/pre-init-spec ::store [_]
  (s/keys :req-un [::db/pool ::wrk/executor ::sprops]))

(defmethod ig/init-key ::store
  [_ {:keys [pool] :as cfg}]
  (if (db/read-only? pool)
    (make-inmemory-store cfg)
    (make-database-store cfg)))

(defmethod ig/halt-key! ::store
  [_ _])

;; --- IMPL

(defn- create-session!
  [store profile-id user-agent]
  (let [params {:user-agent user-agent
                :profile-id profile-id}]
    (write-session store nil params)))

(defn- update-session!
  [store session]
  (update-session store session))

(defn- delete-session!
  [store {:keys [cookies] :as request}]
  (let [name (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (when-let [token (get-in cookies [name :value])]
      (delete-session store token))))

(defn- retrieve-session
  [store request]
  (let [cookie-name (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (when-let [cookie (yrq/get-cookie request cookie-name)]
      (read-session store (:value cookie)))))

(defn assign-auth-token-cookie
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

(defn assign-authenticated-cookie
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

(defn clear-auth-token-cookie
  [response]
  (let [name (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (update response :cookies assoc name {:path "/" :value "" :max-age -1})))

(defn- clear-authenticated-cookie
  [response]
  (let [name   (cf/get :authenticated-cookie-name default-authenticated-cookie-name)
        domain (cf/get :authenticated-cookie-domain)]
    (cond-> response
      (string? domain)
      (update :cookies assoc name {:domain domain :path "/" :value "" :max-age -1}))))

(defn- make-middleware
  [{:keys [store] :as cfg}]
  (letfn [;; Check if time reached for automatic session renewal
          (renew-session? [{:keys [updated-at] :as session}]
            (and (dt/instant? updated-at)
                 (let [elapsed (dt/diff updated-at (dt/now))]
                   (neg? (compare default-renewal-max-age elapsed)))))

          ;; Wrap respond with session renewal code
          (wrap-respond [respond session]
            (fn [response]
              (p/let [session (update-session! store session)]
                (-> response
                    (assign-auth-token-cookie session)
                    (assign-authenticated-cookie session)
                    (respond)))))]

    {:name :session
     :compile (fn [& _]
                (fn [handler]
                  (fn [request respond raise]
                    (try
                      (-> (retrieve-session store request)
                          (p/finally (fn [session cause]
                                       (cond
                                         (some? cause)
                                         (raise cause)

                                         (nil? session)
                                         (handler request respond raise)

                                         :else
                                         (let [request    (-> request
                                                              (assoc :profile-id (:profile-id session))
                                                              (assoc :session-id (:id session)))
                                               respond    (cond-> respond
                                                            (renew-session? session)
                                                            (wrap-respond session))]

                                           (handler request respond raise))))))

                      (catch Throwable cause
                        (raise cause))))))}))


;; --- STATE INIT: SESSION

(s/def ::store #(satisfies? ISessionStore %))

(defmethod ig/pre-init-spec :app.http/session [_]
  (s/keys :req-un [::store]))

(defmethod ig/prep-key :app.http/session
  [_ cfg]
  (d/merge {:buffer-size 128}
           (d/without-nils cfg)))

(defmethod ig/init-key :app.http/session
  [_ {:keys [store] :as cfg}]
  (-> cfg
      (assoc :middleware (make-middleware cfg))
      (assoc :create (fn [profile-id]
                       (fn [request response]
                         (p/let [uagent  (yrq/get-header request "user-agent")
                                 session (create-session! store profile-id uagent)]
                           (-> response
                               (assign-auth-token-cookie session)
                               (assign-authenticated-cookie session))))))
      (assoc :delete (fn [request response]
                       (p/do
                         (delete-session! store request)
                         (-> response
                             (assoc :status 204)
                             (assoc :body nil)
                             (clear-auth-token-cookie)
                             (clear-authenticated-cookie)))))))

;; --- STATE INIT: SESSION GC

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
