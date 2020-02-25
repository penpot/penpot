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
   [vertx.stream :as vs]
   [vertx.eventbus :as ve])
  (:import
   java.lang.AutoCloseable
   io.vertx.core.Promise
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket))

(defrecord WebSocket [conn input output]
  AutoCloseable
  (close [it]
    (vs/close! input)
    (vs/close! output)))

(defn- write-to-websocket
  [conn message]
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
    d))

(defn- default-on-error
  [^Throwable err]
  (log/error "Unexpected exception on websocket handler:\n"
             (with-out-str
               (.printStackTrace err (java.io.PrintWriter. *out*)))))

(defn websocket
  [{:keys [handler on-error
           input-buffer-size
           output-buffer-size]
    :or {on-error default-on-error
         input-buffer-size 64
         output-buffer-size 64}}]
  (reify
    vh/IAsyncResponse
    (-handle-response [it request]
      (let [^HttpServerRequest req (::vh/request request)
            ^ServerWebSocket conn (.upgrade req)

            inp-s (vs/stream input-buffer-size)
            out-s (vs/stream output-buffer-size)

            ctx (vu/current-context)
            ws  (->WebSocket conn inp-s out-s)

            impl-on-error
            (fn [err]
              (.close ^AutoCloseable ws)
              (on-error err))

            impl-on-close
            (fn [_]
              (vs/close! inp-s)
              (vs/close! out-s))

            impl-on-message
            (fn [message]
              (when-not (vs/offer! inp-s message)
                (.pause conn)
                (-> (vs/put! inp-s message)
                    (p/then' (fn [res]
                               (when-not (false? res)
                                 (.resume conn)))))))]

        (.exceptionHandler conn ^Handler (vi/fn->handler impl-on-error))
        (.textMessageHandler conn ^Handler (vi/fn->handler impl-on-message))
        (.closeHandler conn ^Handler (vi/fn->handler impl-on-close))

        (vs/loop []
          (p/let [msg (vs/take! out-s)]
            (when-not (nil? msg)
              (-> (write-to-websocket conn msg)
                  (p/then' (fn [_] (p/recur)))
                  (p/catch' (fn [err]
                              (on-error err)
                              (p/recur)))))))

        (vu/run-on-context! ctx #(handler ws))))))
