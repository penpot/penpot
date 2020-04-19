;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.redis
  "Asynchronous posgresql client."
  (:refer-clojure :exclude [get set run!])
  (:require
   [promesa.core :as p])
  (:import
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.api.StatefulRedisConnection
   ))

(defrecord Client [conn uri]
  java.lang.AutoCloseable
  (close [_]
    (.shutdown ^RedisClient conn)))

(defrecord Connection [cmd conn]
  java.lang.AutoCloseable
  (close [_]
    (.close ^StatefulRedisConnection conn)))

(defn client
  [uri]
  (->Client (RedisClient/create) (RedisURI/create uri)))

(defn connect
  [client]
  (let [^RedisURI uri (:uri client)
        ^RedisClient conn (:conn client)
        ^StatefulRedisConnection conn' (.connect conn StringCodec/UTF8 uri)]
    (->Connection (.async conn') conn')))

(declare impl-with-conn)

(defmacro with-conn
  [[csym sym] & body]
  `(impl-with-conn ~sym (fn [~csym] ~@body)))

(defn impl-with-conn
  [client f]
  (let [^RedisURI uri (:uri client)
        ^RedisClient conn (:conn client)]
    (-> (.connectAsync conn StringCodec/UTF8 uri)
        (p/then (fn [^StatefulRedisConnection conn]
                  (let [cmd  (.async conn)
                        conn (->Connection cmd conn)]
                    (-> (p/do! (f conn))
                        (p/handle (fn [v e]
                                    (.close conn)
                                    (if e
                                      (throw e)
                                      v))))))))))

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

