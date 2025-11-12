;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.variants-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-update-property-name
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-name v-id 0 "NewName1")
                    (clvp/generate-update-property-name v-id 1 "NewName2"))


        file'   (thf/apply-changes file changes)



        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (-> comp01' :variant-properties first :name) "NewName1"))
    (t/is (= (-> comp01' :variant-properties last :name) "NewName2"))
    (t/is (= (-> comp02' :variant-properties first :name) "NewName1"))
    (t/is (= (-> comp02' :variant-properties last :name) "NewName2"))))



(t/deftest test-add-new-property-without-values
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)


        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id))


        file'   (thf/apply-changes file changes)



        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (count (:variant-properties comp01)) 1))
    (t/is (= (count (:variant-properties comp01')) 2))
    (t/is (= (count (:variant-properties comp02)) 1))
    (t/is (= (count (:variant-properties comp02')) 2))
    (t/is (= (-> comp01' :variant-properties last :value) ""))))



(t/deftest test-add-new-property-with-values
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)


        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id {:fill-values? true}))


        file'   (thf/apply-changes file changes)



        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (count (:variant-properties comp01)) 1))
    (t/is (= (count (:variant-properties comp01')) 2))
    (t/is (= (count (:variant-properties comp02)) 1))
    (t/is (= (count (:variant-properties comp02')) 2))
    (t/is (= (-> comp01' :variant-properties last :value) "Value 1"))
    (t/is (= (-> comp02' :variant-properties last :value) "Value 2"))))



(t/deftest test-add-new-property-with-the-same-value
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)


        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id {:property-value "Value 1"}))


        file'   (thf/apply-changes file changes)



        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (count (:variant-properties comp01)) 1))
    (t/is (= (count (:variant-properties comp01')) 2))
    (t/is (= (count (:variant-properties comp02)) 1))
    (t/is (= (count (:variant-properties comp02')) 2))
    (t/is (= (-> comp01' :variant-properties last :value) "Value 1"))
    (t/is (= (-> comp02' :variant-properties last :value) "Value 1"))))



(t/deftest test-remove-property
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id))


        file    (thf/apply-changes file changes)
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)


        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-remove-property v-id 0))


        file'   (thf/apply-changes file changes)



        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (count (:variant-properties comp01)) 2))
    (t/is (= (count (:variant-properties comp01')) 1))
    (t/is (= (count (:variant-properties comp02)) 2))
    (t/is (= (count (:variant-properties comp02')) 1))
    (t/is (= (-> comp01' :variant-properties first :name) "Property 2"))))

(t/deftest test-update-property-value
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))

        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 "NewValue1")
                    (clvp/generate-update-property-value (:id comp02) 0 "NewValue2"))

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    ;; ==== Check
    (t/is (= (-> comp01' :variant-properties first :value) "NewValue1"))
    (t/is (= (-> comp02' :variant-properties first :value) "NewValue2"))))


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
                    (pcb/with-page-id (:id page))
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
