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

(defrecord MouseEvent [type ctrl shift alt meta])
(defrecord PointerEvent [source pt ctrl shift alt meta])
(defrecord ScrollEvent [point])

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

(defn pointer-event?
  [v]
  (instance? PointerEvent v))

(defn scroll-event?
  [v]
  (instance? ScrollEvent v))

(defn interaction-event?
  [event]
  (or (kbd/keyboard-event? event)
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

(defonce ^:private window-blur
  (->> (rx/from-event globals/window "blur")
       (rx/map (constantly false))
       (rx/share)))

(defonce keyboard
  (->> st/stream
       (rx/filter kbd/keyboard-event?)
       (rx/share)))

(defonce keyboard-alt
  (let [sub (rx/behavior-subject nil)
        ob  (->> keyboard
                 (rx/filter kbd/alt-key?)
                 (rx/map kbd/key-down-event?)
                 ;; Fix a situation caused by using `ctrl+alt` kind of
                 ;; shortcuts, that makes keyboard-alt stream
                 ;; registering the key pressed but on blurring the
                 ;; window (unfocus) the key down is never arrived.
                 (rx/merge window-blur)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-ctrl
  (let [sub (rx/behavior-subject nil)
        ob  (->> keyboard
                 (rx/filter kbd/ctrl-key?)
                 (rx/map kbd/key-down-event?)
                 ;; Fix a situation caused by using `ctrl+alt` kind of
                 ;; shortcuts, that makes keyboard-alt stream
                 ;; registering the key pressed but on blurring the
                 ;; window (unfocus) the key down is never arrived.
                 (rx/merge window-blur)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-meta
  (let [sub (rx/behavior-subject nil)
        ob  (->> keyboard
                 (rx/filter kbd/meta-key?)
                 (rx/map kbd/key-down-event?)
                 ;; Fix a situation caused by using `ctrl+alt` kind of
                 ;; shortcuts, that makes keyboard-alt stream
                 ;; registering the key pressed but on blurring the
                 ;; window (unfocus) the key down is never arrived.
                 (rx/merge window-blur)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))

(defonce keyboard-mod
  (if (cfg/check-platform? :macos)
    keyboard-meta
    keyboard-ctrl))

(defonce keyboard-space
  (let [sub (rx/behavior-subject nil)
        ob  (->> keyboard
                 (rx/filter kbd/space?)
                 (rx/filter (complement kbd/editing-event?))
                 (rx/map kbd/key-down-event?)
                 (rx/dedupe))]
    (rx/subscribe-with ob sub)
    sub))
