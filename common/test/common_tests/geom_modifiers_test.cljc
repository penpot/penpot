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

(def ^:private flex-layout-defaults
  "The flex layout attributes a board gets when a flex layout is added
  to it in the workspace."
  {:layout                 :flex
   :layout-flex-dir        :row
   :layout-gap-type        :multiple
   :layout-gap             {:row-gap 0 :column-gap 0}
   :layout-align-items     :start
   :layout-justify-content :start
   :layout-align-content   :stretch
   :layout-wrap-type       :nowrap
   :layout-padding-type    :simple
   :layout-padding         {:p1 0 :p2 0 :p3 0 :p4 0}})

(defn- auto-board-after-layout
  "The board `:frame1`, sized by its content on both axes, once its
  layout runs again (a move of its 50 x 50 child triggers it)."
  [frame-params]
  (let [file     (-> (thf/sample-file :file1)
                     (add-flex-frame :frame1 (merge flex-layout-defaults
                                                    {:layout-flex-dir :column
                                                     :layout-item-h-sizing :auto
                                                     :layout-item-v-sizing :auto}
                                                    frame-params))
                     (add-rect-child :rect1 :frame1))
        objects  (:objects (thf/current-page file))
        frame-id (thi/id :frame1)
        result   (gm/set-objects-modifiers {(thi/id :rect1) {:modifiers (ctm/move-modifiers (gpt/point 0 1))}}
                                           objects)]
    (gsh/transform-shape (get objects frame-id)
                         (get-in result [frame-id :modifiers]))))

(t/deftest auto-sized-board-keeps-its-min-and-max-sizes
  (t/testing "A board sized by its content follows it"
    (let [board (auto-board-after-layout {})]
      (t/is (mth/close? 50 (:width board)))
      (t/is (mth/close? 50 (:height board)))))

  (t/testing "A board sized by its content keeps its minimum sizes"
    (let [board (auto-board-after-layout {:layout-item-min-w 70
                                          :layout-item-min-h 80})]
      (t/is (mth/close? 70 (:width board)))
      (t/is (mth/close? 80 (:height board)))))

  (t/testing "A board sized by its content keeps its maximum sizes"
    (let [board (auto-board-after-layout {:layout-item-max-w 20
                                          :layout-item-max-h 30})]
      (t/is (mth/close? 20 (:width board)))
      (t/is (mth/close? 30 (:height board)))))

  (t/testing "The minimum wins over a smaller maximum, as in CSS"
    (let [board (auto-board-after-layout {:layout-item-min-w 70
                                          :layout-item-max-w 20
                                          :layout-item-min-h 80
                                          :layout-item-max-h 30})]
      (t/is (mth/close? 70 (:width board)))
      (t/is (mth/close? 80 (:height board))))))
