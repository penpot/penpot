;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.async
  (:require
   [app.common.exceptions :as ex]
   [clojure.core.async :as a]
   [clojure.core.async.impl.protocols :as ap]
   [clojure.spec.alpha :as s])
  (:import
   java.util.concurrent.Executor
   java.util.concurrent.RejectedExecutionException))

(s/def ::executor #(instance? Executor %))
(s/def ::channel #(satisfies? ap/Channel %))

(defonce processors
  (delay (.availableProcessors (Runtime/getRuntime))))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Exception e# e#))))

(defmacro thread
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

(defmacro with-closing
  [ch & body]
  `(try
     ~@body
     (finally
       (some-> ~ch a/close!))))

(defn thread-call
  [^Executor executor f]
  (let [ch (a/chan 1)
        f' (fn []
             (try
               (let [ret (ex/try* f identity)]
                 (when (some? ret) (a/>!! ch ret)))
               (finally
                 (a/close! ch))))]
    (try
      (.execute executor f')
      (catch RejectedExecutionException _cause
        (a/close! ch)))

    ch))

(defmacro with-thread
  [executor & body]
  (if (= executor ::default)
    `(a/thread-call (^:once fn* [] (try ~@body (catch Exception e# e#))))
    `(thread-call ~executor (^:once fn* [] ~@body))))

(defn batch
  [in {:keys [max-batch-size
              max-batch-age
              buffer-size
              init]
       :or {max-batch-size 200
            max-batch-age (* 30 1000)
            buffer-size 128
            init #{}}
       :as opts}]
  (let [out (a/chan buffer-size)]
    (a/go-loop [tch (a/timeout max-batch-age) buf init]
      (let [[val port] (a/alts! [tch in])]
        (cond
          (identical? port tch)
          (if (empty? buf)
            (recur (a/timeout max-batch-age) buf)
            (do
              (a/>! out [:timeout buf])
              (recur (a/timeout max-batch-age) init)))

          (nil? val)
          (if (empty? buf)
            (a/close! out)
            (do
              (a/offer! out [:timeout buf])
              (a/close! out)))

          (identical? port in)
          (let [buf (conj buf val)]
            (if (>= (count buf) max-batch-size)
              (do
                (a/>! out [:size buf])
                (recur (a/timeout max-batch-age) init))
              (recur tch buf))))))
    out))

(defn thread-sleep
  [ms]
  (Thread/sleep (long ms)))
