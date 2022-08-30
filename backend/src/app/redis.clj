;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.redis
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.metrics :as mtx]
   [app.redis.script :as-alias rscript]
   [app.util.time :as dt]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
   clojure.lang.IDeref
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.ScriptOutputType
   io.lettuce.core.api.StatefulConnection
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.api.async.RedisScriptingAsyncCommands
   io.lettuce.core.codec.ByteArrayCodec
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
   io.lettuce.core.resource.ClientResources
   io.lettuce.core.resource.DefaultClientResources
   io.netty.util.HashedWheelTimer
   io.netty.util.Timer
   java.lang.AutoCloseable
   java.time.Duration))

(set! *warn-on-reflection* true)

(declare initialize-resources)
(declare shutdown-resources)
(declare connect)
(declare close!)

(s/def ::timer
  #(instance? Timer %))

(s/def ::connection
  #(or (instance? StatefulRedisConnection %)
       (and (instance? IDeref %)
            (instance? StatefulRedisConnection (deref %)))))

(s/def ::pubsub-connection
  #(or (instance? StatefulRedisPubSubConnection %)
       (and (instance? IDeref %)
            (instance? StatefulRedisPubSubConnection (deref %)))))

(s/def ::redis-uri
  #(instance? RedisURI %))

(s/def ::resources
  #(instance? ClientResources %))

(s/def ::pubsub-listener
  #(instance? RedisPubSubListener %))

(s/def ::uri ::us/not-empty-string)
(s/def ::timeout ::dt/duration)
(s/def ::connect? ::us/boolean)
(s/def ::io-threads ::us/integer)
(s/def ::worker-threads ::us/integer)

(s/def ::redis
  (s/keys :req [::resources ::redis-uri ::timer ::mtx/metrics]
          :opt [::connection]))

(defmethod ig/pre-init-spec ::redis [_]
  (s/keys :req-un [::uri ::mtx/metrics]
          :opt-un [::timeout
                   ::connect?
                   ::io-threads
                   ::worker-threads]))

(defmethod ig/prep-key ::redis
  [_ cfg]
  (let [runtime (Runtime/getRuntime)
        cpus    (.availableProcessors ^Runtime runtime)]
    (merge {:timeout (dt/duration 5000)
            :io-threads (max 3 cpus)
            :worker-threads (max 3 cpus)}
         (d/without-nils cfg))))

(defmethod ig/init-key ::redis
  [_ {:keys [connect?] :as cfg}]
  (let [cfg (initialize-resources cfg)]
    (cond-> cfg
      connect? (assoc ::connection (connect cfg)))))

(defmethod ig/halt-key! ::redis
  [_ state]
  (shutdown-resources state))

(def default-codec
  (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE))

(def string-codec
  (RedisCodec/of StringCodec/UTF8 StringCodec/UTF8))

(defn- initialize-resources
  "Initialize redis connection resources"
  [{:keys [uri io-threads worker-threads connect? metrics] :as cfg}]
  (l/info :hint "initialize redis resources"
          :uri uri
          :io-threads io-threads
          :worker-threads worker-threads
          :connect? connect?)

  (let [timer     (HashedWheelTimer.)
        resources (.. (DefaultClientResources/builder)
                      (ioThreadPoolSize ^long io-threads)
                      (computationThreadPoolSize ^long worker-threads)
                      (timer ^Timer timer)
                      (build))

        redis-uri (RedisURI/create ^String uri)]

    (-> cfg
        (assoc ::mtx/metrics metrics)
        (assoc ::cache (atom {}))
        (assoc ::timer timer)
        (assoc ::redis-uri redis-uri)
        (assoc ::resources resources))))

(defn- shutdown-resources
  [{:keys [::resources ::cache ::timer]}]
  (run! close! (vals @cache))
  (when resources
    (.shutdown ^ClientResources resources))
  (when timer
    (.stop ^Timer timer)))

(defn connect
  [{:keys [::resources ::redis-uri] :as cfg}
   & {:keys [timeout codec type] :or {codec default-codec type :default}}]

  (us/assert! ::resources resources)

  (let [client  (RedisClient/create ^ClientResources resources ^RedisURI redis-uri)
        timeout (or timeout (:timeout cfg))
        conn    (case type
                  :default (.connect ^RedisClient client ^RedisCodec codec)
                  :pubsub  (.connectPubSub ^RedisClient client ^RedisCodec codec))]

    (.setTimeout ^StatefulConnection conn ^Duration timeout)

    (reify
      IDeref
      (deref [_] conn)

      AutoCloseable
      (close [_]
        (.close ^StatefulConnection conn)
        (.shutdown ^RedisClient client)))))

(defn get-or-connect
  [{:keys [::cache] :as state} key options]
  (assoc state ::connection
         (or (get @cache key)
             (-> (swap! cache (fn [cache]
                                (when-let [prev (get cache key)]
                                  (close! prev))
                                (assoc cache key (connect state options))))
                 (get key)))))

(defn add-listener!
  [conn listener]
  (us/assert! ::pubsub-connection @conn)
  (us/assert! ::pubsub-listener listener)

  (.addListener ^StatefulRedisPubSubConnection @conn
                ^RedisPubSubListener listener)
  conn)

(defn publish!
  [conn topic message]
  (us/assert! ::us/string topic)
  (us/assert! ::us/bytes message)
  (us/assert! ::connection @conn)

  (let [pcomm (.async ^StatefulRedisConnection @conn)]
    (.publish ^RedisAsyncCommands pcomm ^String topic ^bytes message)))

(defn subscribe!
  "Blocking operation, intended to be used on a worker/agent thread."
  [conn & topics]
  (us/assert! ::pubsub-connection @conn)
  (let [topics (into-array String (map str topics))
        cmd    (.sync ^StatefulRedisPubSubConnection @conn)]
    (.subscribe ^RedisPubSubCommands cmd topics)))

(defn unsubscribe!
  "Blocking operation, intended to be used on a worker/agent thread."
  [conn & topics]
  (us/assert! ::pubsub-connection @conn)
  (let [topics (into-array String (map str topics))
        cmd    (.sync ^StatefulRedisPubSubConnection @conn)]
    (.unsubscribe ^RedisPubSubCommands cmd topics)))

(defn open?
  [conn]
  (.isOpen ^StatefulConnection @conn))

(defn pubsub-listener
  [& {:keys [on-message on-subscribe on-unsubscribe]}]
  (reify RedisPubSubListener
    (message [_ pattern topic message]
      (when on-message
        (on-message pattern topic message)))

    (message [_ topic message]
      (when on-message
        (on-message nil topic message)))

    (psubscribed [_ pattern count]
      (when on-subscribe
        (on-subscribe pattern nil count)))

    (punsubscribed [_ pattern count]
      (when on-unsubscribe
        (on-unsubscribe pattern nil count)))

    (subscribed [_ topic count]
      (when on-subscribe
        (on-subscribe nil topic count)))

    (unsubscribed [_ topic count]
      (when on-unsubscribe
        (on-unsubscribe nil topic count)))))

(defn close!
  [o]
  (.close ^AutoCloseable o))

(def ^:private scripts-cache (atom {}))
(def noop-fn (constantly nil))

(s/def ::rscript/name qualified-keyword?)
(s/def ::rscript/path ::us/not-empty-string)
(s/def ::rscript/keys (s/every any? :kind vector?))
(s/def ::rscript/vals (s/every any? :kind vector?))

(s/def ::rscript/script
  (s/keys :req [::rscript/name
                ::rscript/path]
          :opt [::rscript/keys
                ::rscript/vals]))

(defn eval!
  [{:keys [::mtx/metrics] :as state} script]
  (us/assert! ::rscript/script script)
  (us/assert! ::redis state)

  (let [rconn (-> state ::connection deref)
        cmd   (.async ^StatefulRedisConnection rconn)
        keys  (into-array String (map str (::rscript/keys script)))
        vals  (into-array String (map str (::rscript/vals script)))
        sname (::rscript/name script)]

    (letfn [(on-error [cause]
              (if (instance? io.lettuce.core.RedisNoScriptException cause)
                (do
                  (l/error :hint "no script found" :name sname :cause cause)
                  (-> (load-script)
                      (p/then eval-script)))
                (if-let [on-error (::rscript/on-error script)]
                  (on-error cause)
                  (p/rejected cause))))

            (eval-script [sha]
              (let [start-ts (System/nanoTime)]
                (-> (.evalsha ^RedisScriptingAsyncCommands cmd
                              ^String sha
                              ^ScriptOutputType ScriptOutputType/MULTI
                              ^"[Ljava.lang.String;" keys
                              ^"[Ljava.lang.String;" vals)
                    (p/then (fn [result]
                              (let [elapsed (dt/duration {:nanos (- (System/nanoTime) start-ts)})]
                                (mtx/run! metrics {:id :redis-eval-timing
                                                   :labels [(name sname)]
                                                   :val (inst-ms elapsed)})
                                (l/trace :hint "eval script"
                                         :name (name sname)
                                         :sha sha
                                         :params (str/join "," (::rscript/vals script))
                                         :elapsed (dt/format-duration elapsed))
                                result)))
                    (p/catch on-error))))

            (read-script []
              (-> script ::rscript/path io/resource slurp))

            (load-script []
              (l/trace :hint "load script" :name sname)
              (-> (.scriptLoad ^RedisScriptingAsyncCommands cmd
                               ^String (read-script))
                  (p/then (fn [sha]
                            (swap! scripts-cache assoc sname sha)
                            sha))))]

      (if-let [sha (get @scripts-cache sname)]
        (eval-script sha)
        (-> (load-script)
            (p/then eval-script))))))
