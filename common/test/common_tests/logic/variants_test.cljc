;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variants-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-duplicate-variant-container
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)

        variant-container (ths/get-shape file :v01)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (cll/generate-duplicate-changes objects                    ;; objects
                                                    page                       ;; page
                                                    #{(:id variant-container)} ;; ids
                                                    (gpt/point 0 0)            ;; delta
                                                    {(:id  file) file}         ;; libraries
                                                    (:data file)               ;; library-data
                                                    (:id file)))               ;; file-id

        ;; ==== Get
        file'   (thf/apply-changes file changes)
        data'   (:data file')
        page'   (thf/current-page file')
        objects' (:objects page')]

    ;; ==== Check
    (thf/validate-file! file')
    (t/is (= (count (:components data)) 2))
    (t/is (= (count (:components data')) 4))
    (t/is (= (count objects) 4))
    (t/is (= (count objects') 7))))

(t/deftest test-delete-variant
  ;; When a variant container becomes empty, it id automatically deleted
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        container (ths/get-shape file :v01)
        m01-id    (-> (ths/get-shape file :m01) :id)
        m02-id    (-> (ths/get-shape file :m02) :id)

        page    (thf/current-page file)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page page)
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (#(second (cls/generate-delete-shapes % #{m01-id m02-id} {}))))

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        container' (ths/get-shape file' :v01)]

    ;; ==== Check
    ;; The variant containew was not nil before the deletion
    (t/is (not (nil? container)))
    ;; The variant containew is nil after the deletion
    (t/is (nil? container'))))

(t/deftest test-instantiate-component-over-variant-container
  ;; When a component is instantiated at a position over a variant container,
  ;; the new copy must not become a child of the container (its children can
  ;; only be variant mains)
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        container (ths/get-shape file :v01)
        page      (thf/current-page file)

        ;; ==== Action
        ;; Instantiate at a position inside the variant container, without
        ;; an explicit parent, so the destiny frame is chosen by position
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (:objects page)
                                            (:id file)
                                            (thi/id :c01)
                                            (gpt/point (:x container) (:y container))
                                            page
                                            {(:id file) file})

        file'      (thf/apply-changes file changes)

        ;; ==== Get
        new-shape' (ths/get-shape-by-id file' (:id new-shape))]

    ;; ==== Check
    (thf/validate-file! file')
    (t/is (some? new-shape'))
    (t/is (not= (:parent-id new-shape') (:id container)))))
