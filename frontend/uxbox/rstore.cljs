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
  (-apply-watch [event state]))

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

(defonce bus (rx/bus))

(defn emit!
  "Emits an event or a collection of them.
  The order of events does not matters."
  ([event]
   (rx/push! bus event))
  ([event & events]
   (run! #(rx/push! bus %) (into [event] events))))

(defn swap-state
  "A helper for just apply some function to state
  without a need to declare additional event."
  [f]
  (reify
    UpdateEvent
    (-apply-update [_ state]
      (f state))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:rstore/swap-state>"))))

(defn reset-state
  "A event that resets the internal state with
  the provided value."
  [state]
  (reify
    IPrintWithWriter
    (-pr-writer [_ writer x]
      (-write writer "#<event:rstore/reset-state ")
      (-pr-writer state writer x)
      (-write writer ">"))

    UpdateEvent
    (-apply-update [_ _]
      state)))

(defn init
  "Initializes the stream event loop and
  return a stream with model changes."
  [state]
  (let [update-s (rx/filter update? bus)
        watch-s  (rx/filter watch? bus)
        effect-s (rx/filter effect? bus)
        state-s (->> update-s
                     (rx/scan #(-apply-update %2 %1) state)
                     (rx/share))]

    ;; Process effects: combine with the latest model to process the new effect
    (-> (rx/with-latest-from vector state-s effect-s)
        (rx/subscribe (fn [[event model]] (-apply-effect event model))))

    ;; Process event sources: combine with the latest model and the result will be
    ;; pushed to the event-stream bus
    (as-> (rx/with-latest-from vector state-s watch-s) $
      (rx/flat-map (fn [[event model]] (-apply-watch event model)) $)
      (rx/on-value $ emit!))

    ;; Initialize the stream machinary with initial state.
    (emit! (swap-state (fn [s] (merge s state))))
    state-s))
