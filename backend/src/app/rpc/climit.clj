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
   java.util.concurrent.atomic.AtomicLong))

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
      (let [params (reduce-kv (fn [result k v]
                                (assoc result k (assoc v ::id k)))
                              params
                              params)
            cache  (create-cache cfg)]

        (reify
          IConcurrencyLimiter
          (get-config [_ id]
            (get params id))

          (invoke [_ config handler]
            (impl-invoke metrics cache config handler)))))))

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
  [metrics mlabels stats elapsed]
  (let [mpermits (:max-permits stats)
        permits  (:permits stats)
        queue    (:queue stats)
        queue    (- queue mpermits)
        queue    (if (neg? queue) 0 queue)]

    (mtx/run! metrics
              :id :rpc-climit-queue
              :val queue
              :labels mlabels)

    (mtx/run! metrics
              :id :rpc-climit-permits
              :val permits
              :labels mlabels)

    (when elapsed
      (mtx/run! metrics
                :id :rpc-climit-timing
                :val (inst-ms elapsed)
                :labels mlabels))))

(defn- prepare-params-for-debug
  [params]
  (-> (select-keys params [::rpc/profile-id :file-id :profile-id])
      (set/rename-keys {::rpc/profile-id :profile-id})
      (update-vals str)))

(defn- log
  [action req-id stats limit-id limit-label limit-params elapsed]
  (let [mpermits (:max-permits stats)
        queue    (:queue stats)
        queue    (- queue mpermits)
        queue    (if (neg? queue) 0 queue)
        level    (if (pos? queue) :warn :trace)]

    (l/log level
           :hint action
           :req req-id
           :id limit-id
           :label limit-label
           :queue queue
           :elapsed (some-> elapsed ct/format-duration)
           :params @limit-params)))

(def ^:private idseq (AtomicLong. 0))

(defn- impl-invoke
  [metrics cache config handler]
  (let [limit-id     (::id config)
        limit-key    (::key config)
        limit-label  (::label config)
        limit-params (delay
                       (prepare-params-for-debug
                        (::params config)))

        mlabels     (into-array String [(id->str limit-id)])
        limit-id    (id->str limit-id limit-key)
        limiter     (cache/get cache limit-id (partial create-limiter config))
        tpoint      (ct/tpoint)
        req-id      (.incrementAndGet ^AtomicLong idseq)]
    (try
      (let [stats (pbh/get-stats limiter)]
        (measure metrics mlabels stats nil)
        (log "enqueued" req-id stats limit-id limit-label limit-params nil))

      (pbh/invoke! limiter (fn []
                             (let [elapsed (tpoint)
                                   stats   (pbh/get-stats limiter)]
                               (measure metrics mlabels stats elapsed)
                               (log "acquired" req-id stats limit-id limit-label limit-params elapsed)
                               (handler))))

      (catch ExceptionInfo cause
        (let [{:keys [type code]} (ex-data cause)]
          (if (= :bulkhead-error type)
            (let [elapsed (tpoint)
                  stats   (pbh/get-stats limiter)]
              (log "rejected" req-id stats limit-id limit-label limit-params elapsed)
              (ex/raise :type :concurrency-limit
                        :code code
                        :hint "concurrency limit reached"
                        :cause cause))
            (throw cause))))

      (finally
        (let [elapsed (tpoint)
              stats (pbh/get-stats limiter)]

          (measure metrics mlabels stats nil)
          (log "finished" req-id stats limit-id limit-label limit-params elapsed))))))

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
            (concat global-limits (get-limits mdata)))

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
  "Run a function in context of climit.
  Intended to be used in virtual threads."
  [{:keys [::executor ::rpc/climit] :as cfg} f params]
  (let [f (if climit
            (let [f (if (some? executor)
                      (fn [cfg params] (px/await! (px/submit! executor (fn [] (f cfg params)))))
                      f)]
              (build-exec-chain cfg f))
            f)]
    (f cfg params)))
