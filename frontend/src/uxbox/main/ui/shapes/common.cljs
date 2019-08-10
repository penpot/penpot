;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.common
  (:require
   [potok.core :as ptk]
   [beicon.core :as rx]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.workspace.streams :as ws]
   [uxbox.util.dom :as dom]))

;; --- Shape Movement (by mouse)

(defn start-move
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            wst (get-in state [:workspace pid])
            stoper (->> ws/interaction-events
                        (rx/filter ws/mouse-up?)
                        (rx/take 1))
            stream (->> ws/mouse-position-deltas
                        (rx/take-until stoper))]
      (rx/concat
       (when (refs/alignment-activated? (:flags wst))
         (rx/of (dw/initial-shape-align id)))
       (rx/map #(dw/apply-temporal-displacement id %) stream)
       (rx/of (dw/apply-displacement id)))))))

(defn start-move-selected
  []
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            selected (get-in state [:workspace pid :selected])]
        (rx/from-coll (map start-move selected))))))


(defn on-mouse-down
  [event {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        drawing? @refs/selected-drawing-tool]
    (when-not (:blocked shape)
      (cond
        drawing?
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (st/emit! (dw/select-shape id)
                    (start-move-selected)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (st/emit! (dw/select-shape id))
            (st/emit! (dw/deselect-all)
                      (dw/select-shape id)
                      (start-move-selected))))
        :else
        (do
          (dom/stop-propagation event)
          (st/emit! (start-move-selected)))))))
