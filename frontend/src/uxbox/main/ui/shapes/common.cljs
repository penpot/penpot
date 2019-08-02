;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.common
  (:require
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.util.dom :as dom]))

;; --- Events

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
          (st/emit! (udw/select-shape id)
                    (udw/start-move-selected)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (st/emit! (udw/select-shape id))
            (st/emit! (udw/deselect-all)
                      (udw/select-shape id)
                      (udw/start-move-selected))))
        :else
        (do
          (dom/stop-propagation event)
          (st/emit! (udw/start-move-selected)))))))
