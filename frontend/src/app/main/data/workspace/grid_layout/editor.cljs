;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid-layout.editor
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [potok.v2.core :as ptk]))

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

(defn add-to-selection
  ([grid-id cell-id]
   (add-to-selection grid-id cell-id false))
  ([grid-id cell-id shift?]
   (ptk/reify ::add-to-selection
     ptk/UpdateEvent
     (update [_ state]
       (if shift?
         (let [objects  (dsh/lookup-page-objects state)
               grid     (get objects grid-id)
               selected (or (dm/get-in state [:workspace-grid-edition grid-id :selected]) #{})
               selected (into selected [cell-id])
               cells    (->> selected (map #(dm/get-in grid [:layout-grid-cells %])))

               {:keys [first-row last-row first-column last-column]} (ctl/cells-coordinates cells)
               new-selected
               (into #{}
                     (map :id)
                     (ctl/cells-in-area grid first-row last-row first-column last-column))]
           (assoc-in state [:workspace-grid-edition grid-id :selected] new-selected))
         (update-in state [:workspace-grid-edition grid-id :selected] (fnil conj #{}) cell-id))))))

(defn set-selection
  [grid-id cell-id]
  (ptk/reify ::set-selection
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-grid-edition grid-id :selected] #{cell-id}))))

(defn remove-selection
  [grid-id cell-id]
  (ptk/reify ::remove-selection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-grid-edition grid-id :selected] disj cell-id))))

(defn clear-selection
  [grid-id]
  (ptk/reify ::clear-selection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-grid-edition grid-id] dissoc :selected))))

(defn clean-selection
  [grid-id]
  (ptk/reify ::clean-selection
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            shape (get objects grid-id)]
        (update-in state [:workspace-grid-edition grid-id :selected]
                   (fn [selected]
                     (into #{}
                           (filter #(contains? (:layout-grid-cells shape) %))
                           selected)))))))

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
      (let [objects (dsh/lookup-page-objects state)
            srect (get-in objects [grid-id :selrect])]
        (-> state
            (update :workspace-local
                    (fn [{:keys [zoom vport] :as local}]
                      (let [{:keys [x y width height]} srect
                            x     (+ x (/ width 2) (- (/ (:width vport) 2 zoom)))
                            y     (+ y (/ height 2) (- (/ (:height vport) 2 zoom)))
                            srect (grc/make-rect x y width height)]
                        (-> local
                            (update :vbox merge (select-keys srect [:x :y :x1 :x2 :y1 :y2])))))))))))

(defn select-track-cells
  [grid-id type index]
  (ptk/reify ::select-track-cells
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            parent  (get objects grid-id)

            cells
            (if (= type :column)
              (ctl/cells-by-column parent index)
              (ctl/cells-by-row parent index))

            selected (into #{} (map :id) cells)]
        (assoc-in state [:workspace-grid-edition grid-id :selected] selected)))))
