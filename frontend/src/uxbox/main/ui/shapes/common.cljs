;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

;; TODO: we need to consider moving this under uxbox.ui.workspace
;; namespace because this is logic only related to workspace
;; manipulation. Staying here causes a lot of confusion and finding
;; this code is also very difficult.

(ns uxbox.main.ui.shapes.common
  (:require
   [potok.core :as ptk]
   [beicon.core :as rx]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.dom :as dom]))

;; --- Shape Movement (by mouse)

(def start-move-selected
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            flags (get-in state [:workspace pid :flags])
            selected (get-in state [:workspace pid :selected])
            stoper (rx/filter uws/mouse-up? stream)
            position @uws/mouse-position]
        (rx/concat
         (when (refs/alignment-activated? flags)
           (rx/of (dw/initial-selection-align selected)))
         (->> (uws/mouse-position-deltas position)
              (rx/map #(dw/apply-temporal-displacement-in-bulk selected %))
              (rx/take-until stoper))
         (rx/of (dw/materialize-current-modifier-in-bulk selected)))))))

(defn on-mouse-down
  [event {:keys [id type] :as shape} selected]
  (let [selected? (contains? selected id)
        drawing? @refs/selected-drawing-tool]
    (when-not (:blocked shape)
      (cond
        drawing?
        nil

        (= type :canvas)
        (when selected?
          (dom/stop-propagation event)
          (st/emit! start-move-selected))

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (st/emit! dw/deselect-all
                    (dw/select-shape id)
                    start-move-selected))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (st/emit! (dw/select-shape id))
            (st/emit! dw/deselect-all
                      (dw/select-shape id)
                      start-move-selected)))
        :else
        (do
          (dom/stop-propagation event)
          (st/emit! start-move-selected))))))
