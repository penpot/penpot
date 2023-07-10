;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid-layout.editor
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [potok.core :as ptk]))

(defn hover-grid-cell
  [grid-id cell-id add-to-set]
  (ptk/reify ::hover-grid-cell
    ptk/UpdateEvent
    (update [_ state]
      (update-in
       state
       [:workspace-grid-edition grid-id :hover]
       (fn [hover-set]
         (let [hover-set (or hover-set #{})]
           (if add-to-set
             (conj hover-set cell-id)
             (disj hover-set cell-id))))))))

(defn select-grid-cell
  [grid-id cell-id]
  (ptk/reify ::select-grid-cell
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-grid-edition grid-id :selected] cell-id))))

(defn remove-selection
  [grid-id]
  (ptk/reify ::remove-selection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-grid-edition grid-id] dissoc :selected))))

(defn stop-grid-layout-editing
  [grid-id]
  (ptk/reify ::stop-grid-layout-editing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-grid-edition dissoc grid-id))))

(defn locate-board
  [grid-id]
  (ptk/reify ::locate-board
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)
            srect (get-in objects [grid-id :selrect])]
        (-> state
            (update :workspace-local
                    (fn [{:keys [zoom vport] :as local}]
                      (let [{:keys [x y width height]} srect
                            x     (+ x (/ width 2) (- (/ (:width vport) 2 zoom)))
                            y     (+ y (/ height 2) (- (/ (:height vport) 2 zoom)))
                            srect (gsh/make-selrect x y width height)]
                        (-> local
                            (update :vbox merge (select-keys srect [:x :y :x1 :x2 :y1 :y2])))))))))))

