;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.websockets
  "A interface to webworkers exposed functionality."
  (:require
   [app.config :as cfg]
   [app.util.transit :as t]
   [beicon.core :as rx]
   [goog.events :as ev]
   [potok.core :as ptk])
  (:import
   goog.Uri
   goog.net.WebSocket
   goog.net.WebSocket.EventType))

(defprotocol IWebSocket
  (-stream [_] "Retrienve the message stream")
  (-send [_ message] "send a message")
  (-close [_] "close websocket"))

(defn uri
  ([path] (uri path {}))
  ([path params]
   (let [uri (.parse ^js Uri cfg/public-uri)]
     (.setPath ^js uri path)
     (if (= (.getScheme ^js uri) "http")
       (.setScheme ^js uri "ws")
       (.setScheme ^js uri "wss"))
     (run! (fn [[k v]]
             (.setParameterValue ^js uri (name k) (str v)))
           params)
     (.toString uri))))

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
    (.open ws uri)
    (reify
      cljs.core/IDeref
      (-deref [_] ws)

      IWebSocket
      (-stream [_] sb)
      (-send [_ msg]
        (when (.isOpen ^js ws)
          (.send ^js ws msg)))
      (-close [_]
        (.close ws)
        (rx/end! sb)
        (ev/unlistenByKey lk1)
        (ev/unlistenByKey lk2)
        (ev/unlistenByKey lk3)))))

(defn message?
  [msg]
  (= (:type msg) :message))

(defn send!
  [ws msg]
  (-send ws (t/encode msg)))
