;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.workspace.align
  "Shape alignmen impl."
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.core :refer (worker)]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.workers :as uw]))

(defn- move
  [shape p1]
  (let [dx (- (:x2 shape) (:x1 shape))
        dy (- (:y2 shape) (:y1 shape))
        p2 (gpt/add p1 [dx dy])]
    (assoc shape
           :x1 (:x p1)
           :y1 (:y p1)
           :x2 (:x p2)
           :y2 (:y p2))))

(defn translate
  [{:keys [x1 y1] :as shape}]
  (let [message {:cmd :grid/align
                 :point (gpt/point x1 y1)}]
    (->> (uw/ask! worker message)
         (rx/map (fn [{:keys [point]}]
                   (if point
                     (move shape point)
                     shape))))))
