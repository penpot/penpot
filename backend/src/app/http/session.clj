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
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as-alias http]
   [app.http.auth :as-alias http.auth]
   [app.http.session.tasks :as-alias tasks]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [integrant.core :as ig]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

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
  (read-session [_ id])
  (create-session [_ params])
  (update-session [_ session])
  (delete-session [_ id]))

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
  [:map {:title "SessionParams" :closed true}
   [:profile-id ::sm/uuid]
   [:user-agent {:optional true} ::sm/text]
   [:sso-provider-id {:optional true} ::sm/uuid]
   [:sso-session-id {:optional true} :string]])

(def ^:private valid-params?
  (sm/validator schema:params))

(defn- database-manager
  [pool]
  (reify ISessionManager
    (read-session [_ id]
      (if (string? id)
        ;; Backward compatibility
        (let [session (db/exec-one! pool (sql/select :http-session {:id id}))]
          (-> session
              (assoc :modified-at (:updated-at session))
              (dissoc :updated-at)))
        (db/exec-one! pool (sql/select :http-session-v2 {:id id}))))

    (create-session [_ params]
      (assert (valid-params? params) "expect valid session params")

      (let [now    (ct/now)
            params (-> params
                       (assoc :id (uuid/next))
                       (assoc :created-at now)
                       (assoc :modified-at now))]
        (db/insert! pool :http-session-v2 params
                    {::db/return-keys true})))

    (update-session [_ session]
      (let [modified-at (ct/now)]
        (if (string? (:id session))
          (let [params (-> session
                           (assoc :id (uuid/next))
                           (assoc :created-at modified-at)
                           (assoc :modified-at modified-at))]
            (db/insert! pool :http-session-v2 params))

          (db/update! pool :http-session-v2
                      {:modified-at modified-at}
                      {:id (:id session)}))))

    (delete-session [_ id]
      (if (string? id)
        (db/delete! pool :http-session {:id id} {::db/return-keys false})
        (db/delete! pool :http-session-v2 {:id id} {::db/return-keys false}))
      nil)))

(defn inmemory-manager
  []
  (let [cache (atom {})]
    (reify ISessionManager
      (read-session [_ id]
        (get @cache id))

      (create-session [_ params]
        (assert (valid-params? params) "expect valid session params")

        (let [now     (ct/now)
              session (-> params
                          (assoc :id (uuid/next))
                          (assoc :created-at now)
                          (assoc :modified-at now))]
          (swap! cache assoc (:id session) session)
          session))

      (update-session [_ session]
        (let [modified-at (ct/now)]
          (swap! cache update (:id session) assoc :modified-at modified-at)
          (assoc session :modified-at modified-at)))

      (delete-session [_ id]
        (swap! cache dissoc id)
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

(declare ^:private assign-session-cookie)
(declare ^:private clear-session-cookie)

(defn- assign-token
  [cfg session]
  (let [token (tokens/generate cfg
                               {:iss "authentication"
                                :aud "penpot"
                                :sid (:id session)
                                :iat (:modified-at session)
                                :uid (:profile-id session)
                                :sso-provider-id (:sso-provider-id session)
                                :sso-session-id (:sso-session-id session)})]
    (assoc session :token token)))

(defn create-fn
  [{:keys [::manager] :as cfg} {profile-id :id :as profile}
   & {:keys [sso-provider-id sso-session-id]}]

  (assert (manager? manager) "expected valid session manager")
  (assert (uuid? profile-id) "expected valid uuid for profile-id")

  (fn [request response]
    (let [uagent  (yreq/get-header request "user-agent")
          session (->> {:user-agent uagent
                        :profile-id profile-id
                        :sso-provider-id sso-provider-id
                        :sso-session-id sso-session-id}
                       (d/without-nils)
                       (create-session manager)
                       (assign-token cfg))]

      (l/trc :hint "create" :id (str (:id session)) :profile-id (str profile-id))
      (assign-session-cookie response session))))

(defn delete-fn
  [{:keys [::manager]}]
  (assert (manager? manager) "expected valid session manager")
  (fn [request response]
    (some->> (get request ::id) (delete-session manager))
    (clear-session-cookie response)))

(defn decode-token
  [cfg token]
  (try
    (tokens/verify cfg {:token token :iss "authentication"})
    (catch Throwable cause
      (l/trc :hint "exception on decoding token"
             :token token
             :cause cause))))

(defn get-session
  [request]
  (get request ::session))

(defn invalidate-others
  [cfg session]
  (let [sql "delete from http_session_v2 where profile_id = ? and id != ?"]
    (-> (db/exec-one! cfg [sql (:profile-id session) (:id session)])
        (db/get-update-count))))

(defn- renew-session?
  [{:keys [id modified-at] :as session}]
  (or (string? id)
      (and (ct/inst? modified-at)
           (let [elapsed (ct/diff modified-at (ct/now))]
             (neg? (compare default-renewal-max-age elapsed))))))

(defn- wrap-authz
  [handler {:keys [::manager] :as cfg}]
  (assert (manager? manager) "expected valid session manager")
  (fn [request]
    (let [{:keys [type token claims]} (get request ::http/auth-data)]
      (cond
        (= type :cookie)
        (let [session (if-let [sid (:sid claims)]
                        (read-session manager sid)
                        ;; BACKWARD COMPATIBILITY WITH OLD TOKENS
                        (read-session manager token))

              request (cond-> request
                        (some? session)
                        (-> (assoc ::profile-id (:profile-id session))
                            (assoc ::session session)))

              response (handler request)]

          (if (renew-session? session)
            (let [session (->> session
                               (update-session manager)
                               (assign-token cfg))]
              (assign-session-cookie response session))
            response))

        (= type :bearer)
        (let [session (if-let [sid (:sid claims)]
                        (read-session manager sid)
                        ;; BACKWARD COMPATIBILITY WITH OLD TOKENS
                        (read-session manager token))

              request (cond-> request
                        (some? session)
                        (-> (assoc ::profile-id (:profile-id session))
                            (assoc ::session session)))]
          (handler request))

        :else
        (handler request)))))

(def authz
  {:name ::authz
   :compile (constantly wrap-authz)})

;; --- IMPL

(defn- assign-session-cookie
  [response {token :token modified-at :modified-at}]
  (let [max-age    (cf/get :auth-token-cookie-max-age default-cookie-max-age)
        created-at modified-at
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
    (update response ::yres/cookies assoc name cookie)))

(defn- clear-session-cookie
  [response]
  (let [cname (cf/get :auth-token-cookie-name)]
    (update response ::yres/cookies assoc cname {:path "/" :value "" :max-age 0})))

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
