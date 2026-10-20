;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.climit
  "Concurrencly limiter for RPC."
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.util.cache :as cache]
   [app.util.services :as-alias sv]
   [app.worker :as-alias wrk]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh])
  (:import
   clojure.lang.ExceptionInfo
   java.util.concurrent.atomic.AtomicLong
   java.util.concurrent.ConcurrentHashMap))

(set! *warn-on-reflection* true)

(declare ^:private impl-invoke)
(declare ^:private id->str)
(declare ^:private create-cache)

(defprotocol IConcurrencyLimiter
  (^:private get-config [_ limit-id] "get a config for a key")
  (^:private invoke [_ config handler] "invoke a handler for a config"))

(sm/register!
 {:type ::rpc/climit
  :pred #(satisfies? IConcurrencyLimiter %)})

(def ^:private schema:config
  [:map-of :keyword
   [:map
    [::id {:optional true} :keyword]
    [::key {:optional true} :any]
    [::label {:optional true} ::sm/text]
    [::params {:optional true} :map]
    [::permits {:optional true} ::sm/int]
    [::queue {:optional true} ::sm/int]
    [::timeout {:optional true} ::sm/int]]])

(def ^:private check-config
  (sm/check-fn schema:config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WAITING COUNTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private waiting-sample-interval-ms 15000)

(defn- create-waiting-counters
  "Per limiter name, how many calls on this instance are waiting for a
  permit. Every limiter of a name adds to the same entry, so the value does
  not depend on which of them ran last the way a sampled gauge does: a
  request that enters and never leaves stays visible for ever. The map
  belongs to the component, so its counts die with it instead of surviving
  into the next system built in the same JVM."
  []
  (ConcurrentHashMap.))

(defn- waiting-counter
  ^AtomicLong
  [^ConcurrentHashMap counters name]
  (.computeIfAbsent counters name
                    (reify java.util.function.Function
                      (apply [_ _] (AtomicLong. 0)))))

(defn- publish-waiting
  [metrics name value]
  (mtx/run! metrics
            :id :rpc-climit-waiting
            :val value
            :labels (into-array String [name])))

(defn- waiting-inc
  [metrics counters name]
  (publish-waiting metrics name (.incrementAndGet (waiting-counter counters name))))

(defn- waiting-dec
  [metrics counters name]
  (publish-waiting metrics name (.decrementAndGet (waiting-counter counters name))))

(defn- republish-waiting
  "Publish the waiting count of every name again. A count that stops changing
  (a limiter whose requests never come back and whose traffic died) has to keep
  being written, or the series goes stale and the pile-up disappears."
  [metrics counters]
  (doseq [[name counter] counters]
    (publish-waiting metrics name (.get ^AtomicLong counter))))

(defn- create-waiting-sampler
  "A daemon sampler that republishes the waiting counts of this component."
  [metrics counters]
  (mtx/create-sampler waiting-sample-interval-ms
                      (fn [] (republish-waiting metrics counters))
                      {:name "climit-metrics"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord ConcurrencyLimiter [params cache metrics counters sampler]
  IConcurrencyLimiter
  (get-config [_ id]
    (get params id))

  (invoke [_ config handler]
    (impl-invoke metrics counters cache config handler)))

(def ^:private schema:climit-params
  [:map
   ::mtx/metrics
   ::wrk/executor
   [::enabled {:optional true} ::sm/boolean]
   [::config {:optional true} ::fs/path]])

(defmethod ig/assert-key ::rpc/climit
  [_ params]
  (assert (sm/valid? schema:climit-params params)))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::config ::enabled ::mtx/metrics] :as cfg}]
  (when enabled
    (when-let [params (some->> config slurp edn/read-string check-config)]
      (l/inf :hint "initializing concurrency limit" :config (str config))
      (let [params   (reduce-kv (fn [result k v]
                                  (when-not (:timeout v)
                                    (l/wrn :hint "limiter without timeout, a saturated one parks its threads forever"
                                           :id k))
                                  (assoc result k (assoc v ::id k)))
                                params
                                params)
            cache    (create-cache cfg)
            counters (create-waiting-counters)]

        (->ConcurrencyLimiter params cache metrics counters
                              (create-waiting-sampler metrics counters))))))

(defmethod ig/halt-key! ::rpc/climit
  [_ instance]
  (when-let [sampler (:sampler instance)]
    (l/inf :hint "stopping concurrency limit metrics")
    (px/shutdown-now sampler)))

(defn- id->str
  ([id]
   (-> (str id)
       (subs 1)))
  ([id key]
   (if key
     (str (-> (str id) (subs 1)) "/" key)
     (id->str id))))

(defn- create-limiter
  [config id]
  (l/trc :hint "created" :id id)
  (pbh/create :permits (or (:permits config) (:concurrency config))
              :queue (or (:queue config) (:queue-size config))
              :timeout (:timeout config)
              :type :semaphore))

(defn- create-cache
  [{:keys [::wrk/executor]}]
  (letfn [(on-remove [id _ cause]
            (l/trc :hint "disposed" :id id :reason (str cause)))]
    (cache/create :executor executor
                  :on-remove on-remove
                  :keepalive "5m")))

(defn- measure
  "Publish the wait time of a call that got its permit, in milliseconds. The
  queue is not published here: a gauge written by whichever limiter of the name
  ran last cannot show it, and the `:rpc-climit-waiting` gauge accumulates the
  entries and the exits instead."
  [metrics mlabels elapsed]
  (mtx/run! metrics
            :id :rpc-climit-timing
            :val (inst-ms elapsed)
            :labels mlabels))

(defn- prepare-params-for-debug
  [params]
  (-> (select-keys params [::rpc/profile-id :file-id :profile-id])
      (set/rename-keys {::rpc/profile-id :profile-id})
      (update-vals str)))

(defn- log
  [{:keys [id label params permits queue-max]} action req-id stats elapsed code reason]
  (let [mpermits (:max-permits stats)
        queue    (:queue stats)
        queue    (- queue mpermits)
        queue    (if (neg? queue) 0 queue)
        level    (if (pos? queue) :warn :trace)]

    (l/log level
           :hint action
           :req req-id
           :id id
           :label label
           :queue queue
           :queue-max queue-max
           :permits permits
           :elapsed (some-> elapsed ct/format-duration)
           :code code
           :reason reason
           :params @params)))

(def ^:private idseq (AtomicLong. 0))

(def ^:private ^ThreadLocal held-limiters
  ;; Limiters a thread is inside of, identified by the limiter instance, which
  ;; already encodes the limit id and its key: the cache is per id+key pair.
  ;; `pbh/invoke!` runs the handler on the calling thread, so a handler that
  ;; enters climit again for a limit it holds finds it here. Without this a
  ;; thread parks forever behind a permit it holds itself.
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] (java.util.HashSet.)))))

