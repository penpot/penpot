;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.eventbus
  (:require [promesa.core :as p]
            [vertx.util :as vu])
  (:import io.vertx.core.Vertx
           io.vertx.core.Handler
           io.vertx.core.Context
           io.vertx.core.eventbus.Message
           io.vertx.core.eventbus.MessageConsumer
           io.vertx.core.eventbus.DeliveryOptions
           io.vertx.core.eventbus.EventBus
           io.vertx.core.eventbus.MessageCodec
           java.util.function.Supplier))

(declare opts->delivery-opts)
(declare resolve-eventbus)
(declare build-message-codec)
(declare build-message)

;; --- Public Api

(defn consumer
  [vsm topic f]
  (let [^EventBus bus (resolve-eventbus vsm)
        ^MessageConsumer consumer (.consumer bus ^String topic)]
    (.handler consumer (reify Handler
                         (handle [_ msg]
                           (.pause consumer)
                           (-> (p/do! (f vsm (build-message msg)))
                               (p/handle (fn [res err]
                                           (.resume consumer)
                                           (.reply msg (or res err)
                                                   (opts->delivery-opts {}))))))))
    consumer))

(defn publish!
  ([vsm topic msg] (publish! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)]
     (.publish ^EventBus bus
               ^String topic
               ^Object msg
               ^DeliveryOptions opts)
     nil)))

(defn send!
  ([vsm topic msg] (send! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)]
     (.send ^EventBus bus
            ^String topic
            ^Object msg
            ^DeliveryOptions opts)
     nil)))

(defn request!
  ([vsm topic msg] (request! vsm topic msg {}))
  ([vsm topic msg opts]
   (let [bus (resolve-eventbus vsm)
         opts (opts->delivery-opts opts)
         d (p/deferred)]
     (.request ^EventBus bus
               ^String topic
               ^Object msg
               ^DeliveryOptions opts
               ^Handler (vu/deferred->handler d))
     (p/then' d build-message))))

(defn configure!
  [vsm opts]
  (let [^EventBus bus (resolve-eventbus vsm)]
    (.registerCodec bus (build-message-codec))))

(defrecord Msg [body])

(defn message?
  [v]
  (instance? Msg v))

;; --- Impl

(defn- resolve-eventbus
  [o]
  (cond
    (instance? Vertx o) (.eventBus ^Vertx o)
    (instance? Context o) (resolve-eventbus (.owner ^Context o))
    (instance? EventBus o) o
    :else (throw (ex-info "unexpected argument" {}))))

(defn- build-message-codec
  []
  ;; TODO: implement the wire encode/decode using transit+msgpack
  (reify MessageCodec
    (encodeToWire [_ buffer data])
    (decodeFromWire [_ pos buffer])
    (transform [_ data] data)
    (name [_] "clj:msgpack")
    (^byte systemCodecID [_] (byte -1))))

(defn- build-message
  [^Message msg]
  (let [metadata {::reply-to (.replyAddress msg)
                  ::send? (.isSend msg)
                  ::address (.address msg)}
        body (.body msg)]
    (Msg. body metadata nil)))

(defn- opts->delivery-opts
  [{:keys [codec local?]}]
  (let [^DeliveryOptions opts (DeliveryOptions.)]
    (.setCodecName opts (or codec "clj:msgpack"))
    (when local? (.setLocalOnly opts true))
    opts))


