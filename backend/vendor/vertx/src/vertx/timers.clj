;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.timers
  "The timers and async scheduled tasks."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [vertx.impl :as impl])
  (:import
   io.vertx.core.Vertx
   io.vertx.core.Handler))

;; --- Low Level API

(defn schedule-once!
  [vsm ms f]
  (let [^Vertx system (impl/resolve-system vsm)
        ^Handler handler (impl/fn->handler (fn [v] (f)))
        timer-id (.setTimer system ms handler)]
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.cancelTimer system timer-id)))))

(defn sechdule-periodic!
  [vsm ms f]
  (let [^Vertx system (impl/resolve-system vsm)
        ^Handler handler (impl/fn->handler (fn [v] (f)))
        timer-id (.setPeriodic system ms handler)]
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.cancelTimer system timer-id)))))

;; --- High Level API

(s/def ::once boolean?)
(s/def ::repeat boolean?)
(s/def ::delay integer?)
(s/def ::fn (s/or :fn fn? :var var?))

(s/def ::schedule-opts
  (s/keys :req [::fn ::delay] :opt [::once ::repeat]))

(defn schedule!
  "High level schedule function."
  [vsm {:keys [::once ::repeat ::delay] :as opts}]
  (s/assert ::schedule-opts opts)

  (when (and (not once) (not repeat))
    (throw (IllegalArgumentException. "you should specify `once` or `repeat` params")))

  (let [system (impl/resolve-system vsm)
        state  (atom nil)
        taskfn (fn wrapped-task []
                 (-> (p/do! ((::fn opts) opts))
                     (p/catch' (constantly nil))  ; explicitly ignore all errors
                     (p/then'  (fn [_]            ; the user needs to catch errors
                                 (if repeat
                                   (let [tid (schedule-once! vsm delay wrapped-task)]
                                     (reset! state tid)
                                     nil))
                                 (do
                                   (reset! state nil)
                                   nil)))))
        tid (reset! state (schedule-once! vsm delay taskfn))]
    (reify
      java.lang.AutoCloseable
      (close [this]
        (when (compare-and-set! state tid nil)
          (.cancelTimer ^Vertx system tid))))))

