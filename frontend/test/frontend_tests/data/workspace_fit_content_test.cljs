;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-fit-content-test
  "Tests that `Resize board to fit content` keeps fill children at their
  current size instead of collapsing them."
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.transforms :as dwt]
   [cljs.test :as t :include-macros true]))

(defn- fit-content
  "Return the shapes of the page `:board` after fit content, by label."
  [file labels]
  (let [objects   (:objects (cthf/current-page file))
        board     (get objects (cthi/id :board))
        modif     (dwt/fit-layout-modifiers objects board)
        transform (fn [label]
                    (let [id (cthi/id label)]
                      (gsh/transform-shape (get objects id)
                                           (get-in modif [id :modifiers]))))]
    (into {} (map (juxt identity transform)) labels)))

(defn- add-row-board
  [file]
  (ctho/add-frame file :board
                  :x 0 :y 0 :width 96 :height 40
                  :layout :flex
                  :layout-flex-dir :row
                  :layout-gap-type :multiple
                  :layout-gap {:row-gap 4 :column-gap 4}
                  :layout-padding-type :multiple
                  :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}))

(defn- add-icon
  [file]
  (cths/add-sample-shape file :icon
                         :type :rect
                         :parent-label :board
                         :width 16 :height 16
                         :layout-item-h-sizing :fix
                         :layout-item-v-sizing :fix))

(t/deftest fit-content-keeps-a-fill-child-size
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-icon)
                   (cths/add-sample-shape :fill-child
                                          :type :rect
                                          :parent-label :board
                                          :width 60 :height 16
                                          :layout-item-h-sizing :fill
                                          :layout-item-v-sizing :fix))
        result (fit-content file [:board :fill-child])]
    (t/is (= 60 (:width (:fill-child result))))
    (t/is (= 96 (:width (:board result))))))

(t/deftest fit-content-keeps-a-fill-grandchild-inside-a-fill-board
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-icon)
                   (ctho/add-frame :inner
                                   :parent-label :board
                                   :width 60 :height 16
                                   :layout :flex
                                   :layout-flex-dir :column
                                   :layout-item-h-sizing :fill
                                   :layout-item-v-sizing :fix)
                   (cths/add-sample-shape :leaf
                                          :type :rect
                                          :parent-label :inner
                                          :width 60 :height 16
                                          :layout-item-h-sizing :fill
                                          :layout-item-v-sizing :fix))
        result (fit-content file [:inner :leaf])]
    (t/is (= 60 (:width (:inner result))))
    (t/is (= 60 (:width (:leaf result))))))

(t/deftest fit-content-still-fits-fix-children
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-icon))
        result (fit-content file [:board])]
    (t/is (= 32 (:width (:board result))))))

(defn- add-child
  [file label sizing-h sizing-v]
  (cths/add-sample-shape file label
                         :type :rect
                         :parent-label :board
                         :width 30 :height 24
                         :layout-item-h-sizing sizing-h
                         :layout-item-v-sizing sizing-v))

(t/deftest fit-content-fits-the-cross-axis-next-to-a-fixed-sibling
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-icon)
                   (add-child :child :fix :fill))
        result (fit-content file [:board :child])]
    (t/is (= 32 (:height (:board result))))
    (t/is (= 16 (:height (:child result))))))

(t/deftest fit-content-fits-a-fill-both-axes-child-next-to-a-fixed-sibling
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-icon)
                   (add-child :child :fill :fill))
        result (fit-content file [:board :child])]
    (t/is (= 66 (:width (:board result))))
    (t/is (= 32 (:height (:board result))))
    (t/is (= 30 (:width (:child result))))
    (t/is (= 16 (:height (:child result))))))

(t/deftest fit-content-keeps-heights-when-all-children-are-fill-height
  (let [file   (-> (cthf/sample-file :file1)
                   (add-row-board)
                   (add-child :a :fix :fill)
                   (add-child :b :fix :fill))
        result (fit-content file [:board :a :b])]
    (t/is (= 24 (:height (:a result))))
    (t/is (= 24 (:height (:b result))))
    (t/is (= 40 (:height (:board result))))))

(t/deftest fit-content-keeps-a-grid-fill-child-size-next-to-a-fixed-sibling
  (let [file   (-> (cthf/sample-file :file1)
                   (ctho/add-frame :board
                                   :x 0 :y 0 :width 200 :height 40
                                   :layout :grid
                                   :layout-grid-dir :row
                                   :layout-grid-columns [{:type :auto}
                                                         {:type :auto}]
                                   :layout-grid-rows [{:type :auto}]
                                   :layout-grid-cells {}
                                   :layout-padding-type :simple
                                   :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                   :layout-gap-type :multiple
                                   :layout-gap {:row-gap 0 :column-gap 0}
                                   :layout-item-h-sizing :auto
                                   :layout-item-v-sizing :fix)
                   (cths/add-sample-shape :fill-child
                                          :type :rect
                                          :parent-label :board
                                          :width 60 :height 16
                                          :layout-item-h-sizing :fill
                                          :layout-item-v-sizing :fix)
                   (cths/add-sample-shape :fixed-sibling
                                          :type :rect
                                          :parent-label :board
                                          :width 16 :height 16
                                          :layout-item-h-sizing :fix
                                          :layout-item-v-sizing :fix)
                   (cths/update-shape
                    :board
                    :layout-grid-cells
                    (let [fill-cell-id  (cthi/new-id! :fill-cell)
                          fixed-cell-id (cthi/new-id! :fixed-cell)]
                      {fill-cell-id {:id fill-cell-id
                                     :row 1 :column 1
                                     :row-span 1 :column-span 1
                                     :position :auto
                                     :align-self :auto :justify-self :auto
                                     :shapes [(cthi/id :fill-child)]}
                       fixed-cell-id {:id fixed-cell-id
                                      :row 1 :column 2
                                      :row-span 1 :column-span 1
                                      :position :auto
                                      :align-self :auto :justify-self :auto
                                      :shapes [(cthi/id :fixed-sibling)]}})))
        result (fit-content file [:board :fill-child :fixed-sibling])]
    (t/is (= 60 (:width (:fill-child result))))
    (t/is (= 16 (:width (:fixed-sibling result))))
    (t/is (= 76 (:width (:board result))))))
