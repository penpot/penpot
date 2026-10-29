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
   [app.common.logic.variant-properties :as clvp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.variant :as ctv]
   [clojure.test :as t]
   [cuerdas.core :as str]))

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

(t/deftest test-update-property-value-sanitizes
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)
        comp01  (thc/get-component file :c01)
        before  (-> comp01 :variant-properties first :value)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 "a=b,"))

        file'   (thf/apply-changes file changes)
        comp01' (thc/get-component file' :c01)
        main'   (ths/get-shape file' :m01)

        noop    (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 42))
        file-noop (thf/apply-changes file noop)
        comp-noop (thc/get-component file-noop :c01)

        cleared (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 "=,="))
        file-cleared (thf/apply-changes file cleared)
        comp-cleared (thc/get-component file-cleared :c01)
        main-cleared (ths/get-shape file-cleared :m01)

        nilled  (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 nil))
        file-nilled (thf/apply-changes file nilled)
        comp-nilled (thc/get-component file-nilled :c01)]

    (t/is (= "ab" (-> comp01' :variant-properties first :value)))
    (t/is (= "ab" (:variant-name main')))
    (t/is (= before (-> comp-noop :variant-properties first :value)))
    (t/is (= "" (-> comp-cleared :variant-properties first :value)))
    (t/is (not (str/includes? (str (:variant-name main-cleared)) "=")))
    (t/is (= "" (-> comp-nilled :variant-properties first :value)))))

(t/deftest test-update-property-name-sanitizes-and-noops
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)
        before  (-> (thc/get-component file :c01) :variant-properties first :name)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-name v-id 0 "Size=Big"))

        file'   (thf/apply-changes file changes)

        noop    (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-name v-id 0 "=,="))
        file-noop (thf/apply-changes file noop)]

    (t/is (= "SizeBig" (-> (thc/get-component file' :c01) :variant-properties first :name)))
    (t/is (= before (-> (thc/get-component file-noop :c01) :variant-properties first :name)))))

(t/deftest test-add-new-property-sanitizes
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)
        before-count (-> (thc/get-component file :c01) :variant-properties count)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id {:property-name "Size=Big"
                                                          :property-value "a=b,"}))

        file'   (thf/apply-changes file changes)
        comp01' (thc/get-component file' :c01)

        noop    (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id {:property-name "=,="}))
        file-noop (thf/apply-changes file noop)]

    (t/is (= "SizeBig" (-> comp01' :variant-properties last :name)))
    (t/is (= "ab" (-> comp01' :variant-properties last :value)))
    (t/is (= before-count
             (-> (thc/get-component file-noop :c01) :variant-properties count)))))

(t/deftest test-add-new-property-blank-value-does-not-trail-separator
  (let [file     (-> (thf/sample-file :file1)
                     (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id     (-> (ths/get-shape file :v01) :id)
        page     (thf/current-page file)
        main     (ths/get-shape file :m01)
        before   (:variant-name main)
        changes  (-> (pcb/empty-changes nil)
                     (pcb/with-page-id (:id page))
                     (pcb/with-library-data (:data file))
                     (pcb/with-objects (:objects page))
                     (clvp/generate-add-new-property v-id {:property-name "Size"
                                                           :property-value "=,="}))
        file'    (thf/apply-changes file changes)
        comp01'  (thc/get-component file' :c01)
        main'    (ths/get-shape file' :m01)]

    (t/is (= before (:variant-name main')))
    (t/is (not (str/ends-with? (str (:variant-name main')) ", ")))
    (t/is (= "" (-> comp01' :variant-properties last :value)))
    (t/is (= "Size" (-> comp01' :variant-properties last :name)))))

(t/deftest test-update-property-value-from-formula-strips-equal-in-value
  ;; Option A / M2: formula parse may leave `=` inside a value; write path strips it.
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)
        comp01  (thc/get-component file :c01)
        parsed  (ctv/properties-formula->map "border=yes color=yes")
        value   (-> parsed first :value)
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 value))
        file'   (thf/apply-changes file changes)
        comp01' (thc/get-component file' :c01)
        main'   (ths/get-shape file' :m01)]

    (t/is (= "yes color=yes" value))
    (t/is (= "yes coloryes" (-> comp01' :variant-properties first :value)))
    (t/is (= "yes coloryes" (:variant-name main')))
    (t/is (not= "yes color=yes" (-> comp01' :variant-properties first :value)))))

(t/deftest test-make-shapes-variant-sanitizes-component-name-as-value
  ;; create-new-properties-from-variant adds (:name component) as a value when
  ;; moving a variant into a differently named container. Production always
  ;; pairs generate-make-shapes-variant with change-parent.
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                      (thv/add-variant :v02 :c03 :m03 :c04 :m04)
                      (thc/update-component :c03 {:name "Big=red"}))
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        shape     (ths/get-shape file :m03)
        changes   (-> (pcb/empty-changes nil)
                      (pcb/with-page-id (:id page))
                      (pcb/with-library-data (:data file))
                      (pcb/with-objects (:objects page))
                      (clvp/generate-make-shapes-variant [shape] container)
                      (pcb/change-parent (:id container) [shape] 0))
        file'     (thf/apply-changes file changes)
        values    (->> (thc/get-component file' :c03)
                       :variant-properties
                       (map :value)
                       set)]

    (t/is (contains? values "Bigred"))))


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
