;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.streams
  "A collection of derived streams."
  (:require [beicon.core :as rx]
            [uxbox.main.store :as st]
            [uxbox.main.user-events :as uev]
            [uxbox.main.refs :as refs]
            [uxbox.main.workers :as uwrk]
            [uxbox.util.geom.point :as gpt]))

(def page-id-ref-s (rx/from-atom refs/selected-page-id))

;; --- Events

(defn- user-interaction-event?
  [event]
  (or (uev/keyboard-event? event)
      (uev/mouse-event? event)))

(defonce events
  (rx/filter user-interaction-event? st/stream))

;; --- Mouse Position Stream

(defonce mouse-position
  (rx/filter uev/pointer-event? st/stream))

(defonce canvas-mouse-position
  (->> mouse-position
       (rx/map :canvas)
       (rx/share)))

(defonce viewport-mouse-position
  (->> mouse-position
       (rx/map :viewport)
       (rx/share)))

(defonce window-mouse-position
  (->> mouse-position
       (rx/map :window)
       (rx/share)))

(defonce mouse-position-ctrl
  (->> mouse-position
       (rx/map :ctrl)
       (rx/share)))

(defn- coords-delta
  [[old new]]
  (gpt/subtract new old))

(defonce mouse-position-deltas
  (->> viewport-mouse-position
       (rx/sample 10)
       (rx/map #(gpt/divide % @refs/selected-zoom))
       (rx/mapcat (fn [point]
                    (if @refs/selected-alignment
                      (uwrk/align-point point)
                      (rx/of point))))
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))
