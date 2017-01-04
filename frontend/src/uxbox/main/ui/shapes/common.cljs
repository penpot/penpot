;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.common
  (:require [lentes.core :as l]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.dom :as dom]))

;; --- Refs

(def edition-ref
  (-> (l/in [:workspace :edition])
      (l/derive st/state)))

(def drawing-state-ref
  (-> (l/in [:workspace :drawing])
      (l/derive st/state)))

(def selected-ref
  (-> (l/in [:workspace :selected])
      (l/derive st/state)))

;; --- Movement

(defn start-move
  []
  (letfn [(on-move [shape delta]
            (st/emit! (uds/apply-temporal-displacement shape delta)))
          (on-stop [{:keys [id] :as shape}]
            (rlocks/release! :shape/move)
            (st/emit! (uds/apply-displacement shape)))
          (on-start [shape]
            (let [stoper (->> (rx/map first wb/events-s)
                              (rx/filter #(= % :mouse/up))
                              (rx/take 1))
                  stream (->> wb/mouse-delta-s
                              (rx/take-until stoper))
                  on-move (partial on-move shape)
                  on-stop (partial on-stop shape)]
              (when @wb/alignment-ref
                (st/emit! (uds/initial-align-shape shape)))
              (rx/subscribe stream on-move nil on-stop)))]

    (rlocks/acquire! :shape/move)
    (run! on-start @selected-ref)))

;; --- Events

(defn on-mouse-down
  [event {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        drawing? @drawing-state-ref]
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
