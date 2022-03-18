;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.websocket
  "A general protocol implementation on top of websockets."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.core.async :as a]
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

(def noop (constantly nil))

(defn handler
  "A WebSocket upgrade handler factory. Returns a handler that can be
  used to upgrade to websocket connection. This handler implements the
  basic custom protocol on top of websocket connection with all the
  borring stuff already handled (lifecycle, heartbeat,...).

  The provided function should have the `(fn [ws msg])` signature.

  It also accepts some options that allows you parametrize the
  protocol behavior. The options map will be used as-as for the
  initial data of the `ws` data structure"
  ([handle-message] (handler handle-message {}))
  ([handle-message {:keys [::input-buff-size
                           ::output-buff-size
                           ::idle-timeout
                           metrics]
                    :or {input-buff-size 64
                         output-buff-size 64
                         idle-timeout 30000}
                    :as options}]
   (fn [{:keys [::yws/channel] :as request}]
     (let [input-ch   (a/chan input-buff-size)
           output-ch  (a/chan output-buff-size)
           pong-ch    (a/chan (a/sliding-buffer 6))
           close-ch   (a/chan)

           options    (atom
                       (-> options
                           (assoc ::input-ch input-ch)
                           (assoc ::output-ch output-ch)
                           (assoc ::close-ch close-ch)
                           (assoc ::channel channel)
                           (dissoc ::metrics)))

           terminated (atom false)
           created-at (dt/now)

           on-open
           (fn [channel]
             (mtx/run! metrics {:id :websocket-active-connections :inc 1})
             (yws/idle-timeout! channel (dt/duration idle-timeout)))

           on-terminate
           (fn [& _args]
             (when (compare-and-set! terminated false true)
               (mtx/run! metrics {:id :websocket-active-connections :dec 1})
               (mtx/run! metrics {:id :websocket-session-timing :val (/ (inst-ms (dt/diff created-at (dt/now))) 1000.0)})

               (a/close! close-ch)
               (a/close! pong-ch)
               (a/close! output-ch)
               (a/close! input-ch)))

           on-error
           (fn [_ error]
             (on-terminate)
             ;; TODO: properly log timeout exceptions
             (when-not (or (instance? java.nio.channels.ClosedChannelException error)
                           (instance? java.net.SocketException error))
               (l/error :hint (ex-message error) :cause error)))

           on-message
           (fn [_ message]
             (mtx/run! metrics {:id :websocket-messages-total :labels ["recv"] :inc 1})
             (try
               (let [message (t/decode-str message)]
                 (a/offer! input-ch message))
               (catch Throwable e
                 (l/warn :hint "error on decoding incoming message from websocket"
                         :wsmsg (pr-str message)
                         :cause e)
                 (on-terminate))))

           on-pong
           (fn [_ buffers]
             (a/>!! pong-ch (yu/copy-many buffers)))]

       ;; launch heartbeat process
       (-> @options
           (assoc ::pong-ch pong-ch)
           (assoc ::on-close on-terminate)
           (process-heartbeat))

       ;; Forward all messages from output-ch to the websocket
       ;; connection
       (a/go-loop []
         (when-let [val (a/<! output-ch)]
           (mtx/run! metrics {:id :websocket-messages-total :labels ["send"] :inc 1})
           (a/<! (ws-send! channel (t/encode-str val)))
           (recur)))

       ;; React on messages received from the client
       (process-input options handle-message)

       {:on-open on-open
        :on-error on-error
        :on-close on-terminate
        :on-text on-message
        :on-pong on-pong}))))

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
  (let [{:keys [::input-ch ::output-ch ::close-ch]} @wsp
        handler (wrap-handler handler)]
    (a/go
      (a/<! (handler wsp {:type :connect}))
      (a/<! (a/go-loop []
              (when-let [message (a/<! input-ch)]
                (let [[val port] (a/alts! [(handler wsp message) close-ch])]
                  (when-not (= port close-ch)
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
  [{:keys [::channel ::close-ch ::on-close ::pong-ch
           ::heartbeat-interval ::max-missed-heartbeats]
    :or {heartbeat-interval 2000
         max-missed-heartbeats 4}}]
  (let [beats (atom #{})]
    (a/go-loop [i 0]
      (let [[_ port] (a/alts! [close-ch (a/timeout heartbeat-interval)])]
        (when (and (yws/connected? channel)
                   (not= port close-ch))
          (a/<! (ws-ping! channel (encode-beat i)))
          (let [issued (swap! beats conj (long i))]
            (if (>= (count issued) max-missed-heartbeats)
              (on-close channel -1 "heartbeat-timeout")
              (recur (inc i)))))))

    (a/go-loop []
      (when-let [buffer (a/<! pong-ch)]
        (swap! beats disj (decode-beat buffer))
        (recur)))))

