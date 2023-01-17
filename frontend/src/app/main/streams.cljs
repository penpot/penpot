;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.streams
  "User interaction events and streams."
  (:require
   [app.config :as cfg]
   [app.main.store :as st]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [beicon.core :as rx]))

;; --- User Events

(defrecord KeyboardEvent [type key shift ctrl alt meta editing])

(defn keyboard-event?
  [v]
  (instance? KeyboardEvent v))

(defn key-up?
  [v]
  (and (keyboard-event? v)
       (= :up (:type v))))

(defn key-down?
  [v]
  (and (keyboard-event? v)
       (= :down (:type v))))

(defrecord MouseEvent [type ctrl shift alt meta])

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

(defrecord PointerEvent [source pt ctrl shift alt meta])

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

(defonce mouse-position-meta
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter pointer-event?)
                 (rx/map :meta)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce mouse-position-mod
  (if (cfg/check-platform? :macos)
    mouse-position-meta
    mouse-position-ctrl))

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
                       (rx/filter kbd/alt-key?)
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
                       (rx/filter kbd/ctrl-key?)
                       (rx/map #(= :down (:type %))))
                  ;; Fix a situation caused by using `ctrl+alt` kind of shortcuts,
                  ;; that makes keyboard-alt stream registering the key pressed but
                  ;; on blurring the window (unfocus) the key down is never arrived.
                  (->> window-blur
                       (rx/map (constantly false))))
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-meta
  (let [sub (rx/behavior-subject nil)
        ob  (->> (rx/merge
                  (->> st/stream
                       (rx/filter keyboard-event?)
                       (rx/filter kbd/meta-key?)
                       (rx/map #(= :down (:type %))))
                  ;; Fix a situation caused by using `ctrl+alt` kind of shortcuts,
                  ;; that makes keyboard-alt stream registering the key pressed but
                  ;; on blurring the window (unfocus) the key down is never arrived.
                  (->> window-blur
                       (rx/map (constantly false))))
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-mod
  (if (cfg/check-platform? :macos)
    keyboard-meta
    keyboard-ctrl))

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

(defonce keyboard-z
  (let [sub (rx/behavior-subject nil)
        ob  (->> st/stream
                 (rx/filter keyboard-event?)
                 (rx/filter kbd/z?)
                 (rx/filter (comp not kbd/editing?))
                 (rx/map #(= :down (:type %)))
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))
