;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.async
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [cuerdas.core :as str])
  (:import
   java.util.concurrent.Executor
   java.util.concurrent.ThreadFactory
   java.util.concurrent.ForkJoinPool
   java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
   java.util.concurrent.ExecutorService
   java.util.concurrent.atomic.AtomicLong))

(s/def ::executor #(instance? Executor %))

(defonce processors
  (delay (.availableProcessors (Runtime/getRuntime))))

;; (defn forkjoin-thread-factory
;;   [f]
;;   (reify ForkJoinPool$ForkJoinWorkerThreadFactory
;;     (newThread [this pool]
;;       (let [wth (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)]
;;         (f wth)))))

;; (defn forkjoin-named-thread-factory
;;   [name]
;;   (reify ForkJoinPool$ForkJoinWorkerThreadFactory
;;     (newThread [this pool]
;;       (let [wth (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)]
;;         (.setName wth (str name ":" (.getPoolIndex wth)))
;;         wth))))

;; (defn forkjoin-pool
;;   [{:keys [factory async? parallelism]
;;     :or {async? true}
;;     :as opts}]
;;   (let [parallelism (or parallelism @processors)
;;         factory (cond
;;                   (fn? factory) (forkjoin-thread-factory factory)
;;                   (instance? ForkJoinPool$ForkJoinWorkerThreadFactory factory) factory
;;                   (nil? factory) ForkJoinPool/defaultForkJoinWorkerThreadFactory
;;                   :else (throw (ex-info "Unexpected thread factory" {:factory factory})))]
;;     (ForkJoinPool. (or parallelism @processors) factory nil async?)))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Exception e# e#))))

(defmacro thread-try
  [& body]
  `(a/thread
     (try
       ~@body
       (catch Exception e#
         e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Exception r#)
       (throw r#)
       r#)))

(defn thread-call
  [^Executor executor f]
  (let [c (a/chan 1)]
    (try
      (.execute executor
                (fn []
                  (try
                    (let [ret (try (f) (catch Exception e e))]
                      (when-not (nil? ret)
                        (a/>!! c ret)))
                    (finally
                      (a/close! c)))))
      c
      (catch java.util.concurrent.RejectedExecutionException e
        (a/close! c)
        c))))


(defmacro with-thread
  [executor & body]
  (if (= executor ::default)
    `(a/thread-call (^:once fn* [] (try ~@body (catch Exception e# e#))))
    `(thread-call ~executor (^:once fn* [] ~@body))))