(defn- impl-invoke
  [metrics counters cache config handler]
  (let [limit-id     (::id config)
        limit-key    (::key config)
        limit-label  (::label config)
        limit-name   (id->str limit-id)
        limit-params (delay
                       (prepare-params-for-debug
                        (::params config)))

        mlabels      (into-array String [limit-name])
        limit-id     (id->str limit-id limit-key)
        limit        {:id limit-id
                      :label limit-label
                      :params limit-params
                      :permits (or (:permits config) (:concurrency config) 1)
                      :queue-max (or (:queue config) (:queue-size config))}
        limiter      (cache/get cache limit-id (partial create-limiter config))
        held         ^java.util.Set (.get ^ThreadLocal held-limiters)]

    (if (.contains held limiter)
      ;; The calling thread already holds this limiter for this key: run the
      ;; handler inline instead of queueing behind itself.
      (do
        (l/trc :hint "reentrant invocation" :id limit-id :label limit-label)
        (handler))

      (let [tpoint        (ct/tpoint)
            req-id        (.incrementAndGet ^AtomicLong idseq)
            waiting       (volatile! true)
            stop-waiting  (fn []
                            ;; Exactly one decrement per increment: the flag
                            ;; makes a second call a no-op and the `finally`
                            ;; guarantees that there is one.
                            (when @waiting
                              (vreset! waiting false)
                              (waiting-dec metrics counters limit-name)))]

        (.add held limiter)

        (try
          ;; The increment lives inside the `try`, so a throwable from the
          ;; publishing path unwinds through the `finally`: the thread drops the
          ;; mark and the count comes back down instead of staying up for good.
          (waiting-inc metrics counters limit-name)

          (let [stats (pbh/get-stats limiter)]
            (log limit "enqueued" req-id stats nil nil nil))

          (pbh/invoke! limiter (fn []
                                 (stop-waiting)
                                 (let [elapsed (tpoint)
                                       stats   (pbh/get-stats limiter)]
                                   (measure metrics mlabels elapsed)
                                   (log limit "acquired" req-id stats elapsed nil nil)
                                   (handler))))

          (catch ExceptionInfo cause
            (let [{:keys [type code] :as data} (ex-data cause)]
              (if (= :bulkhead-error type)
                (let [elapsed (tpoint)
                      stats   (pbh/get-stats limiter)
                      reason  (if (some? (:timeout data)) "timeout" "queue-full")]
                  (mtx/run! metrics
                            :id :rpc-climit-rejected
                            :inc 1
                            :labels (into-array String [limit-name reason]))
                  (log limit "rejected" req-id stats elapsed code reason)
                  (ex/raise :type :concurrency-limit
                            :code code
                            :hint "concurrency limit reached"
                            :cause cause))
                (throw cause))))

          (finally
            (.remove held limiter)
            (stop-waiting)
            (let [elapsed (tpoint)
                  stats   (pbh/get-stats limiter)]

              (log limit "finished" req-id stats elapsed nil nil))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MIDDLEWARE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private noop-fn (constantly nil))
(def ^:private global-limits
  [[:root/global noop-fn]
   [:root/by-profile ::rpc/profile-id]])

(defn- get-limits
  [cfg]
  (when-let [ref (get cfg ::id)]
    (cond
      (keyword? ref)
      [[ref]]

      (and (vector? ref)
           (keyword (first ref)))
      [ref]

      (and (vector? ref)
           (vector? (first ref)))
      (rseq ref)

      :else
      (throw (IllegalArgumentException. "unable to normalize limit")))))

(defn wrap
  [cfg handler {label ::sv/name :as mdata}]
  (if-let [climit (::rpc/climit cfg)]
    (reduce (fn [handler [limit-id key-fn]]
              (if-let [config (get-config climit limit-id)]
                (let [key-fn (or key-fn noop-fn)]
                  (l/trc :hint "instrumenting method"
                         :method label
                         :limit (id->str limit-id)
                         :timeout (:timeout config)
                         :permits (:permits config)
                         :queue (:queue config)
                         :keyed (not= key-fn nil))

                  (if (and (= key-fn ::rpc/profile-id)
                           (false? (::rpc/auth mdata true)))

                    ;; We don't enforce by-profile limit on methods that does
                    ;; not require authentication
                    handler

                    (fn [cfg params]
                      (let [config (-> config
                                       (assoc ::key (key-fn params))
                                       (assoc ::label label)
                                       ;; NOTE: only used for debugging output
                                       (assoc ::params params))]
                        (invoke climit config (partial handler cfg params))))))

                (do
                  (l/wrn :hint "no config found for specified queue" :id (id->str limit-id))
                  handler)))
            handler
            ;; The root limiters go last so they end up outermost: every path
            ;; meets them before the command's own limits. The nested
            ;; `climit/invoke!` chains carry only the command's own limits and
            ;; run inside those of the enclosing call, so this puts both paths
            ;; in the same order. With the opposite order two calls of the same
            ;; profile hold the same limiters inverted and park for good.
            (concat (get-limits mdata) global-limits))

    handler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-exec-chain
  [{:keys [::label ::rpc/climit] :as cfg} f]
  (reduce (fn [handler [limit-id limit-key]]
            (if-let [config (get-config climit limit-id)]
              (let [config (-> config
                               (assoc ::key limit-key)
                               (assoc ::label label))]
                (fn [cfg params]
                  (let [config (assoc config ::params params)]
                    (invoke climit config (partial handler cfg params)))))
              (do
                (l/wrn :hint "config not found" :label label :id limit-id)
                f)))
          f
          (get-limits cfg)))

(defn invoke!
  "Run a function in context of climit."
  [{:keys [::rpc/climit] :as cfg} f params]
  (let [f (if climit
            (build-exec-chain cfg f)
            f)]
    (f cfg params)))
