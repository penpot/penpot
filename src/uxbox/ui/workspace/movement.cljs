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
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.workspace.align :as align]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]))

(declare initialize)
(declare handle-movement)

;; --- Lenses

(defn- resolve-selected
  [state]
  (let [selected (get-in state [:workspace :selected])
        xf (map #(get-in state [:shapes-by-id %]))]
    (into #{} xf selected)))

(def ^:const ^:private selected-shapes-l
  (-> (l/getter resolve-selected)
      (l/focus-atom st/state)))

;; --- Public Api

(defn watch-move-actions
  []
  (as-> uuc/actions-s $
    (rx/filter #(= "ui.shape.move" (:type %)) $)
    (rx/on-value $ initialize)))

;; --- Implementation

(defn- initialize
  []
  (let [shapes @selected-shapes-l
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter empty?)
                    (rx/take 1))]
    (as-> wb/mouse-delta-s $
      (rx/take-until stoper $)
      (rx/scan (fn [acc delta]
                 (mapv #(sh/move % delta) acc)) shapes $)
      (rx/subscribe $ handle-movement))))

(defn- handle-movement
  [delta]
  (doseq [shape delta]
    (rs/emit! (uds/update-shape shape))))

