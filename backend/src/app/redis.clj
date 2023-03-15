;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.redis
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.metrics :as mtx]
   [app.redis.script :as-alias rscript]
   [app.util.cache :as cache]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   clojure.lang.IDeref
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

(declare initialize-resources)
(declare shutdown-resources)
(declare connect*)

(s/def ::timer
  #(instance? Timer %))

(s/def ::default-connection
  #(or (instance? StatefulRedisConnection %)
       (and (instance? IDeref %)
            (instance? StatefulRedisConnection (deref %)))))

(s/def ::pubsub-connection
  #(or (instance? StatefulRedisPubSubConnection %)
       (and (instance? IDeref %)
            (instance? StatefulRedisPubSubConnection (deref %)))))

(s/def ::connection
  (s/or :default ::default-connection
        :pubsub  ::pubsub-connection))

(s/def ::connection-holder
  (s/keys :req [::connection]))

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
(s/def ::cache some?)

(s/def ::redis
  (s/keys :req [::resources
                ::redis-uri
                ::timer
                ::mtx/metrics]
          :opt [::connection
                ::cache]))

(defmethod ig/prep-key ::redis
  [_ cfg]
  (let [cpus    (px/get-available-processors)
        threads (max 1 (int (* cpus 0.2)))]
    (merge {::timeout (dt/duration "10s")
            ::io-threads (max 3 threads)
            ::worker-threads (max 3 threads)}
           (d/without-nils cfg))))

(defmethod ig/pre-init-spec ::redis [_]
  (s/keys :req [::uri ::mtx/metrics]
          :opt [::timeout
                ::connect?
                ::io-threads
                ::worker-threads]))

(defmethod ig/init-key ::redis
  [_ {:keys [::connect?] :as cfg}]
  (let [state (initialize-resources cfg)]
    (cond-> state
      connect? (assoc ::connection (connect* cfg {})))))

(defmethod ig/halt-key! ::redis
  [_ state]
  (shutdown-resources state))

(def default-codec
  (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE))

(def string-codec
  (RedisCodec/of StringCodec/UTF8 StringCodec/UTF8))

(defn- create-cache
  [{:keys [::wrk/executor] :as cfg}]
  (letfn [(on-remove [key val cause]
            (l/trace :hint "evict connection (cache)" :key key :reason cause)
            (some-> val d/close!))]
    (cache/create :executor executor
                  :on-remove on-remove
                  :keepalive "5m")))

(defn- initialize-resources
  "Initialize redis connection resources"
  [{:keys [::uri ::io-threads ::worker-threads ::connect?] :as cfg}]
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

        redis-uri (RedisURI/create ^String uri)
        cfg       (-> cfg
                      (assoc ::resources resources)
                      (assoc ::timer timer)
                      (assoc ::redis-uri redis-uri))]

    (assoc cfg ::cache (create-cache cfg))))

(defn- shutdown-resources
  [{:keys [::resources ::cache ::timer]}]
  (cache/invalidate-all! cache)

  (when resources
    (.shutdown ^ClientResources resources))

  (when timer
    (.stop ^Timer timer)))

(defn connect*
  [{:keys [::resources ::redis-uri] :as state}
   {:keys [timeout codec type]
    :or {codec default-codec type :default}}]

  (us/assert! ::resources resources)
  (let [client  (RedisClient/create ^ClientResources resources ^RedisURI redis-uri)
        timeout (or timeout (::timeout state))
        conn    (case type
                  :default (.connect ^RedisClient client ^RedisCodec codec)
                  :pubsub  (.connectPubSub ^RedisClient client ^RedisCodec codec))]

    (l/trc :hint "connect" :hid (hash client))
    (.setTimeout ^StatefulConnection conn ^Duration timeout)
    (reify
      IDeref
      (deref [_] conn)

      AutoCloseable
      (close [_]
        (ex/ignoring (.close ^StatefulConnection conn))
        (ex/ignoring (.shutdown ^RedisClient client))
        (l/trc :hint "disconnect" :hid (hash client))))))

(defn connect
  [state & {:as opts}]
  (let [connection (connect* state opts)]
    (-> state
        (assoc ::connection connection)
        (dissoc ::cache)
        (vary-meta assoc `d/close! (fn [_] (d/close! connection))))))

(defn get-or-connect
  [{:keys [::cache] :as state} key options]
  (us/assert! ::redis state)
  (let [connection (cache/get cache key (fn [_] (connect* state options)))]
    (-> state
        (dissoc ::cache)
        (assoc ::connection connection))))

(defn add-listener!
  [{:keys [::connection] :as conn} listener]
  (us/assert! ::pubsub-connection connection)
  (us/assert! ::pubsub-listener listener)
  (.addListener ^StatefulRedisPubSubConnection @connection
                ^RedisPubSubListener listener)
  conn)

(defn publish!
  [{:keys [::connection]} topic message]
  (us/assert! ::us/string topic)
  (us/assert! ::us/bytes message)
  (us/assert! ::default-connection connection)

  (let [pcomm (.async ^StatefulRedisConnection @connection)]
    (.publish ^RedisAsyncCommands pcomm ^String topic ^bytes message)))

(defn subscribe!
  "Blocking operation, intended to be used on a thread/agent thread."
  [{:keys [::connection]} & topics]
  (us/assert! ::pubsub-connection connection)
  (try
    (let [topics (into-array String (map str topics))
          cmd    (.sync ^StatefulRedisPubSubConnection @connection)]
      (.subscribe ^RedisPubSubCommands cmd topics))
    (catch RedisCommandInterruptedException cause
      (throw (InterruptedException. (ex-message cause))))))

(defn unsubscribe!
  "Blocking operation, intended to be used on a thread/agent thread."
  [{:keys [::connection]} & topics]
  (us/assert! ::pubsub-connection connection)
  (try
    (let [topics (into-array String (map str topics))
          cmd    (.sync ^StatefulRedisPubSubConnection @connection)]
      (.unsubscribe ^RedisPubSubCommands cmd topics))
    (catch RedisCommandInterruptedException cause
      (throw (InterruptedException. (ex-message cause))))))

(defn rpush!
  [{:keys [::connection]} key payload]
  (us/assert! ::default-connection connection)
  (us/assert! (or (and (vector? payload)
                       (every? bytes? payload))
                  (bytes? payload)))
  (try
    (let [cmd  (.sync ^StatefulRedisConnection @connection)
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

(defn blpop!
  [{:keys [::connection]} timeout & keys]
  (us/assert! ::default-connection connection)
  (try
    (let [keys    (into-array Object (map str keys))
          cmd     (.sync ^StatefulRedisConnection @connection)
          timeout (/ (double (inst-ms timeout)) 1000.0)]
      (when-let [res (.blpop ^RedisCommands cmd
                             ^double timeout
                             ^"[Ljava.lang.String;" keys)]
        (MapEntry/create
         (.getKey ^KeyValue res)
         (.getValue ^KeyValue res))))
    (catch RedisCommandInterruptedException cause
      (throw (InterruptedException. (ex-message cause))))))

(defn open?
  [{:keys [::connection]}]
  (us/assert! ::pubsub-connection connection)
  (.isOpen ^StatefulConnection @connection))

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
  [{:keys [::mtx/metrics ::connection] :as state} script]
  (us/assert! ::redis state)
  (us/assert! ::default-connection connection)
  (us/assert! ::rscript/script script)

  (let [cmd   (.async ^StatefulRedisConnection @connection)
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
