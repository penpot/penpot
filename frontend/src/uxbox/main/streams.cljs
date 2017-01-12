;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.streams
  "A collection of derived streams."
  (:require [beicon.core :as rx]
            [uxbox.main.refs :as refs]
            [uxbox.main.workers :as uwrk]
            [uxbox.util.geom.point :as gpt]))

(def page-id-ref-s (rx/from-atom refs/selected-page-id))

;; --- Scroll Stream

(defonce scroll-b (rx/subject))

(defonce scroll-s
  (as-> scroll-b $
    (rx/sample 10 $)
    (rx/merge $ (rx/of (gpt/point)))
    (rx/dedupe $)))

(defonce scroll-a
  (rx/to-atom scroll-s))

;; --- Events

(defonce events-b (rx/subject))
(defonce events-s (rx/dedupe events-b))

;; --- Mouse Position Stream

(defonce mouse-b (rx/subject))
(defonce mouse-s (rx/dedupe mouse-b))

(defonce mouse-canvas-s
  (->> mouse-s
       (rx/map :canvas-coords)
       (rx/share)))

(defonce mouse-canvas-a
  (rx/to-atom mouse-canvas-s))

(defonce mouse-viewport-s
  (->> mouse-s
       (rx/map :viewport-coords)
       (rx/share)))

(defonce mouse-viewport-a
  (rx/to-atom mouse-viewport-s))

(defonce mouse-absolute-s
  (->> mouse-s
       (rx/map :window-coords)
       (rx/share)))

(defonce mouse-absolute-a
  (rx/to-atom mouse-absolute-s))

(defonce mouse-ctrl-s
  (->> mouse-s
       (rx/map :ctrl)
       (rx/share)))

(defn- coords-delta
  [[old new]]
  (gpt/subtract new old))

(defonce mouse-delta-s
  (->> mouse-viewport-s
       (rx/sample 10)
       (rx/map #(gpt/divide % @refs/selected-zoom))
       (rx/mapcat (fn [point]
                    (if @refs/selected-alignment
                      (uwrk/align-point point)
                      (rx/of point))))
       (rx/buffer 2 1)
       (rx/map coords-delta)
       (rx/share)))
