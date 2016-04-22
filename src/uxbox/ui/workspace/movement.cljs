;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.movement
  "Shape movement in workspace logic."
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.constants :as c]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom :as geom]
            [uxbox.util.geom.point :as gpt]))

;; --- Public Api

(declare watch-movement)

(defn watch-move-actions
  []
  (let [initialize #(run! watch-movement @wb/selected-shapes-l)
        stream (rx/filter #(= "ui.shape.move" (:type %)) uuc/actions-s)]
    (rx/subscribe stream initialize)))

;; --- Implementation

(defn- watch-movement
  [shape]
  (let [stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter empty?)
                    (rx/take 1))
        stream (->> wb/mouse-delta-s
                    (rx/take-until stoper)
                    (rx/map #(gpt/divide % @wb/zoom-l)))]
    (when @wb/alignment-l
      (rs/emit! (uds/initial-align-shape shape)))
    (rx/subscribe stream #(rs/emit! (uds/move-shape shape %)))))
