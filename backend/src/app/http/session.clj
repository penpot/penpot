;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.session
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [ring.middleware.session.store :as rss]))

;; A default cookie name for storing the session. We don't allow to configure it.
(def token-cookie-name "auth-token")

;; A cookie that we can use to check from other sites of the same domain if a user
;; is registered. Is not intended for on premise installations, although nothing
;; prevents using it if some one wants to.
(def authenticated-cookie-name "authenticated")

(deftype DatabaseStore [pool tokens]
  rss/SessionStore
  (read-session [_ token]
    (db/exec-one! pool (sql/select :http-session {:id token})))

  (write-session [_ _ data]
    (let [profile-id (:profile-id data)
          user-agent (:user-agent data)
          token      (tokens :generate {:iss "authentication"
                                        :iat (dt/now)
                                        :uid profile-id})

          now        (dt/now)
          params     {:user-agent user-agent
                      :profile-id profile-id
                      :created-at now
                      :updated-at now
                      :id token}]
      (db/insert! pool :http-session params)
      token))

  (delete-session [_ token]
    (db/delete! pool :http-session {:id token})
    nil))

(deftype MemoryStore [cache tokens]
  rss/SessionStore
  (read-session [_ token]
    (get @cache token))

  (write-session [_ _ data]
    (let [profile-id (:profile-id data)
          user-agent (:user-agent data)
          token      (tokens :generate {:iss "authentication"
                                        :iat (dt/now)
                                        :uid profile-id})
          params     {:user-agent user-agent
                      :profile-id profile-id
                      :id token}]

      (swap! cache assoc token params)
      token))

  (delete-session [_ token]
    (swap! cache dissoc token)
    nil))

;; --- IMPL

(defn- create-session
  [store request profile-id]
  (let [params {:user-agent (get-in request [:headers "user-agent"])
                :profile-id profile-id}]
    (rss/write-session store nil params)))

(defn- delete-session
  [store {:keys [cookies] :as request}]
  (when-let [token (get-in cookies [token-cookie-name :value])]
    (rss/delete-session store token)))

(defn- retrieve-session
  [store token]
  (when token
    (rss/read-session store token)))

(defn- retrieve-from-request
  [store {:keys [cookies] :as request}]
  (->> (get-in cookies [token-cookie-name :value])
       (retrieve-session store)))

(defn- add-cookies
  [response token]
  (let [cors?   (contains? cfg/flags :cors)
        secure? (contains? cfg/flags :secure-session-cookies)
        authenticated-cookie-domain (cfg/get :authenticated-cookie-domain)]
    (update response :cookies
            (fn [cookies]
              (cond-> cookies
                :always
                (assoc token-cookie-name {:path "/"
                                          :http-only true
                                          :value token
                                          :same-site (if cors? :none :lax)
                                          :secure secure?})

                (some? authenticated-cookie-domain)
                (assoc authenticated-cookie-name {:domain authenticated-cookie-domain
                                                  :path "/"
                                                  :value true
                                                  :same-site :strict
                                                  :secure secure?}))))))

(defn- clear-cookies
  [response]
  (let [authenticated-cookie-domain (cfg/get :authenticated-cookie-domain)]
    (assoc response :cookies {token-cookie-name {:path "/"
                                                 :value ""
                                                 :max-age -1}
                              authenticated-cookie-name {:domain authenticated-cookie-domain
                                                         :path "/"
                                                         :value ""
                                                         :max-age -1}})))

(defn- middleware
  [events-ch store handler]
  (fn [request respond raise]
    (if-let [{:keys [id profile-id] :as session} (retrieve-from-request store request)]
      (do
        (a/>!! events-ch id)
        (l/set-context! {:profile-id profile-id})
        (handler (assoc request :profile-id profile-id :session-id id) respond raise))
      (handler request respond raise))))

;; --- STATE INIT: SESSION

(s/def ::tokens fn?)
(defmethod ig/pre-init-spec ::session [_]
  (s/keys :req-un [::db/pool ::tokens]))

(defmethod ig/prep-key ::session
  [_ cfg]
  (d/merge {:buffer-size 128}
           (d/without-nils cfg)))

(defmethod ig/init-key ::session
  [_ {:keys [pool tokens] :as cfg}]
  (let [events-ch (a/chan (a/dropping-buffer (:buffer-size cfg)))
        store     (if (db/read-only? pool)
                    (->MemoryStore (atom {}) tokens)
                    (->DatabaseStore pool tokens))]

    (when (db/read-only? pool)
      (l/warn :hint "sessions module initialized with in-memory store"))

    (-> cfg
        (assoc ::events-ch events-ch)
        (assoc :middleware (partial middleware events-ch store))
        (assoc :create (fn [profile-id]
                         (fn [request response]
                           (let [token (create-session store request profile-id)]
                             (add-cookies response token)))))
        (assoc :delete (fn [request response]
                         (delete-session store request)
                         (-> response
                             (assoc :status 204)
                             (assoc :body "")
                             (clear-cookies)))))))

(defmethod ig/halt-key! ::session
  [_ data]
  (a/close! (::events-ch data)))


;; --- STATE INIT: SESSION UPDATER

(declare update-sessions)

(s/def ::session map?)
(s/def ::max-batch-age ::cfg/http-session-updater-batch-max-age)
(s/def ::max-batch-size ::cfg/http-session-updater-batch-max-size)

(defmethod ig/pre-init-spec ::updater [_]
  (s/keys :req-un [::db/pool ::wrk/executor ::mtx/metrics ::session]
          :opt-un [::max-batch-age
                   ::max-batch-size]))

(defmethod ig/prep-key ::updater
  [_ cfg]
  (merge {:max-batch-age (dt/duration {:minutes 5})
          :max-batch-size 200}
         (d/without-nils cfg)))

(defmethod ig/init-key ::updater
  [_ {:keys [session metrics] :as cfg}]
  (l/info :action "initialize session updater"
          :max-batch-age (str (:max-batch-age cfg))
          :max-batch-size (str (:max-batch-size cfg)))
  (let [input (aa/batch (::events-ch session)
                        {:max-batch-size (:max-batch-size cfg)
                         :max-batch-age (inst-ms (:max-batch-age cfg))})]
    (a/go-loop []
      (when-let [[reason batch] (a/<! input)]
        (let [result (a/<! (update-sessions cfg batch))]
          (mtx/run! metrics {:id :session-update-total :inc 1})
          (cond
            (ex/exception? result)
            (l/error :task "updater"
                     :hint "unexpected error on update sessions"
                     :cause result)

            (= :size reason)
            (l/debug :task "updater"
                     :hint "update sessions"
                     :reason (name reason)
                     :count result))

          (recur))))))

(defn- update-sessions
  [{:keys [pool executor]} ids]
  (aa/with-thread executor
    (db/exec-one! pool ["update http_session set updated_at=now() where id = ANY(?)"
                        (into-array String ids)])
    (count ids)))

;; --- STATE INIT: SESSION GC

(declare sql:delete-expired)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-task [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::max-age]))

(defmethod ig/prep-key ::gc-task
  [_ cfg]
  (merge {:max-age (dt/duration {:days 15})}
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
