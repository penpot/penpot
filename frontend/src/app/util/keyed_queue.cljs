;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.keyed-queue
  (:require [app.common.logging :as l]))

(l/set-level! :debug)

(defrecord KeyedQueue [f items keys time max-iterations timeout])

(declare dequeue)
(declare process)

(defn create
  ([f time]
   (create f time 2))
  ([f time max-iterations]
   (KeyedQueue. f
                #js []
                (js/Map.)
                time
                max-iterations
                nil)))

(defn- has-requested-process?
  [queue]
  (assert (instance? KeyedQueue queue))
  (not (nil? (unchecked-get queue "timeout"))))

(defn- request-process
  ([queue]
   (request-process queue (unchecked-get queue "time")))
  ([queue time]
   (assert (instance? KeyedQueue queue))
   (l/dbg :hint "keyed-queue::request-process" :time time)
   (unchecked-set queue "timeout"
                  (js/setTimeout (fn [] (process queue)) time))))

(defn- process
  [queue]
  (assert (instance? KeyedQueue queue))
  (unchecked-set queue "timeout" nil)
  (let [f (unchecked-get queue "f")
        max-iterations (unchecked-get queue "max-iterations")]
    (loop [item (dequeue queue)
           iterations 0]
      (l/dbg :hint "keyed-queue::process" :item item)
      (when (some? item)
        (f item)
        (if (>= iterations max-iterations)
          (request-process queue 1000)
          (recur (dequeue queue) (inc iterations)))))))

(defn- dequeue
  [queue]
  (assert (instance? KeyedQueue queue))
  (l/dbg :hint "keyed-queue::dequeue")
  (let [items (unchecked-get queue "items")
        keys  (unchecked-get queue "keys")
        key (.shift items)]
    (if key
      (let [item (.get keys key)]
        (.delete keys key)
        item)
      nil)))

(defn enqueue
  [queue key item]
  (assert (instance? KeyedQueue queue))
  (l/dbg :hint "keyed-queue::enqueue" :key key :item item)
  (let [items (unchecked-get queue "items")
        keys  (unchecked-get queue "keys")]
    (when-not (.has keys key)
      (.push items key))
    (.set keys key item))
  (when-not (has-requested-process? queue)
    (request-process queue)))
