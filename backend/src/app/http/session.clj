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
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http.auth :as-alias http.auth]
   [app.http.session.tasks :as-alias tasks]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Default value for cookie max-age
(def default-cookie-max-age (ct/duration {:days 7}))

;; Default age for automatic session renewal
(def default-renewal-max-age (ct/duration {:hours 6}))

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
   [:created-at ::ct/inst]])

(def ^:private valid-params?
  (sm/validator schema:params))

(defn- prepare-session-params
  [params key]
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
      (let [params (-> params
                       (assoc :created-at (ct/now))
                       (prepare-session-params key))]
        (db/insert! pool :http-session params)
        params))

    (update! [_ params]
      (let [updated-at (ct/now)]
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
        (let [params (-> params
                         (assoc :created-at (ct/now))
                         (prepare-session-params key))]
          (swap! cache assoc key params)
          params))

      (update! [_ params]
        (let [updated-at (ct/now)]
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
(declare ^:private clear-auth-token-cookie)
(declare ^:private gen-token)

(defn create-fn
  [{:keys [::manager] :as cfg} profile-id]
  (assert (manager? manager) "expected valid session manager")
  (assert (uuid? profile-id) "expected valid uuid for profile-id")

  (fn [request response]
    (let [uagent  (yreq/get-header request "user-agent")
          params  {:profile-id profile-id
                   :user-agent uagent}
          token   (gen-token cfg params)
          session (write! manager token params)]
      (l/trc :hint "create" :profile-id (str profile-id))
      (-> response
          (assign-auth-token-cookie session)))))

(defn delete-fn
  [{:keys [::manager]}]
  (assert (manager? manager) "expected valid session manager")
  (fn [request response]
    (let [cname   (cf/get :auth-token-cookie-name)
          cookie  (yreq/get-cookie request cname)]
      (l/trc :hint "delete" :profile-id (:profile-id request))
      (some->> (:value cookie) (delete! manager))
      (-> response
          (assoc :status 204)
          (assoc :body nil)
          (clear-auth-token-cookie)))))

(defn- gen-token
  [cfg {:keys [profile-id created-at]}]
  (tokens/generate cfg {:iss "authentication"
                        :iat created-at
                        :uid profile-id}))
(defn decode-token
  [cfg token]
  (try
    (tokens/verify cfg {:token token :iss "authentication"})
    (catch Throwable cause
      (l/trc :hint "exception on decoding token"
             :token token
             :cause cause))))

(defn- renew-session?
  [{:keys [updated-at] :as session}]
  (and (ct/inst? updated-at)
       (let [elapsed (ct/diff updated-at (ct/now))]
         (neg? (compare default-renewal-max-age elapsed)))))

(defn- wrap-authz
  [handler {:keys [::manager] :as cfg}]
  (assert (manager? manager) "expected valid session manager")
  (fn [{:keys [::http.auth/token-type] :as request}]
    (cond
      (= token-type :cookie)
      (let [session (some->> (get request ::http.auth/token)
                             (read manager))
            request (cond-> request
                      (some? session)
                      (-> (assoc ::profile-id (:profile-id session))
                          (assoc ::id (:id session))))

            response (handler request)]

        (if (renew-session? session)
          (let [session (update! manager session)]
            (-> response
                (assign-auth-token-cookie session)))
          response))

      (= token-type :bearer)
      (let [session (some->> (get request ::http.auth/token)
                             (read manager))
            request (cond-> request
                      (some? session)
                      (-> (assoc ::profile-id (:profile-id session))
                          (assoc ::id (:id session))))]
        (handler request))

      :else
      (handler request))))

(def authz
  {:name ::authz
   :compile (constantly wrap-authz)})

;; --- IMPL

(defn- assign-auth-token-cookie
  [response {token :id updated-at :updated-at}]
  (let [max-age    (cf/get :auth-token-cookie-max-age default-cookie-max-age)
        created-at updated-at
        renewal    (ct/plus created-at default-renewal-max-age)
        expires    (ct/plus created-at max-age)
        secure?    (contains? cf/flags :secure-session-cookies)
        strict?    (contains? cf/flags :strict-session-cookies)
        cors?      (contains? cf/flags :cors)
        name       (cf/get :auth-token-cookie-name)
        comment    (str "Renewal at: " (ct/format-inst renewal :rfc1123))
        cookie     {:path "/"
                    :http-only true
                    :expires expires
                    :value token
                    :comment comment
                    :same-site (if cors? :none (if strict? :strict :lax))
                    :secure secure?}]
    (update response :cookies assoc name cookie)))

(defn- clear-auth-token-cookie
  [response]
  (let [cname (cf/get :auth-token-cookie-name)]
    (update response :cookies assoc cname {:path "/" :value "" :max-age 0})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: SESSION GC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::tasks/gc
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid database pool")
  (assert (ct/duration? (::tasks/max-age params))))

(defmethod ig/expand-key ::tasks/gc
  [k v]
  (let [max-age (cf/get :auth-token-cookie-max-age default-cookie-max-age)]
    {k (merge {::tasks/max-age max-age} (d/without-nils v))}))

(def ^:private
  sql:delete-expired
  "DELETE FROM http_session
    WHERE updated_at < ?::timestamptz
       or (updated_at is null and
           created_at < ?::timestamptz)")

(defn- collect-expired-tasks
  [{:keys [::db/conn ::tasks/max-age]}]
  (let [threshold (ct/minus (ct/now) max-age)
        result    (-> (db/exec-one! conn [sql:delete-expired threshold threshold])
                      (db/get-update-count))]
    (l/dbg :task "gc"
           :hint "clean http sessions"
           :deleted result)
    result))

(defmethod ig/init-key ::tasks/gc
  [_ {:keys [::tasks/max-age] :as cfg}]
  (l/dbg :hint "initializing session gc task" :max-age max-age)
  (fn [_]
    (db/tx-run! cfg collect-expired-tasks)))
