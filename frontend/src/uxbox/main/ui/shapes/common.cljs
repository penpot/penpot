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
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.streams :as uws]
   [uxbox.main.geom :as geom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.dom :as dom]))

;; --- Shape Movement (by mouse)

(def start-move-selected
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            stoper (rx/filter uws/mouse-up? stream)
            zero-point? #(= % (gpt/point 0 0))
            position @uws/mouse-position]
        (rx/concat
         (->> (uws/mouse-position-deltas position)
              (rx/filter (complement zero-point?))
              (rx/map #(dw/apply-displacement-in-bulk selected %))
              (rx/take-until stoper))
         (rx/of (dw/materialize-displacement-in-bulk selected)))))))

(def start-move-frame
  (ptk/reify ::start-move-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            stoper (rx/filter uws/mouse-up? stream)
            zero-point? #(= % (gpt/point 0 0))
            frame-id (first selected)
            position @uws/mouse-position]
        (rx/concat
         (->> (uws/mouse-position-deltas position)
              (rx/filter (complement zero-point?))
              (rx/map #(dw/apply-frame-displacement frame-id %))
              (rx/take-until stoper))
         (rx/of (dw/materialize-frame-displacement frame-id)))))))

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
          (st/emit! start-move-frame))

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


(defn on-context-menu
  [event shape]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [position (dom/get-client-position event)]
    (st/emit!(dw/show-shape-context-menu {:position position
                                          :shape shape}))))
