;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.websockets
  "A interface to webworkers exposed functionality."
  (:require
   [app.common.transit :as t]
   [beicon.core :as rx]
   [goog.events :as ev])
  (:import
   goog.net.WebSocket
   goog.net.WebSocket.EventType))

(defprotocol IWebSocket
  (-stream [_] "Retrieve the message stream")
  (-send [_ message] "send a message")
  (-close [_] "close websocket"))

(defn open
  [uri]
  (let [sb (rx/subject)
        ws (WebSocket. #js {:autoReconnect true})
        lk1 (ev/listen ws EventType.MESSAGE
                       #(rx/push! sb {:type :message :payload (.-message %)}))
        lk2 (ev/listen ws EventType.ERROR
                       #(rx/push! sb {:type :error :payload %}))
        lk3 (ev/listen ws EventType.OPENED
                       #(rx/push! sb {:type :opened :payload %}))]
    (.open ws (str uri))
    (reify
      cljs.core/IDeref
      (-deref [_] ws)

      IWebSocket
      (-stream [_] sb)
      (-send [_ msg]
        (when (.isOpen ^js ws)
          (.send ^js ws msg)))
      (-close [_]
        (rx/end! sb)
        (ev/unlistenByKey lk1)
        (ev/unlistenByKey lk2)
        (ev/unlistenByKey lk3)
        (.close ^js ws)
        (.dispose ^js ws)))))


(defn message?
  [msg]
  (= (:type msg) :message))

(defn send!
  [ws msg]
  (-send ws (t/encode-str msg)))
