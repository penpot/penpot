;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variants-reparent-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- relocate
  [file parent-id ids & {:keys [to-index] :or {to-index 0}}]
  (let [page (thf/current-page file)]
    (-> (pcb/empty-changes nil)
        (pcb/with-page-id (:id page))
        (pcb/with-objects (:objects page))
        (pcb/with-library-data (:data file))
        (cls/generate-relocate parent-id to-index (set ids)))))

(defn- move-into-frame
  [file frame-id ids]
  (let [page    (thf/current-page file)
        objects (:objects page)]
    (-> (pcb/empty-changes nil (:id page))
        (pcb/with-objects objects)
        (pcb/with-library-data (:data file))
        (cls/generate-move-shapes-into-frame frame-id ids objects true))))

(defn- property-names
  [component]
  (mapv :name (:variant-properties component)))

(defn- strip-component-times
  "Undo restamps :modified-at on components when mains are updated.
   Compare file data without that clock."
  [data]
  (update data :components update-vals #(dissoc % :modified-at)))

(t/deftest test-relocate-plain-shape-onto-container-is-rejected
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-rect :rect1))
        container  (ths/get-shape file :v01)
        rect       (ths/get-shape file :rect1)
        children   (:shapes container)
        changes    (relocate file (:id container) [(:id rect)])
        file'      (thf/apply-changes file changes)
        rect'      (ths/get-shape file' :rect1)
        container' (ths/get-shape file' :v01)]

    (t/is (empty? (:redo-changes changes)))
    (t/is (= (:parent-id rect) (:parent-id rect')))
    (t/is (= (:constraints-h rect) (:constraints-h rect')))
    (t/is (= (:constraints-v rect) (:constraints-v rect')))
    (t/is (= children (:shapes container')))
    (thf/validate-file! file')))

(t/deftest test-relocate-component-copy-onto-container-is-rejected
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-simple-component-with-copy :c03 :m03 :r03 :copy03))
        container  (ths/get-shape file :v01)
        copy       (ths/get-shape file :copy03)
        comp       (thc/get-component file :c03)
        children   (:shapes container)
        changes    (relocate file (:id container) [(:id copy)])
        file'      (thf/apply-changes file changes)
        copy'      (ths/get-shape file' :copy03)
        comp'      (thc/get-component file' :c03)
        container' (ths/get-shape file' :v01)]

    (t/is (empty? (:redo-changes changes)))
    (t/is (= (:parent-id copy) (:parent-id copy')))
    (t/is (nil? (:variant-id copy')))
    (t/is (= (:variant-id comp) (:variant-id comp')))
    (t/is (= (:variant-properties comp) (:variant-properties comp')))
    (t/is (= children (:shapes container')))
    (thf/validate-file! file')))

(t/deftest test-relocate-mixed-selection-onto-container-is-rejected
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                      (tho/add-rect :rect1)
                      (tho/add-simple-component :c03 :m03 :r03))
        container (ths/get-shape file :v01)
        rect      (ths/get-shape file :rect1)
        main      (ths/get-shape file :m03)
        changes   (relocate file (:id container) [(:id rect) (:id main)])
        file'     (thf/apply-changes file changes)
        rect'     (ths/get-shape file' :rect1)
        main'     (ths/get-shape file' :m03)]

    (t/is (empty? (:redo-changes changes)))
    (t/is (= (:parent-id rect) (:parent-id rect')))
    (t/is (= (:parent-id main) (:parent-id main')))
    (thf/validate-file! file')))

(t/deftest test-relocate-main-into-container-becomes-variant
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-simple-component :c03 :m03 :r03))
        container  (ths/get-shape file :v01)
        main       (ths/get-shape file :m03)
        sibling    (thc/get-component file :c01)
        changes    (relocate file (:id container) [(:id main)])
        file'      (thf/apply-changes file changes)
        file''     (thf/apply-undo-changes file' changes)
        main'      (ths/get-shape file' :m03)
        comp'      (thc/get-component file' :c03)
        sibling'   (thc/get-component file' :c01)
        container' (ths/get-shape file' :v01)]

    (t/is (= (:id container') (:parent-id main')))
    (t/is (= (:id container') (:variant-id main')))
    (t/is (= (:id container') (:variant-id comp')))
    (t/is (some? (:variant-name main')))
    (t/is (= (property-names sibling') (property-names comp')))
    (t/is (= (property-names sibling) (property-names sibling')))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file''))))
    (thf/validate-file! file')))

(t/deftest test-relocate-reorder-mains-does-not-rebuild-properties
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        container (ths/get-shape file :v01)
        m01       (ths/get-shape file :m01)
        m02       (ths/get-shape file :m02)
        c01       (thc/get-component file :c01)
        c02       (thc/get-component file :c02)
        changes   (relocate file (:id container) [(:id m01)] :to-index 0)
        file'     (thf/apply-changes file changes)
        file''    (thf/apply-undo-changes file' changes)
        m01'      (ths/get-shape file' :m01)
        m02'      (ths/get-shape file' :m02)
        c01'      (thc/get-component file' :c01)
        c02'      (thc/get-component file' :c02)]

    (t/is (= (:variant-name m01) (:variant-name m01')))
    (t/is (= (:variant-name m02) (:variant-name m02')))
    (t/is (= (:variant-properties c01) (:variant-properties c01')))
    (t/is (= (:variant-properties c02) (:variant-properties c02')))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file''))))
    (thf/validate-file! file')))

(t/deftest test-relocate-all-mains-within-container-keeps-container
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        container  (ths/get-shape file :v01)
        m01        (ths/get-shape file :m01)
        m02        (ths/get-shape file :m02)
        changes    (relocate file (:id container) [(:id m01) (:id m02)])
        file'      (thf/apply-changes file changes)
        container' (ths/get-shape file' :v01)]

    (t/is (= (:id container) (:id container')))
    (t/is (= (set (:shapes container)) (set (:shapes container'))))
    (thf/validate-file! file')))

(t/deftest test-relocate-main-out-of-container-clears-variant-data
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-frame :board1))
        board      (ths/get-shape file :board1)
        m01        (ths/get-shape file :m01)
        changes    (relocate file (:id board) [(:id m01)])
        file'      (thf/apply-changes file changes)
        file''     (thf/apply-undo-changes file' changes)
        m01'       (ths/get-shape file' :m01)
        c01'       (thc/get-component file' :c01)
        container' (ths/get-shape file' :v01)]

    (t/is (= (:id board) (:parent-id m01')))
    (t/is (nil? (:variant-id m01')))
    (t/is (nil? (:variant-name m01')))
    (t/is (nil? (:variant-id c01')))
    (t/is (nil? (:variant-properties c01')))
    (t/is (some? container'))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file''))))
    (thf/validate-file! file')))

(t/deftest test-relocate-last-mains-out-deletes-empty-container
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-frame :board1))
        board      (ths/get-shape file :board1)
        m01        (ths/get-shape file :m01)
        m02        (ths/get-shape file :m02)
        changes    (relocate file (:id board) [(:id m01) (:id m02)])
        file'      (thf/apply-changes file changes)
        file''     (thf/apply-undo-changes file' changes)
        container' (ths/get-shape file' :v01)
        m01'       (ths/get-shape file' :m01)
        m02'       (ths/get-shape file' :m02)
        c01'       (thc/get-component file' :c01)
        c02'       (thc/get-component file' :c02)]

    (t/is (nil? container'))
    (t/is (some? m01'))
    (t/is (some? m02'))
    (t/is (= (:id board) (:parent-id m01')))
    (t/is (= (:id board) (:parent-id m02')))
    (t/is (nil? (:variant-id m01')))
    (t/is (nil? (:variant-id m02')))
    (t/is (nil? (:variant-name m01')))
    (t/is (nil? (:variant-name m02')))
    (t/is (some? c01'))
    (t/is (some? c02'))
    (t/is (nil? (:variant-id c01')))
    (t/is (nil? (:variant-id c02')))
    (t/is (nil? (:variant-properties c01')))
    (t/is (nil? (:variant-properties c02')))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file''))))
    (thf/validate-file! file')))

(t/deftest test-board-draw-strips-variant-data-when-main-leaves
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-frame :board1))
        board      (ths/get-shape file :board1)
        m01        (ths/get-shape file :m01)
        changes    (move-into-frame file (:id board) [(:id m01)])
        file'      (thf/apply-changes file changes)
        file''     (thf/apply-undo-changes file' changes)
        m01'       (ths/get-shape file' :m01)
        c01'       (thc/get-component file' :c01)
        container' (ths/get-shape file' :v01)]

    (t/is (= (:id board) (:parent-id m01')))
    (t/is (nil? (:variant-id m01')))
    (t/is (nil? (:variant-name m01')))
    (t/is (nil? (:variant-id c01')))
    (t/is (nil? (:variant-properties c01')))
    (t/is (some? container'))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file''))))
    (thf/validate-file! file')))

(t/deftest test-board-draw-last-main-deletes-empty-container
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-frame :board1))
        board      (ths/get-shape file :board1)
        m01        (ths/get-shape file :m01)
        m02        (ths/get-shape file :m02)
        file*      (thf/apply-changes file (move-into-frame file (:id board) [(:id m01)]))
        changes    (move-into-frame file* (:id board) [(:id m02)])
        file'      (thf/apply-changes file* changes)
        container' (ths/get-shape file' :v01)
        m01'       (ths/get-shape file' :m01)
        m02'       (ths/get-shape file' :m02)
        c01'       (thc/get-component file' :c01)
        c02'       (thc/get-component file' :c02)]

    (t/is (nil? container'))
    (t/is (some? m01'))
    (t/is (some? m02'))
    (t/is (= (:id board) (:parent-id m01')))
    (t/is (= (:id board) (:parent-id m02')))
    (t/is (nil? (:variant-id m01')))
    (t/is (nil? (:variant-id m02')))
    (t/is (nil? (:variant-name m01')))
    (t/is (nil? (:variant-name m02')))
    (t/is (some? c01'))
    (t/is (some? c02'))
    (t/is (nil? (:variant-id c01')))
    (t/is (nil? (:variant-id c02')))
    (t/is (nil? (:variant-properties c01')))
    (t/is (nil? (:variant-properties c02')))
    (thf/validate-file! file')))

(t/deftest test-board-draw-undo-restores-emptied-container
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-frame :board1))
        board      (ths/get-shape file :board1)
        m01        (ths/get-shape file :m01)
        m02        (ths/get-shape file :m02)
        changes*   (move-into-frame file (:id board) [(:id m01)])
        file*      (thf/apply-changes file changes*)
        changes    (move-into-frame file* (:id board) [(:id m02)])
        file'      (thf/apply-changes file* changes)
        file''     (thf/apply-undo-changes file' changes)
        file'''    (thf/apply-undo-changes file'' changes*)]

    (t/is (= (strip-component-times (:data file*))
             (strip-component-times (:data file''))))
    (t/is (= (strip-component-times (:data file))
             (strip-component-times (:data file'''))))))

(t/deftest test-duplicate-variant-main-keeps-variant-id
  (let [file       (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        container  (ths/get-shape file :v01)
        file'      (tho/duplicate-shape file :m01)
        objects'   (:objects (thf/current-page file'))
        variant-mains (->> (vals objects')
                           (filter ctk/main-instance?)
                           (filter #(= (:id container) (:variant-id %))))]

    (t/is (= 3 (count variant-mains)))
    (thf/validate-file! file')))
