;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.redis
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

(defrecord Client [client uri]
  java.lang.AutoCloseable
  (close [_]
    (.shutdown ^RedisClient client)))

(defrecord Connection [^RedisAsyncCommands cmd]
  java.lang.AutoCloseable
  (close [_]
    (let [conn (.getStatefulConnection cmd)]
      (.close ^StatefulRedisConnection conn))))

(defn client
  [uri]
  (->Client (RedisClient/create) (RedisURI/create uri)))

(defn connect
  [client]
  (let [^RedisURI uri (:uri client)
        ^RedisClient client (:client client)]
    (-> (.connectAsync client StringCodec/UTF8 uri)
        (p/then' (fn [^StatefulRedisConnection conn]
                   (->Connection (.async conn)))))))

(defn- impl-subscribe
  [^String topic ^StatefulRedisPubSubConnection conn]
  (let [cmd      (.async conn)
        output   (a/chan 1 (filter string?))
        buffer   (a/chan (a/sliding-buffer 64))
        listener (reify RedisPubSubListener
                   (message [it pattern channel message])
                   (message [it channel message]
                     ;; There are no back pressure, so we use a
                     ;; slidding buffer for cases when the pubsub
                     ;; broker sends more messages that we can
                     ;; process.
                     (a/put! buffer message))
                   (psubscribed [it pattern count]
                     #_(prn "psubscribed" pattern count))
                   (punsubscribed [it pattern count]
                     #_(prn "punsubscribed" pattern count))
                   (subscribed [it channel count]
                     #_(prn "subscribed" channel count))
                   (unsubscribed [it channel count]
                     #_(prn "unsubscribed" channel count)))]
    (.addListener conn listener)
    (a/go-loop []
      (let [[val port] (a/alts! [buffer (a/timeout 5000)])
            message (if (= port buffer) val ::keepalive)]
        (if (a/>! output message)
          (recur)
          (do
            (a/close! buffer)
            (when (.isOpen conn)
              (.close conn))))))
    (-> (.subscribe ^RedisPubSubAsyncCommands cmd (into-array String [topic]))
        (p/then' (constantly output)))))

(defn subscribe
  [client topic]
  (let [^RedisURI uri (:uri client)
        ^RedisClient client (:client client)]
    (-> (.connectPubSubAsync client StringCodec/UTF8 uri)
        (p/then (partial impl-subscribe topic)))))

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

