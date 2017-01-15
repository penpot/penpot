;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.user-events
  "Workspace user (keyboard, mouse and pointer) events."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.geom.point :as gpt]))

(defrecord KeyboardEvent [type key shift ctrl])
(defrecord MouseEvent [type ctrl shift])

(defrecord PointerEvent [window
                         viewport
                         canvas
                         ctrl
                         shift]
  ptk/UpdateEvent
  (update [it state]
    (assoc-in state [:workspace :pointer] it)))

(defn keyboard-event
  [type key ctrl shift]
  {:pre [(keyword? type)
         (integer? key)
         (boolean? ctrl)
         (boolean? shift)]}
  (KeyboardEvent. type key ctrl shift))

(defn keyboard-event?
  [v]
  (instance? KeyboardEvent v))

(defn mouse-event
  [type ctrl shift]
  {:pre [(keyword? type)
         (boolean? ctrl)
         (boolean? shift)]}
  (MouseEvent. type ctrl shift))

(defn mouse-event?
  [v]
  (instance? MouseEvent v))

(defn mouse-up?
  [v]
  (and (mouse-event? v)
       (= :up (:type v))))

;; TODO: add spec

(defn pointer-event
  [window viewport canvas ctrl shift]
  {:pre [(gpt/point? window)
         (gpt/point? viewport)
         (or (gpt/point? canvas)
             (nil? canvas))
         (boolean? ctrl)
         (boolean? shift)]}
  (PointerEvent. window
                 viewport
                 canvas
                 ctrl
                 shift))

(defn pointer-event?
  [v]
  (instance? PointerEvent v))
