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
   [app.util.cache :as cache]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh])
  (:import
   clojure.lang.ExceptionInfo
   java.util.concurrent.atomic.AtomicLong))

(set! *warn-on-reflection* true)

(defn- id->str
  ([id]
   (-> (str id)
       (subs 1)))
  ([id key]
   (if key
     (str (-> (str id) (subs 1)) "/" key)
     (id->str id))))

(defn- create-cache
  [{:keys [::wrk/executor]}]
  (letfn [(on-remove [key _ cause]
            (let [[id skey] key]
              (l/trc :hint "disposed" :id (id->str id skey) :reason (str cause))))]
    (cache/create :executor executor
                  :on-remove on-remove
                  :keepalive "5m")))

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
  (s/keys :req [::mtx/metrics ::wrk/executor ::path]))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::path ::mtx/metrics] :as cfg}]
  (when (contains? cf/flags :rpc-climit)
    (when-let [params (some->> path slurp edn/read-string)]
      (l/inf :hint "initializing concurrency limit" :config (str path))
      (us/verify! ::config params)
      {::cache (create-cache cfg)
       ::config params
       ::mtx/metrics metrics})))

(s/def ::cache cache/cache?)
(s/def ::instance
  (s/keys :req [::cache ::config]))

(s/def ::rpc/climit
  (s/nilable ::instance))

(defn- create-limiter
  [config [id skey]]
  (l/trc :hint "created" :id (id->str id skey))
  (pbh/create :permits (or (:permits config) (:concurrency config))
              :queue (or (:queue config) (:queue-size config))
              :timeout (:timeout config)
              :type :semaphore))

(defmacro ^:private measure-and-log!
  [metrics mlabels stats id action limit-id limit-label profile-id elapsed]
  `(let [mpermits# (:max-permits ~stats)
         mqueue#   (:max-queue ~stats)
         permits#  (:permits ~stats)
         queue#    (:queue ~stats)
         queue#    (- queue# mpermits#)
         queue#    (if (neg? queue#) 0 queue#)
         level#    (if (pos? queue#) :warn :trace)]

     (mtx/run! ~metrics
               :id :rpc-climit-queue
               :val queue#
               :labels ~mlabels)

     (mtx/run! ~metrics
               :id :rpc-climit-permits
               :val permits#
               :labels ~mlabels)

     (l/log level#
            :hint ~action
            :req ~id
            :id ~limit-id
            :label ~limit-label
            :profile-id (str ~profile-id)
            :permits permits#
            :queue queue#
            :max-permits mpermits#
            :max-queue mqueue#
            ~@(if (some? elapsed)
                [:elapsed `(dt/format-duration ~elapsed)]
                []))))

(def ^:private idseq (AtomicLong. 0))

(defn- invoke
  [limiter metrics limit-id limit-key limit-label profile-id f params]
  (let [tpoint    (dt/tpoint)
        mlabels   (into-array String [(id->str limit-id)])
        limit-id  (id->str limit-id limit-key)
        stats     (pbh/get-stats limiter)
        id        (.incrementAndGet ^AtomicLong idseq)]

    (try
      (measure-and-log! metrics mlabels stats id "enqueued" limit-id limit-label profile-id nil)
      (px/invoke! limiter (fn []
                            (let [elapsed (tpoint)
                                  stats   (pbh/get-stats limiter)]
                              (measure-and-log! metrics mlabels stats id "acquired" limit-id limit-label profile-id elapsed)
                              (mtx/run! metrics
                                        :id :rpc-climit-timing
                                        :val (inst-ms elapsed)
                                        :labels mlabels)
                              (apply f params))))

      (catch ExceptionInfo cause
        (let [{:keys [type code]} (ex-data cause)]
          (if (= :bulkhead-error type)
            (let [elapsed (tpoint)]
              (measure-and-log! metrics mlabels stats id "reject" limit-id limit-label profile-id elapsed)
              (ex/raise :type :concurrency-limit
                        :code code
                        :hint "concurrency limit reached"
                        :cause cause))
            (throw cause))))

      (finally
        (let [elapsed (tpoint)
              stats (pbh/get-stats limiter)]
          (measure-and-log! metrics mlabels stats id "finished" limit-id limit-label profile-id elapsed))))))

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
  [{:keys [::rpc/climit ::mtx/metrics]} handler mdata]
  (let [cache  (::cache climit)
        config (::config climit)
        label  (::sv/name mdata)]

    (if climit
      (reduce (fn [handler [limit-id key-fn]]
                (if-let [config (get config limit-id)]
                  (let [key-fn (or key-fn noop-fn)]
                    (l/dbg :hint "instrumenting method"
                           :method label
                           :limit (id->str limit-id)
                           :timeout (:timeout config)
                           :permits (:permits config)
                           :queue (:queue config)
                           :keyed (not= key-fn noop-fn))

                    (if (and (= key-fn ::rpc/profile-id)
                             (false? (::rpc/auth mdata true)))

                      ;; We don't enforce by-profile limit on methods that does
                      ;; not require authentication
                      handler

                      (fn [cfg params]
                        (let [limit-key  (key-fn params)
                              cache-key  [limit-id limit-key]
                              limiter    (cache/get cache cache-key (partial create-limiter config))
                              profile-id (if (= key-fn ::rpc/profile-id)
                                           limit-key
                                           (get params ::rpc/profile-id))]
                          (invoke limiter metrics limit-id limit-key label profile-id handler [cfg params])))))

                  (do
                    (l/wrn :hint "no config found for specified queue" :id (id->str limit-id))
                    handler)))

              handler
              (concat global-limits (get-limits mdata)))
      handler)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-exec-chain
  [{:keys [::label ::profile-id ::rpc/climit ::mtx/metrics] :as cfg} f]
  (let [config (get climit ::config)
        cache  (get climit ::cache)]

    (reduce (fn [handler [limit-id limit-key :as ckey]]
              (let [config (get config limit-id)]
                (when-not config
                  (throw (IllegalArgumentException.
                          (str/ffmt "config not found for: %" limit-id))))

                (fn [& params]
                  (let [limiter (cache/get cache ckey (partial create-limiter config))]
                    (invoke limiter metrics limit-id limit-key label profile-id handler params)))))
            f
            (get-limits cfg))))

(defn invoke!
  "Run a function in context of climit.
  Intended to be used in virtual threads."
  [{:keys [::executor] :as cfg} f & params]
  (let [f (if (some? executor)
            (fn [& params] (px/await! (px/submit! executor (fn [] (apply f params)))))
            f)
        f (build-exec-chain cfg f)]
    (apply f params)))
