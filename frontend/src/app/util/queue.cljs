;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.queue
  (:require [app.common.logging :as l]
            [app.common.math :as mth]
            [app.util.time :as t]))

(l/set-level! :info)

(declare process)
(declare dequeue)

(defrecord Queue [f items timeout time threshold max-iterations])

(defn create
  [f threshold]
  (Queue. f
          #js []
          nil
          0
          threshold
          ##Inf))

(defn- measure-fn
  [f & args]
  (let [tp       (t/tpoint-ms)
        _        (apply f args)
        duration (tp)]
    (l/dbg :hint "queue::measure-fn" :duration duration)
    duration))

(defn- next-process-time
  [queue]
  (let [time      (unchecked-get queue "time")
        threshold (unchecked-get queue "threshold")
        max-time  5000
        min-time  1000
        calc-time (mth/min (mth/max (* (- time threshold) 10) min-time) max-time)]
    (l/dbg :hint "queue::next-process-time" :time time :threshold threshold :calc-time calc-time :max-time max-time :min-time min-time)
    calc-time))

(defn- has-requested-process?
  [queue]
  (not (nil? (unchecked-get queue "timeout"))))

(defn- request-process
  [queue time]
  (l/dbg :hint "queue::request-process" :time time)
  (unchecked-set queue "timeout" (js/setTimeout (fn [] (process queue)) time)))

;; NOTE: Right now there are no cases where we need to cancel a process
;;       but if we do, we can use this function
#_(defn- cancel-process
    [queue]
    (l/dbg :hint "queue::cancel-process")
    (let [timeout (unchecked-get queue "timeout")]
      (when (some? timeout)
        (js/clearTimeout timeout))
      (unchecked-set queue "timeout" nil)))

(defn- process
  [queue]
  (unchecked-set queue "timeout" nil)
  (unchecked-set queue "time" 0)
  (let [threshold      (unchecked-get queue "threshold")
        max-iterations (unchecked-get queue "max-iterations")
        f              (unchecked-get queue "f")]
    (loop [item (dequeue queue)
           iterations 0]
      (l/dbg :hint "queue::process" :item item)
      (when (some? item)
        (let [duration (measure-fn f item)
              time     (unchecked-get queue "time")
              time     (unchecked-set queue "time" (+ time duration))]
          (if (or (> time threshold) (>= iterations max-iterations))
            (request-process queue (next-process-time queue))
            (recur (dequeue queue) (inc iterations))))))))

(defn- dequeue
  [queue]
  (let [items (unchecked-get queue "items")]
    (.shift items)))

(defn enqueue-first
  [queue item]
  (assert (instance? Queue queue))
  (let [items (unchecked-get queue "items")]
    (.unshift items item)
    (when-not (has-requested-process? queue)
      (request-process queue (next-process-time queue)))))

(defn enqueue-last
  [queue item]
  (assert (instance? Queue queue))
  (let [items (unchecked-get queue "items")]
    (.push items item)
    (when-not (has-requested-process? queue)
      (request-process queue (next-process-time queue)))))

(defn enqueue-unique
  [queue item f]
  (assert (instance? Queue queue))
  (let [items (unchecked-get queue "items")]
    ;; If tag is "frame", then they are added to the front of the queue
    ;; so that they are processed first, anything else is added to the
    ;; end of the queue.
    (if (= (unchecked-get item "tag") "frame")
      (when-not (.find ^js items f)
        (enqueue-first queue item))
      (when-not (.findLast ^js items f)
        (enqueue-last queue item)))))
