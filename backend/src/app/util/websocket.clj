;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.websocket
  "A general protocol implementation on top of websockets using vthreads."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.loggers.audit :refer [parse-client-ip]]
   [app.util.time :as dt]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [yetti.request :as yr]
   [yetti.util :as yu]
   [yetti.websocket :as yws])
  (:import
   java.nio.ByteBuffer))

(def noop (constantly nil))
(def identity-3 (fn [_ _ o] o))
(def max-missed-heartbeats 3)
(def heartbeat-interval 5000)

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
    (try
      (handler wsp message)
      (catch Throwable cause
        (if (ex/error? cause)
          {:type :error :error (ex-data cause)}
          {:type :error :error {:message (ex-message cause)}})))))

(declare start-io-loop!)

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
             ::idle-timeout]
      :or {input-buff-size 64
           output-buff-size 64
           idle-timeout 60000
           on-connect identity
           on-snd-message identity-3
           on-rcv-message identity-3}
      :as options}]

  (assert (fn? on-rcv-message) "'on-rcv-message' should be a function")
  (assert (fn? on-snd-message) "'on-snd-message' should be a function")
  (assert (fn? on-connect) "'on-connect' should be a function")

  (fn [{:keys [::yws/channel] :as request}]
    (let [input-ch   (sp/chan :buf input-buff-size)
          output-ch  (sp/chan :buf output-buff-size)
          hbeat-ch   (sp/chan :buf (sp/sliding-buffer 6))
          close-ch   (sp/chan)

          ip-addr    (parse-client-ip request)
          uagent     (yr/get-header request "user-agent")
          id         (uuid/next)
          state      (atom {})
          beats      (atom #{})

          options    (-> options
                         (update ::handler wrap-handler)
                         (assoc ::id id)
                         (assoc ::state state)
                         (assoc ::beats beats)
                         (assoc ::created-at (dt/now))
                         (assoc ::input-ch input-ch)
                         (assoc ::heartbeat-ch hbeat-ch)
                         (assoc ::output-ch output-ch)
                         (assoc ::close-ch close-ch)
                         (assoc ::channel channel)
                         (assoc ::remote-addr ip-addr)
                         (assoc ::user-agent uagent)
                         (on-connect))

          on-ws-open
          (fn [channel]
            (l/trace :fn "on-ws-open" :conn-id id)
            (let [timeout (dt/duration idle-timeout)
                  name    (str "penpot/websocket/io-loop/" id)]
              (yws/idle-timeout! channel timeout)
              (px/fn->thread (partial start-io-loop! options)
                             {:name name :virtual true})))

          on-ws-terminate
          (fn [_ code reason]
            (l/trace :fn "on-ws-terminate"
                     :conn-id id
                     :code code
                     :reason reason)
            (sp/close! close-ch))

          on-ws-error
          (fn [_ cause]
            (sp/close! close-ch cause))

          on-ws-message
          (fn [_ message]
            (sp/offer! input-ch message)
            (swap! state assoc ::last-activity-at (dt/now)))

          on-ws-pong
          (fn [_ buffers]
            ;; (l/trace :fn "on-ws-pong" :buffers (pr-str buffers))
            (sp/put! hbeat-ch (yu/copy-many buffers)))]

      (yws/on-close! channel (fn [_]
                               (sp/close! close-ch)))

      {:on-open on-ws-open
       :on-error on-ws-error
       :on-close on-ws-terminate
       :on-text on-ws-message
       :on-pong on-ws-pong})))

(defn- handle-ping!
  [{:keys [::id ::beats ::channel] :as wsp} beat-id]
  (l/trace :hint "ping" :beat beat-id :conn-id id)
  (yws/ping! channel (encode-beat beat-id))
  (let [issued (swap! beats conj (long beat-id))]
    (not (>= (count issued) max-missed-heartbeats))))

(defn- start-io-loop!
  [{:keys [::id ::close-ch ::input-ch ::output-ch ::heartbeat-ch ::channel ::handler ::beats ::on-rcv-message ::on-snd-message] :as wsp}]
  (px/thread
    {:name (str "penpot/websocket/io-loop/" id)
     :virtual true}
    (try
      (handler wsp {:type :open})
      (loop [i 0]
        (let [ping-ch (sp/timeout-chan heartbeat-interval)
              [msg p] (sp/alts! [close-ch input-ch output-ch heartbeat-ch ping-ch])]
          (when (yws/connected? channel)
            (cond
              (identical? p ping-ch)
              (if (handle-ping! wsp i)
                (recur (inc i))
                (yws/close! channel 8802 "missing to many pings"))

              (or (identical? p close-ch) (nil? msg))
              (do :nothing)

              (identical? p heartbeat-ch)
              (let [beat (decode-beat msg)]
                ;; (l/trace :hint "pong" :beat beat :conn-id id)
                (swap! beats disj beat)
                (recur i))

              (identical? p input-ch)
              (let [message (t/decode-str msg)
                    message (on-rcv-message message)
                    {:keys [request-id] :as response} (handler wsp message)]
                (when (map? response)
                  (sp/put! output-ch
                           (cond-> response
                             (some? request-id)
                             (assoc :request-id request-id))))
                (recur i))

              (identical? p output-ch)
              (let [message (on-snd-message msg)
                    message (t/encode-str message {:type :json-verbose})]
                ;; (l/trace :hint "writing message to output" :message msg)
                (yws/send! channel message)
                (recur i))))))

      (catch java.nio.channels.ClosedChannelException _)
      (catch java.net.SocketException _)
      (catch java.io.IOException _)

      (catch InterruptedException _
        (l/debug :hint "websocket thread interrumpted" :conn-id id))

      (catch Throwable cause
        (l/error :hint "unhandled exception on websocket thread"
                 :conn-id id
                 :cause cause))

      (finally
        (handler wsp {:type :close})

        (when (yws/connected? channel)
          ;; NOTE: we need to ignore all exceptions here because
          ;; there can be a race condition that first returns that
          ;; channel is connected but on closing, will raise that
          ;; channel is already closed.
          (ex/ignoring
           (yws/close! channel 8899 "terminated")))

        (when-let [on-disconnect (::on-disconnect wsp)]
          (on-disconnect))

        (l/trace :hint "websocket thread terminated" :conn-id id)))))
