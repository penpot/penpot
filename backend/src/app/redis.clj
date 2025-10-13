;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.redis
  "The msgbus abstraction implemented using redis as underlying backend."
  (:refer-clojure :exclude [eval get set run!])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.generic-pool :as gpool]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.metrics :as mtx]
   [app.redis.script :as-alias rscript]
   [app.worker :as wrk]
   [app.worker.executor]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   clojure.lang.MapEntry
   io.lettuce.core.KeyValue
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisCommandInterruptedException
   io.lettuce.core.RedisCommandTimeoutException
   io.lettuce.core.RedisException
   io.lettuce.core.RedisURI
   io.lettuce.core.ScriptOutputType
   io.lettuce.core.SetArgs
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.sync.RedisCommands
   io.lettuce.core.api.sync.RedisScriptingCommands
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
   io.lettuce.core.resource.ClientResources
   io.lettuce.core.resource.DefaultClientResources
   io.netty.channel.nio.NioEventLoopGroup
   io.netty.util.HashedWheelTimer
   io.netty.util.Timer
   io.netty.util.concurrent.EventExecutorGroup
   java.lang.AutoCloseable
   java.time.Duration))

(set! *warn-on-reflection* true)

(def ^:const MAX-EVAL-RETRIES 18)

(def default-timeout
  (ct/duration "10s"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL & PRIVATE API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IConnection
  (-set-timeout [_ timeout] "set connection timeout")
  (-get-timeout [_] "get current timeout")
  (-reset-timeout [_] "reset to default timeout"))

(defprotocol IDefaultConnection
  "Public API of default redis connection"
  (-publish [_ topic message])
  (-rpush [_ key payload])
  (-blpop [_ timeout keys])
  (-eval [_ script])
  (-get [_ key])
  (-set [_ key val args])
  (-del [_ key-or-keys])
  (-ping [_]))

(defprotocol IPubSubConnection
  (-add-listener [_ listener])
  (-subscribe [_ topics])
  (-unsubscribe [_ topics]))

(def ^:private default-codec
  (RedisCodec/of StringCodec/UTF8 StringCodec/UTF8))

(defn- impl-eval
  [cmd cache metrics script]
  (let [keys    (into-array String (map str (::rscript/keys script)))
        vals    (into-array String (map str (::rscript/vals script)))
        sname   (::rscript/name script)

        read-script
        (fn []
          (-> script ::rscript/path io/resource slurp))

        load-script
        (fn []
          (let [id (.scriptLoad ^RedisScriptingCommands cmd
                                ^String (read-script))]
            (swap! cache assoc sname id)
            (l/trc :hint "load script" :name sname :id id)

            id))

        eval-script
        (fn [id]
          (try
            (let [tpoint  (ct/tpoint)
                  result  (.evalsha ^RedisScriptingCommands cmd
                                    ^String id
                                    ^ScriptOutputType ScriptOutputType/MULTI
                                    ^"[Ljava.lang.String;" keys
                                    ^"[Ljava.lang.String;" vals)
                  elapsed (tpoint)]

              (mtx/run! metrics {:id :redis-eval-timing
                                 :labels [(name sname)]
                                 :val (inst-ms elapsed)})

              (l/trc :hint "eval script"
                     :name (name sname)
                     :id id
                     :params (str/join "," (::rscript/vals script))
                     :elapsed (ct/format-duration elapsed))

              result)

            (catch io.lettuce.core.RedisNoScriptException _cause
              ::load)

            (catch Throwable cause
              (when-let [on-error (::rscript/on-error script)]
                (on-error cause))
              (throw cause))))

        eval-script'
        (fn [id]
          (loop [id      id
                 retries 0]
            (if (> retries MAX-EVAL-RETRIES)
              (ex/raise :type :internal
                        :code ::max-eval-retries-reached
                        :hint (str "unable to eval redis script " sname))
              (let [result (eval-script id)]
                (if (= result ::load)
                  (recur (load-script)
                         (inc retries))
                  result)))))]

    (if-let [id (c/get @cache sname)]
      (eval-script' id)
      (-> (load-script)
          (eval-script')))))

(deftype Connection [^StatefulRedisConnection conn
                     ^RedisCommands cmd
                     ^Duration timeout
                     cache metrics]
  AutoCloseable
  (close [_]
    (ex/ignoring (.close conn)))

  IConnection
  (-set-timeout [_ timeout]
    (.setTimeout conn ^Duration timeout))

  (-reset-timeout [_]
    (.setTimeout conn timeout))

  (-get-timeout [_]
    (.getTimeout conn))

  IDefaultConnection
  (-publish [_ topic message]
    (.publish cmd ^String topic ^String message))

  (-rpush [_ key elements]
    (try
      (let [vals (make-array String (count elements))]
        (loop [i 0 xs (seq elements)]
          (when xs
            (aset ^"[[Ljava.lang.String;" vals i ^String (first xs))
            (recur (inc i) (next xs))))

        (.rpush cmd
                ^String key
                ^"[[Ljava.lang.String;" vals))

      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-blpop [_ keys timeout]
    (try
      (let [keys (into-array String keys)]
        (when-let [res (.blpop cmd
                               ^double timeout
                               ^"[Ljava.lang.String;" keys)]
          (MapEntry/create
           (.getKey ^KeyValue res)
           (.getValue ^KeyValue res))))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-get [_ key]
    (assert (string? key) "key expected to be string")
    (.get cmd ^String key))

  (-set [_ key val args]
    (.set cmd
          ^String key
          ^bytes val
          ^SetArgs args))

  (-del [_ keys]
    (let [keys (into-array String keys)]
      (.del cmd ^String/1 keys)))

  (-ping [_]
    (.ping cmd))

  (-eval [_ script]
    (impl-eval cmd cache metrics script)))


(deftype SubscriptionConnection [^StatefulRedisPubSubConnection conn
                                 ^RedisPubSubCommands cmd
                                 ^Duration timeout]
  AutoCloseable
  (close [_]
    (ex/ignoring (.close conn)))

  IConnection
  (-set-timeout [_ timeout]
    (.setTimeout conn ^Duration timeout))

  (-reset-timeout [_]
    (.setTimeout conn timeout))

  (-get-timeout [_]
    (.getTimeout conn))

  IPubSubConnection
  (-add-listener [_ listener]
    (.addListener conn ^RedisPubSubListener listener))

  (-subscribe [_ topics]
    (try
      (let [topics (into-array String topics)]
        (.subscribe cmd topics))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-unsubscribe [_ topics]
    (try
      (let [topics (into-array String topics)]
        (.unsubscribe cmd topics))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-set-args
  [options]
  (reduce-kv (fn [^SetArgs args k v]
               (case k
                 :ex (if (instance? Duration v)
                       (.ex args ^Duration v)
                       (.ex args (long v)))
                 :px (.px args (long v))
                 :nx (if v (.nx args) args)
                 :keep-ttl (if v (.keepttl args) args)))
             (SetArgs.)
             options))

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

(defn connect
  [cfg & {:as options}]
  (assert (contains? cfg ::mtx/metrics) "missing ::mtx/metrics on provided system")
  (assert (contains? cfg ::client) "missing ::rds/client on provided system")

  (let [state   (::client cfg)

        cache   (::cache state)
        client  (::client state)
        timeout (or (some-> (:timeout options) ct/duration)
                    (::timeout state))

        conn    (.connect ^RedisClient client
                          ^RedisCodec default-codec)
        cmd     (.sync ^StatefulRedisConnection conn)]

    (.setTimeout ^StatefulRedisConnection conn ^Duration timeout)
    (->Connection conn cmd timeout cache (::mtx/metrics cfg))))

(defn connect-pubsub
  [cfg & {:as options}]
  (let [state   (::client cfg)
        client  (::client state)

        timeout (or (some-> (:timeout options) ct/duration)
                    (::timeout state))
        conn    (.connectPubSub ^RedisClient client
                                ^RedisCodec default-codec)
        cmd     (.sync ^StatefulRedisPubSubConnection conn)]


    (.setTimeout ^StatefulRedisPubSubConnection conn
                 ^Duration timeout)
    (->SubscriptionConnection conn cmd timeout)))

(defn get
  [conn key]
  (assert (string? key) "key must be string instance")
  (try
    (-get conn key)
    (catch RedisCommandTimeoutException cause
      (l/err :hint "timeout on get redis key" :key key :cause cause)
      nil)))

(defn set
  ([conn key val]
   (set conn key val nil))
  ([conn key val args]
   (assert (string? key) "key must be string instance")
   (assert (string? val) "val must be string instance")
   (let [args (cond
                (or (instance? SetArgs args)
                    (nil? args))
                args

                (map? args)
                (build-set-args args)

                :else
                (throw (IllegalArgumentException. "invalid args")))]

     (try
       (-set conn key val args)
       (catch RedisCommandTimeoutException cause
         (l/err :hint "timeout on set redis key" :key key :cause cause)
         nil)))))

(defn del
  [conn key-or-keys]
  (let [keys (if (vector? key-or-keys) key-or-keys [key-or-keys])]
    (assert (every? string? keys) "only string keys allowed")
    (try
      (-del conn keys)
      (catch RedisCommandTimeoutException cause
        (l/err :hint "timeout on del redis key" :key key :cause cause)
        nil))))

(defn ping
  [conn]
  (-ping conn))

(defn blpop
  [conn key-or-keys timeout]
  (let [keys    (if (vector? key-or-keys) key-or-keys [key-or-keys])
        timeout (cond
                  (ct/duration? timeout)
                  (/ (double (inst-ms timeout)) 1000.0)

                  (double? timeout)
                  timeout

                  (int? timeout)
                  (/ (double timeout) 1000.0)

                  :else
                  0)]

    (assert (every? string? keys) "only string keys allowed")
    (-blpop conn keys timeout)))

(defn rpush
  [conn key elements]
  (assert (string? key) "key must be string instance")
  (assert (every? string? elements) "elements should be all strings")
  (let [elements (vec elements)]
    (-rpush conn key elements)))

(defn publish
  [conn topic payload]
  (assert (string? topic) "expected topic to be string")
  (assert (string? payload) "expected message to be a byte array")
  (-publish conn topic payload))

(def ^:private schema:script
  [:map {:title "script"}
   [::rscript/name qualified-keyword?]
   [::rscript/path ::sm/text]
   [::rscript/keys {:optional true} [:vector :any]]
   [::rscript/vals {:optional true} [:vector :any]]])

(def ^:private valid-script?
  (sm/lazy-validator schema:script))

(defn eval
  [conn script]
  (assert (valid-script? script) "expected valid script")
  (-eval conn script))

(defn add-listener
  [conn listener]
  (let [listener (cond
                   (map? listener)
                   (pubsub-listener listener)

                   (instance? RedisPubSubListener listener)
                   listener

                   :else
                   (throw (IllegalArgumentException. "invalid listener provided")))]

    (-add-listener conn listener)))

(defn subscribe
  [conn topic-or-topics]
  (let [topics (if (vector? topic-or-topics) topic-or-topics [topic-or-topics])]
    (assert (every? string? topics))
    (-subscribe conn topics)))

(defn unsubscribe
  [conn topic-or-topics]
  (let [topics (if (vector? topic-or-topics) topic-or-topics [topic-or-topics])]
    (assert (every? string? topics))
    (-unsubscribe conn topics)))

(defn set-timeout
  [conn timeout]
  (let [timeout (ct/duration timeout)]
    (-set-timeout conn timeout)))

(defn get-timeout
  [conn]
  (-get-timeout conn))

(defn reset-timeout
  [conn]
  (-reset-timeout conn))

(defn timeout-exception?
  [cause]
  (instance? RedisCommandTimeoutException cause))

(defn exception?
  [cause]
  (instance? RedisException cause))

(defn get-pooled
  [cfg]
  (let [pool (::pool cfg)]
    (gpool/get pool)))

(defn close
  [o]
  (.close ^AutoCloseable o))

(defn pool
  [cfg & {:as options}]
  (gpool/create :create-fn (partial connect cfg options)
                :destroy-fn close
                :dispose-fn -reset-timeout))

(defn run!
  [cfg f & args]
  (if (gpool/pool? cfg)
    (apply f {::pool cfg} f args)
    (let [pool (::pool cfg)]
      (with-open [^AutoCloseable conn (gpool/get pool)]
        (apply f (assoc cfg ::conn @conn) args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/expand-key ::client
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::timeout (ct/duration "10s")))})

(def ^:private schema:client
  [:map {:title "RedisClient"}
   [::timer [:fn #(instance? HashedWheelTimer %)]]
   [::cache ::sm/atom]
   [::timeout ::ct/duration]
   [::resources [:fn #(instance? DefaultClientResources %)]]])

(def check-client
  (sm/check-fn schema:client))

(sm/register! ::client schema:client)
(sm/register!
 {:type ::pool
  :pred gpool/pool?})

(def ^:private schema:client-params
  [:map {:title "redis-params"}
   ::wrk/netty-io-executor
   ::wrk/netty-executor
   [::uri ::sm/uri]
   [::timeout ::ct/duration]])

(def ^:private check-client-params
  (sm/check-fn schema:client-params))

(defmethod ig/assert-key ::client
  [_ params]
  (check-client-params params))

(defmethod ig/init-key ::client
  [_ {:keys [::uri ::wrk/netty-io-executor ::wrk/netty-executor] :as params}]

  (l/inf :hint "initialize redis client" :uri (str uri))

  (let [timer     (HashedWheelTimer.)
        cache     (atom {})

        resources (.. (DefaultClientResources/builder)
                      (eventExecutorGroup ^EventExecutorGroup netty-executor)

                      ;; We provide lettuce with a shared event loop
                      ;; group instance instead of letting lettuce to
                      ;; create its own
                      (eventLoopGroupProvider
                       (reify io.lettuce.core.resource.EventLoopGroupProvider
                         (allocate [_ _] netty-io-executor)
                         (threadPoolSize [_]
                           (.executorCount ^NioEventLoopGroup netty-io-executor))
                         (release [_ _ _ _ _]
                           ;; Do nothing
                           )
                         (shutdown [_ _ _ _]
                           ;; Do nothing
                           )))

                      (timer ^Timer timer)
                      (build))

        redis-uri (RedisURI/create ^String (str uri))
        client    (RedisClient/create ^ClientResources resources
                                      ^RedisURI redis-uri)]

    {::client client
     ::cache cache
     ::timer timer
     ::timeout default-timeout
     ::resources resources}))

(defmethod ig/halt-key! ::client
  [_ {:keys [::client ::timer ::resources]}]
  (ex/ignoring (.shutdown ^RedisClient client))
  (ex/ignoring (.shutdown ^ClientResources resources))
  (ex/ignoring (.stop ^Timer timer)))

(defmethod ig/assert-key ::pool
  [_ {:keys [::client]}]
  (check-client client))

(defmethod ig/init-key ::pool
  [_ cfg]
  (pool cfg {:timeout (ct/duration 2000)}))

(defmethod ig/halt-key! ::pool
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))
