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
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh])
  (:import
   clojure.lang.ExceptionInfo))

(set! *warn-on-reflection* true)

(defn- id->str
  [id]
  (-> (str id)
      (subs 1)))

(defn- create-bulkhead-cache
  [config]
  (letfn [(load-fn [[id skey]]
            (when-let [config (get config id)]
              (l/trc :hint "insert into cache" :id (id->str id) :key skey)
              (pbh/create :permits (or (:permits config) (:concurrency config))
                          :queue (or (:queue config) (:queue-size config))
                          :timeout (:timeout config)
                          :type :semaphore)))

          (on-remove [key _ cause]
            (let [[id skey] key]
              (l/trc :hint "evict from cache" :id (id->str id) :key skey :reason (str cause))))]

    (cache/create :executor :same-thread
                  :on-remove on-remove
                  :keepalive "5m"
                  :load-fn load-fn)))

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
  (s/keys :req [::mtx/metrics ::path]))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::path ::mtx/metrics] :as cfg}]
  (when (contains? cf/flags :rpc-climit)
    (when-let [params (some->> path slurp edn/read-string)]
      (l/inf :hint "initializing concurrency limit" :config (str path))
      (us/verify! ::config params)
      {::cache (create-bulkhead-cache params)
       ::config params
       ::mtx/metrics metrics})))

(s/def ::cache cache/cache?)
(s/def ::instance
  (s/keys :req [::cache ::config]))

(s/def ::rpc/climit
  (s/nilable ::instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke!
  [cache metrics id key f]
  (if-let [limiter (cache/get cache [id key])]
    (let [tpoint  (dt/tpoint)
          labels  (into-array String [(id->str id)])
          wrapped (fn []
                    (let [elapsed (tpoint)
                          stats   (pbh/get-stats limiter)]
                      (l/trc :hint "acquired"
                             :id (id->str id)
                             :key key
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
                            (l/trc :hint "finished"
                                   :id (id->str id)
                                   :key key
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
          (l/trc :hint "enqueued"
                 :id (id->str id)
                 :key key
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
          (measure! (pbh/get-stats limiter)))))

    (do
      (l/wrn :hint "unable to load limiter" :id (id->str id))
      (f))))

(defn configure
  [{:keys [::rpc/climit]} id]
  (us/assert! ::rpc/climit climit)
  (assoc climit ::id id))

(defn run!
  "Run a function in context of climit.
  Intended to be used in virtual threads."
  ([{:keys [::id ::cache ::mtx/metrics]} f]
   (if (and cache id)
     (invoke! cache metrics id nil f)
     (f)))

  ([{:keys [::id ::cache ::mtx/metrics]} f executor]
   (let [f #(p/await! (px/submit! executor f))]
     (if (and cache id)
       (invoke! cache metrics id nil f)
       (f)))))

(def noop-fn (constantly nil))

(defn wrap
  [{:keys [::rpc/climit ::mtx/metrics]} f {:keys [::id ::key-fn] :or {key-fn noop-fn} :as mdata}]
  (if (and (some? climit) (some? id))
    (if-let [config (get-in climit [::config id])]
      (let [cache (::cache climit)]
        (l/dbg :hint "instrumenting method"
               :limit (id->str id)
               :service-name (::sv/name mdata)
               :timeout (:timeout config)
               :permits (:permits config)
               :queue (:queue config)
               :keyed? (not= key-fn noop-fn))

        (fn [cfg params]
          (invoke! cache metrics id (key-fn params) (partial f cfg params))))

      (do
        (l/wrn :hint "no config found for specified queue" :id (id->str id))
        f))

    f))
