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
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.util.inet :as inet]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [promesa.util :as pu]
   [yetti.request :as yreq]
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

(defn listener
  "A WebSocket upgrade handler factory. Returns a handler that can be
  used to upgrade to websocket connection. This handler implements the
  basic custom protocol on top of websocket connection with all the
  borring stuff already handled (lifecycle, heartbeat,...).

  The provided function should have the `(fn [ws msg])` signature.

  It also accepts some options that allows you parametrize the
  protocol behavior. The options map will be used as-as for the
  initial data of the `ws` data structure"
  [request & {:keys [::on-rcv-message
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

  (let [input-ch   (sp/chan :buf input-buff-size)
        output-ch  (sp/chan :buf output-buff-size)
        hbeat-ch   (sp/chan :buf (sp/sliding-buffer 6))
        close-ch   (sp/chan)
        ip-addr    (inet/parse-request request)
        uagent     (yreq/get-header request "user-agent")
        id         (uuid/next)
        state      (atom {})
        beats      (atom #{})
        options    (-> options
                       (update ::handler wrap-handler)
                       (assoc ::id id)
                       (assoc ::state state)
                       (assoc ::beats beats)
                       (assoc ::created-at (ct/now))
                       (assoc ::input-ch input-ch)
                       (assoc ::heartbeat-ch hbeat-ch)
                       (assoc ::output-ch output-ch)
                       (assoc ::close-ch close-ch)
                       (assoc ::remote-addr ip-addr)
                       (assoc ::user-agent uagent))]

    {:on-open
     (fn on-open [channel]
       (l/dbg :fn "on-open" :conn-id (str id))
       (let [options (-> options
                         (assoc ::channel channel)
                         (on-connect))
             timeout (ct/duration idle-timeout)]

         (yws/set-idle-timeout! channel timeout)
         (px/submit! :vthread (partial start-io-loop! options))))

     :on-close
     (fn on-close [_channel code reason]
       (l/dbg :fn "on-close"
              :conn-id (str id)
              :code code
              :reason reason)
       (sp/close! close-ch))

     :on-error
     (fn on-error [_channel cause]
       (sp/close! close-ch cause))

     :on-message
     (fn on-message [_channel message]
       (when (string? message)
         (sp/offer! input-ch message)
         (swap! state assoc ::last-activity-at (ct/now))))

     :on-pong
     (fn on-pong [_channel data]
       (sp/put! hbeat-ch data))}))

(defn- handle-ping!
  [{:keys [::id ::beats ::channel] :as wsp} beat-id]
  (l/trc :hint "send ping" :beat beat-id :conn-id (str id))
  (yws/ping channel (encode-beat beat-id))
  (let [issued (swap! beats conj (long beat-id))]
    (not (>= (count issued) max-missed-heartbeats))))

(defn- start-io-loop!
  [{:keys [::id ::close-ch ::input-ch ::output-ch ::heartbeat-ch
           ::channel ::handler ::beats ::on-rcv-message ::on-snd-message]
    :as wsp}]
  (try
    (handler wsp {:type :open})
    (loop [i 0]
      (let [ping-ch (sp/timeout-chan heartbeat-interval)
            [msg p] (sp/alts! [close-ch input-ch output-ch heartbeat-ch ping-ch])]
        (when (yws/open? channel)
          (cond
            (identical? p ping-ch)
            (if (handle-ping! wsp i)
              (recur (inc i))
              (do
                (l/trc :hint "closing" :reason "missing to many pings")
                (yws/close channel 8802 "missing to many pings")))

            (or (identical? p close-ch) (nil? msg))
            (do :nothing)

            (identical? p heartbeat-ch)
            (let [beat (decode-beat msg)]
              (l/trc :hint "pong received" :beat beat :conn-id (str id))
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
              (yws/send channel message)
              (recur i))))))

    (catch InterruptedException _cause
      (l/dbg :hint "websocket thread interrumpted" :conn-id id))

    (catch Throwable cause
      (let [cause (pu/unwrap-exception cause)]
        (if (or (instance? java.nio.channels.ClosedChannelException cause)
                (instance? java.net.SocketException cause)
                (instance? java.io.IOException cause))
          nil
          (l/err :hint "unhandled exception on websocket thread"
                 :conn-id id
                 :cause cause))))
    (finally
      (try
        (handler wsp {:type :close})

        (when (yws/open? channel)
          ;; NOTE: we need to ignore all exceptions here because
          ;; there can be a race condition that first returns that
          ;; channel is connected but on closing, will raise that
          ;; channel is already closed.
          (ex/ignoring
           (yws/close channel 8899 "terminated")))

        (when-let [on-disconnect (::on-disconnect wsp)]
          (on-disconnect))

        (catch Throwable cause
          (throw cause)))

      (l/trc :hint "websocket thread terminated" :conn-id id))))
