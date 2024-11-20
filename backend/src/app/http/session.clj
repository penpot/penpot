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
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http.session.tasks :as-alias tasks]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A default cookie name for storing the session.
(def default-auth-token-cookie-name "auth-token")

;; A cookie that we can use to check from other sites of the same
;; domain if a user is authenticated.
(def default-auth-data-cookie-name "auth-data")

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

(defn manager?
  [o]
  (satisfies? ISessionManager o))

(sm/register!
 {:type ::manager
  :pred manager?})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:params
  [:map {:title "session-params"}
   [:user-agent ::sm/text]
   [:profile-id ::sm/uuid]
   [:created-at ::sm/inst]])

(def ^:private valid-params?
  (sm/validator schema:params))

(defn- prepare-session-params
  [key params]
  (assert (string? key) "expected key to be a string")
  (assert (not (str/blank? key)) "expected key to be not empty")
  (assert (valid-params? params) "expected valid params")

  {:user-agent (:user-agent params)
   :profile-id (:profile-id params)
   :created-at (:created-at params)
   :updated-at (:created-at params)
   :id key})

(defn- database-manager
  [pool]
  (reify ISessionManager
    (read [_ token]
      (db/exec-one! pool (sql/select :http-session {:id token})))

    (write! [_ key params]
      (let [params (prepare-session-params key params)]
        (db/insert! pool :http-session params)
        params))

    (update! [_ params]
      (let [updated-at (dt/now)]
        (db/update! pool :http-session
                    {:updated-at updated-at}
                    {:id (:id params)})
        (assoc params :updated-at updated-at)))

    (delete! [_ token]
      (db/delete! pool :http-session {:id token})
      nil)))

(defn inmemory-manager
  []
  (let [cache (atom {})]
    (reify ISessionManager
      (read [_ token]
        (get @cache token))

      (write! [_ key params]
        (let [params (prepare-session-params key params)]
          (swap! cache assoc key params)
          params))

      (update! [_ params]
        (let [updated-at (dt/now)]
          (swap! cache update (:id params) assoc :updated-at updated-at)
          (assoc params :updated-at updated-at)))

      (delete! [_ token]
        (swap! cache dissoc token)
        nil))))

(defmethod ig/assert-key ::manager
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::manager
  [_ {:keys [::db/pool]}]
  (if (db/read-only? pool)
    (inmemory-manager)
    (database-manager pool)))

(defmethod ig/halt-key! ::manager
  [_ _])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MANAGER IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private assign-auth-token-cookie)
(declare ^:private assign-auth-data-cookie)
(declare ^:private clear-auth-token-cookie)
(declare ^:private clear-auth-data-cookie)
(declare ^:private gen-token)

(defn create-fn
  [{:keys [::manager ::setup/props]} profile-id]
  (assert (manager? manager) "expected valid session manager")
  (assert (uuid? profile-id) "expected valid uuid for profile-id")

  (fn [request response]
    (let [uagent  (yreq/get-header request "user-agent")
          params  {:profile-id profile-id
                   :user-agent uagent
                   :created-at (dt/now)}
          token   (gen-token props params)
          session (write! manager token params)]
      (l/trace :hint "create" :profile-id (str profile-id))
      (-> response
          (assign-auth-token-cookie session)
          (assign-auth-data-cookie session)))))

(defn delete-fn
  [{:keys [::manager]}]
  (assert (manager? manager) "expected valid session manager")
  (fn [request response]
    (let [cname   (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
          cookie  (yreq/get-cookie request cname)]
      (l/trace :hint "delete" :profile-id (:profile-id request))
      (some->> (:value cookie) (delete! manager))
      (-> response
          (assoc :status 204)
          (assoc :body nil)
          (clear-auth-token-cookie)
          (clear-auth-data-cookie)))))

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
        cookie (some-> (yreq/get-cookie request cname) :value)]
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

(defn- wrap-soft-auth
  [handler {:keys [::manager ::setup/props]}]
  (assert (manager? manager) "expected valid session manager")
  (letfn [(handle-request [request]
            (try
              (let [token  (get-token request)
                    claims (decode-token props token)]
                (cond-> request
                  (map? claims)
                  (-> (assoc ::token-claims claims)
                      (assoc ::token token))))
              (catch Throwable cause
                (l/trace :hint "exception on decoding malformed token" :cause cause)
                request)))]

    (fn [request]
      (handler (handle-request request)))))

(defn- wrap-authz
  [handler {:keys [::manager]}]
  (assert (manager? manager) "expected valid session manager")
  (fn [request]
    (let [session  (get-session manager (::token request))
          request  (cond-> request
                     (some? session)
                     (assoc ::profile-id (:profile-id session)
                            ::id (:id session)))
          response (handler request)]

      (if (renew-session? session)
        (let [session (update! manager session)]
          (-> response
              (assign-auth-token-cookie session)
              (assign-auth-data-cookie session)))
        response))))

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
        strict?    (contains? cf/flags :strict-session-cookies)
        cors?      (contains? cf/flags :cors)
        name       (cf/get :auth-token-cookie-name default-auth-token-cookie-name)
        comment    (str "Renewal at: " (dt/format-instant renewal :rfc1123))
        cookie     {:path "/"
                    :http-only true
                    :expires expires
                    :value token
                    :comment comment
                    :same-site (if cors? :none (if strict? :strict :lax))
                    :secure secure?}]
    (update response :cookies assoc name cookie)))

(defn- assign-auth-data-cookie
  [response {profile-id :profile-id updated-at :updated-at}]
  (let [max-age    (cf/get :auth-token-cookie-max-age default-cookie-max-age)
        domain     (cf/get :auth-data-cookie-domain)
        cname      default-auth-data-cookie-name

        created-at (or updated-at (dt/now))
        renewal    (dt/plus created-at default-renewal-max-age)
        expires    (dt/plus created-at max-age)

        comment    (str "Renewal at: " (dt/format-instant renewal :rfc1123))
        secure?    (contains? cf/flags :secure-session-cookies)
        strict?    (contains? cf/flags :strict-session-cookies)
        cors?      (contains? cf/flags :cors)

        cookie     {:domain domain
                    :expires expires
                    :path "/"
                    :comment comment
                    :value (u/map->query-string {:profile-id profile-id})
                    :same-site (if cors? :none (if strict? :strict :lax))
                    :secure secure?}]

    (cond-> response
      (string? domain)
      (update :cookies assoc cname cookie))))

(defn- clear-auth-token-cookie
  [response]
  (let [cname (cf/get :auth-token-cookie-name default-auth-token-cookie-name)]
    (update response :cookies assoc cname {:path "/" :value "" :max-age 0})))

(defn- clear-auth-data-cookie
  [response]
  (let [cname  default-auth-data-cookie-name
        domain (cf/get :auth-data-cookie-domain)]
    (cond-> response
      (string? domain)
      (update :cookies assoc cname {:domain domain :path "/" :value "" :max-age 0}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: SESSION GC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: MOVE

(defmethod ig/assert-key ::tasks/gc
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid database pool")
  (assert (dt/duration? (::tasks/max-age params))))

(defmethod ig/expand-key ::tasks/gc
  [k v]
  (let [max-age (cf/get :auth-token-cookie-max-age default-cookie-max-age)]
    {k (merge {::tasks/max-age max-age} (d/without-nils v))}))

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

