;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variant-properties-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.variant-properties :as clvp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; =============================================================================
;; generate-update-property-name tests
;; =============================================================================

(t/deftest test-update-property-name-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; variant-id nil
    (let [changes (clvp/generate-update-property-name base-changes nil 0 "NewName")]
      (t/is (pcb/empty-changes? changes)))

    ;; variant-id non-existent
    (let [changes (clvp/generate-update-property-name base-changes (uuid/next) 0 "NewName")]
      (t/is (pcb/empty-changes? changes)))

    ;; pos nil
    (let [changes (clvp/generate-update-property-name base-changes v-id nil "NewName")]
      (t/is (pcb/empty-changes? changes)))

    ;; pos out of range (negative)
    (let [changes (clvp/generate-update-property-name base-changes v-id -1 "NewName")]
      (t/is (pcb/empty-changes? changes)))

    ;; pos out of range (too large)
    (let [changes (clvp/generate-update-property-name base-changes v-id 100 "NewName")]
      (t/is (pcb/empty-changes? changes)))))

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

(t/deftest test-update-property-name-duplicate
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-name v-id 0 "Property 2"))

        file'   (thf/apply-changes file changes)

        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    (t/is (= "Property 2 (1)" (-> comp01' :variant-properties first :name)))
    (t/is (= "Property 2"     (-> comp01' :variant-properties last :name)))
    (t/is (= "Property 2 (1)" (-> comp02' :variant-properties first :name)))
    (t/is (= "Property 2"     (-> comp02' :variant-properties last :name)))))

;; =============================================================================
;; generate-remove-property tests
;; =============================================================================

(t/deftest test-remove-property-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        orig-names (mapv :name (:variant-properties comp01))

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; variant-id nil
    (let [changes (clvp/generate-remove-property base-changes nil 0)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= orig-names (mapv :name (:variant-properties comp01')))))

    ;; variant-id non-existent
    (let [changes (clvp/generate-remove-property base-changes (uuid/next) 0)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= orig-names (mapv :name (:variant-properties comp01')))))

    ;; pos nil
    (let [changes (clvp/generate-remove-property base-changes v-id nil)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= orig-names (mapv :name (:variant-properties comp01')))))

    ;; pos out of range (negative)
    (let [changes (clvp/generate-remove-property base-changes v-id -1)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= orig-names (mapv :name (:variant-properties comp01')))))

    ;; pos out of range (too large)
    (let [changes (clvp/generate-remove-property base-changes v-id 100)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= orig-names (mapv :name (:variant-properties comp01')))))))

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

;; =============================================================================
;; generate-update-property-value tests
;; =============================================================================

(t/deftest test-update-property-value-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        c01-id  (:id comp01)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; component-id nil
    (let [changes (clvp/generate-update-property-value base-changes nil 0 "NewVal")]
      (t/is (pcb/empty-changes? changes)))

    ;; component-id non-existent
    (let [changes (clvp/generate-update-property-value base-changes (uuid/next) 0 "NewVal")]
      (t/is (pcb/empty-changes? changes)))

    ;; pos nil
    (let [changes (clvp/generate-update-property-value base-changes c01-id nil "NewVal")]
      (t/is (pcb/empty-changes? changes)))

    ;; pos out of range
    (let [changes (clvp/generate-update-property-value base-changes c01-id 100 "NewVal")]
      (t/is (pcb/empty-changes? changes)))))

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
    (t/is (= (-> comp02' :variant-properties first :value) "NewValue2"))
    ;; The last call validates the main instance of comp02
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp02)]))))

(t/deftest test-update-property-value-duplicate-value
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        comp02  (thc/get-component file :c02)
        c01-id  (:id comp01)
        c02-id  (:id comp02)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value c01-id 0 "SharedValue")
                    (clvp/generate-update-property-value c02-id 0 "SharedValue"))

        file'   (thf/apply-changes file changes)

        comp01' (thc/get-component file' :c01)
        comp02' (thc/get-component file' :c02)]

    (t/is (= "SharedValue" (-> comp01' :variant-properties first :value)))
    (t/is (= "SharedValue" (-> comp02' :variant-properties first :value)))
    ;; The last call validates the main instance of comp02
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp02)]))))

(t/deftest test-update-property-value-validates-main
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)
        comp01  (thc/get-component file :c01)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-update-property-value (:id comp01) 0 "NewValue"))]

    ;; Only the main instance of the updated component changes
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp01)]))))

;; =============================================================================
;; generate-set-variant-error tests
;; =============================================================================

(t/deftest test-set-variant-error-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; component-id nil
    (let [changes (clvp/generate-set-variant-error base-changes nil "error")]
      (t/is (pcb/empty-changes? changes)))

    ;; component-id non-existent
    (let [changes (clvp/generate-set-variant-error base-changes (uuid/next) "error")]
      (t/is (pcb/empty-changes? changes)))))

(t/deftest test-set-variant-error-set
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        c01-id  (:id comp01)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-set-variant-error c01-id "MyError"))

        file'   (thf/apply-changes file changes)

        main01' (ths/get-shape file' :m01)]

    (t/is (= "MyError" (:variant-error main01')))
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp01)]))))

(t/deftest test-set-variant-error-add-and-clear
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        comp01  (thc/get-component file :c01)
        c01-id  (:id comp01)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-set-variant-error c01-id "SomeError")
                    (clvp/generate-set-variant-error c01-id nil))

        file'   (thf/apply-changes file changes)

        main01' (ths/get-shape file' :m01)]

    (t/is (nil? (:variant-error main01')))
    ;; The last call, which clears the error, validates the same main instance
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp01)]))))

(t/deftest test-set-variant-error-validates-main
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)
        comp01  (thc/get-component file :c01)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-set-variant-error (:id comp01) "MyError"))]

    ;; Only the main instance of the component changes
    (t/is (thf/validates-shapes-last? changes (:id page) [(:main-instance-id comp01)]))))

