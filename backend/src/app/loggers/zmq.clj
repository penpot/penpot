;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.zmq
  "A generic ZMQ listener."
  (:require
   [app.common.exceptions :as ex]
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
                               (keep prepare)))
        mult   (a/mult output)]
    (when endpoint
      (let [thread (Thread. #(start-rcv-loop {:out buffer :endpoint endpoint}))]
        (.setDaemon thread false)
        (.setName thread "penpot/zmq-logger-receiver")
        (.start thread)))

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

(def ^:private json-mapper
  (json/mapper
   {:encode-key-fn str/camel
    :decode-key-fn (comp keyword str/kebab)}))

(defn- start-rcv-loop
  ([] (start-rcv-loop nil))
  ([{:keys [out endpoint] :or {endpoint "tcp://localhost:5556"}}]
   (let [out    (or out (a/chan 1))
         zctx   (ZContext. 1)
         socket (.. zctx (createSocket SocketType/SUB))]
     (.. socket (connect ^String endpoint))
     (.. socket (subscribe ""))
     (.. socket (setReceiveTimeOut 5000))
     (loop []
       (let [msg (.recv ^ZMQ$Socket socket)
             msg (ex/ignoring (json/read msg json-mapper))
             msg (if (nil? msg) :empty msg)]
         (if (a/>!! out msg)
           (recur)
           (do
             (.close ^java.lang.AutoCloseable socket)
             (.destroy ^ZContext zctx))))))))

(s/def ::logger-name string?)
(s/def ::level string?)
(s/def ::thread string?)
(s/def ::time-millis integer?)
(s/def ::message string?)
(s/def ::context-map map?)
(s/def ::thrown map?)

(s/def ::log4j-event
  (s/keys :req-un [::logger-name ::level ::thread ::time-millis ::message]
          :opt-un [::context-map ::thrown]))

(defn- prepare
  [event]
  (if (s/valid? ::log4j-event event)
    (merge {:message      (:message event)
            :created-at   (dt/instant (:time-millis event))
            :logger/name  (:logger-name event)
            :logger/level (str/lower (:level event))}

           (when-let [trace (-> event :thrown :extended-stack-trace)]
             {:trace trace})

           (:context-map event))
    (do
      (l/warn :hint "invalid event" :event event)
      nil)))
