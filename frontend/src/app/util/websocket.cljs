;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.websocket
  "A interface to webworkers exposed functionality."
  (:require
   [app.common.transit :as t]
   [beicon.v2.core :as rx]
   [goog.events :as ev])
  (:import
   goog.net.WebSocket
   goog.net.WebSocket.EventType))

(defprotocol IWebSocket
  (-stream [_] "Retrieve the message stream")
  (-send [_ message] "send a message")
  (-close [_] "close websocket")
  (-open? [_] "check if the channel is open"))

(defn create
  [uri]
  (let [sb   (rx/subject)
        ws   (WebSocket. #js {:autoReconnect true})
        data (atom {})
        lk1  (ev/listen ws EventType.MESSAGE
                        #(rx/push! sb {:type :message :payload (.-message %)}))
        lk2  (ev/listen ws EventType.ERROR
                        #(rx/push! sb {:type :error :payload %}))
        lk3  (ev/listen ws EventType.OPENED
                        #(rx/push! sb {:type :opened :payload %}))]

    (.open ws (str uri))
    (reify
      IDeref
      (-deref [_] (-deref data))

      IReset
      (-reset! [_ newval]
        (-reset! data newval))

      ISwap
      (-swap! [_ f]
        (-swap! data f))
      (-swap! [_ f x]
        (-swap! data f x))
      (-swap! [_ f x y]
        (-swap! data f x y))
      (-swap! [_ f x y more]
        (-swap! data f x y more))

      IWatchable
      (-notify-watches [_ oldval newval]
        (-notify-watches data oldval newval))

      (-add-watch [_ key f]
        (-add-watch data key f))

      (-remove-watch [_ key]
        (-remove-watch data key))

      IHash
      (-hash [_] (goog/getUid ws))

      IWebSocket
      (-stream [_]
        (->> sb
             (rx/map (fn [{:keys [type payload] :as message}]
                       (cond-> message
                         (= :message type)
                         (assoc :payload (t/decode-str payload)))))))

      (-send [_ msg]
        (when (.isOpen ^js ws)
          (.send ^js ws msg)))

      (-open? [_]
        (.isOpen ^js ws))

      (-close [_]
        (rx/end! sb)
        (ev/unlistenByKey lk1)
        (ev/unlistenByKey lk2)
        (ev/unlistenByKey lk3)
        (.close ^js ws)
        (.dispose ^js ws)))))

(defn message-event?
  ^boolean
  [msg]
  (= (:type msg) :message))

(defn error-event?
  ^boolean
  [msg]
  (= (:type msg) :error))

(defn opened-event?
  ^boolean
  [msg]
  (= (:type msg) :opened))

(defn send!
  [ws msg]
  (if *assert*
    (-send ws (t/encode-str msg {:type :json-verbose}))
    (-send ws (t/encode-str msg))))

(defn close!
  [ws]
  (-close ws))

(defn open?
  [ws]
  (-open? ws))

(defn get-rcv-stream
  [ws]
  (-stream ws))
