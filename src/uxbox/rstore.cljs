;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.rstore
  "Reactive storage management architecture helpers."
  (:require [beicon.core :as rx]))

;; An abstraction for implement a simple state
;; transition. The `-apply-update` function receives
;; the state and shoudl return the transformed state.

(defprotocol UpdateEvent
  (-apply-update [event state]))

;; An abstraction for perform some async stuff such
;; as communicate with api rest or other resources
;; that implies asynchronous access.
;; The `-apply-watch` receives the state and should
;; return a reactive stream of events (that can be
;; of `UpdateEvent`, `WatchEvent` or `EffectEvent`.

(defprotocol WatchEvent
  (-apply-watch [event state s]))

;; An abstraction for perform just side effects. It
;; receives state and its return value is completly
;; ignored.

(defprotocol EffectEvent
  (-apply-effect [event state]))

(defn update?
  "Return `true` when `e` satisfies
  the UpdateEvent protocol."
  [e]
  (satisfies? UpdateEvent e))

(defn watch?
  "Return `true` when `e` satisfies
  the WatchEvent protocol."
  [e]
  (satisfies? WatchEvent e))

(defn effect?
  "Return `true` when `e` satisfies
  the EffectEvent protocol."
  [e]
  (satisfies? EffectEvent e))

(extend-protocol UpdateEvent
  function
  (-apply-update [func state]
    (func state)))

(defonce ^:private bus (rx/bus))
(defonce stream (rx/map identity bus))

(defn emit!
  "Emits an event or a collection of them.
  The order of events does not matters."
  ([event]
   (rx/push! bus event))
  ([event & events]
   (run! emit! (cons event events))))

(defrecord SwapState [f]
  UpdateEvent
  (-apply-update [_ state]
    (f state)))

(defn swap
  "A helper for just apply some function to state
  without a need to declare additional event."
  [f]
  (->SwapState f))

(defn reset
  "A event that resets the internal state with
  the provided value."
  [v]
  (->SwapState (fn [_] v)))

(enable-console-print!)

(defn- on-error
  "A default error handler."
  [e]
  (println "Unexpected error: " e)
  (js/console.error e.stack)
  (rx/throw e))

(defn init
  "Initializes the stream event loop and
  return a stream with model changes."
  [state]
  (let [watch-s  (rx/filter watch? stream)
        effect-s (rx/filter effect? stream)
        update-s (rx/filter update? stream)
        state-s (->> update-s
                     (rx/scan #(-apply-update %2 %1) state)
                     (rx/catch on-error)
                     (rx/retry 1024)
                     (rx/share))]

    ;; Process event sources: combine with the latest model and the result will be
    ;; pushed to the event-stream bus
    (as-> watch-s $
      (rx/with-latest-from vector state-s $)
      (rx/flat-map (fn [[event model]] (-apply-watch event model stream)) $)
      (rx/catch on-error $)
      (rx/retry 1024 $)
      (rx/on-value $ emit!))

    ;; Process effects: combine with the latest model to process the new effect
    (as-> effect-s $
      (rx/with-latest-from vector state-s $)
      (rx/subscribe $ (fn [[event model]] (-apply-effect event model))))

    ;; Initialize the stream machinary with initial state.
    (emit! (swap #(merge % state)))
    state-s))
