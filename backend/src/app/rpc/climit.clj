;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.climit
  "Concurrencly limiter for RPC."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pxb])
  (:import
   com.github.benmanes.caffeine.cache.Cache
   com.github.benmanes.caffeine.cache.CacheLoader
   com.github.benmanes.caffeine.cache.Caffeine
   com.github.benmanes.caffeine.cache.RemovalListener))

(defn- capacity-exception?
  [o]
  (and (ex/error? o)
       (let [data (ex-data o)]
         (and (= :bulkhead-error (:type data))
              (= :capacity-limit-reached (:code data))))))

(defn invoke!
  [limiter f]
  (->> (px/submit! limiter f)
       (p/hcat (fn [result cause]
                 (cond
                   (capacity-exception? cause)
                   (p/rejected
                    (ex/error :type :internal
                              :code :concurrency-limit-reached
                              :queue (-> limiter meta ::bkey name)
                              :cause cause))

                   (some? cause)
                   (p/rejected cause)

                   :else
                   (p/resolved result))))))

(defn- create-limiter
  [{:keys [::wrk/executor ::mtx/metrics ::bkey ::skey concurrency queue-size]}]
  (let [labels   (into-array String [(name bkey)])
        on-queue (fn [instance]
                   (l/trace :hint "enqueued"
                            :key (name bkey)
                            :skey (str skey)
                            :queue-size (get instance ::pxb/current-queue-size)
                            :concurrency (get instance ::pxb/current-concurrency))
                   (mtx/run! metrics
                             :id :rpc-climit-queue-size
                             :val (get instance ::pxb/current-queue-size)
                             :labels labels)
                   (mtx/run! metrics
                             :id :rpc-climit-concurrency
                             :val (get instance ::pxb/current-concurrency)
                             :labels labels))

        on-run   (fn [instance task]
                   (let [elapsed (- (inst-ms (dt/now))
                                    (inst-ms task))]
                     (l/trace :hint "execute"
                              :key (name bkey)
                              :skey (str skey)
                              :elapsed (str elapsed "ms"))
                     (mtx/run! metrics
                               :id :rpc-climit-timing
                               :val elapsed
                               :labels labels)
                     (mtx/run! metrics
                               :id :rpc-climit-queue-size
                               :val (get instance ::pxb/current-queue-size)
                               :labels labels)
                     (mtx/run! metrics
                               :id :rpc-climit-concurrency
                               :val (get instance ::pxb/current-concurrency)
                               :labels labels)))

        options  {:executor executor
                  :concurrency concurrency
                  :queue-size (or queue-size Integer/MAX_VALUE)
                  :on-queue on-queue
                  :on-run on-run}]

    (-> (pxb/create options)
        (vary-meta assoc ::bkey bkey ::skey skey))))

(defn- create-cache
  [{:keys [::wrk/executor] :as params} config]
  (let [listener (reify RemovalListener
                   (onRemoval [_ key _val cause]
                     (l/trace :hint "cache: remove" :key key :reason (str cause))))

        loader   (reify CacheLoader
                   (load [_ key]
                     (let [[bkey skey] key]
                       (when-let [config (get config bkey)]
                         (-> (merge params config)
                             (assoc ::bkey bkey)
                             (assoc ::skey skey)
                             (create-limiter))))))]

  (.. (Caffeine/newBuilder)
      (weakValues)
      (executor executor)
      (removalListener listener)
      (build loader))))

(defprotocol IConcurrencyManager)

(s/def ::concurrency ::us/integer)
(s/def ::queue-size ::us/integer)
(s/def ::config
  (s/map-of keyword?
            (s/keys :req-un [::concurrency]
                    :opt-un [::queue-size])))

(defmethod ig/prep-key ::rpc/climit
  [_ cfg]
  (merge {::path (cf/get :rpc-climit-config)}
         (d/without-nils cfg)))

(s/def ::path ::fs/path)

(defmethod ig/pre-init-spec ::rpc/climit [_]
  (s/keys :req [::wrk/executor ::mtx/metrics ::path]))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::path] :as params}]
  (when (contains? cf/flags :rpc-climit)
    (if-let [config (some->> path slurp edn/read-string)]
      (do
        (l/info :hint "initializing concurrency limit" :config (str path))
        (us/verify! ::config config)

        (let [cache (create-cache params config)]
          ^{::cache cache}
          (reify
            IConcurrencyManager
            clojure.lang.IDeref
            (deref [_] config)

            clojure.lang.ILookup
            (valAt [_ key]
              (let [key (if (vector? key) key [key])]
                (.get ^Cache cache key))))))

      (l/warn :hint "unable to load configuration" :config (str path)))))


(s/def ::rpc/climit
  (s/nilable #(satisfies? IConcurrencyManager %)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-dispatch
  [lim & body]
  `(if ~lim
     (invoke! ~lim (^:once fn [] (p/wrap (do ~@body))))
     (p/wrap (do ~@body))))

(defn wrap
  [{:keys [::rpc/climit]} f {:keys [::queue ::key-fn] :as mdata}]
  (if (and (some? climit)
           (some? queue))
    (if-let [config (get @climit queue)]
      (do
        (l/debug :hint "wrap: instrumenting method"
                 :limit-name (name queue)
                 :service-name (::sv/name mdata)
                 :queue-size (or (:queue-size config) Integer/MAX_VALUE)
                 :concurrency (:concurrency config)
                 :keyed? (some? key-fn))
        (if (some? key-fn)
          (fn [cfg params]
            (let [key [queue (key-fn params)]
                  lim (get climit key)]
              (invoke! lim (partial f cfg params))))
          (let [lim (get climit queue)]
            (fn [cfg params]
              (invoke! lim (partial f cfg params))))))
      (do
        (l/warn :hint "wrap: no config found"
                :queue (name queue)
                :service (::sv/name mdata))
        f))
    f))
