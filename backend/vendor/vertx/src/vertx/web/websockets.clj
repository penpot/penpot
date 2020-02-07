;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.websockets
  "Web Sockets."
  (:require
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.impl :as vi]
   [vertx.util :as vu]
   [vertx.eventbus :as ve])
  (:import
   java.lang.AutoCloseable
   io.vertx.core.Future
   io.vertx.core.Promise
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket))

(defprotocol IWebSocket
  (send! [it message]))

(defrecord WebSocket [conn]
  AutoCloseable
  (close [it]
    (.close ^ServerWebSocket conn))

  IWebSocket
  (send! [it message]
    (let [d (p/deferred)]
      (cond
        (string? message)
        (.writeTextMessage ^ServerWebSocket conn
                           ^String message
                           ^Handler (vi/deferred->handler d))

        (instance? Buffer message)
        (.writeBinaryMessage ^ServerWebSocket conn
                             ^Buffer message
                             ^Handler (vi/deferred->handler d))

        :else
        (p/reject! (ex-info "invalid message type" {:message message})))
      d)))

(defn- default-on-error
  [ws err]
  (log/error "Unexpected exception on websocket handler:\n"
             (with-out-str
               (.printStackTrace err (java.io.PrintWriter. *out*))))
  (.close ^AutoCloseable ws))

(defrecord WebSocketResponse [on-init on-text-message on-error on-close]
  vh/IAsyncResponse
  (-handle-response [it ctx]
    (let [^HttpServerRequest req (::vh/request ctx)
          ^ServerWebSocket conn (.upgrade req)

          wsref (volatile! (->WebSocket conn))

          impl-on-error (fn [e] (on-error @wsref e))
          impl-on-close (fn [_] (on-close @wsref))

          impl-on-message
          (fn [message]
            (-> (p/do! (on-text-message @wsref message))
                (p/finally (fn [res err]
                             (if err
                               (impl-on-error err)
                               (do
                                 (.fetch conn 1)
                                 (when (instance? WebSocket res)
                                   (vreset! wsref res))))))))]

      (-> (p/do! (on-init @wsref))
          (p/finally (fn [data error]
                       (cond
                         (not (nil? error))
                         (impl-on-error error)

                         (instance? WebSocket data)
                         (do
                           (vreset! wsref data)
                           (.exceptionHandler conn (vi/fn->handler impl-on-error))
                           (.textMessageHandler conn (vi/fn->handler impl-on-message))
                           (.closeHandler conn (vi/fn->handler impl-on-close)))

                         :else
                         (.reject conn)))))
      nil)))

(defn websocket
  [& {:keys [on-init on-text-message on-error on-close]
      :or {on-error default-on-error}}]
  (->WebSocketResponse on-init on-text-message on-error on-close))
