;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.repair-test
  "Tests for the validate / repair functions in app.common.files.validate
   and app.common.files.repair. 

   The tests generate cases of broken files and check that the validation functions
   generate accurate errors, and that the repair functions return the file to
   a stable state."
  (:require
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest repair-main-instance-not-a-variant
  (t/testing "detect and repair a variant component whose root shape is not a variant"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (ths/update-shape :root1 :variant-id nil))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          root1'  (ths/get-shape file' :root1 :page-label :page1)]

      (t/is (= 2 (count errors)))  ;; There are two different checks that detect the same problem
      (t/is (= :main-instance-not-a-variant (:code (first errors))))

      (t/is (nil? errors'))
      (t/is (= (thi/id :variant1) (:variant-id root1'))))))

(t/deftest repair-invalid-variant-id-variant-component-bad-id
  (t/testing "detect and repair a variant component whose variant id does not match the container's id"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (ths/update-shape :root1 :variant-id (uuid/next)))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          root1'  (ths/get-shape file' :root1 :page-label :page1)]

      (t/is (= 2 (count errors)))  ;; There are two different validation that actually check the same problem
      (t/is (= :main-instance-invalid-variant-id (:code (first errors))))
      (t/is (= :variant-component-bad-id (:code (second errors))))

      (t/is (nil? errors'))
      (t/is (= (thi/id :variant1) (:variant-id root1'))))))

(t/deftest repair-invalid-variant-properties
  (t/testing "detect and repair a second variant component whose properties do not match the first variant component's properties"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Component1 has ["Property 1", "Property 2"], component2 gets ["Property 1", "Property 3"]
                      ;; This breaks validation: prop-names mismatch (missing "Property 2", extra "Property 3")
                      (thc/update-component :component1 {:variant-properties [{:name "Property 1" :value "Value1"}
                                                                              {:name "Property 2" :value "ValueA"}]})
                      (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value "Value2"}
                                                                              {:name "Property 3" :value "ValueB"}]})
                      (ths/update-shape :root1 :variant-name "Value1, ValueA")
                      (ths/update-shape :root2 :variant-name "Value2, ValueB"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          comp1'  (thc/get-component file' :component1)
          comp2'  (thc/get-component file' :component2)
          root1'  (ths/get-shape file' :root1)
          root2'  (ths/get-shape file' :root2)]

      (t/is (= 1 (count errors)))
      (t/is (= :invalid-variant-properties (:code (first errors))))

      (t/is (nil? errors'))

      ;; After repair, component1's properties are rebuilt to match component2's property names
      ;; (the first child in the variant container is root2, so prop-names come from component2)
      ;; "Property 1" keeps its value, "Property 3" is added with empty value, "Property 2" is removed
      (t/is (= [{:name "Property 1" :value "Value1"}
                {:name "Property 3" :value ""}]
               (:variant-properties comp1')))

      (t/is (= "Value1" (:variant-name root1')))

      ;; Component2 is unchanged (it was the reference for the property names)
      (t/is (= [{:name "Property 1" :value "Value2"}
                {:name "Property 3" :value "ValueB"}]
               (:variant-properties comp2')))

      (t/is (= "Value2, ValueB" (:variant-name root2'))))))

(t/deftest repair-variant-not-main
  (t/testing "detect and repair a non-main-instance shape inside a variant container"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                         ;; Add a third child to the variant container with :variant-id but NOT a main-instance
                         (ths/add-sample-shape :bad-shape
                                               :type :frame
                                               :parent-label :variant1
                                               :variant-id (thi/id :variant1)
                                               :variant-name "")
                         ;; Add a child to the bad shape (to verify the repair deletes it too)
                         (ths/add-sample-shape :bad-child
                                               :type :rect
                                               :parent-label :bad-shape))

          errors     (cfv/validate-file file {})
          changes    (cfr/repair-file file {} errors)
          file'      (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors'    (cfv/validate-file file' {})

          bad-shape' (ths/get-shape file' :bad-shape)
          bad-child' (ths/get-shape file' :bad-child)]

      (t/is (= 4 (count errors)))  ;; The bad container also triggers other errors
      (t/is (= :invalid-variant-properties (:code (nth errors 0))))
      (t/is (= :variant-not-main (:code (nth errors 1))))
      (t/is (= :variant-component-bad-name (:code (nth errors 2))))
      (t/is (= :variant-component-bad-id (:code (nth errors 3))))
      (t/is (nil? errors'))

      (t/is (nil? bad-shape'))
      (t/is (nil? bad-child')))))

(t/deftest repair-parent-not-variant
  (t/testing "detect and repair a variant shape whose parent is not a variant-container"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                         ;; Break the variant container
                         (ths/update-shape :variant1 :is-variant-container false))

          errors     (cfv/validate-file file {})
          changes    (cfr/repair-file file {} errors)
          file'      (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors'    (cfv/validate-file file' {})

          container' (ths/get-shape file' :variant1)]

      (t/is (= 2 (count errors)))  ;; The error is detected twice, once for each child of the variant container
      (t/is (= :parent-not-variant (:code (first errors))))
      (t/is (= :parent-not-variant (:code (second errors))))
      (t/is (nil? errors'))

      (t/is (true? (:is-variant-container container'))))))

(t/deftest repair-variant-main-bad-name
  (t/testing "detect and repair a main instance whose name doesn't match the variant container's name"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Change root1's name so it doesn't match the container
                      (ths/update-shape :root1 :name "WrongName"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          root1'  (ths/get-shape file' :root1)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-main-bad-name (:code (first errors))))
      (t/is (nil? errors'))
      (t/is (= "Board" (:name root1'))))))

(t/deftest repair-variant-main-bad-variant-name
  (t/testing "detect and repair a variant shape whose :variant-name doesn't match the component's properties"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (thc/update-component :component1 {:variant-properties [{:name "Property 1" :value "Value1"}
                                                                              {:name "Property 2" :value "ValueA"}]})
                      (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value "Value2"}
                                                                              {:name "Property 2" :value "ValueB"}]})
                      ;; Change root1's :variant-name to something wrong
                      (ths/update-shape :root1 :variant-name "WrongVariantName")
                      (ths/update-shape :root2 :variant-name "Value2, ValueB"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          root1'  (ths/get-shape file' :root1)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-main-bad-variant-name (:code (first errors))))
      (t/is (nil? errors'))
      (t/is (= "Value1, ValueA" (:variant-name root1'))))))

(t/deftest repair-variant-component-bad-name
  (t/testing "detect and repair a variant component whose path/name doesn't match the container name"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Update names to have path structure
                      (ths/update-shape :variant1 :name "Group / Subgroup / Component")
                      (ths/update-shape :root1 :name "Group / Subgroup / Component")
                      (ths/update-shape :root2 :name "Group / Subgroup / Component")
                      ;; Update component paths and names
                      (thc/update-component :component1 {:path "Group / Subgroup" :name "Component"})
                      (thc/update-component :component2 {:path "Group / Subgroup" :name "Component"})
                      ;; Break component1's name
                      (thc/update-component :component1 {:name "WrongName"}))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          comp1'  (thc/get-component file' :component1)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-component-bad-name (:code (first errors))))
      (t/is (nil? errors'))
      (t/is (= "Group / Subgroup" (:path comp1')))
      (t/is (= "Component" (:name comp1'))))))
