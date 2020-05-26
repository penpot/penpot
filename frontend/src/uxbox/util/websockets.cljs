;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.websockets
  "A interface to webworkers exposed functionality."
  (:require
   [goog.events :as ev]
   [uxbox.config :as cfg]
   [beicon.core :as rx]
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
   (let [uri (.parse Uri cfg/backend-uri)]
     (.setPath uri path)
     (if (= (.getScheme uri) "http")
       (.setScheme uri "ws")
       (.setScheme uri "wss"))
     (run! (fn [[k v]]
             (.setParameterValue uri (name k) (str v)))
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
        (when (.isOpen ws)
          (.send ws msg)))
      (-close [_]
        (.close ws)
        (rx/end! sb)
        (ev/unlistenByKey lk1)
        (ev/unlistenByKey lk2)
        (ev/unlistenByKey lk3)))))
