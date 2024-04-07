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
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]))

;; --- User Events

(defn interaction-event?
  [event]
  (or ^boolean (kbd/keyboard-event? event)
      ^boolean (mse/mouse-event? event)))

;; --- Derived streams

(defonce ^:private pointer
  (->> st/stream
       (rx/filter mse/pointer-event?)
       (rx/share)))

(defonce mouse-position
  (let [sub (rx/behavior-subject nil)
        ob  (->> pointer
                 (rx/filter #(= :viewport (mse/get-pointer-source %)))
                 (rx/map mse/get-pointer-position))]
    (rx/sub! ob sub)
    sub))

(defonce mouse-position-ctrl
  (let [sub (rx/behavior-subject nil)
        ob  (->> pointer
                 (rx/map mse/get-pointer-ctrl-mod)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
    sub))

(defonce mouse-position-meta
  (let [sub (rx/behavior-subject nil)
        ob  (->> pointer
                 (rx/map mse/get-pointer-meta-mod)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
    sub))

(defonce mouse-position-mod
  (if (cfg/check-platform? :macos)
    mouse-position-meta
    mouse-position-ctrl))

(defonce mouse-position-shift
  (let [sub (rx/behavior-subject nil)
        ob  (->> pointer
                 (rx/map mse/get-pointer-shift-mod)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
    sub))

(defonce mouse-position-alt
  (let [sub (rx/behavior-subject nil)
        ob  (->> pointer
                 (rx/map mse/get-pointer-alt-mod)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
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
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
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
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
    sub))

(defonce keyboard-shift
  (let [sub (rx/behavior-subject nil)
        ob  (->> keyboard
                 (rx/filter kbd/shift-key?)
                 (rx/map kbd/key-down-event?)
                 ;; Fix a situation caused by using `ctrl+alt` kind of
                 ;; shortcuts, that makes keyboard-alt stream
                 ;; registering the key pressed but on blurring the
                 ;; window (unfocus) the key down is never arrived.
                 (rx/merge window-blur)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
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
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
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
                 ;; Fix a situation caused by using `ctrl+alt` kind of
                 ;; shortcuts, that makes keyboard-alt stream
                 ;; registering the key pressed but on blurring the
                 ;; window (unfocus) the key down is never arrived.
                 (rx/merge window-blur)
                 (rx/pipe (rxo/distinct-contiguous)))]
    (rx/sub! ob sub)
    sub))
