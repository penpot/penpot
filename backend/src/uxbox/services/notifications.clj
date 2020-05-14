;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.notifications
  "A websocket based notifications mechanism."
  (:require
   [clojure.core.async :as a :refer [>! <!]]
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [ring.adapter.jetty9 :as jetty]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.uuid :as uuid]
   [uxbox.redis :as redis]
   [uxbox.db :as db]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Throwable e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))

(defmacro thread-try
  [& body]
  `(a/thread
     (try
       ~@body
       (catch Throwable e#
         e#))))

;; --- Redis Interactions

(defn- publish
  [channel message]
  (go-try
   (let [message (t/encode-str message)]
     (<? (redis/run :publish {:channel (str channel)
                              :message message})))))

(def ^:private
  sql:retrieve-presence
  "select * from presence
    where file_id=?
      and (clock_timestamp() - updated_at) < '5 min'::interval")

(defn- retrieve-presence
  [file-id]
  (thread-try
   (let [rows (db/exec! db/pool [sql:retrieve-presence file-id])]
     (mapv (juxt :session-id :profile-id) rows))))

(def ^:private
  sql:update-presence
  "insert into presence (file_id, session_id, profile_id, updated_at)
   values (?, ?, ?, clock_timestamp())
       on conflict (file_id, session_id, profile_id)
       do update set updated_at=clock_timestamp()")

(defn- update-presence
  [file-id session-id profile-id]
  (thread-try
   (let [now (dt/now)
         sql [sql:update-presence file-id session-id profile-id]]
     (db/exec-one! db/pool sql))))

(defn- delete-presence
  [file-id session-id profile-id]
  (thread-try
   (db/delete! db/pool :presence {:file-id file-id
                                  :profile-id profile-id
                                  :session-id session-id})))

;; --- WebSocket Messages Handling

(defmulti handle-message
  (fn [ws message] (:type message)))

;; TODO: check permissions for join a file-id channel (probably using
;; single use token for avoid explicit database query).

(defmethod handle-message :connect
  [{:keys [file-id profile-id session-id output] :as ws} message]
  (log/info (str "profile " profile-id " is connected to " file-id))
  (go-try
   (<? (update-presence file-id session-id profile-id))
   (let [members (<? (retrieve-presence file-id))]
     (<? (publish file-id {:type :presence :sessions members})))))

(defmethod handle-message :disconnect
  [{:keys [profile-id file-id session-id] :as ws} message]
  (log/info (str "profile " profile-id " is disconnected from " file-id))
  (go-try
   (<? (delete-presence file-id session-id profile-id))
   (let [members (<? (retrieve-presence file-id))]
     (<? (publish file-id {:type :presence :sessions members})))))

(defmethod handle-message :keepalive
  [{:keys [profile-id file-id session-id] :as ws} message]
  (update-presence file-id session-id profile-id))

(defmethod handle-message :pointer-update
  [{:keys [profile-id file-id session-id] :as ws} message]
  (let [message (assoc message
                       :profile-id profile-id
                       :session-id session-id)]
    (publish file-id message)))

(defmethod handle-message :default
  [ws message]
  (a/go
    (log/warn (str "received unexpected message: " message))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- forward-message
  [{:keys [out session-id profile-id] :as ws} message]
  (go-try
   (when-not (= (:session-id message) session-id)
     (>! out message))))

(defn start-loop!
  [{:keys [in out sub] :as ws}]
  (go-try
   (loop []
     (let [timeout (a/timeout 30000)
           [val port] (a/alts! [in sub timeout])]
       ;; (prn "alts" val "from" (cond (= port in)  "input"
       ;;                              (= port sub) "redis"
       ;;                              :else "timeout"))

       (cond
         ;; Process message coming from connected client
         (and (= port in) (not (nil? val)))
         (do
           (<? (handle-message ws val))
           (recur))

         ;; Forward message to the websocket
         (and (= port sub) (not (nil? val)))
         (do
           (<? (forward-message ws val))
           (recur))

         ;; Timeout channel signaling
         (= port timeout)
         (do
           (>! out {:type :ping})
           (recur))

         :else
         nil)))))

(defn disconnect!
  [conn]
  (let [session (.getSession conn)]
    (when session
      (.disconnect session))))

(defn- on-subscribed
  [{:keys [conn] :as ws}]
  (a/go
    (try
      (<? (handle-message ws {:type :connect}))
      (<? (start-loop! ws))
      (<? (handle-message ws {:type :disconnect}))
      (catch Throwable err
        (log/error "Unexpected exception on websocket handler:\n"
                   (with-out-str
                     (.printStackTrace err (java.io.PrintWriter. *out*))))
        (disconnect! conn)))))

(defrecord WebSocket [conn in out sub])

(defn- start-rcv-loop!
  [{:keys [conn out] :as ws}]
  (a/go-loop []
    (let [val (a/<! out)]
      (when-not (nil? val)
        (jetty/send! conn (t/encode-str val))
        (recur)))))

(defn websocket
  [{:keys [file-id] :as params}]
  (let [in  (a/chan 32)
        out (a/chan 32)]
    {:on-connect (fn [conn]
                   (let [xf  (map t/decode-str)
                         sub (redis/subscribe (str file-id) xf)
                         ws  (WebSocket. conn in out sub nil params)]
                     (start-rcv-loop! ws)
                     (a/go
                       (a/<! (on-subscribed ws))
                       (a/close! sub))))

     :on-error (fn [conn e]
                 ;; (prn "websocket" :on-error e)
                 (a/close! out)
                 (a/close! in))

     :on-close (fn [conn status-code reason]
                 ;; (prn "websocket" :on-close status-code reason)
                 (a/close! out)
                 (a/close! in))

     :on-text (fn [ws message]
                (let [message (t/decode-str message)]
                  ;; (prn "websocket" :on-text message)
                  (a/>!! in message)))

     :on-bytes (fn [ws bytes offset len]
                 #_(prn "websocket" :on-bytes bytes))}))


