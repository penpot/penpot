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
   [app.config :as cf]
   [app.loggers.zmq.receiver :as-alias receiver]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   org.zeromq.SocketType
   org.zeromq.ZMQ$Socket
   org.zeromq.ZContext))

(declare prepare)
(declare start-rcv-loop)

(defmethod ig/init-key ::receiver
  [_ cfg]
  (let [uri    (cf/get :loggers-zmq-uri)
        buffer (a/chan 1)
        output (a/chan 1 (comp (filter map?)
                               (keep prepare)))
        mult   (a/mult output)
        thread (when uri
                 (px/thread
                   {:name "penpot/zmq-receiver"
                    :daemon false}
                   (l/info :hint "receiver started")
                   (try
                     (start-rcv-loop buffer uri)
                     (catch InterruptedException _
                       (l/debug :hint "receiver interrupted"))
                     (catch java.lang.IllegalStateException cause
                       (if (= "errno 4" (ex-message cause))
                         (l/debug :hint "receiver interrupted")
                         (l/error :hint "unhandled error" :cause cause)))
                     (catch Throwable cause
                       (l/error :hint "unhandled error" :cause cause))
                     (finally
                       (l/info :hint "receiver terminated")))))]

    (a/pipe buffer output)
    (-> cfg
        (assoc ::receiver/mult mult)
        (assoc ::receiver/thread thread)
        (assoc ::receiver/output output)
        (assoc ::receiver/buffer buffer))))

(s/def ::receiver/mult some?)
(s/def ::receiver/thread #(instance? Thread %))
(s/def ::receiver/output some?)
(s/def ::receiver/buffer some?)
(s/def ::receiver
  (s/keys :req [::receiver/mult
                ::receiver/thread
                ::receiver/output
                ::receiver/buffer]))

(defn sub!
  [{:keys [::receiver/mult]} ch]
  (a/tap mult ch))

(defmethod ig/halt-key! ::receiver
  [_ {:keys [::receiver/buffer ::receiver/thread]}]
  (some-> thread px/interrupt!)
  (some-> buffer a/close!))

(def ^:private json-mapper
  (json/mapper
   {:encode-key-fn str/camel
    :decode-key-fn (comp keyword str/kebab)}))

(defn- start-rcv-loop
  [output endpoint]
  (let [zctx   (ZContext. 1)
        socket (.. zctx (createSocket SocketType/SUB))]
    (try
      (.. socket (connect ^String endpoint))
      (.. socket (subscribe ""))
      (.. socket (setReceiveTimeOut 5000))
      (loop []
        (let [msg (.recv ^ZMQ$Socket socket)
              msg (ex/ignoring (json/read msg json-mapper))
              msg (if (nil? msg) :empty msg)]
          (when (a/>!! output msg)
            (recur))))

      (finally
        (.close ^java.lang.AutoCloseable socket)
        (.destroy ^ZContext zctx)))))

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
