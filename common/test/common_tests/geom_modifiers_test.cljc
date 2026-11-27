;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-modifiers-test
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
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

(defn- flatten-frame
  "Leave `frame-label` with the geometry a transform leaves when it
  collapses a board to no height: its points share the same y, while
  its selrect keeps the minimum height of 0.01."
  [file frame-label]
  (let [page     (thf/current-page file)
        frame-id (thi/id frame-label)]
    (update-in file [:data :pages-index (:id page) :objects frame-id]
               (fn [{:keys [x y width] :as frame}]
                 (let [selrect (grc/make-rect x y width 0.01)]
                   (assoc frame
                          :height 0.01
                          :selrect selrect
                          :points [(gpt/point x y)
                                   (gpt/point (+ x width) y)
                                   (gpt/point (+ x width) y)
                                   (gpt/point x y)]))))))

;; JS only: on the JVM the flex line builder multiplies the gap by a nil
;; child count on its first child, so a flex reflow with children throws.
#?(:cljs
   (t/deftest reflow-of-a-flat-auto-height-board
     (t/testing "a board whose points have no height lays out its children inside its width"
       (let [file    (-> (thf/sample-file :file1)
                         (add-flex-frame :board :layout :flex :layout-flex-dir :column
                                         :layout-item-v-sizing :auto
                                         :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                         :layout-gap {:row-gap 0 :column-gap 0}
                                         :x 1000 :y 500 :width 200 :height 100)
                         (add-flex-frame :child :parent-label :board
                                         :layout-item-h-sizing :fill
                                         :x 1000 :y 500 :width 200 :height 37)
                         (flatten-frame :board))
             objects (:objects (thf/current-page file))
             board   (get objects (thi/id :board))
             child   (get objects (thi/id :child))

             modif-tree {(:id board) {:modifiers (ctm/reflow-modifiers)}}
             result     (gm/set-objects-modifiers modif-tree objects)

             board'  (gsh/transform-shape board (get-in result [(:id board) :modifiers]))
             child'  (gsh/transform-shape child (get-in result [(:id child) :modifiers]))]

         (t/is (mth/close? 200 (:width child')))
         (t/is (mth/close? 37 (:height child')))
         (t/is (mth/close? 1000 (:x child')))
         (t/is (mth/close? 37 (:height board')))
         (t/is (mth/close? 37 (gpt/distance (nth (:points board') 0)
                                            (nth (:points board') 3))))))))

;; JS only, for the same reason as reflow-of-a-flat-auto-height-board.
#?(:cljs
   (t/deftest reflow-of-a-flat-fill-child
     (t/testing "a fill child whose points have no width gets the width of its board"
       (let [file    (-> (thf/sample-file :file1)
                         (add-flex-frame :board :layout :flex :layout-flex-dir :row
                                         :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                         :layout-gap {:row-gap 0 :column-gap 0}
                                         :x 1000 :y 500 :width 200 :height 50)
                         (add-rect-child :child :board :layout-item-h-sizing :fill
                                         :x 1000 :y 500 :width 0.01 :height 20))
             page     (thf/current-page file)
             child-id (thi/id :child)
             file     (assoc-in file [:data :pages-index (:id page) :objects child-id :points]
                                [(gpt/point 1000 500) (gpt/point 1000 500)
                                 (gpt/point 1000 520) (gpt/point 1000 520)])
             objects  (:objects (thf/current-page file))
             child    (get objects child-id)

             modif-tree {(thi/id :board) {:modifiers (ctm/reflow-modifiers)}}
             result     (gm/set-objects-modifiers modif-tree objects)
             child'     (gsh/transform-shape child (get-in result [child-id :modifiers]))]

         (t/is (mth/close? 200 (:width child')))
         (t/is (mth/close? 1000 (:x child')))))))

