;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.movement
  "Shape movement in workspace logic."
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]))

(declare initialize)
(declare handle-movement)

;; --- Public Api

(defn watch-move-actions
  []
  (as-> uuc/actions-s $
    (rx/filter #(= "ui.shape.move" (:type %)) $)
    (rx/on-value $ initialize)))

;; --- Implementation

(defn- initialize
  []
  (let [stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter empty?)
                    (rx/take 1))]
    (as-> wb/mouse-delta-s $
      (rx/take-until stoper $)
      (rx/on-value $ handle-movement))))

(defn- handle-movement
  [delta]
  (let [pageid (get-in @st/state [:workspace :page])
        selected (get-in @st/state [:workspace :selected])
        shapes (->> (vals @wb/shapes-by-id-l)
                    (filter #(= (:page %) pageid))
                    (filter (comp selected :id)))
        delta (gpt/divide delta @wb/zoom-l)]
    (doseq [{:keys [id group]} shapes]
      (rs/emit! (uds/move-shape id delta)))))

