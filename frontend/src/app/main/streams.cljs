;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.streams
  "User interaction events and streams."
  (:require
   [app.main.store :as st]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [beicon.core :as rx]))

;; --- User Events

(defrecord KeyboardEvent [type key shift ctrl alt meta editing])

(defn keyboard-event?
  [v]
  (instance? KeyboardEvent v))

(defrecord MouseEvent [type ctrl shift alt])

(defn mouse-event?
  [v]
  (instance? MouseEvent v))

(defn mouse-down?
  [v]
  (and (mouse-event? v)
       (= :down (:type v))))

(defn mouse-up?
  [v]
  (and (mouse-event? v)
       (= :up (:type v))))

(defn mouse-click?
  [v]
  (and (mouse-event? v)
       (= :click (:type v))))

(defn mouse-double-click?
  [v]
  (and (mouse-event? v)
       (= :double-click (:type v))))

(defrecord PointerEvent [source pt ctrl shift alt])

(defn pointer-event?
  [v]
  (instance? PointerEvent v))

(defrecord ScrollEvent [point])

(defn scroll-event?
  [v]
  (instance? ScrollEvent v))

(defn interaction-event?
  [event]
  (or (keyboard-event? event)
      (mouse-event? event)))

;; --- Derived streams

(defonce mouse-position
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter pointer-event?)
                 (rx/filter #(= :viewport (:source %)))
                 (rx/map :pt))]
    (rx/subscribe-with ob sub)
    sub))

(defonce mouse-position-ctrl
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter pointer-event?)
                 (rx/map :ctrl)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce mouse-position-shift
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter pointer-event?)
                 (rx/map :shift)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce mouse-position-alt
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter pointer-event?)
                 (rx/map :alt)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))


(defonce window-blur
  (->> (rx/from-event globals/window "blur")
       (rx/share)))

(defonce keyboard-alt
  (let [sub (rx/behavior-subject nil)
        ob  (->> (rx/merge
                  (->> st/stream
                       (rx/filter keyboard-event?)
                       (rx/filter kbd/altKey?)
                       (rx/map #(= :down (:type %))))
                  ;; Fix a situation caused by using `ctrl+alt` kind of shortcuts,
                  ;; that makes keyboard-alt stream registering the key pressed but
                  ;; on blurring the window (unfocus) the key down is never arrived.
                  (->> window-blur
                       (rx/map (constantly false))))
                 (rx/dedupe))]
        (rx/subscribe-with ob sub)
        sub))

(defonce keyboard-ctrl
  (let [sub (rx/behavior-subject nil)
        ob  (->> (rx/merge
                  (->> st/stream
                       (rx/filter keyboard-event?)
                       (rx/filter kbd/ctrlKey?)
                       (rx/map #(= :down (:type %))))
                  ;; Fix a situation caused by using `ctrl+alt` kind of shortcuts,
                  ;; that makes keyboard-alt stream registering the key pressed but
                  ;; on blurring the window (unfocus) the key down is never arrived.
                  (->> window-blur
                       (rx/map (constantly false))))
                 (rx/dedupe))]
        (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-space
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter keyboard-event?)
                 (rx/filter kbd/space?)
                 (rx/filter (comp not kbd/editing?))
                 (rx/map #(= :down (:type %)))
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))
