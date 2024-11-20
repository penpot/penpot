;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.redis
  "The msgbus abstraction implemented using redis as underlying backend."
  (:refer-clojure :exclude [eval])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.metrics :as mtx]
   [app.redis.script :as-alias rscript]
   [app.util.cache :as cache]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   clojure.lang.MapEntry
   io.lettuce.core.KeyValue
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisCommandInterruptedException
   io.lettuce.core.RedisCommandTimeoutException
   io.lettuce.core.RedisException
   io.lettuce.core.RedisURI
   io.lettuce.core.ScriptOutputType
   io.lettuce.core.api.StatefulConnection
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.api.async.RedisScriptingAsyncCommands
   io.lettuce.core.api.sync.RedisCommands
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

(declare ^:private initialize-resources)
(declare ^:private shutdown-resources)
(declare ^:private impl-eval)

(defprotocol IRedis
  (-connect [_ options])
  (-get-or-connect [_ key options]))

(defprotocol IConnection
  (publish [_ topic message])
  (rpush [_ key payload])
  (blpop [_ timeout keys])
  (eval [_ script]))

(defprotocol IPubSubConnection
  (add-listener [_ listener])
  (subscribe [_ topics])
  (unsubscribe [_ topics]))

(def default-codec
  (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE))

(def string-codec
  (RedisCodec/of StringCodec/UTF8 StringCodec/UTF8))

(sm/register!
 {:type ::connection
  :pred #(satisfies? IConnection %)
  :type-properties
  {:title "connection"
   :description "redis connection instance"}})

(sm/register!
 {:type ::pubsub-connection
  :pred #(satisfies? IPubSubConnection %)
  :type-properties
  {:title "connection"
   :description "redis connection instance"}})

(defn redis?
  [o]
  (satisfies? IRedis o))

(sm/register!
 {:type ::redis
  :pred redis?})

(def ^:private schema:script
  [:map {:title "script"}
   [::rscript/name qualified-keyword?]
   [::rscript/path ::sm/text]
   [::rscript/keys {:optional true} [:vector :any]]
   [::rscript/vals {:optional true} [:vector :any]]])

(def valid-script?
  (sm/lazy-validator schema:script))

(defmethod ig/expand-key ::redis
  [k v]
  (let [cpus    (px/get-available-processors)
        threads (max 1 (int (* cpus 0.2)))]
    {k (-> (d/without-nils v)
           (assoc ::timeout (dt/duration "10s"))
           (assoc ::io-threads (max 3 threads))
           (assoc ::worker-threads (max 3 threads)))}))

(def ^:private schema:redis-params
  [:map {:title "redis-params"}
   ::wrk/executor
   ::mtx/metrics
   [::uri ::sm/uri]
   [::worker-threads ::sm/int]
   [::io-threads ::sm/int]
   [::timeout ::dt/duration]])

(defmethod ig/assert-key ::redis
  [_ params]
  (assert (sm/check schema:redis-params params)))

(defmethod ig/init-key ::redis
  [_ params]
  (initialize-resources params))

(defmethod ig/halt-key! ::redis
  [_ instance]
  (d/close! instance))

