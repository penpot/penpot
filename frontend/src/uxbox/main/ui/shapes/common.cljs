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
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.viewer :as dv]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.streams :as uws]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.dom :as dom]))

;; --- Shape Movement (by mouse)
(defn on-mouse-down
  [event {:keys [id type] :as shape}]
  (let [selected @refs/selected-shapes
        selected? (contains? selected id)
        drawing? @refs/selected-drawing-tool
        button (.-which (.-nativeEvent event))]
    (when-not (:blocked shape)
      (cond
        (not= 1 button)
        nil

        drawing?
        nil

        (= type :frame)
        (when selected?
          (dom/stop-propagation event)
          (st/emit! (dw/start-move-selected)))

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (st/emit! dw/deselect-all
                    (dw/select-shape id)
                    (dw/start-move-selected)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (st/emit! (dw/select-shape id))
            (st/emit! dw/deselect-all
                      (dw/select-shape id)
                      (dw/start-move-selected))))
        :else
        (do
          (dom/stop-propagation event)
          (st/emit! (dw/start-move-selected)))))))


;; --- Workspace context menu
(defn on-context-menu
  [event shape]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [position (dom/get-client-position event)]
    (st/emit! (dw/show-shape-context-menu {:position position
                                           :shape shape}))))


;; --- Interaction actions (in viewer mode)

(defn on-mouse-down-viewer
  [event {:keys [interactions] :as shape}]
  (let [interaction (first (filter #(= (:action-type % :click)) interactions))]
    (case (:action-type interaction)
      :navigate
      (let [frame-id (:destination interaction)]
        (st/emit! (dv/go-to-frame frame-id)))
      nil)))

