;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.ws
  "Web Socket handlers"
  (:require
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.blob :as blob]
   [uxbox.util.transit :as t]
   [uxbox.util.uuid :as uuid]
   [vertx.eventbus :as ve]
   [vertx.http :as vh]
   [vertx.util :as vu]
   [vertx.timers :as vt]
   [vertx.web :as vw]
   [vertx.web.websockets :as ws])
  (:import
   java.lang.AutoCloseable
   io.vertx.core.Handler
   io.vertx.core.Promise
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket))

;; --- State Management

(defonce state
  (atom {}))

(defn send!
  [ws message]
  (ws/send! ws (-> (t/encode message)
                   (t/bytes->str))))

(defmulti handle-message
  (fn [ws message] (:type message)))

(defmethod handle-message :connect
  [{:keys [file-id user-id] :as ws} message]
  (let [local (swap! state assoc-in [file-id user-id] ws)
        sessions (get local file-id)
        message {:type :who :users (set (keys sessions))}]
    (run! #(send! % message) (vals sessions))))

(defmethod handle-message :disconnect
  [{:keys [user-id] :as ws} {:keys [file-id] :as message}]
  (let [local (swap! state update file-id dissoc user-id)
        sessions (get local file-id)
        message {:type :who :users (set (keys sessions))}]
    (run! #(send! % message) (vals sessions))))

(defmethod handle-message :who
  [{:keys [file-id] :as ws} message]
  (let [users (keys (get @state file-id))]
    (send! ws {:type :who :users (set users)})))

(defmethod handle-message :pointer-update
  [{:keys [user-id file-id] :as ws} message]
  (let [sessions (->> (vals (get @state file-id))
                      (remove #(= user-id (:user-id %))))
        message (assoc message :user-id user-id)]
    (run! #(send! % message) sessions)))

(defn- on-eventbus-message
  [{:keys [file-id user-id] :as ws} {:keys [body] :as message}]
  (send! ws body))

(defn- start-eventbus-consumer!
  [vsm ws fid]
  (let [topic (str "internal.uxbox.file." fid)]
    (ve/consumer vsm topic #(on-eventbus-message ws %2))))

;; --- Handler

(defn- on-init
  [req ws]
  (let [ctx (vu/current-context)
        file-id (get-in req [:path-params :file-id])
        user-id (:user req)
        ws (assoc ws
                  :user-id user-id
                  :file-id file-id)
        send-ping #(send! ws {:type :ping})
        sem1 (start-eventbus-consumer! ctx ws file-id)
        sem2 (vt/schedule-periodic! ctx 30000 send-ping)]
    (handle-message ws {:type :connect})
    (assoc ws ::sem1 sem1 ::sem2 sem2)))

(defn- on-text-message
  [ws message]
  (->> (t/str->bytes message)
       (t/decode)
       (handle-message ws)))

(defn- on-close
  [ws]
  (let [file-id (:file-id ws)]
    (handle-message ws {:type :disconnect
                        :file-id file-id})
    (.close ^AutoCloseable (::sem1 ws))
    (.close ^AutoCloseable (::sem2 ws))))

(defn handler
  [{:keys [user] :as req}]
  (ws/websocket :on-init (partial on-init req)
                :on-text-message on-text-message
                ;; :on-error on-error
                :on-close on-close))

