;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-modifiers-test
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Helpers

(defn- add-flex-frame
  "Create a flex layout frame"
  [file frame-label & {:keys [width height] :as params}]
  (ths/add-sample-shape file frame-label
                        (merge {:type :frame
                                :name "FlexFrame"
                                :layout-flex-dir :row
                                :width (or width 200)
                                :height (or height 200)}
                               params)))

(defn- add-rect-child
  "Create a rectangle child inside a parent"
  [file rect-label parent-label & {:keys [width height x y] :as params}]
  (ths/add-sample-shape file rect-label
                        (merge {:type :rect
                                :name "Rect"
                                :parent-label parent-label
                                :width (or width 50)
                                :height (or height 50)
                                :x (or x 0)
                                :y (or y 0)}
                               params)))

(defn- add-ghost-child-id
  "Add a non-existent child ID to a frame's shapes list.
   This simulates data inconsistency where a child ID is referenced
   but the child shape doesn't exist in objects."
  [file frame-label ghost-id]
  (let [page (thf/current-page file)
        frame-id (thi/id frame-label)]
    (update file :data
            (fn [file-data]
              (update-in file-data [:pages-index (:id page) :objects frame-id :shapes]
                         conj ghost-id)))))

;; ---- Tests

(t/deftest flex-layout-with-normal-children
  (t/testing "set-objects-modifiers processes flex layout children correctly"
    (let [file    (-> (thf/sample-file :file1)
                      (add-flex-frame :frame1)
                      (add-rect-child :rect1 :frame1))
          page    (thf/current-page file)
          objects (:objects page)
          rect-id  (thi/id :rect1)

          ;; Create a move modifier for the rectangle
          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; This should not crash
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      ;; The rectangle should have modifiers
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-with-nonexistent-child
  (t/testing "set-objects-modifiers handles flex frame with non-existent child in shapes"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :frame1)
                       (add-rect-child :rect1 :frame1)
                       ;; Add a non-existent child ID to the frame's shapes
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          ;; Create a move modifier for the existing rectangle
          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; This should NOT crash even though the flex frame has
          ;; a child ID (ghost-id) that doesn't exist in objects
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-with-all-ghost-children
  (t/testing "set-objects-modifiers handles flex frame with only non-existent children"
    (let [ghost1 (thi/next-uuid)
          ghost2 (thi/next-uuid)
          file   (-> (thf/sample-file :file1)
                     (add-flex-frame :frame1)
                     ;; Add only non-existent children to the frame's shapes
                     (add-ghost-child-id :frame1 ghost1)
                     (add-ghost-child-id :frame1 ghost2))
          page    (thf/current-page file)
          objects (:objects page)
          frame-id (thi/id :frame1)

          ;; Create a move modifier for the frame itself
          modif-tree {frame-id {:modifiers (ctm/move-modifiers (gpt/point 5 5))}}

          ;; Should not crash even though the flex frame has
          ;; no existing children in its shapes list
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result)))))

(t/deftest grid-layout-with-nonexistent-child
  (t/testing "set-objects-modifiers handles grid frame with non-existent child in shapes"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (ths/add-sample-shape :frame1
                                             {:type :frame
                                              :name "GridFrame"
                                              :layout-grid-dir :row
                                              :width 200
                                              :height 200})
                       (add-rect-child :rect1 :frame1)
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; Should not crash for grid layout with ghost child
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-resize-with-nonexistent-child
  (t/testing "resize modifier propagation handles non-existent children"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :frame1)
                       (add-rect-child :rect1 :frame1)
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          frame-id (thi/id :frame1)

          ;; Create a resize modifier for the frame itself
          modif-tree {frame-id {:modifiers (ctm/resize-modifiers
                                            (gpt/point 2 2)
                                            (gpt/point 0 0))}}

          ;; Should not crash when propagating resize through flex layout
          ;; that has ghost children
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      ;; The frame should have modifiers
      (t/is (contains? result frame-id)))))

(t/deftest nested-flex-layout-with-nonexistent-child
  (t/testing "nested flex layout handles non-existent children in outer frame"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :outer-frame)
                       (add-flex-frame :inner-frame :parent-label :outer-frame)
                       (add-rect-child :rect1 :inner-frame)
                       (add-ghost-child-id :outer-frame ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 5 10))}}

          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

