;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.websocket
  "A general protocol implementation on top of websockets."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.loggers.audit :refer [parse-client-ip]]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [yetti.request :as yr]
   [yetti.util :as yu]
   [yetti.websocket :as yws])
  (:import
   java.nio.ByteBuffer))

(declare decode-beat)
(declare encode-beat)
(declare start-io-loop)
(declare ws-ping!)
(declare ws-send!)
(declare filter-options)

(def noop (constantly nil))
(def identity-3 (fn [_ _ o] o))

(defn handler
  "A WebSocket upgrade handler factory. Returns a handler that can be
  used to upgrade to websocket connection. This handler implements the
  basic custom protocol on top of websocket connection with all the
  borring stuff already handled (lifecycle, heartbeat,...).

  The provided function should have the `(fn [ws msg])` signature.

  It also accepts some options that allows you parametrize the
  protocol behavior. The options map will be used as-as for the
  initial data of the `ws` data structure"
  [& {:keys [::on-rcv-message
             ::on-snd-message
             ::on-connect
             ::input-buff-size
             ::output-buff-size
             ::handler
             ::idle-timeout]
      :or {input-buff-size 64
           output-buff-size 64
           idle-timeout 60000
           on-connect noop
           on-snd-message identity-3
           on-rcv-message identity-3}
      :as options}]

  (assert (fn? on-rcv-message) "'on-rcv-message' should be a function")
  (assert (fn? on-snd-message) "'on-snd-message' should be a function")
  (assert (fn? on-connect) "'on-connect' should be a function")

  (fn [{:keys [::yws/channel] :as request}]
    (let [input-ch   (a/chan input-buff-size)
          output-ch  (a/chan output-buff-size)
          hbeat-ch   (a/chan (a/sliding-buffer 6))
          close-ch   (a/chan)
          stop-ch    (a/chan)

          ip-addr    (parse-client-ip request)
          uagent     (yr/get-header request "user-agent")
          id         (uuid/next)

          options    (-> (filter-options options)
                         (merge {::id id
                                 ::created-at (dt/now)
                                 ::input-ch input-ch
                                 ::heartbeat-ch hbeat-ch
                                 ::output-ch output-ch
                                 ::close-ch close-ch
                                 ::stop-ch stop-ch
                                 ::channel channel
                                 ::remote-addr ip-addr
                                 ::user-agent uagent})
                         (atom))

          ;; call the on-connect hook and memoize the on-terminate instance
          on-terminate (on-connect options)

          on-ws-open
          (fn [channel]
            (l/trace :fn "on-ws-open" :conn-id id)
            (yws/idle-timeout! channel (dt/duration idle-timeout)))

          on-ws-terminate
          (fn [_ code reason]
            (l/trace :fn "on-ws-terminate" :conn-id id :code code :reason reason)
            (a/close! close-ch))

          on-ws-error
          (fn [_ error]
            (when-not (or (instance? java.nio.channels.ClosedChannelException error)
                          (instance? java.net.SocketException error)
                          (instance? java.io.IOException error))
              (l/error :fn "on-ws-error" :conn-id id
                       :hint (ex-message error)
                       :cause error))
            (on-ws-terminate nil 8801 "close after error"))

          on-ws-message
          (fn [_ message]
            (try
              (let [message (on-rcv-message options message)
                    message (t/decode-str message)]
                (a/offer! input-ch message)
                (swap! options assoc ::last-activity-at (dt/now)))
              (catch Throwable e
                (l/warn :hint "error on decoding incoming message from websocket"
                        :wsmsg (pr-str message)
                        :cause e)
                (a/>! close-ch [8802 "decode error"])
                (a/close! close-ch))))

          on-ws-pong
          (fn [_ buffers]
            (a/>!! hbeat-ch (yu/copy-many buffers)))]

      ;; Wait a close signal
      (a/go
        (let [[code reason] (a/<! close-ch)]
          (a/close! stop-ch)
          (a/close! hbeat-ch)
          (a/close! output-ch)
          (a/close! input-ch)

          (when (and code reason)
            (l/trace :hint "close channel condition" :code code :reason reason)
            (yws/close! channel code reason))

          (when (fn? on-terminate)
            (on-terminate))

          (l/trace :hint "connection terminated")))

      ;; React on messages received from the client
      (a/go
        (a/<! (start-io-loop options handler on-snd-message on-ws-terminate))
        (l/trace :hint "io loop terminated"))

      {:on-open on-ws-open
       :on-error on-ws-error
       :on-close on-ws-terminate
       :on-text on-ws-message
       :on-pong on-ws-pong})))