(defn- initialize-resources
  "Initialize redis connection resources"
  [{:keys [::uri ::io-threads ::worker-threads ::wrk/executor ::mtx/metrics] :as params}]

  (l/inf :hint "initialize redis resources"
         :uri (str uri)
         :io-threads io-threads
         :worker-threads worker-threads)

  (let [timer     (HashedWheelTimer.)
        resources (.. (DefaultClientResources/builder)
                      (ioThreadPoolSize ^long io-threads)
                      (computationThreadPoolSize ^long worker-threads)
                      (timer ^Timer timer)
                      (build))

        redis-uri (RedisURI/create ^String (str uri))

        shutdown  (fn [client conn]
                    (ex/ignoring (.close ^StatefulConnection conn))
                    (ex/ignoring (.close ^RedisClient client))
                    (l/trc :hint "disconnect" :hid (hash client)))

        on-remove (fn [key val cause]
                    (l/trace :hint "evict connection (cache)" :key key :reason cause)
                    (some-> val d/close!))

        cache     (cache/create :executor executor
                                :on-remove on-remove
                                :keepalive "5m")]
    (reify
      java.lang.AutoCloseable
      (close [_]
        (ex/ignoring (cache/invalidate! cache))
        (ex/ignoring (.shutdown ^ClientResources resources))
        (ex/ignoring (.stop ^Timer timer)))

      IRedis
      (-get-or-connect [this key options]
        (let [create (fn [_] (-connect this options))]
          (cache/get cache key create)))

      (-connect [_ options]
        (let [timeout (or (:timeout options) (::timeout params))
              codec   (get options :codec default-codec)
              type    (get options :type :default)
              client  (RedisClient/create ^ClientResources resources
                                          ^RedisURI redis-uri)]

          (l/trc :hint "connect" :hid (hash client))
          (if (= type :pubsub)
            (let [conn (.connectPubSub ^RedisClient client
                                       ^RedisCodec codec)]
              (.setTimeout ^StatefulConnection conn
                           ^Duration timeout)
              (reify
                IPubSubConnection
                (add-listener [_ listener]
                  (assert (instance? RedisPubSubListener listener) "expected listener instance")
                  (.addListener ^StatefulRedisPubSubConnection conn
                                ^RedisPubSubListener listener))

                (subscribe [_ topics]
                  (try
                    (let [topics (into-array String (map str topics))
                          cmd    (.sync ^StatefulRedisPubSubConnection conn)]
                      (.subscribe ^RedisPubSubCommands cmd topics))
                    (catch RedisCommandInterruptedException cause
                      (throw (InterruptedException. (ex-message cause))))))

                (unsubscribe [_ topics]
                  (try
                    (let [topics (into-array String (map str topics))
                          cmd    (.sync ^StatefulRedisPubSubConnection conn)]
                      (.unsubscribe ^RedisPubSubCommands cmd topics))
                    (catch RedisCommandInterruptedException cause
                      (throw (InterruptedException. (ex-message cause))))))


                AutoCloseable
                (close [_] (shutdown client conn))))

            (let [conn (.connect ^RedisClient client ^RedisCodec codec)]
              (.setTimeout ^StatefulConnection conn ^Duration timeout)
              (reify
                IConnection
                (publish [_ topic message]
                  (assert (string? topic) "expected topic to be string")
                  (assert (bytes? message) "expected message to be a byte array")

                  (let [pcomm (.async ^StatefulRedisConnection conn)]
                    (.publish ^RedisAsyncCommands pcomm ^String topic ^bytes message)))

                (rpush [_ key payload]
                  (assert (or (and (vector? payload)
                                   (every? bytes? payload))
                              (bytes? payload)))
                  (try
                    (let [cmd  (.sync ^StatefulRedisConnection conn)
                          data (if (vector? payload) payload [payload])
                          vals (make-array (. Class (forName "[B")) (count data))]

                      (loop [i 0 xs (seq data)]
                        (when xs
                          (aset ^"[[B" vals i ^bytes (first xs))
                          (recur (inc i) (next xs))))

                      (.rpush ^RedisCommands cmd
                              ^String key
                              ^"[[B" vals))

                    (catch RedisCommandInterruptedException cause
                      (throw (InterruptedException. (ex-message cause))))))

                (blpop [_ timeout keys]
                  (try
                    (let [keys    (into-array Object (map str keys))
                          cmd     (.sync ^StatefulRedisConnection conn)
                          timeout (/ (double (inst-ms timeout)) 1000.0)]
                      (when-let [res (.blpop ^RedisCommands cmd
                                             ^double timeout
                                             ^"[Ljava.lang.String;" keys)]
                        (MapEntry/create
                         (.getKey ^KeyValue res)
                         (.getValue ^KeyValue res))))
                    (catch RedisCommandInterruptedException cause
                      (throw (InterruptedException. (ex-message cause))))))

                (eval [_ script]
                  (assert (valid-script? script) "expected valid script")
                  (impl-eval conn metrics script))

                AutoCloseable
                (close [_] (shutdown client conn))))))))))

(defn connect
  [instance & {:as opts}]
  (assert (satisfies? IRedis instance) "expected valid redis instance")
  (-connect instance opts))

(defn get-or-connect
  [instance key & {:as opts}]
  (assert (satisfies? IRedis instance) "expected valid redis instance")
  (-get-or-connect instance key opts))

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

(def ^:private scripts-cache (atom {}))

(defn- impl-eval
  [^StatefulRedisConnection connection metrics script]
  (let [cmd   (.async ^StatefulRedisConnection connection)
        keys  (into-array String (map str (::rscript/keys script)))
        vals  (into-array String (map str (::rscript/vals script)))
        sname (::rscript/name script)]

    (letfn [(on-error [cause]
              (if (instance? io.lettuce.core.RedisNoScriptException cause)
                (do
                  (l/error :hint "no script found" :name sname :cause cause)
                  (->> (load-script)
                       (p/mcat eval-script)))
                (if-let [on-error (::rscript/on-error script)]
                  (on-error cause)
                  (p/rejected cause))))

            (eval-script [sha]
              (let [tpoint (dt/tpoint)]
                (->> (.evalsha ^RedisScriptingAsyncCommands cmd
                               ^String sha
                               ^ScriptOutputType ScriptOutputType/MULTI
                               ^"[Ljava.lang.String;" keys
                               ^"[Ljava.lang.String;" vals)
                     (p/fmap (fn [result]
                               (let [elapsed (tpoint)]
                                 (mtx/run! metrics {:id :redis-eval-timing
                                                    :labels [(name sname)]
                                                    :val (inst-ms elapsed)})
                                 (l/trace :hint "eval script"
                                          :name (name sname)
                                          :sha sha
                                          :params (str/join "," (::rscript/vals script))
                                          :elapsed (dt/format-duration elapsed))
                                 result)))
                     (p/merr on-error))))

            (read-script []
              (-> script ::rscript/path io/resource slurp))

            (load-script []
              (l/trace :hint "load script" :name sname)
              (->> (.scriptLoad ^RedisScriptingAsyncCommands cmd
                                ^String (read-script))
                   (p/fmap (fn [sha]
                             (swap! scripts-cache assoc sname sha)
                             sha))))]

      (p/await!
       (if-let [sha (get @scripts-cache sname)]
         (eval-script sha)
         (->> (load-script)
              (p/mapcat eval-script)))))))

(defn timeout-exception?
  [cause]
  (instance? RedisCommandTimeoutException cause))

(defn exception?
  [cause]
  (instance? RedisException cause))
