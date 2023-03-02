;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.climit
  "Concurrencly limiter for RPC."
  (:refer-clojure :exclude [run!])
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.climit.config :as-alias config]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh])
  (:import
   clojure.lang.ExceptionInfo
   com.github.benmanes.caffeine.cache.LoadingCache
   com.github.benmanes.caffeine.cache.CacheLoader
   com.github.benmanes.caffeine.cache.Caffeine
   com.github.benmanes.caffeine.cache.RemovalListener))

(set! *warn-on-reflection* true)

(defn- create-cache
  [{:keys [::wrk/executor] :as params} config]
  (let [listener (reify RemovalListener
                   (onRemoval [_ key _val cause]
                     (l/trace :hint "cache: remove" :key key :reason (str cause))))

        loader   (reify CacheLoader
                   (load [_ key]
                     (let [config (get config (nth key 0))]
                       (pbh/create :permits (or (:permits config) (:concurrency config))
                                   :queue (or (:queue config) (:queue-size config))
                                   :timeout (:timeout config)
                                   :executor executor
                                   :type (:type config :semaphore)))))]
    (.. (Caffeine/newBuilder)
        (weakValues)
        (executor executor)
        (removalListener listener)
        (build loader))))

(s/def ::config/permits ::us/integer)
(s/def ::config/queue ::us/integer)
(s/def ::config/timeout ::us/integer)
(s/def ::config
  (s/map-of keyword?
            (s/keys :opt-un [::config/permits
                             ::config/queue
                             ::config/timeout])))

(defmethod ig/prep-key ::rpc/climit
  [_ cfg]
  (assoc cfg ::path (cf/get :rpc-climit-config)))

(s/def ::path ::fs/path)
(defmethod ig/pre-init-spec ::rpc/climit [_]
  (s/keys :req [::wrk/executor ::mtx/metrics ::path]))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::path ::mtx/metrics ::wrk/executor] :as cfg}]
  (when (contains? cf/flags :rpc-climit)
    (when-let [params (some->> path slurp edn/read-string)]
      (l/info :hint "initializing concurrency limit" :config (str path))
      (us/verify! ::config params)
      {::cache (create-cache cfg params)
       ::config params
       ::wrk/executor executor
       ::mtx/metrics metrics})))

(s/def ::cache #(instance? LoadingCache %))
(s/def ::instance
  (s/keys :req [::cache ::config ::wrk/executor]))

(s/def ::rpc/climit
  (s/nilable ::instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke!
  [cache metrics id key f]
  (let [limiter (.get ^LoadingCache cache [id key])
        tpoint  (dt/tpoint)
        labels  (into-array String [(name id)])

        wrapped
        (fn []
          (let [elapsed (tpoint)
                stats   (pbh/get-stats limiter)]
            (l/trace :hint "executed"
                     :id (name id)
                     :key key
                     :fnh (hash f)
                     :permits (:permits stats)
                     :queue (:queue stats)
                     :max-permits (:max-permits stats)
                     :max-queue (:max-queue stats)
                     :elapsed (dt/format-duration elapsed))
            (mtx/run! metrics
                      :id :rpc-climit-timing
                      :val (inst-ms elapsed)
                      :labels labels)
            (try
              (f)
              (finally
                (let [elapsed (tpoint)]
                  (l/trace :hint "finished"
                           :id (name id)
                           :key key
                           :fnh (hash f)
                           :permits (:permits stats)
                           :queue (:queue stats)
                           :max-permits (:max-permits stats)
                           :max-queue (:max-queue stats)
                           :elapsed (dt/format-duration elapsed)))))))
        measure!
        (fn [stats]
          (mtx/run! metrics
                    :id :rpc-climit-queue
                    :val (:queue stats)
                    :labels labels)
          (mtx/run! metrics
                    :id :rpc-climit-permits
                    :val (:permits stats)
                    :labels labels))]

    (try
      (let [stats (pbh/get-stats limiter)]
        (measure! stats)
        (l/trace :hint "enqueued"
                 :id (name id)
                 :key key
                 :fnh (hash f)
                 :permits (:permits stats)
                 :queue (:queue stats)
                 :max-permits (:max-permits stats)
                 :max-queue (:max-queue stats))
        (pbh/invoke! limiter wrapped))
      (catch ExceptionInfo cause
        (let [{:keys [type code]} (ex-data cause)]
          (if (= :bulkhead-error type)
            (ex/raise :type :concurrency-limit
                      :code code
                      :hint "concurrency limit reached")
            (throw cause))))

      (finally
        (measure! (pbh/get-stats limiter))))))


(defn run!
  [{:keys [::id ::cache ::mtx/metrics]} f]
  (if (and cache id)
    (invoke! cache metrics id nil f)
    (f)))

(defn submit!
  [{:keys [::id ::cache ::wrk/executor ::mtx/metrics]} f]
  (let [f (partial px/submit! executor f)]
    (if (and cache id)
      (p/await! (invoke! cache metrics id nil f))
      (p/await! (f)))))

(defn configure
  ([{:keys [::rpc/climit]} id]
   (us/assert! ::rpc/climit climit)
   (assoc climit ::id id))
  ([{:keys [::rpc/climit]} id executor]
   (us/assert! ::rpc/climit climit)
   (-> climit
       (assoc ::id id)
       (assoc ::wrk/executor executor))))

(defmacro with-dispatch!
  "Dispatch blocking operation to a separated thread protected with the
  specified concurrency limiter. If climit is not active, the function
  will be scheduled to execute without concurrency monitoring."
  [instance & body]
  (if (vector? instance)
    `(-> (app.rpc.climit/configure ~@instance)
         (app.rpc.climit/run! (^:once fn* [] ~@body)))
    `(run! ~instance (^:once fn* [] ~@body))))

(defmacro with-dispatch
  "Dispatch blocking operation to a separated thread protected with
  the specified semaphore.
  DEPRECATED"
  [& params]
  `(with-dispatch! ~@params))

(def noop-fn (constantly nil))

(defn wrap
  [{:keys [::rpc/climit ::mtx/metrics]} f {:keys [::id ::key-fn] :or {key-fn noop-fn} :as mdata}]
  (if (and (some? climit) (some? id))
    (if-let [config (get-in climit [::config id])]
      (let [cache (::cache climit)]
        (l/debug :hint "wrap: instrumenting method"
                 :limit (name id)
                 :service-name (::sv/name mdata)
                 :timeout (:timeout config)
                 :permits (:permits config)
                 :queue (:queue config)
                 :keyed? (some? key-fn))
        (fn [cfg params]
          (invoke! cache metrics id (key-fn params) (partial f cfg params))))

      (do
        (l/warn :hint "no config found for specified queue" :id id)
        f))

    f))