;; =============================================================================
;; generate-reorder-variant-poperties tests
;; =============================================================================

(t/deftest test-reorder-variant-properties-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; variant-id nil
    (let [changes (clvp/generate-reorder-variant-poperties base-changes nil 0 1)]
      (t/is (pcb/empty-changes? changes)))

    ;; variant-id non-existent
    (let [changes (clvp/generate-reorder-variant-poperties base-changes (uuid/next) 0 1)]
      (t/is (pcb/empty-changes? changes)))

    ;; from-pos nil
    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id nil 1)]
      (t/is (pcb/empty-changes? changes)))

    ;; to-space-between-pos nil
    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id 0 nil)]
      (t/is (pcb/empty-changes? changes)))

    ;; from-pos negative is clamped to 0
    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id -1 1)]
      (t/is (pcb/empty-changes? changes)))

    ;; to-space-between-pos too large is clamped to last index
    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id 1 100)]
      (t/is (pcb/empty-changes? changes)))))

(t/deftest test-reorder-variant-properties-move
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-reorder-variant-poperties v-id 0 2))

        file'   (thf/apply-changes file changes)

        comp01' (thc/get-component file' :c01)]

    (t/is (= "Property 2" (-> comp01' :variant-properties first :name)))
    (t/is (= "Property 1" (-> comp01' :variant-properties last :name)))
    (t/is (thf/validates-shapes-last? changes (:id page) [v-id]))))

(t/deftest test-reorder-variant-properties-clamped-pos
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id 0 100)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= "Property 2" (-> comp01' :variant-properties first :name)))
      (t/is (= "Property 1" (-> comp01' :variant-properties last :name)))
      (t/is (thf/validates-shapes-last? changes (:id page) [v-id])))

    (let [changes (clvp/generate-reorder-variant-poperties base-changes v-id 1 -1)
          file'   (thf/apply-changes file changes)
          comp01' (thc/get-component file' :c01)]
      (t/is (= "Property 2" (-> comp01' :variant-properties first :name)))
      (t/is (= "Property 1" (-> comp01' :variant-properties last :name)))
      (t/is (thf/validates-shapes-last? changes (:id page) [v-id])))))

(t/deftest test-reorder-variant-properties-validates-container
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-reorder-variant-poperties v-id 0 2))]

    ;; All the components of the variant change, so validate the container,
    ;; which checks every main instance and that all of them keep the same
    ;; order of properties
    (t/is (thf/validates-shapes-last? changes (:id page) [v-id]))))

;; =============================================================================
;; generate-add-new-property tests
;; =============================================================================

(t/deftest test-add-new-property-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; variant-id nil
    (let [changes (clvp/generate-add-new-property base-changes nil)]
      (t/is (pcb/empty-changes? changes)))

    ;; variant-id non-existent
    (let [changes (clvp/generate-add-new-property base-changes (uuid/next))]
      (t/is (pcb/empty-changes? changes)))))

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
    (t/is (= (-> comp01' :variant-properties last :value) ""))
    (t/is (thf/validates-shapes-last? changes (:id page) [v-id]))))

(t/deftest test-add-new-property-skip-validation
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id :skip-validation? true))]

    (t/is (seq (:redo-changes changes)))
    (t/is (not-any? #(= :validate-shapes (:type %)) (:redo-changes changes)))))

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

(t/deftest test-add-new-property-duplicate-name
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        v-id    (-> (ths/get-shape file :v01) :id)
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-add-new-property v-id {:property-name "Property 1"}))

        file'   (thf/apply-changes file changes)

        comp01' (thc/get-component file' :c01)
        prop-names (mapv :name (:variant-properties comp01'))]

    (t/is (= 2 (count prop-names)))
    (t/is (= "Property 1" (first prop-names)))
    (t/is (= "Property 1 (1)" (last prop-names)))))

;; =============================================================================
;; generate-make-shapes-no-variant tests
;; =============================================================================

(t/deftest test-make-shapes-no-variant-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))]

    ;; empty shapes list
    (let [changes (clvp/generate-make-shapes-no-variant base-changes [])]
      (t/is (pcb/empty-changes? changes)))

    ;; nil shapes list
    (let [changes (clvp/generate-make-shapes-no-variant base-changes nil)]
      (t/is (pcb/empty-changes? changes)))))