(defn- reflow
  [objects ids]
  (->> objects
       (gm/set-objects-modifiers (into {} (map #(vector % {:modifiers (ctm/reflow-modifiers)})) ids))
       (gtr/apply-objects-modifiers objects)))

(defn- edges
  [{:keys [points]}]
  (let [[p0 p1 _ p3] points]
    [(gpt/to-vec p0 p1) (gpt/to-vec p0 p3)]))

(t/deftest grid-child-skews-with-its-skewed-grid
  (t/testing "a grid board skewed by its turned parent filling a flex skews its children too"
    (let [file    (-> (thf/sample-file :file1)
                      (tho/add-frame :outer :width 340 :height 200 :layout :flex :layout-flex-dir :row
                                     :layout-align-items :center :layout-justify-content :center
                                     :layout-padding-type :simple :layout-padding {:p1 30 :p2 30 :p3 30 :p4 30})
                      (tho/add-frame :card :parent-label :outer :x 120 :y 50 :width 100 :height 100)
                      (tho/add-grid-frame :inner :parent-label :card :x 140 :y 70 :width 60 :height 60)
                      (add-rect-child :cell :inner :x 140 :y 70 :width 20 :height 20)
                      (tho/place-in-grid-columns :inner 1 {1 :cell}))
          ids     [(thi/id :outer) (thi/id :inner)]
          objects (reflow (:objects (thf/current-page file)) ids)
          card    (get objects (thi/id :card))
          objects (->> objects
                       (gm/set-objects-modifiers
                        {(:id card) {:modifiers (ctm/rotation-modifiers card (gsh/shape->center card) 45)}})
                       (gtr/apply-objects-modifiers objects))
          objects (reflow (assoc-in objects [(:id card) :layout-item-h-sizing] :fill) ids)
          inner   (get objects (thi/id :inner))
          cell    (get objects (thi/id :cell))
          [inner-h inner-v] (edges inner)
          [cell-h cell-v] (edges cell)]

      (t/is (gpt/close? (gpt/scale inner-h (/ 1 3)) cell-h))
      (t/is (gpt/close? (gpt/scale inner-v (/ 1 3)) cell-v))
      (t/is (gpt/close? (first (:points inner)) (first (:points cell)))))))

(t/deftest grid-fill-keeps-a-turned-child-at-least-one-pixel-thick
  ;; a fill child turned in its grid fills along the grid's axes, which skews
  ;; it; a fixed sibling leaves its column no width, and the child narrows
  ;; only until it is 1px thick in its own axes
  (let [file    (-> (thf/sample-file :file)
                    (ths/add-sample-shape :grid {:type :frame :name "Grid" :width 100 :height 100
                                                 :layout :grid :layout-grid-dir :row
                                                 :layout-grid-rows [{:type :flex :value 1}]
                                                 :layout-grid-columns [{:type :flex :value 1} {:type :flex :value 1}
                                                                       {:type :flex :value 1}]
                                                 :layout-gap {:row-gap 5 :column-gap 9}
                                                 :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}})
                    (ths/add-sample-shape :child {:type :frame :name "Child" :parent-label :grid
                                                  :width 100 :height 100 :layout-item-h-sizing :fill})
                    (ths/add-sample-shape :rect {:type :rect :name "Rect" :parent-label :grid
                                                 :width 70 :height 30}))
        page    (thf/current-page file)
        objects (:objects page)
        child   (get objects (thi/id :child))
        turned  (gsh/transform-shape child (ctm/rotation-modifiers child (gsh/shape->center child) 45))
        grid    (-> (get objects (thi/id :grid))
                    (assoc :shapes [(thi/id :child) (thi/id :rect)])
                    (assoc :layout-grid-cells
                           {(thi/new-id! :cell-1) {:id (thi/id :cell-1) :row 1 :column 1 :row-span 1 :column-span 1
                                                   :position :manual :shapes [(thi/id :child)]}
                            (thi/new-id! :cell-2) {:id (thi/id :cell-2) :row 1 :column 2 :row-span 1 :column-span 1
                                                   :position :manual :shapes [(thi/id :rect)]}
                            (thi/new-id! :cell-3) {:id (thi/id :cell-3) :row 1 :column 3 :row-span 1 :column-span 1
                                                   :position :auto :shapes []}}))
        objects (assoc objects (thi/id :child) turned (thi/id :grid) grid)
        tree    (gm/set-objects-modifiers {(thi/id :grid) {:modifiers (ctm/reflow-modifiers)}} objects)
        result  (gsh/transform-shape turned (get-in tree [(thi/id :child) :modifiers]))
        [p1 p2 _ p4] (:points result)
        e1      (gpt/to-vec p1 p2)
        e2      (gpt/to-vec p1 p4)
        area    (abs (- (* (:x e1) (:y e2)) (* (:y e1) (:x e2))))]
    (t/is (>= (/ area (max (gpt/length e1) (gpt/length e2))) 0.999))))

(t/deftest a-turned-board-narrowed-to-its-minimum-keeps-a-left-child-width
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-frame :board :width 120 :height 140)
                    (add-rect-child :child :board :width 70 :height 25 :constraints-h :left :constraints-v :top))
        objects (:objects (thf/current-page file))
        board   (get objects (thi/id :board))
        objects (->> objects
                     (gm/set-objects-modifiers
                      {(:id board) {:modifiers (ctm/rotation-modifiers board (gsh/shape->center board) 45)}})
                     (gtr/apply-objects-modifiers objects))
        board   (get objects (thi/id :board))
        objects (->> objects
                     (gm/set-objects-modifiers
                      {(:id board) {:modifiers (ctm/change-size board 0.01 140)}})
                     (gtr/apply-objects-modifiers objects))
        child   (get objects (thi/id :child))]
    (t/is (< (abs (- 70 (:width child))) 0.001))
    (t/is (< (abs (- 25 (:height child))) 0.001))))
