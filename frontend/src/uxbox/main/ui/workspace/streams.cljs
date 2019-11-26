;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.streams
  "User interaction events and streams."
  (:require
   [beicon.core :as rx]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.geom.point :as gpt]))

;; --- User Events

(defrecord KeyboardEvent [type key shift ctrl])

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

(defrecord MouseEvent [type ctrl shift])

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

(defn mouse-click?
  [v]
  (and (mouse-event? v)
       (= :click (:type v))))

(defrecord PointerEvent [source pt ctrl shift])

(defn pointer-event?
  [v]
  (instance? PointerEvent v))

(defrecord ScrollEvent [point])

(defn scroll-event
  [pt]
  {:pre [(gpt/point? pt)]}
  (ScrollEvent. pt))

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


(defn mouse-position-deltas
  [current]
  (->> (rx/concat (rx/of current)
                  (rx/sample 10 mouse-position))
       (rx/map #(gpt/divide % @refs/selected-zoom))
       (rx/mapcat (fn [point]
                    (if @refs/selected-alignment
                      (uwrk/align-point point)
                      (rx/of point))))
       (rx/buffer 2 1)
       (rx/map (fn [[old new]]
                 (gpt/subtract new old)))))

(defonce viewport-scroll
  (let [sub (rx/behavior-subject nil)
        sob (->> (rx/filter scroll-event? st/stream)
                 (rx/map :point))]
    (rx/subscribe-with sob sub)
    sub))

