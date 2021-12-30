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

(defmacro call-mtx
  [definitions name & args]
  `(when-let [mtx-fn# (some-> ~definitions ~name ::mtx/fn)]
     (mtx-fn# ~@args)))

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
                           ::metrics]
                    :or {input-buff-size 64
                         output-buff-size 64
                         idle-timeout 30000}
                    :as options}]
   (fn [_]
     (let [input-ch   (a/chan input-buff-size)
           output-ch  (a/chan output-buff-size)
           pong-ch    (a/chan (a/sliding-buffer 6))
           close-ch   (a/chan)
           options    (-> options
                          (assoc ::input-ch input-ch)
                          (assoc ::output-ch output-ch)
                          (assoc ::close-ch close-ch)
                          (dissoc ::metrics))

           terminated (atom false)
           created-at (dt/now)

           on-terminate
           (fn [& _args]
             (when (compare-and-set! terminated false true)
               (call-mtx metrics :connections {:cmd :dec :by 1})
               (call-mtx metrics :sessions {:val (/ (inst-ms (dt/diff created-at (dt/now))) 1000.0)})

               (a/close! close-ch)
               (a/close! pong-ch)
               (a/close! output-ch)
               (a/close! input-ch)))

           on-error
           (fn [_ error]
             (on-terminate)
             (when-not (or (instance? org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException error)
                           (instance? java.nio.channels.ClosedChannelException error))
               (l/error :hint (ex-message error) :cause error)))

           on-connect
           (fn [conn]
             (call-mtx metrics :connections {:cmd :inc :by 1})

             (let [wsp (atom (assoc options ::conn conn))]
               ;; Handle heartbeat
               (yws/idle-timeout! conn (dt/duration idle-timeout))
               (-> @wsp
                   (assoc ::pong-ch pong-ch)
                   (assoc ::on-close on-terminate)
                   (process-heartbeat))

               ;; Forward all messages from output-ch to the websocket
               ;; connection
               (a/go-loop []
                 (when-let [val (a/<! output-ch)]
                   (call-mtx metrics :messages {:labels ["send"]})
                   (a/<! (ws-send! conn (t/encode-str val)))
                   (recur)))

               ;; React on messages received from the client
               (process-input wsp handle-message)))

           on-message
           (fn [_ message]
             (call-mtx metrics :messages {:labels ["recv"]})
             (try
               (let [message (t/decode-str message)]
                 (a/offer! input-ch message))
               (catch Throwable e
                 (l/warn :hint "error on decoding incoming message from websocket"
                         :cause e)
                 (on-terminate))))

           on-pong
           (fn [_ buffer]
             (a/>!! pong-ch buffer))]

       {:on-connect on-connect
        :on-error on-error
        :on-close on-terminate
        :on-text on-message
        :on-pong on-pong}))))

(defn- ws-send!
  [conn s]
  (let [ch (a/chan 1)]
    (yws/send! conn s (fn [e]
                        (when e (a/offer! ch e))
                        (a/close! ch)))
    ch))

(defn- ws-ping!
  [conn s]
  (let [ch (a/chan 1)]
    (yws/ping! conn s (fn [e]
                        (when e (a/offer! ch e))
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

(defn- process-input
  [wsp handler]
  (let [{:keys [::input-ch ::output-ch ::close-ch]} @wsp]
    (a/go
      (a/<! (handler wsp {:type :connect}))
      (a/<! (a/go-loop []
              (when-let [request (a/<! input-ch)]
                (let [[val port] (a/alts! [(handler wsp request) close-ch])]
                  (when-not (= port close-ch)
                    (cond
                      (ex/ex-info? val)
                      (a/>! output-ch {:type :error :error (ex-data val)})

                      (ex/exception? val)
                      (a/>! output-ch {:type :error :error {:message (ex-message val)}})

                      (map? val)
                      (a/>! output-ch (cond-> val (:request-id request) (assoc :request-id (:request-id request)))))

                    (recur))))))
      (a/<! (handler wsp {:type :disconnect})))))

(defn- process-heartbeat
  [{:keys [::conn ::close-ch ::on-close ::pong-ch
           ::heartbeat-interval ::max-missed-heartbeats]
    :or {heartbeat-interval 2000
         max-missed-heartbeats 4}}]
  (let [beats (atom #{})]
    (a/go-loop [i 0]
      (let [[_ port] (a/alts! [close-ch (a/timeout heartbeat-interval)])]
        (when (and (yws/connected? conn)
                   (not= port close-ch))
          (a/<! (ws-ping! conn (encode-beat i)))
          (let [issued (swap! beats conj (long i))]
            (if (>= (count issued) max-missed-heartbeats)
              (on-close conn -1 "heartbeat-timeout")
              (recur (inc i)))))))

    (a/go-loop []
      (when-let [buffer (a/<! pong-ch)]
        (swap! beats disj (decode-beat buffer))
        (recur)))))

