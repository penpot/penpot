;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.session
  (:refer-clojure :exclude [read])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http.session.tasks :as-alias tasks]
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
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
  (write! [_ key data])
  (update! [_ data])
  (delete! [_ key]))

(s/def ::manager #(satisfies? ISessionManager %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::session-params
  (s/keys :req-un [::user-agent
                   ::profile-id
                   ::created-at]))

(defn- prepare-session-params
  [key params]
  (us/assert! ::us/not-empty-string key)
  (us/assert! ::session-params params)

  {:user-agent (:user-agent params)
   :profile-id (:profile-id params)
   :created-at (:created-at params)
   :updated-at (:created-at params)
   :id key})

(defn- database-manager
  [{:keys [::db/pool ::wrk/executor ::main/props]}]
  ^{::wrk/executor executor
    ::db/pool pool
    ::main/props props}
  (reify ISessionManager
    (read [_ token]
      (px/with-dispatch executor
        (db/exec-one! pool (sql/select :http-session {:id token}))))

    (write! [_ key params]
      (px/with-dispatch executor
        (let [params (prepare-session-params key params)]
          (db/insert! pool :http-session params)
          params)))

    (update! [_ params]
      (let [updated-at (dt/now)]
        (px/with-dispatch executor
          (db/update! pool :http-session
                      {:updated-at updated-at}
                      {:id (:id params)})
          (assoc params :updated-at updated-at))))

    (delete! [_ token]
      (px/with-dispatch executor
        (db/delete! pool :http-session {:id token})
        nil))))

(defn inmemory-manager
  [{:keys [::db/pool ::wrk/executor ::main/props]}]
  (let [cache (atom {})]
    ^{::main/props props
      ::wrk/executor executor
      ::db/pool pool}
    (reify ISessionManager
      (read [_ token]
        (p/do (get @cache token)))

      (write! [_ key params]
        (p/do
          (let [params (prepare-session-params key params)]
            (swap! cache assoc key params)
            params)))

      (update! [_ params]
        (p/do
          (let [updated-at (dt/now)]
            (swap! cache update (:id params) assoc :updated-at updated-at)
            (assoc params :updated-at updated-at))))

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

(declare ^:private assign-auth-token-cookie)
(declare ^:private assign-authenticated-cookie)
(declare ^:private clear-auth-token-cookie)
(declare ^:private clear-authenticated-cookie)
(declare ^:private gen-token)

(defn create-fn
  [{:keys [::manager]} profile-id]
  (us/assert! ::manager manager)
  (us/assert! ::us/uuid profile-id)

  (let [props (-> manager meta ::main/props)]
    (fn [request response]
      (let [uagent (yrq/get-header request "user-agent")
            params {:profile-id profile-id
                    :user-agent uagent
                    :created-at (dt/now)}
            token  (gen-token props params)]

        (->> (write! manager token params)
             (p/fmap (fn [session]
                       (l/trace :hint "create" :profile-id (str profile-id))
                       (-> response
                           (assign-auth-token-cookie session)
                           (assign-authenticated-cookie session)))))))))
(defn delete-fn
  [{:keys [::manager]}]
  (us/assert! ::manager manager)
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

(defn- gen-token
  [props {:keys [profile-id created-at]}]
  (tokens/generate props {:iss "authentication"
                          :iat created-at
                          :uid profile-id}))
(defn- decode-token
  [props token]
  (when token
    (tokens/verify props {:token token :iss "authentication"})))

(defn- get-token
  [request]
  (let [cname  (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
        cookie (some-> (yrq/get-cookie request cname) :value)]
    (when-not (str/empty? cookie)
      cookie)))

(defn- get-session
  [manager token]
  (some->> token (read manager)))

(defn- renew-session?
  [{:keys [updated-at] :as session}]
  (and (dt/instant? updated-at)
       (let [elapsed (dt/diff updated-at (dt/now))]
         (neg? (compare default-renewal-max-age elapsed)))))

(defn- wrap-reneval
  [respond manager session]
  (fn [response]
    (p/let [session (update! manager session)]
      (-> response
          (assign-auth-token-cookie session)
          (assign-authenticated-cookie session)
          (respond)))))

(defn- wrap-soft-auth
  [handler {:keys [::manager]}]
  (us/assert! ::manager manager)

  (let [{:keys [::wrk/executor ::main/props]} (meta manager)]
    (fn [request respond raise]
      (let [token (ex/try! (get-token request))]
        (if (ex/exception? token)
          (raise token)
          (->> (px/submit! executor (partial decode-token props token))
               (p/fnly (fn [claims cause]
                         (when cause
                           (l/trace :hint "exception on decoding malformed token" :cause cause))
                         (let [request (cond-> request
                                         (map? claims)
                                         (-> (assoc ::token-claims claims)
                                             (assoc ::token token)))]
                           (handler request respond raise))))))))))

(defn- wrap-authz
  [handler {:keys [::manager]}]
  (us/assert! ::manager manager)
  (fn [request respond raise]
    (if-let [token (::token request)]
      (->> (get-session manager token)
           (p/fnly (fn [session cause]
                     (cond
                       (some? cause)
                       (raise cause)

                       (nil? session)
                       (handler request respond raise)

                       :else
                       (let [request (-> request
                                         (assoc ::profile-id (:profile-id session))
                                         (assoc ::id (:id session)))
                             respond (cond-> respond
                                       (renew-session? session)
                                       (wrap-reneval manager session))]
                         (handler request respond raise))))))

      (handler request respond raise))))

(def soft-auth
  {:name ::soft-auth
   :compile (constantly wrap-soft-auth)})

(def authz
  {:name ::authz
   :compile (constantly wrap-authz)})

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
        domain     (cf/get :authenticated-cookie-domain)
        cname      (cf/get :authenticated-cookie-name "authenticated")

        created-at (or updated-at (dt/now))
        renewal    (dt/plus created-at default-renewal-max-age)
        expires    (dt/plus created-at max-age)

        comment    (str "Renewal at: " (dt/format-instant renewal :rfc1123))
        secure?    (contains? cf/flags :secure-session-cookies)

        cookie     {:domain domain
                    :expires expires
                    :path "/"
                    :comment comment
                    :value true
                    :same-site :strict
                    :secure secure?}]
    (cond-> response
      (string? domain)
      (update :cookies assoc cname cookie))))

(defn- clear-auth-token-cookie
  [response]
  (let [cname (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (update response :cookies assoc cname {:path "/" :value "" :max-age 0})))

(defn- clear-authenticated-cookie
  [response]
  (let [cname  (cf/get :authenticated-cookie-name default-authenticated-cookie-name)
        domain (cf/get :authenticated-cookie-domain)]
    (cond-> response
      (string? domain)
      (update :cookies assoc cname {:domain domain :path "/" :value "" :max-age 0}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: SESSION GC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::tasks/max-age ::dt/duration)

(defmethod ig/pre-init-spec ::tasks/gc [_]
  (s/keys :req [::db/pool]
          :opt [::tasks/max-age]))

(defmethod ig/prep-key ::tasks/gc
  [_ cfg]
  (let [max-age (cf/get :auth-token-cookie-max-age default-cookie-max-age)]
    (merge {::tasks/max-age max-age} (d/without-nils cfg))))

(def ^:private
  sql:delete-expired
  "delete from http_session
    where updated_at < now() - ?::interval
       or (updated_at is null and
           created_at < now() - ?::interval)")

(defmethod ig/init-key ::tasks/gc
  [_ {:keys [::db/pool ::tasks/max-age] :as cfg}]
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

