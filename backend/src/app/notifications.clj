;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.notifications
  "A websocket based notifications mechanism."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.transit :as t]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [ring.adapter.jetty9 :as jetty]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare retrieve-file)
(declare websocket)
(declare handler)

(s/def ::session map?)
(s/def ::msgbus fn?)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::msgbus ::db/pool ::session ::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ {:keys [session metrics] :as cfg}]
  (let [wrap-session    (:middleware session)

        mtx-active-connections
        (mtx/create
         {:name "websocket_notifications_active_connections"
          :registry (:registry metrics)
          :type :gauge
          :help "Active websocket connections on notifications service."})

        mtx-message-recv
        (mtx/create
         {:name "websocket_notifications_message_recv_timing"
          :registry (:registry metrics)
          :type :summary
          :help "Message receive summary timing (ms)."})

        mtx-message-send
        (mtx/create
         {:name "websocket_notifications_message_send_timing"
          :registry (:registry metrics)
          :type :summary
          :help "Message receive summary timing (ms)."})

        cfg (assoc cfg
                   :mtx-active-connections mtx-active-connections
                   :mtx-message-recv mtx-message-recv
                   :mtx-message-send mtx-message-send)]
    (-> #(handler cfg %)
        (wrap-session)
        (wrap-keyword-params)
        (wrap-cookies)
        (wrap-params))))

(s/def ::file-id ::us/uuid)
(s/def ::session-id ::us/uuid)

(s/def ::websocket-handler-params
  (s/keys :req-un [::file-id ::session-id]))

(defn- handler
  [{:keys [pool] :as cfg} {:keys [profile-id params] :as req}]
  (let [params (us/conform ::websocket-handler-params params)
        file   (retrieve-file pool (:file-id params))
        cfg    (merge cfg params
                      {:profile-id profile-id
                       :team-id (:team-id file)})]
    (cond
      (not profile-id)
      {:error {:code 403 :message "Authentication required"}}

      (not file)
      {:error {:code 404 :message "File does not exists"}}

      :else
      (websocket cfg))))