(defn- ws-send!
  [channel s]
  (let [ch (a/chan 1)]
    (try
      (yws/send! channel s (fn [e]
                             (when e (a/offer! ch e))
                             (a/close! ch)))
      (catch Throwable cause
        (a/offer! ch cause)
        (a/close! ch)))
    ch))

(defn- ws-ping!
  [channel s]
  (let [ch (a/chan 1)]
    (try
      (yws/ping! channel s (fn [e]
                             (when e (a/offer! ch e))
                             (a/close! ch)))
      (catch Throwable cause
        (a/offer! ch cause)
        (a/close! ch)))
    ch))

(defn- encode-beat
  [n]
  (doto (ByteBuffer/allocate 8)
    (.putLong n)
    (.rewind)))

(defn- decode-beat
  [^ByteBuffer buffer]
  (when (= 8 (.capacity buffer))
    (.rewind buffer)
    (.getLong buffer)))

(defn- wrap-handler
  [handler]
  (fn [wsp message]
    (locking wsp
      (handler wsp message))))

(def max-missed-heartbeats 3)
(def heartbeat-interval 5000)

(defn- start-io-loop
  [wsp handler on-snd-message on-ws-terminate]
  (let [input-ch      (::input-ch @wsp)
        output-ch     (::output-ch @wsp)
        stop-ch       (::stop-ch @wsp)
        hbeat-pong-ch (::heartbeat-ch @wsp)
        channel       (::channel @wsp)
        conn-id       (::id @wsp)
        handler       (wrap-handler handler)
        beats         (atom #{})
        choices       [stop-ch
                       input-ch
                       output-ch
                       hbeat-pong-ch]]

    ;; Start IO loop
    (a/go
      (a/<! (handler wsp {:type :connect}))
      (a/<! (a/go-loop [i 0]
              (let [hbeat-ping-ch (a/timeout heartbeat-interval)
                    [v p]         (a/alts! (conj choices hbeat-ping-ch))]
                (cond
                  (not (yws/connected? channel))
                  (on-ws-terminate nil 8800 "channel disconnected")

                  (= p hbeat-ping-ch)
                  (do
                    (l/trace :hint "ping" :beat i :conn-id conn-id)
                    (a/<! (ws-ping! channel (encode-beat i)))
                    (let [issued (swap! beats conj (long i))]
                      (if (>= (count issued) max-missed-heartbeats)
                        (on-ws-terminate nil 8802 "heartbeat: timeout")
                        (recur (inc i)))))

                  (= p hbeat-pong-ch)
                  (let [beat (decode-beat v)]
                    (l/trace :hint "pong" :beat beat :conn-id conn-id)
                    (swap! beats disj beat)
                    (recur i))

                  (= p input-ch)
                  (let [result (a/<! (handler wsp v))]
                    ;; (l/trace :hint "message received" :message v)
                    (cond
                      (ex/error? result)
                      (a/>! output-ch {:type :error :error (ex-data result)})

                      (ex/exception? result)
                      (a/>! output-ch {:type :error :error {:message (ex-message result)}})

                      (map? result)
                      (a/>! output-ch (cond-> result (:request-id v) (assoc :request-id (:request-id v)))))
                    (recur i))

                  (= p output-ch)
                  (let [v (on-snd-message wsp v)]
                    ;; (l/trace :hint "writing message to output" :message v)
                    (a/<! (ws-send! channel (t/encode-str v)))
                    (recur i))))))

      (a/<! (handler wsp {:type :disconnect})))))

(defn- filter-options
  "Remove from options all namespace qualified keys that matches the
  current namespace."
  [options]
  (into {}
        (remove (fn [[key]]
                  (= (namespace key) "app.util.websocket")))
        options))
