;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.async
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [clojure.core.async :as a])
  (:import
   java.util.concurrent.Executor))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Exception e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Exception r#)
       (throw r#)
       r#)))

(defmacro thread-try
  [& body]
  `(a/thread
     (try
       ~@body
       (catch Exception e#
         e#))))


(s/def ::executor #(instance? Executor %))

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
        (a/offer! c e)
        (a/close! c)
        c))))
