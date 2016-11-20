;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns executors.core
  "A executos service abstraction layer."
  (:import java.util.function.Supplier
           java.util.concurrent.ForkJoinPool
           java.util.concurrent.Future
           java.util.concurrent.CompletableFuture
           java.util.concurrent.ExecutorService
           java.util.concurrent.TimeoutException
           java.util.concurrent.ThreadFactory
           java.util.concurrent.TimeUnit
           java.util.concurrent.ScheduledExecutorService
           java.util.concurrent.Executors))

(def ^:const +max-priority+ Thread/MAX_PRIORITY)
(def ^:const +min-priority+ Thread/MIN_PRIORITY)
(def ^:const +norm-priority+ Thread/NORM_PRIORITY)

;; --- Protocols

(defprotocol IExecutor
  (^:private -execute [_ task] "Execute a task in a executor.")
  (^:private -submit [_ task] "Submit a task and return a promise."))

(defprotocol IScheduledExecutor
  (^:provate -schedule [_ ms task] "Schedule a task to execute in a future."))

(defprotocol IScheduledTask
  "A cancellation abstraction."
  (-cancel [_])
  (-cancelled? [_]))

;; --- Implementation

(defn- thread-factory-adapter
  "Adapt a simple clojure function into a
  ThreadFactory instance."
  [func]
  (reify ThreadFactory
    (^Thread newThread [_ ^Runnable runnable]
      (func runnable))))

(defn- thread-factory
  [{:keys [daemon priority]
    :or {daemon true
         priority Thread/NORM_PRIORITY}}]
  (thread-factory-adapter
   (fn [runnable]
     (let [thread (Thread. ^Runnable runnable)]
       (.setDaemon thread daemon)
       (.setPriority thread priority)
       thread))))

(defn- resolve-thread-factory
  [opts]
  (cond
    (map? opts) (thread-factory opts)
    (fn? opts) (thread-factory-adapter opts)
    (instance? ThreadFactory opts) opts
    :else (throw (ex-info "Invalid thread factory" {}))))

(deftype ScheduledTask [^Future fut]
  clojure.lang.IDeref
  (deref [_]
    (.get fut))

  clojure.lang.IBlockingDeref
  (deref [_ ms default]
    (try
      (.get fut ms TimeUnit/MILLISECONDS)
        (catch TimeoutException e
          default)))

    clojure.lang.IPending
    (isRealized [_] (and (.isDone fut)
                         (not (.isCancelled fut))))

    IScheduledTask
    (-cancelled? [_]
      (.isCancelled fut))

    (-cancel [_]
      (when-not (.isCancelled fut)
        (.cancel fut true))))

(extend-type ExecutorService
  IExecutor
  (-execute [this task]
    (CompletableFuture/runAsync ^Runnable task this))

  (-submit [this task]
    (let [supplier (reify Supplier (get [_] (task)))]
      (CompletableFuture/supplyAsync supplier this))))

(extend-type ScheduledExecutorService
  IScheduledExecutor
  (-schedule [this ms func]
    (let [fut (.schedule this func ms TimeUnit/MILLISECONDS)]
      (ScheduledTask. fut))))

;; --- Public Api (Pool Constructors)

(defn common-pool
  "Get the common pool."
  []
  (ForkJoinPool/commonPool))

(defn cached
  "A cached thread pool constructor."
  ([]
   (Executors/newCachedThreadPool))
  ([opts]
   (let [factory (resolve-thread-factory opts)]
     (Executors/newCachedThreadPool factory))))

(defn fixed
  "A fixed thread pool constructor."
  ([n]
   (Executors/newFixedThreadPool (int n)))
  ([n opts]
   (let [factory (resolve-thread-factory opts)]
     (Executors/newFixedThreadPool (int n) factory))))

(defn single-thread
  "A single thread pool constructor."
  ([]
   (Executors/newSingleThreadExecutor))
  ([opts]
   (let [factory (resolve-thread-factory opts)]
     (Executors/newSingleThreadExecutor factory))))

(defn scheduled
  "A scheduled thread pool constructo."
  ([] (Executors/newScheduledThreadPool (int 1)))
  ([n] (Executors/newScheduledThreadPool (int n)))
  ([n opts]
   (let [factory (resolve-thread-factory opts)]
     (Executors/newScheduledThreadPool (int n) factory))))

;; --- Public Api (Task Execution)

(defn execute
  "Execute a task in a provided executor.

  A task is a plain clojure function or
  jvm Runnable instance."
  ([task]
   (-> (common-pool)
       (-execute task)))
  ([executor task]
   (-execute executor task)))

(defn submit
  "Submit a task to be executed in a provided executor
  and return a promise that will be completed with
  the return value of a task.

  A task is a plain clojure function."
  ([task]
   (-> (common-pool)
       (-submit task)))
  ([executor task]
   (-submit executor task)))

(defn schedule
  "Schedule task exection for some time in the future."
  ([ms task]
   (-> (common-pool)
       (-schedule ms task)))
  ([executor ms task]
   (-schedule executor ms task)))
