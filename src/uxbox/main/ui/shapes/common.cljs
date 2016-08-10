;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.common
  (:require [sablono.core :refer-macros [html]]
            [lentes.core :as l]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def selected-shapes-ref
  (-> (l/in [:workspace :selected])
      (l/derive st/state)))

(def drawing-state-ref
  (-> (l/in [:workspace :drawing])
      (l/derive st/state)))

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
          (rs/emit! (uds/select-shape id))
          (rlocks/acquire! :shape/move))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (rs/emit! (uds/select-shape id))
            (do
              (rs/emit! (uds/deselect-all)
                        (uds/select-shape id))
              (rlocks/acquire! :shape/move))))

        :else
        (do
          (dom/stop-propagation event)
          (rlocks/acquire! :shape/move))))))
