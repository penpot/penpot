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
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.transit :as t]
   [uxbox.util.blob :as blob]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.util :as vu]
   [vertx.eventbus :as ve])
  (:import
   io.vertx.core.Future
   io.vertx.core.Promise
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket))

(declare ws-websocket)
(declare ws-send!)

;; --- State Management

(defonce state
  (atom {}))

(defn send!
  [ws message]
  (ws-send! ws (-> (t/encode message)
                   (t/bytes->str))))

(defmulti handle-message
  (fn [ws message] (:type message)))

(defmethod handle-message :connect
  [ws {:keys [file-id user-id] :as message}]
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

;; --- Handler

(declare start-eventbus-consumer!)

(defn handler
  [{:keys [user] :as req}]
  (letfn [(on-init [ws]
            (let [vsm (::vw/execution-context req)
                  fid (get-in req [:path-params :file-id])
                  sem (start-eventbus-consumer! vsm ws fid)]

              (handle-message ws {:type :connect :file-id fid :user-id user})
              (assoc ws
                     ::sem sem
                     :user-id user
                     :file-id fid)))

          (on-message [ws message]
            (try
              (->> (t/str->bytes message)
                   (t/decode)
                   (handle-message ws))
              (catch Throwable err
                (log/error "Unexpected exception:\n"
                           (with-out-str
                             (.printStackTrace err (java.io.PrintWriter. *out*)))))))

          (on-close [ws]
            (let [fid (get-in req [:path-params :file-id])]
              (handle-message ws {:type :disconnect :file-id fid})
              (.unregister (::sem ws))))]

    (-> (ws-websocket)
        (assoc :on-init on-init
               :on-message on-message
               :on-close on-close))))

(defn- on-eventbus-message
  [ws {:keys [body] :as message}]
  ;; TODO
  (ws-send! ws body))

(defn- start-eventbus-consumer!
  [vsm ws fid]
  (let [topic (str "internal.uxbox.file." fid)]
    (ve/consumer vsm topic #(on-eventbus-message ws %2))))

;; --- Internal (vertx api) (experimental)

(defrecord WebSocket [on-init on-message on-close]
  vh/IAsyncResponse
  (-handle-response [this ctx]
    (let [^HttpServerRequest req (::vh/request ctx)
          ^ServerWebSocket ws (.upgrade req)
          local (volatile! (assoc this :ws ws))]
      (-> (p/do! (on-init @local))
          (p/then (fn [data]
                    (vreset! local data)
                    (.textMessageHandler ws (vu/fn->handler
                                             (fn [msg]
                                               (-> (p/do! (on-message @local msg))
                                                   (p/then (fn [data]
                                                             (when (instance? WebSocket data)
                                                               (vreset! local data))
                                                             (.fetch ws 1)))))))
                    (.closeHandler ws (vu/fn->handler (fn [& args] (on-close @local))))))))))

(defn ws-websocket
  []
  (->WebSocket nil nil nil))

(defn ws-send!
  [ws msg]
  (.writeTextMessage ^ServerWebSocket (:ws ws)
                     ^String msg))
