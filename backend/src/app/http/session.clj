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
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.core.async :as a]
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
    (db/exec-one! conn ["select id, profile_id from http_session where id = ?" token])))

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
    (if-let [{:keys [id profile-id] :as session} (retrieve-from-request cfg request)]
      (let [ech (::events-ch cfg)]
        (a/>!! ech id)
        (l/update-thread-context! {:profile-id profile-id})
        (handler (assoc request :profile-id profile-id)))
      (handler request))))

;; --- STATE INIT: SESSION

(s/def ::cookie-name ::cfg/http-session-cookie-name)

(defmethod ig/pre-init-spec ::session [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::cookie-name]))

(defmethod ig/prep-key ::session
  [_ cfg]
  (merge {:cookie-name "auth-token"
          :buffer-size 64}
         (d/without-nils cfg)))

(defmethod ig/init-key ::session
  [_ {:keys [pool] :as cfg}]
  (let [events (a/chan (a/dropping-buffer (:buffer-size cfg)))
        cfg    (assoc cfg
                      :conn pool
                      ::events-ch events)]
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

(defmethod ig/halt-key! ::session
  [_ data]
  (a/close! (::events-ch data)))

;; --- STATE INIT: SESSION UPDATER

(declare batch-events)
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
  (let [input (batch-events cfg (::events-ch session))
        mcnt  (mtx/create
               {:name "http_session_update_total"
                :help "A counter of session update batch events."
                :registry (:registry metrics)
                :type :counter})]
    (a/go-loop []
      (when-let [[reason batch] (a/<! input)]
        (let [result (a/<! (update-sessions cfg batch))]
          (mcnt :inc)
          (if (ex/exception? result)
            (l/error :mod "updater"
                     :hint "unexpected error on update sessions"
                     :cause result)
            (l/debug :mod "updater"
                     :action "update sessions"
                     :reason (name reason)
                     :count result))
          (recur))))))

(defn- timeout-chan
  [cfg]
  (a/timeout (inst-ms (:max-batch-age cfg))))

(defn- batch-events
  [cfg in]
  (let [out (a/chan)]
    (a/go-loop [tch (timeout-chan cfg)
                buf #{}]
      (let [[val port] (a/alts! [tch in])]
        (cond
          (identical? port tch)
          (if (empty? buf)
            (recur (timeout-chan cfg) buf)
            (do
              (a/>! out [:timeout buf])
              (recur (timeout-chan cfg) #{})))

          (nil? val)
          (a/close! out)

          (identical? port in)
          (let [buf (conj buf val)]
            (if (>= (count buf) (:max-batch-size cfg))
              (do
                (a/>! out [:size buf])
                (recur (timeout-chan cfg) #{}))
              (recur tch buf))))))
    out))

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
  (merge {:max-age (dt/duration {:days 2})}
         (d/without-nils cfg)))

(defmethod ig/init-key ::gc-task
  [_ {:keys [pool max-age] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [interval (db/interval max-age)
            result   (db/exec-one! conn [sql:delete-expired interval])
            result   (:next.jdbc/update-count result)]
        (l/debug :mod "gc-task"
                 :action "clean http sessions"
                 :count result)
        result))))

(def ^:private
  sql:delete-expired
  "delete from http_session
    where updated_at < now() - ?::interval")