(t/deftest test-make-shapes-no-variant-remove
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        main01  (ths/get-shape file :m01)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-make-shapes-no-variant [main01]))

        file'   (thf/apply-changes file changes :validate? false)

        comp01' (thc/get-component file' :c01)
        main01' (ths/get-shape file' :m01)]

    (t/is (nil? (:variant-id comp01')))
    (t/is (nil? (:variant-properties comp01')))
    (t/is (nil? (:variant-id main01')))
    (t/is (nil? (:variant-name main01')))
    (t/is (thf/validates-shapes-last? changes (:id page) [(:id main01)]))))

(t/deftest test-make-shapes-no-variant-validates-shapes
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)
        main01  (ths/get-shape file :m01)
        main02  (ths/get-shape file :m02)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-make-shapes-no-variant [main01 main02]))]

    ;; Only the extracted main instances change. The container is not
    ;; validated, as the caller may not have moved the shapes out of it yet
    (t/is (thf/validates-shapes-last? changes (:id page) [(:id main01) (:id main02)]))))

;; =============================================================================
;; generate-make-shapes-variant tests
;; =============================================================================

(t/deftest test-make-shapes-variant-no-op
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page    (thf/current-page file)

        base-changes (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-library-data (:data file))
                         (pcb/with-objects (:objects page)))

        container (ths/get-shape file :v01)]

    ;; empty shapes list
    (let [changes (clvp/generate-make-shapes-variant base-changes [] container)]
      (t/is (pcb/empty-changes? changes)))

    ;; nil shapes list
    (let [container (ths/get-shape file :v01)
          changes (clvp/generate-make-shapes-variant base-changes nil container)]
      (t/is (pcb/empty-changes? changes)))))

(t/deftest test-make-shapes-variant-convert
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))

        container (ths/get-shape file :v01)
        v-id (:id container)

        ;; Add a non-variant component under the variant container
        file (tho/add-simple-component file :c03 :m03 :sh03
                                       :root-params {:parent-label :v01})

        m03 (ths/get-shape file :m03)
        page (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-make-shapes-variant [m03] container))

        file'   (thf/apply-changes file changes :validate? false)

        c03'   (thc/get-component file' :c03)
        m03' (ths/get-shape file' :m03)]

    (t/is (= v-id (:variant-id c03')))
    (t/is (= v-id (:variant-id m03')))
    (t/is (= "Property 1" (-> c03' :variant-properties first :name)))
    (t/is (= "Frame1" (-> c03' :variant-properties first :value)))
    (t/is (= "Frame1" (-> m03' :variant-name)))))

(t/deftest test-make-shapes-variant-with-new-properties-does-not-validate
  ;; The shape is already inside the container but it is not a variant yet,
  ;; as when a component is restored into a variant. The function adds a new
  ;; property to the siblings while the shape is not a variant, so it must
  ;; not validate: the caller validates the container when done
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                    (tho/add-simple-component :c03 :m03 :sh03
                                              :root-params {:parent-label :v01
                                                            :name "Big / Red"}))
        page    (thf/current-page file)

        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (clvp/generate-make-shapes-variant [(ths/get-shape file :m03)]
                                                       (ths/get-shape file :v01)))

        file'   (thf/apply-changes file changes)]

    (t/is (not-any? #(= :validate-shapes (:type %)) (:redo-changes changes)))
    (t/is (= 2 (count (:variant-properties (thc/get-component file' :c01)))))
    (t/is (= 2 (count (:variant-properties (thc/get-component file' :c03)))))))
