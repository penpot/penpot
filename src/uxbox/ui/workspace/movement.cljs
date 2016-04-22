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

(declare initialize)

(defn watch-move-actions
  []
  (as-> uuc/actions-s $
    (rx/filter #(= "ui.shape.move" (:type %)) $)
    (rx/on-value $ initialize)))

;; --- Implementation

(declare watch-movement)

(defn- initialize
  []
  (let [align? @wb/alignment-l
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter empty?)
                    (rx/take 1))]
    (run! (partial watch-movement stoper align?)
          (deref selected-shapes-l))))

(defn- handle-movement
  [{:keys [id] :as shape} delta]
  (rs/emit! (uds/move-shape id delta)))

(defn- watch-movement
  [stoper align? {:keys [id] :as shape}]
  (when align? (rs/emit! (uds/initial-align-shape id)))
  (let [stream (->> wb/mouse-viewport-s
                    (rx/sample 10)
                    (rx/mapcat (fn [point]
                                 (if align?
                                   (uds/align-point point)
                                   (rx/of point))))
                    (rx/buffer 2 1)
                    (rx/map wb/coords-delta)
                    (rx/take-until stoper)
                    (rx/map #(gpt/divide % @wb/zoom-l)))]
    (rx/subscribe stream (partial handle-movement shape))))
