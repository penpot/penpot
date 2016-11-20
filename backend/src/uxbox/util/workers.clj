;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.workers
  "A distributed asynchronous tasks queue implementation on top
  of PostgreSQL reliable advirsory locking mechanism."
  (:require [suricatta.core :as sc]
            [uxbox.db :as db]
            [uxbox.sql :as sql]))

(defn- poll-for-task
  [conn queue]
  (let [sql (sql/acquire-task {:queue queue})]
    (sc/fetch-one conn sql)))

(defn- mark-task-done
  [conn {:keys [id]}]
  (let [sql (sql/mark-task-done {:id id})]
    (sc/execute conn sql)))

(defn- mark-task-failed
  [conn {:keys [id]} error]
  (let [sql (sql/mark-task-done {:id id :error (.getMessage error)})]
    (sc/execute conn sql)))

(defn- watch-unit
  [conn queue callback]
  (let [task (poll-for-task conn queue)]
    (if (nil? task)
      (Thread/sleep 1000)
      (try
        (sc/atomic conn
          (callback conn task)
          (mark-task-done conn task))
        (catch Exception e
          (mark-task-failed conn task e))))))

(defn- watch-loop
  "Watch tasks on the specified queue and executes a
  callback for each task is received.
  NOTE: This function blocks the current thread."
  [queue callback]
  (try
    (loop []
      (with-open [conn (db/connection)]
        (sc/atomic conn (watch-unit conn queue callback)))
      (recur))
    (catch InterruptedException e
      ;; just ignoring
      )))

(defn watch!
  [queue callback]
  (let [runnable #(watch-loop queue callback)
        thread (Thread. ^Runnable runnable)]
    (.setDaemon thread true)
    (.start thread)
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.interrupt thread)
        (.join thread 2000))

      clojure.lang.IDeref
      (deref [_]
        (.join thread))

       clojure.lang.IBlockingDeref
       (deref [_ ms default]
         (.join thread ms)
         default))))
