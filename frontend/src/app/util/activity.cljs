;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.activity
  "When the user last used the pointer or the keyboard, so work that can
  wait is done while they don't."
  (:require
   [beicon.v2.core :as rx]))

(defonce ^:private last-input (volatile! ##-Inf))

(defn- now
  []
  (js/performance.now))

(defn- record-input
  []
  (vreset! last-input (now)))

(defn- on-input
  [^js event]
  ;; Only the user's input counts, not the events that scripts dispatch
  (when (.-isTrusted event)
    (record-input)))

(defonce listening?
  (when (exists? js/document)
    (let [opts #js {:capture true :passive true}]
      (doseq [type ["pointerdown" "keydown" "wheel"]]
        (.addEventListener js/document type on-input opts))
      ;; Only dragging counts: hovering doesn't block anything
      (.addEventListener js/document "pointermove"
                         #(when (pos? (.-buttons ^js %)) (on-input %))
                         opts))
    true))

(defn idle
  "Observable that emits nil and completes once the user hasn't used the
  pointer or the keyboard for `ms`, or after `max-ms` anyway. `input-time`
  gives the time of the last input, by default the user's."
  ([ms max-ms]
   (idle ms max-ms #(deref last-input)))
  ([ms max-ms input-time]
   (rx/create
    (fn [subs]
      (let [start  (now)
            timer* (volatile! nil)
            check  (fn check []
                     (let [time  (now)
                           quiet (- time (input-time))]
                       (if (or (>= quiet ms) (>= (- time start) max-ms))
                         (do (rx/push! subs nil)
                             (rx/end! subs))
                         (vreset! timer* (js/setTimeout check (min (- ms quiet)
                                                                   (- max-ms (- time start))))))))]
        (check)
        #(some-> @timer* js/clearTimeout))))))

(def pause-ms
  "Time without the user using the pointer or the keyboard taken as a pause
  for the work in the background."
  1000)

(def max-pause-wait-ms
  "Longest wait for a pause, so the work goes on while the user works."
  5000)

(defn pause
  "Observable that emits nil and completes once the user makes a pause."
  []
  (idle pause-ms max-pause-wait-ms))
