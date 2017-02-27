;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.common
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.geom :as geom]
            [uxbox.main.user-events :as uev]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.dom :as dom]))

;; --- Movement

;; TODO: implement in the same way as drawing (move under uxbox.main.data.workspace.)

(defn start-move
  []
  (letfn [(on-move [shape delta]
            (st/emit! (uds/apply-temporal-displacement shape delta)))
          (on-stop [shape]
            (st/emit! (uds/apply-displacement shape)))
          (on-start [shape]
            (let [stoper (->> streams/events
                              (rx/filter uev/mouse-up?)
                              (rx/take 1))
                  stream (->> streams/mouse-position-deltas
                              (rx/take-until stoper))
                  on-move (partial on-move shape)
                  on-stop (partial on-stop shape)]
              (when @refs/selected-alignment
                (st/emit! (uds/initial-shape-align shape)))
              (rx/subscribe stream on-move nil on-stop)))]
    (run! on-start @refs/selected-shapes)))

;; --- Events

(defn on-mouse-down
  [event {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        drawing? @refs/selected-drawing-tool]
    (when-not (:blocked shape)
      (cond
        (or drawing?
            (and group (:locked (geom/resolve-parent shape))))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (st/emit! (uds/select-shape id))
          (start-move))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (st/emit! (uds/select-shape id))
            (do
              (st/emit! (uds/deselect-all)
                        (uds/select-shape id))
              (start-move))))

        :else
        (do
          (dom/stop-propagation event)
          (start-move))))))
