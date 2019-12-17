;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.ws
  "Web Socket handlers"
  (:require
   [promesa.core :as p]
   [uxbox.emails :as emails]
   [uxbox.http.session :as session]
   [uxbox.services.init]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries :as sq]
   [uxbox.util.uuid :as uuid]
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
(declare ws-on-message!)
(declare ws-on-close!)

;; --- Public API

(declare on-message)
(declare on-close)
(declare on-eventbus-message)

(def state (atom {}))

(defn handler
  [{:keys [user] :as req}]
  (letfn [(on-init [ws]
            (let [vsm (::vw/execution-context req)
                  tpc "test.foobar"
                  pid (get-in req [:path-params :page-id])
                  sem (ve/consumer vsm tpc #(on-eventbus-message ws %2))]
              (swap! state update pid (fnil conj #{}) user)
              (assoc ws ::sem sem)))

          (on-message [ws message]
            (let [pid (get-in req [:path-params :page-id])]
              (ws-send! ws (str (::counter ws 0)))
              (update ws ::counter (fnil inc 0))))

          (on-close [ws]
            (let [pid (get-in req [:path-params :page-id])]
              (swap! state update pid disj user)
              (.unregister (::sem ws))))]

    ;; (ws-websocket :on-init on-init
    ;;               :on-message on-message
    ;;               :on-close on-close)))

    (-> (ws-websocket)
        (assoc :on-init on-init
               :on-message on-message
               :on-close on-close))))

(defn- on-eventbus-message
  [ws {:keys [body] :as message}]
  (ws-send! ws body))

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
