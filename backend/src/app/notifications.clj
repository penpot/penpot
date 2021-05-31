;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.notifications
  "A websocket based notifications mechanism."
  (:require
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
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
  (s/keys :req-un [::msgbus ::db/pool ::session ::mtx/metrics ::wrk/executor]))

(defmethod ig/init-key ::handler
  [_ {:keys [session metrics] :as cfg}]
  (let [wrap-session    (:middleware session)

        mtx-active-connections
        (mtx/create
         {:name "websocket_active_connections"
          :registry (:registry metrics)
          :type :gauge
          :help "Active websocket connections."})

        mtx-messages
        (mtx/create
         {:name "websocket_message_total"
          :registry (:registry metrics)
          :labels ["op"]
          :type :counter
          :help "Counter of processed messages."})

        mtx-sessions
        (mtx/create
         {:name "websocket_session_timing"
          :registry (:registry metrics)
          :quantiles []
          :help "Websocket session timing (seconds)."
          :type :summary})

        cfg (assoc cfg
                   :mtx-active-connections mtx-active-connections
                   :mtx-messages mtx-messages
                   :mtx-sessions mtx-sessions
                   )]
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


;; --- WEBSOCKET INIT

(declare handle-connect)

(defn- ws-send
  [conn data]
  (try
    (when (jetty/connected? conn)
      (jetty/send! conn data)
      true)
    (catch java.lang.NullPointerException _e
      false)))

(defn websocket
  [{:keys [file-id team-id msgbus executor] :as cfg}]
  (let [rcv-ch       (a/chan 32)
        out-ch       (a/chan 32)
        mtx-aconn    (:mtx-active-connections cfg)
        mtx-messages (:mtx-messages cfg)
        mtx-sessions (:mtx-sessions cfg)
        created-at   (dt/now)
        ws-send      (mtx/wrap-counter ws-send mtx-messages ["send"])]

    (letfn [(on-connect [conn]
              (mtx-aconn :inc)
              ;; A subscription channel should use a lossy buffer
              ;; because we can't penalize normal clients when one
              ;; slow client is connected to the room.
              (let [sub-ch (a/chan (a/dropping-buffer 128))
                    cfg    (assoc cfg
                                  :conn conn
                                  :rcv-ch rcv-ch
                                  :out-ch out-ch
                                  :sub-ch sub-ch)]

                (l/trace :event "connect" :session (:session-id cfg))

                ;; Forward all messages from out-ch to the websocket
                ;; connection
                (a/go-loop []
                  (let [val (a/<! out-ch)]
                    (when (some? val)
                      (when (a/<! (aa/thread-call executor #(ws-send conn (t/encode-str val))))
                        (recur)))))

                (a/go
                  ;; Subscribe to corresponding topics
                  (a/<! (msgbus :sub {:topics [file-id team-id] :chan sub-ch}))
                  (a/<! (handle-connect cfg))

                  ;; when connection is closed
                  (mtx-aconn :dec)
                  (mtx-sessions :observe (/ (inst-ms (dt/diff created-at (dt/now))) 1000.0))

                  ;; close subscription
                  (a/close! sub-ch))))

            (on-error [_conn _e]
              (l/trace :event "error" :session (:session-id cfg))

              (a/close! out-ch)
              (a/close! rcv-ch))

            (on-close [_conn _status _reason]
              (l/trace :event "close" :session (:session-id cfg))

              (a/close! out-ch)
              (a/close! rcv-ch))

            (on-message [_ws message]
              (let [message (t/decode-str message)]
                (when-not (a/offer! rcv-ch message)
                  (l/warn :msg "drop messages"))))]

      {:on-connect on-connect
       :on-error on-error
       :on-close on-close
       :on-text (mtx/wrap-counter on-message mtx-messages ["recv"])
       :on-bytes (constantly nil)})))

;; --- CONNECTION INIT

(declare send-presence)
(declare handle-message)
(declare start-loop!)

(defn- handle-connect
  [cfg]
  (a/go
    (a/<! (handle-message cfg {:type :connect}))
    (a/<! (start-loop! cfg))
    (a/<! (handle-message cfg {:type :disconnect}))))

(defn- start-loop!
  [{:keys [rcv-ch out-ch sub-ch session-id] :as cfg}]
  (a/go-loop []
    (let [timeout    (a/timeout 30000)
          [val port] (a/alts! [rcv-ch sub-ch timeout])]
      (cond
        ;; Process message coming from connected client
        (and (= port rcv-ch) (some? val))
        (do
          (a/<! (handle-message cfg val))
          (recur))

        ;; Process message coming from pubsub.
        (and (= port sub-ch) (some? val))
        (do
          (when-not (= (:session-id val) session-id)
            ;; If we receive a connect message of other user, we need
            ;; to send an update presence to all participants.
            (when (= :connect (:type val))
              (a/<! (send-presence cfg :presence)))

            ;; Then, just forward the message
            (a/>! out-ch val))
          (recur))

        ;; When timeout channel is signaled, we need to send a ping
        ;; message to the output channel. TODO: we need to make this
        ;; more smart.
        (= port timeout)
        (do
          (a/>! out-ch {:type :ping})
          (recur))))))

(defn send-presence
  ([cfg] (send-presence cfg :presence))
  ([{:keys [msgbus session-id profile-id file-id]} type]
   (a/go
     (a/<! (msgbus :pub {:topic file-id
                         :message {:type type
                                   :session-id session-id
                                   :profile-id profile-id}})))))

;; --- INCOMING MSG PROCESSING

(defmulti handle-message
  (fn [_ message] (:type message)))

(defmethod handle-message :connect
  [cfg _]
  (send-presence cfg :connect))

(defmethod handle-message :disconnect
  [cfg _]
  (send-presence cfg :disconnect))

(defmethod handle-message :keepalive
  [_ _]
  (a/go :nothing))

(defmethod handle-message :pointer-update
  [{:keys [profile-id file-id session-id msgbus] :as cfg} message]
  (let [message (assoc message
                       :profile-id profile-id
                       :session-id session-id)]
    (msgbus :pub {:topic file-id
                  :message message})))

(defmethod handle-message :default
  [_ws message]
  (a/go
    (l/log :level :warn
           :msg "received unexpected message"
           :message message)))

