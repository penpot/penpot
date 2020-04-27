;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web.websockets
  "Web Sockets."
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :as a]
   [promesa.core :as p]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.impl :as vi]
   [vertx.util :as vu]
   [vertx.eventbus :as ve])
  (:import
   java.lang.AutoCloseable
   io.vertx.core.AsyncResult
   io.vertx.core.Promise
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket))

(defrecord WebSocket [conn input output on-error]
  AutoCloseable
  (close [it]
    (a/close! input)
    (a/close! output)
    (.close ^ServerWebSocket conn (short 403))))

(defn- write-to-websocket
  [conn on-error message]
  (let [r (a/chan 1)
        h (reify Handler
            (handle [_ ar]
              (if (.failed ^AsyncResult ar)
                (a/put! r (.cause ^AsyncResult ar))
                (a/close! r))))]

    (cond
      (string? message)
      (.writeTextMessage ^ServerWebSocket conn
                         ^String message
                         ^Handler h)

      (instance? Buffer message)
      (.writeBinaryMessage ^ServerWebSocket conn
                           ^Buffer message
                           ^Handler h)

      :else
      (a/put! r (ex-info "invalid message type" {:message message})))
    r))

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

            inp-s (a/chan input-buffer-size)
            out-s (a/chan output-buffer-size)

            ctx (vu/current-context)
            ws  (->WebSocket conn inp-s out-s on-error)

            impl-on-error
            (fn [err]
              (.close ^AutoCloseable ws)
              (on-error err))

            impl-on-close
            (fn [_]
              (a/close! inp-s)
              (a/close! out-s))

            impl-on-message
            (fn [message]
              (when-not (a/offer! inp-s message)
                (.pause conn)
                (a/put! inp-s message
                        (fn [res]
                          (when-not (false? res)
                            (.resume conn))))))]

        (.exceptionHandler conn ^Handler (vi/fn->handler impl-on-error))
        (.textMessageHandler conn ^Handler (vi/fn->handler impl-on-message))
        (.closeHandler conn ^Handler (vi/fn->handler impl-on-close))

        (a/go-loop []
          (let [msg (a/<! out-s)]
            (when-not (nil? msg)
              (let [res (a/<! (write-to-websocket conn on-error msg))]
                (if (instance? Throwable res)
                  (impl-on-error res)
                  (recur))))))

        (vu/run-on-context! ctx #(handler ws))))))
