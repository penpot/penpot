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
(declare process-heartbeat)
(declare process-input)
(declare process-output)
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
           idle-timeout 30000
           on-connect noop
           on-snd-message identity-3
           on-rcv-message identity-3}
      :as options}]

  (assert (fn? on-rcv-message) "'on-rcv-message' should be a function")
  (assert (fn? on-snd-message) "'on-snd-message' should be a function")
  (assert (fn? on-connect) "'on-connect' should be a function")

  (fn [{:keys [::yws/channel session-id] :as request}]
    (let [input-ch   (a/chan input-buff-size)
          output-ch  (a/chan output-buff-size)
          pong-ch    (a/chan (a/sliding-buffer 6))
          close-ch   (a/chan)
          stop-ch    (a/chan)

          ip-addr    (parse-client-ip request)
          uagent     (yr/get-header request "user-agent")
          id         (inst-ms (dt/now))

          options    (-> (filter-options options)
                         (merge {::id id
                                 ::input-ch input-ch
                                 ::output-ch output-ch
                                 ::close-ch close-ch
                                 ::stop-ch stop-ch
                                 ::channel channel
                                 ::remote-addr ip-addr
                                 ::http-session-id session-id
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
            (a/close! close-ch)
            (when-not (or (instance? java.nio.channels.ClosedChannelException error)
                          (instance? java.net.SocketException error)
                          (instance? java.io.IOException error))
              (l/error :hint (ex-message error) :cause error)))

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
                (a/>! close-ch [8801 "decode error"])
                (a/close! close-ch))))

          on-ws-pong
          (fn [_ buffers]
            (a/>!! pong-ch (yu/copy-many buffers)))]

      ;; Launch heartbeat process
      (-> @options
          (assoc ::pong-ch pong-ch)
          (process-heartbeat))

      ;; Wait a close signal
      (a/go
        (let [[code reason] (a/<! close-ch)]
          (a/close! stop-ch)
          (a/close! pong-ch)
          (a/close! output-ch)
          (a/close! input-ch)

          (when (and code reason)
            (l/trace :hint "close channel condition" :code code :reason reason)
            (yws/close! channel code reason))

          (when (fn? on-terminate)
            (on-terminate))))

      ;; Forward all messages from output-ch to the websocket
      ;; connection
      (a/go-loop []
        (when-let [val (a/<! output-ch)]
          (let [val (on-snd-message options val)]
            (a/<! (ws-send! channel (t/encode-str val)))
            (recur))))

      ;; React on messages received from the client

      (process-input options handler)

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
      (catch java.io.IOException cause
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
      (catch java.io.IOException cause
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

(defn- process-input
  [wsp handler]
  (let [{:keys [::input-ch ::output-ch ::stop-ch]} @wsp
        handler (wrap-handler handler)]
    (a/go
      (a/<! (handler wsp {:type :connect}))
      (a/<! (a/go-loop []
              (when-let [message (a/<! input-ch)]
                (let [[val port] (a/alts! [stop-ch (handler wsp message)] :priority true)]
                  (when-not (= port stop-ch)
                    (cond
                      (ex/ex-info? val)
                      (a/>! output-ch {:type :error :error (ex-data val)})

                      (ex/exception? val)
                      (a/>! output-ch {:type :error :error {:message (ex-message val)}})

                      (map? val)
                      (a/>! output-ch (cond-> val (:request-id message) (assoc :request-id (:request-id message)))))
                    (recur))))))
      (a/<! (handler wsp {:type :disconnect})))))

(defn- process-heartbeat
  [{:keys [::channel ::stop-ch ::close-ch ::pong-ch
           ::heartbeat-interval ::max-missed-heartbeats]
    :or {heartbeat-interval 2000
         max-missed-heartbeats 4}}]
  (let [beats (atom #{})]
    (a/go-loop [i 0]
      (let [[_ port] (a/alts! [stop-ch (a/timeout heartbeat-interval)] :priority true)]
        (when (and (yws/connected? channel)
                   (not= port stop-ch))
          (a/<! (ws-ping! channel (encode-beat i)))
          (let [issued (swap! beats conj (long i))]
            (if (>= (count issued) max-missed-heartbeats)
              (do
                (a/>! close-ch [8802 "heart-beat timeout"])
                (a/close! close-ch))
              (recur (inc i)))))))

    (a/go-loop []
      (when-let [buffer (a/<! pong-ch)]
        (swap! beats disj (decode-beat buffer))
        (recur)))))

(defn- filter-options
  "Remove from options all namespace qualified keys that matches the
  current namespace."
  [options]
  (into {}
        (remove (fn [[key]]
                  (= (namespace key) "app.util.websocket")))
        options))
