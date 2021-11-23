;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.zmq
  "A generic ZMQ listener."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   org.zeromq.SocketType
   org.zeromq.ZMQ$Socket
   org.zeromq.ZContext))

(declare prepare)
(declare start-rcv-loop)

(s/def ::endpoint ::us/string)

(defmethod ig/pre-init-spec ::receiver [_]
  (s/keys :opt-un [::endpoint]))

(defmethod ig/init-key ::receiver
  [_ {:keys [endpoint] :as cfg}]
  (l/info :msg "initializing ZMQ receiver" :bind endpoint)
  (let [buffer (a/chan 1)
        output (a/chan 1 (comp (filter map?)
                               (map prepare)))
        mult   (a/mult output)]
    (when endpoint
      (a/thread (start-rcv-loop {:out buffer :endpoint endpoint})))
    (a/pipe buffer output)
    (with-meta
      (fn [cmd ch]
        (case cmd
          :sub (a/tap mult ch)
          :unsub (a/untap mult ch))
        ch)
      {::output output
       ::buffer buffer
       ::mult mult})))

(defmethod ig/halt-key! ::receiver
  [_ f]
  (a/close! (::buffer (meta f))))

(defn- start-rcv-loop
  ([] (start-rcv-loop nil))
  ([{:keys [out endpoint] :or {endpoint "tcp://localhost:5556"}}]
   (let [out    (or out (a/chan 1))
         zctx   (ZContext.)
         socket (.. zctx (createSocket SocketType/SUB))]
     (.. socket (connect ^String endpoint))
     (.. socket (subscribe ""))
     (.. socket (setReceiveTimeOut 5000))
     (loop []
       (let [msg (.recv ^ZMQ$Socket socket)
             msg (json/decode msg)
             msg (if (nil? msg) :empty msg)]
         (if (a/>!! out msg)
           (recur)
           (do
             (.close ^java.lang.AutoCloseable socket)
             (.close ^java.lang.AutoCloseable zctx))))))))

(defn- prepare
  [event]
  (merge
   {:logger     (:loggerName event)
    :level      (str/lower (:level event))
    :thread     (:thread event)
    :created-at (dt/instant (:timeMillis event))
    :message    (:message event)}
   (when-let [ctx (:contextMap event)]
     {:context ctx})
   (when-let [thrown (:thrown event)]
     {:error
      {:class (:name thrown)
       :message (:message thrown)
       :trace (:extendedStackTrace thrown)}})))
