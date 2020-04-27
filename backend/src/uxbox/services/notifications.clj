;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.notifications
  "A websocket based notifications mechanism."
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :as a :refer [>! <!]]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.transit :as t]
   [uxbox.redis :as redis]
   [uxbox.common.uuid :as uuid]
   [vertx.util :as vu :refer [<?]]))

(defn- decode-message
  [message]
  (->> (t/str->bytes message)
       (t/decode)))

(defn- encode-message
  [message]
  (->> (t/encode message)
       (t/bytes->str)))

;; --- Redis Interactions

(defn- publish
  [channel message]
  (vu/go-try
   (let [message (encode-message message)]
     (<? (redis/run :publish {:channel (str channel)
                              :message message})))))

(defn- retrieve-presence
  [key]
  (vu/go-try
   (let [data (<? (redis/run :hgetall {:key key}))]
     (into [] (map (fn [[k v]] [(uuid/uuid k) (uuid/uuid v)])) data))))

(defn- join-room
  [file-id session-id profile-id]
  (let [key (str file-id)
        field (str session-id)
        value (str profile-id)]
    (vu/go-try
     (<? (redis/run :hset {:key key :field field :value value}))
     (<? (retrieve-presence key)))))

(defn- leave-room
  [file-id session-id profile-id]
  (let [key (str file-id)
        field (str session-id)]
    (vu/go-try
     (<? (redis/run :hdel {:key key :field field}))
     (<? (retrieve-presence key)))))

;; --- WebSocket Messages Handling

(defmulti handle-message
  (fn [ws message] (:type message)))

;; TODO: check permissions for join a file-id channel (probably using
;; single use token for avoid explicit database query).

(defmethod handle-message :connect
  [{:keys [file-id profile-id session-id output] :as ws} message]
  (log/info (str "profile " profile-id " is connected to " file-id))
  (vu/go-try
   (let [members (<? (join-room file-id session-id profile-id))]
     (<? (publish file-id {:type :presence :sessions  members})))))

(defmethod handle-message :disconnect
  [{:keys [profile-id file-id session-id] :as ws} message]
  (log/info (str "profile " profile-id " is disconnected from " file-id))
  (vu/go-try
   (let [members (<? (leave-room file-id session-id profile-id))]
     (<? (publish file-id {:type :presence :sessions members})))))

(defmethod handle-message :default
  [ws message]
  (a/go
    (log/warn (str "received unexpected message: " message))))

(defmethod handle-message :pointer-update
  [{:keys [profile-id file-id session-id] :as ws} message]
  (vu/go-try
   (let [message (assoc message
                        :profile-id profile-id
                        :session-id session-id)]
     (<? (publish file-id message)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- process-message
  [ws message]
  (vu/go-try
   (let [message (decode-message message)]
     (<? (handle-message ws message)))))

(defn- forward-message
  [{:keys [output session-id profile-id] :as ws} message]
  (vu/go-try
   (let [message' (decode-message message)]
     (when-not (= (:session-id message') session-id)
       (>! output message)))))

(defn- close-all!
  [{:keys [sch] :as ws}]
  (a/close! sch)
  (.close ^java.lang.AutoCloseable ws))

(defn start-loop!
  [{:keys [input output sch on-error] :as ws}]
  (vu/go-try
   (loop []
     (let [timeout (a/timeout 30000)
           [val port] (a/alts! [input sch timeout])]
       ;; (prn "alts" val "from" (cond (= port input) "input"
       ;;                              (= port sch)   "redis"
       ;;                              :else "timeout"))

       (cond
         ;; Process message coming from connected client
         (and (= port input) (not (nil? val)))
         (do
           (<? (process-message ws val))
           (recur))

         ;; Forward message to the websocket
         (and (= port sch) (not (nil? val)))
         (do
           (<? (forward-message ws val))
           (recur))

         ;; Timeout channel signaling
         (= port timeout)
         (do
           (>! output (encode-message {:type :ping}))
           (recur))

         :else
         nil)))))

(defn- on-subscribed
  [{:keys [on-error] :as ws} sch]
  (let [ws (assoc ws :sch sch)]
    (a/go
      (try
        (<? (handle-message ws {:type :connect}))
        (<? (start-loop! ws))
        (<? (handle-message ws {:type :disconnect}))
        (close-all! ws)
        (catch Throwable e
          (on-error e)
          (close-all! ws))))))

(defn websocket
  [req {:keys [input on-error] :as ws}]
  (let [fid (uuid/uuid (get-in req [:path-params :file-id]))
        sid (uuid/uuid (get-in req [:path-params :session-id]))
        pid (:profile-id req)
        ws  (assoc ws
                   :profile-id pid
                   :file-id fid
                   :session-id sid)]
    (-> (redis/subscribe (str fid))
        (p/finally (fn [sch error]
                     (if error
                       (on-error error)
                       (on-subscribed ws sch)))))))
