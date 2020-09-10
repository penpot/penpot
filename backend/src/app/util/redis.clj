;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.util.redis
  "Asynchronous posgresql client."
  (:refer-clojure :exclude [run!])
  (:require
   [promesa.core :as p]
   [clojure.core.async :as a])
  (:import
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
   io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
   ))

(defrecord Client [^RedisClient inner
                   ^RedisURI uri]
  clojure.lang.IDeref
  (deref [_] inner)

  java.lang.AutoCloseable
  (close [_]
    (.shutdown inner)))

(defrecord Connection [^StatefulRedisConnection inner
                       ^RedisAsyncCommands cmd]
  clojure.lang.IDeref
  (deref [_] inner)

  java.lang.AutoCloseable
  (close [_]
    (.close ^StatefulRedisConnection inner)))

(defn client
  [uri]
  (->Client (RedisClient/create)
            (RedisURI/create uri)))

(defn connect
  [{:keys [uri] :as client}]
  (let [conn (.connect ^RedisClient @client StringCodec/UTF8 ^RedisURI uri)]
    (->Connection conn (.async ^StatefulRedisConnection conn))))

(defn- impl-subscribe
  [topics xform ^StatefulRedisPubSubConnection conn]
  (let [cmd    (.sync conn)
        output (a/chan 1 (comp (filter string?) xform))
        buffer (a/chan (a/sliding-buffer 64))
        sub    (reify RedisPubSubListener
                 (message [it pattern channel message])
                 (message [it channel message]
                   ;; There are no back pressure, so we use a slidding
                   ;; buffer for cases when the pubsub broker sends
                   ;; more messages that we can process.
                   (a/put! buffer message))
                 (psubscribed [it pattern count])
                 (punsubscribed [it pattern count])
                 (subscribed [it channel count])
                 (unsubscribed [it channel count]))]

    ;; Start message event-loop (with keepalive mechanism)
    (a/go-loop []
      (let [[val port] (a/alts! [buffer (a/timeout 5000)])
            message (if (= port buffer) val ::keepalive)]
        (if (a/>! output message)
          (recur)
          (do
            (a/close! buffer)
            (.removeListener conn sub)
            (when (.isOpen conn)
              (.close conn))))))

    ;; Synchronously subscribe to topics
    (.addListener conn sub)
    (.subscribe ^RedisPubSubCommands cmd topics)

    ;; Return the output channel
    output))

(defn subscribe
  [{:keys [uri] :as client} {:keys [topic topics xform]}]
  (let [topics (if (vector? topics)
                 (into-array String (map str topics))
                 (into-array String [(str topics)]))]
    (->> (.connectPubSub ^RedisClient @client StringCodec/UTF8 ^RedisURI uri)
         (impl-subscribe topics xform))))

(defn- resolve-to-bool
  [v]
  (if (= v 1)
    true
    false))

(defmulti impl-run (fn [conn cmd parmas] cmd))

(defn run!
  [conn cmd params]
  (let [^RedisAsyncCommands conn (:cmd conn)]
    (impl-run conn cmd params)))

(defn run
  [conn cmd params]
  (let [res (a/chan 1)]
    (if (instance? Connection conn)
      (-> (run! conn cmd params)
          (p/finally (fn [v e]
                       (if e
                         (a/offer! res e)
                         (a/offer! res v)))))
      (a/close! res))
    res))

(defmethod impl-run :get
  [conn _ {:keys [key]}]
  (.get ^RedisAsyncCommands conn ^String key))

(defmethod impl-run :set
  [conn _ {:keys [key val]}]
  (.set ^RedisAsyncCommands conn ^String key ^String val))

(defmethod impl-run :smembers
  [conn _ {:keys [key]}]
  (-> (.smembers ^RedisAsyncCommands conn ^String key)
      (p/then' #(into #{} %))))

(defmethod impl-run :sadd
  [conn _ {:keys [key val]}]
  (let [keys (into-array String [val])]
    (-> (.sadd ^RedisAsyncCommands conn ^String key ^"[S;" keys)
        (p/then resolve-to-bool))))

(defmethod impl-run :srem
  [conn _ {:keys [key val]}]
  (let [keys (into-array String [val])]
    (-> (.srem ^RedisAsyncCommands conn ^String key ^"[S;" keys)
        (p/then resolve-to-bool))))

(defmethod impl-run :publish
  [conn _ {:keys [channel message]}]
  (-> (.publish ^RedisAsyncCommands conn ^String channel ^String message)
      (p/then resolve-to-bool)))

(defmethod impl-run :hset
  [^RedisAsyncCommands conn _ {:keys [key field value]}]
  (.hset conn key field value))

(defmethod impl-run :hgetall
  [^RedisAsyncCommands conn _ {:keys [key]}]
  (.hgetall conn key))

(defmethod impl-run :hdel
  [^RedisAsyncCommands conn _ {:keys [key field]}]
  (let [fields (into-array String [field])]
    (.hdel conn key fields)))

