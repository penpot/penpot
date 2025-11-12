;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.executor
  "Async tasks abstraction (impl)."
  (:require
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   io.netty.channel.nio.NioEventLoopGroup
   io.netty.util.concurrent.DefaultEventExecutorGroup
   java.util.concurrent.ExecutorService
   java.util.concurrent.ThreadFactory
   java.util.concurrent.TimeUnit))

(set! *warn-on-reflection* true)

(sm/register!
 {:type ::wrk/executor
  :pred #(instance? ExecutorService %)
  :type-properties
  {:title "executor"
   :description "Instance of ExecutorService"}})

(sm/register!
 {:type ::wrk/netty-io-executor
  :pred #(instance? NioEventLoopGroup %)
  :type-properties
  {:title "executor"
   :description "Instance of NioEventLoopGroup"}})

(sm/register!
 {:type ::wrk/netty-executor
  :pred #(instance? DefaultEventExecutorGroup %)
  :type-properties
  {:title "executor"
   :description "Instance of DefaultEventExecutorGroup"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IO Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::wrk/netty-io-executor
  [_ {:keys [threads]}]
  (assert (or (nil? threads) (int? threads))
          "expected valid threads value, revisit PENPOT_NETTY_IO_THREADS environment variable"))

(defmethod ig/init-key ::wrk/netty-io-executor
  [_ {:keys [threads]}]
  (let [factory  (px/thread-factory :prefix "penpot/netty-io/")
        nthreads (or threads (mth/round (/ (px/get-available-processors) 2)))
        nthreads (max 2 nthreads)]
    (l/inf :hint "start netty io executor" :threads nthreads)
    (NioEventLoopGroup. (int nthreads) ^ThreadFactory factory)))

(defmethod ig/halt-key! ::wrk/netty-io-executor
  [_ instance]
  (deref (.shutdownGracefully ^NioEventLoopGroup instance
                              (long 100)
                              (long 1000)
                              TimeUnit/MILLISECONDS)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IO Offload Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::wrk/netty-executor
  [_ {:keys [threads]}]
  (assert (or (nil? threads) (int? threads))
          "expected valid threads value, revisit PENPOT_EXEC_THREADS environment variable"))

(defmethod ig/init-key ::wrk/netty-executor
  [_ {:keys [threads]}]
  (let [factory  (px/thread-factory :prefix "penpot/exec/")
        nthreads (or threads (mth/round (/ (px/get-available-processors) 2)))
        nthreads (max 2 nthreads)]
    (l/inf :hint "start default executor" :threads nthreads)
    (DefaultEventExecutorGroup. (int nthreads) ^ThreadFactory factory)))

(defmethod ig/halt-key! ::wrk/netty-executor
  [_ instance]
  (px/shutdown! instance))
