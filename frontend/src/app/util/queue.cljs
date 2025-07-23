;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.queue
  "Low-Level queuing mechanism, mainly used for process thumbnails"
  (:require
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.time :as ct]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(l/set-level! :info)

(declare process)
(declare request-process)

(defn create
  [find-fn threshold]
  #js {:find-fn find-fn
       :items #js []
       :timeout nil
       :time 0
       :threshold threshold
       :max-iterations ##Inf})

(defn- next-process-time
  [queue]
  (let [time      (unchecked-get queue "time")
        threshold (unchecked-get queue "threshold")
        max-time  5000
        min-time  1000
        calc-time (mth/min (mth/max (* (- time threshold) 10) min-time) max-time)]
    (l/dbg :hint "queue::next-process-time"
           :time time
           :threshold threshold
           :calc-time calc-time
           :max-time max-time
           :min-time min-time)
    calc-time))

(defn- has-requested-process?
  [queue]
  (some? (unchecked-get queue "timeout")))

;; NOTE: Right now there are no cases where we need to cancel a process
;;       but if we do, we can use this function
(defn- cancel-process!
  [queue]
  (l/dbg :hint "queue::cancel-process")
  (let [timeout (unchecked-get queue "timeout")]
    (when (some? timeout)
      (js/clearTimeout timeout))
    (unchecked-set queue "timeout" nil))
  queue)

(defn- process
  [queue iterations]
  (let [threshold      (unchecked-get queue "threshold")
        max-iterations (unchecked-get queue "max-iterations")
        items          (unchecked-get queue "items")
        item           (.shift ^js items)]

    (when (some? item)
      (let [tp  (ct/tpoint-ms)
            f   (unchecked-get item "f")
            res (unchecked-get item "result")]
        (rx/subscribe (f)
                      (fn [o]
                        (rx/push! res o))
                      (fn [e]
                        (rx/error! res e))
                      (fn []
                        (rx/end! res)
                        (let [duration (tp)
                              time     (unchecked-get queue "time")
                              time     (+ time duration)]
                          (unchecked-set queue "time" time)
                          (if (or (> time threshold) (>= iterations max-iterations))
                            (request-process queue 0 (next-process-time queue))
                            (request-process queue (inc iterations) 0)))))))))

(defn- request-process
  [queue iterations time]
  (l/dbg :hint "queue::request-process" :time time)
  (unchecked-set queue "timeout"
                 (js/setTimeout
                  (fn []
                    (unchecked-set queue "timeout" nil)
                    (process queue iterations))
                  time)))

(defn- enqueue-first
  [queue item]
  (let [items (unchecked-get queue "items")]
    (.unshift ^js items item)
    (when-not (has-requested-process? queue)
      (request-process queue 0 (next-process-time queue)))))

(defn- enqueue-last
  [queue item]
  (let [items (unchecked-get queue "items")]
    (.push ^js items item)
    (when-not (has-requested-process? queue)
      (request-process queue 0 (next-process-time queue)))))

(defn enqueue-unique
  [queue request f]
  (let [items   (unchecked-get queue "items")
        find-fn (unchecked-get queue "find-fn")
        result  (rx/subject)]

    (unchecked-set request "result" result)
    (unchecked-set request "f" f)

    ;; If tag is "frame", then they are added to the front of the queue
    ;; so that they are processed first, anything else is added to the
    ;; end of the queue.
    (if (= (unchecked-get request "tag") "frame")
      (let [item (.find ^js items find-fn)]
        (if item
          (let [other-result (unchecked-get item "result")]
            (rx/subscribe other-result result))
          (enqueue-first queue request)))

      (let [item (.findLast ^js items find-fn)]
        (if item
          (let [other-result (unchecked-get item "result")]
            (rx/subscribe other-result result))
          (enqueue-last queue request))))

    (rx/to-observable result)))

(defn clear!
  [queue]
  (-> queue
      (cancel-process!)
      (obj/set! "items" #js [])
      (obj/set! "time" 0)))
