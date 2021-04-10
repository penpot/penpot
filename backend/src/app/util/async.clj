;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.async
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s])
  (:import
   java.util.concurrent.Executor))

(s/def ::executor #(instance? Executor %))

(defonce processors
  (delay (.availableProcessors (Runtime/getRuntime))))

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
                      (when (some? ret) (a/>!! c ret)))
                    (finally
                      (a/close! c)))))
      c
      (catch java.util.concurrent.RejectedExecutionException _e
        (a/close! c)
        c))))


(defmacro with-thread
  [executor & body]
  (if (= executor ::default)
    `(a/thread-call (^:once fn* [] (try ~@body (catch Exception e# e#))))
    `(thread-call ~executor (^:once fn* [] ~@body))))