(def ^:private
  sql:retrieve-file
  "select f.id as id,
          p.team_id as team_id
     from file as f
     join project as p on (p.id = f.project_id)
    where f.id = ?")

(defn- retrieve-file
  [conn id]
  (db/exec-one! conn [sql:retrieve-file id]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket Http Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handle-connect)

(defrecord WebSocket [conn in out sub])

(defn- ws-send
  [conn data]
  (try
    (when (jetty/connected? conn)
      (jetty/send! conn data)
      true)
    (catch java.lang.NullPointerException _e
      false)))

(defn websocket
  [{:keys [file-id team-id msgbus] :as cfg}]
  (let [in  (a/chan 32)
        out (a/chan 32)
        mtx-active-connections (:mtx-active-connections cfg)
        mtx-message-send       (:mtx-message-send cfg)
        mtx-message-recv       (:mtx-message-recv cfg)

        ws-send (mtx/wrap-summary ws-send mtx-message-send)]

    (letfn [(on-connect [conn]
              (mtx-active-connections :inc)
              (let [sub (a/chan)
                    ws  (WebSocket. conn in out sub nil cfg)]

                ;; Subscribe to corresponding topics
                (a/<!! (msgbus :sub {:topic (str file-id) :chan sub}))
                (a/<!! (msgbus :sub {:topic (str team-id) :chan sub}))

                ;; message forwarding loop
                (a/go-loop []
                  (let [val (a/<! out)]
                    (when-not (nil? val)
                      (when (ws-send conn (t/encode-str val))
                        (recur)))))

                (a/go
                  (a/<! (handle-connect ws))
                  (a/close! sub))))

            (on-error [_conn _e]
              (a/close! out)
              (a/close! in))

            (on-close [_conn _status _reason]
              (mtx-active-connections :dec)
              (a/close! out)
              (a/close! in))

            (on-message [_ws message]
              (let [message (t/decode-str message)]
                (a/>!! in message)))]

      {:on-connect on-connect
       :on-error on-error
       :on-close on-close
       :on-text (mtx/wrap-summary on-message mtx-message-recv)
       :on-bytes (constantly nil)})))

(declare handle-message)
(declare start-loop!)

(defn- handle-connect
  [{:keys [conn] :as ws}]
  (a/go
    (try
      (aa/<? (handle-message ws {:type :connect}))
      (aa/<? (start-loop! ws))
      (aa/<? (handle-message ws {:type :disconnect}))
      (catch Throwable err
        (log/errorf err "Unexpected exception on websocket handler.")
        (let [session (.getSession conn)]
          (when session
            (.disconnect session)))))))

(defn- start-loop!
  [{:keys [in out sub session-id] :as ws}]
  (aa/go-try
   (loop []
     (let [timeout (a/timeout 30000)
           [val port] (a/alts! [in sub timeout])]

       (cond
         ;; Process message coming from connected client
         (and (= port in) (not (nil? val)))
         (do
           (aa/<? (handle-message ws val))
           (recur))

         ;; Forward message to the websocket
         (and (= port sub) (not (nil? val)))
         (do
           (when-not (= (:session-id val) session-id)
             (a/>! out val))
           (recur))

         ;; Timeout channel signaling
         (= port timeout)
         (do
           (a/>! out {:type :ping})
           (recur))

         :else
         nil)))))

;; Incoming Messages Handling

(def ^:private
  sql:retrieve-presence
  "select * from presence
    where file_id=?
      and (clock_timestamp() - updated_at) < '5 min'::interval")

(defn- retrieve-presence
  [pool file-id]
  (aa/thread-try
   (let [rows (db/exec! pool [sql:retrieve-presence file-id])]
     (mapv (juxt :session-id :profile-id) rows))))

(def ^:private
  sql:update-presence
  "insert into presence (file_id, session_id, profile_id, updated_at)
   values (?, ?, ?, clock_timestamp())
       on conflict (file_id, session_id, profile_id)
       do update set updated_at=clock_timestamp()")

(defn- update-presence
  [conn file-id session-id profile-id]
  (aa/thread-try
   (let [sql [sql:update-presence file-id session-id profile-id]]
     (db/exec-one! conn sql))))

(defn- delete-presence
  [pool file-id session-id profile-id]
  (aa/thread-try
   (db/delete! pool :presence {:file-id file-id
                               :profile-id profile-id
                               :session-id session-id})))

(defmulti handle-message
  (fn [_ message] (:type message)))

;; TODO: check permissions for join a file-id channel (probably using
;; single use token for avoid explicit database query).

(defmethod handle-message :connect
  [{:keys [file-id profile-id session-id pool msgbus] :as ws} _message]
  ;; (log/debugf "profile '%s' is connected to file '%s'" profile-id file-id)
  (aa/go-try
   (aa/<? (update-presence pool file-id session-id profile-id))
   (let [members (aa/<? (retrieve-presence pool file-id))]
     (aa/<? (msgbus :pub {:topic file-id
                          :message {:type :presence :sessions members}})))))

(defmethod handle-message :disconnect
  [{:keys [profile-id file-id session-id pool msgbus] :as ws} _message]
  ;; (log/debugf "profile '%s' is disconnected from '%s'" profile-id file-id)
  (aa/go-try
   (aa/<? (delete-presence pool file-id session-id profile-id))
   (let [members (aa/<? (retrieve-presence pool file-id))]
     (aa/<? (msgbus :pub {:topic file-id
                          :message {:type :presence :sessions members}})))))

(defmethod handle-message :keepalive
  [{:keys [profile-id file-id session-id pool] :as ws} _message]
  (update-presence pool file-id session-id profile-id))

(defmethod handle-message :pointer-update
  [{:keys [profile-id file-id session-id msgbus] :as ws} message]
  (let [message (assoc message
                       :profile-id profile-id
                       :session-id session-id)]
    (msgbus :pub {:topic file-id
                  :message message})))

(defmethod handle-message :default
  [_ws message]
  (a/go
    (log/warnf "received unexpected message: %s" message)))

