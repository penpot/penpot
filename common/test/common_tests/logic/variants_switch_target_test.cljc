;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variants-switch-target-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.variants :as clv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.shape.layout :as ctl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- libraries
  [file]
  {(:id file) file})

(defn- add-third-variant
  "Add a component :c03 with properties p1v2, p2v1 to the variant :v01 built
   by `thv/add-variant-two-properties`."
  [file]
  (let [variant-id (thi/id :v01)]
    (-> file
        (ths/add-sample-shape :m03 :type :frame :parent-label :v01
                              :variant-id variant-id :variant-name "p1v2, p2v1")
        (thc/make-component :c03 :m03)
        (thc/update-component :c03 {:variant-id variant-id
                                    :variant-properties [{:name "Property 1" :value "p1v2"}
                                                         {:name "Property 2" :value "p2v1"}]}))))

(t/deftest switch-target-has-the-requested-value
  (let [file   (-> (thf/sample-file :file1)
                   (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                   (thc/instantiate-component :c01 :copy01))
        target (clv/find-switch-target (libraries file) (ths/get-shape file :copy01) 0 "Value2")]
    (t/is (= (thi/id :c02) (:id target)))))

(t/deftest switch-target-is-nil-when-the-value-is-current
  (let [file (-> (thf/sample-file :file1)
                 (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                 (thc/instantiate-component :c01 :copy01))]
    (t/is (nil? (clv/find-switch-target (libraries file) (ths/get-shape file :copy01) 0 "Value1")))))

(t/deftest switch-target-is-nil-when-no-variant-has-the-value
  (let [file (-> (thf/sample-file :file1)
                 (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                 (thc/instantiate-component :c01 :copy01))]
    (t/is (nil? (clv/find-switch-target (libraries file) (ths/get-shape file :copy01) 0 "Missing")))))

(t/deftest switch-target-keeps-the-other-properties-when-it-can
  ;; From p1v1, p2v1, setting Property 1 to p1v2 can give c02 (p1v2, p2v2)
  ;; or c03 (p1v2, p2v1). c03 keeps Property 2, so it is nearer.
  (let [file   (-> (thf/sample-file :file1)
                   (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02)
                   (add-third-variant)
                   (thc/instantiate-component :c01 :copy01))
        target (clv/find-switch-target (libraries file) (ths/get-shape file :copy01) 0 "p1v2")]
    (t/is (= (thi/id :c03) (:id target)))))

(t/deftest switch-into-own-main-is-a-nesting-loop
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                    (thc/instantiate-component :c01 :nested :parent-label :m02)
                    (thc/instantiate-component :c01 :copy01))
        objects (:objects (thf/current-page file))
        loop?   #(clv/swap-nesting-loop? objects (ths/get-shape file %) (:data file) (thi/id :c02))]
    (t/is (true? (loop? :nested)))
    (t/is (false? (loop? :copy01)))))

(t/deftest swap-in-place-keeps-index-and-layout-attrs
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                      (ths/add-sample-shape :board :type :frame)
                      (ths/add-sample-shape :first :parent-label :board)
                      (thc/instantiate-component :c01 :copy01 :parent-label :board)
                      (ths/add-sample-shape :last :parent-label :board)
                      (ths/update-shape :copy01 :layout-item-z-index 7))
        page      (thf/current-page file)
        board     (ths/get-shape file :board)
        index     (d/index-of (:shapes board) (thi/id :copy01))
        [new-shape changes _]
        (clv/generate-component-swap-in-place (pcb/empty-changes nil (:id page))
                                              page
                                              (libraries file)
                                              (:data file)
                                              (ths/get-shape file :copy01)
                                              (thi/id :c02)
                                              false)
        file'     (thf/apply-changes file changes)
        new-shape (ths/get-shape-by-id file' (:id new-shape))]
    (t/is (= (thi/id :c02) (:component-id new-shape)))
    (t/is (= index (d/index-of (:shapes (ths/get-shape file' :board)) (:id new-shape))))
    (t/is (= 7 (:layout-item-z-index new-shape)))))

(defn- add-grid-board
  "Add a board :board with a one-row, three-column grid layout, holding a shape
   :first in the first column and a copy :copy01 of :c01 in the third, so the
   second cell is free."
  [file]
  (let [file    (-> file
                    (ths/add-sample-shape :board
                                          :type :frame
                                          :layout :grid
                                          :layout-grid-dir :row
                                          :layout-grid-rows []
                                          :layout-grid-columns []
                                          :layout-grid-cells {})
                    (ths/add-sample-shape :first :parent-label :board)
                    (thc/instantiate-component :c01 :copy01 :parent-label :board))
        objects (:objects (thf/current-page file))
        board   (-> (get objects (thi/id :board))
                    (ctl/add-grid-row {:type :flex :value 1})
                    (ctl/add-grid-column {:type :flex :value 1})
                    (ctl/add-grid-column {:type :flex :value 1})
                    (ctl/add-grid-column {:type :flex :value 1}))
        place   (fn [cells column label]
                  (let [[id _] (d/seek #(= column (:column (val %))) cells)]
                    (assoc-in cells [id :shapes] [(thi/id label)])))
        cells   (-> (:layout-grid-cells board)
                    (place 1 :first)
                    (place 3 :copy01))]
    (-> file
        (ths/update-shape :board :layout-grid-rows (:layout-grid-rows board))
        (ths/update-shape :board :layout-grid-columns (:layout-grid-columns board))
        (ths/update-shape :board :layout-grid-cells cells))))

(t/deftest swap-in-place-keeps-the-grid-cell
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                      (add-grid-board))
        page      (thf/current-page file)
        cell      (ctl/get-cell-by-shape-id (ths/get-shape file :board) (thi/id :copy01))
        [new-shape changes _]
        (clv/generate-component-swap-in-place (pcb/empty-changes nil (:id page))
                                              page
                                              (libraries file)
                                              (:data file)
                                              (ths/get-shape file :copy01)
                                              (thi/id :c02)
                                              false)
        file'     (thf/apply-changes file changes)
        cell'     (ctl/get-cell-by-shape-id (ths/get-shape file' :board) (:id new-shape))]
    (t/is (= 3 (:column cell)))
    (t/is (= (:id cell) (:id cell')))
    (t/is (= [(:id new-shape)] (:shapes cell')))))
