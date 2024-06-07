;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.mouse
  (:require
   [beicon.v2.core :as rx]))

(defrecord MouseEvent [type ctrl shift alt meta])
(defrecord PointerEvent [source pt ctrl shift alt meta])
(defrecord ScrollEvent [point])
(defrecord BlurEvent [])

(defn mouse-event?
  [v]
  (instance? MouseEvent v))

(defn pointer-event?
  [v]
  (instance? PointerEvent v))

(defn scroll-event?
  [v]
  (instance? ScrollEvent v))

(defn blur-event?
  [v]
  (instance? BlurEvent v))

(defn mouse-down-event?
  [^MouseEvent v]
  (= :down (.-type v)))

(defn mouse-up-event?
  [^MouseEvent v]
  (= :up (.-type v)))

(defn mouse-click-event?
  [^MouseEvent v]
  (= :click (.-type v)))

(defn mouse-double-click-event?
  [^MouseEvent v]
  (= :double-click (.-type v)))

(defn get-pointer-source
  [^PointerEvent ev]
  (.-source ev))

(defn get-pointer-position
  [^PointerEvent ev]
  (.-pt ev))

(defn get-pointer-ctrl-mod
  [^PointerEvent ev]
  (.-ctrl ev))

(defn get-pointer-meta-mod
  [^PointerEvent ev]
  (.-meta ev))

(defn get-pointer-alt-mod
  [^PointerEvent ev]
  (.-alt ev))

(defn get-pointer-shift-mod
  [^PointerEvent ev]
  (.-shift ev))

(defn drag-stopper
  "Creates a stream to stop drag events. Takes into account the mouse and also
  if the window loses focus or the esc key is pressed."
  ([stream]
   (drag-stopper stream nil))
  ([stream {:keys [blur? up-mouse? interrupt?] :or {blur? true up-mouse? true interrupt? true}}]
   (rx/merge
    (if blur?
      (->> stream
           (rx/filter blur-event?))
      (rx/empty))
    (if up-mouse?
      (->> stream
           (rx/filter mouse-event?)
           (rx/filter mouse-up-event?))
      (rx/empty))
    (if interrupt?
      (->> stream
           (rx/filter #(= % :interrupt)))
      (rx/empty)))))
