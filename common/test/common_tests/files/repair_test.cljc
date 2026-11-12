;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- repair-until-valid
  [file]
  (cfr/repair-file-until-valid
   file {}
   (fn [file _] (cfv/validate-file file {}))))

(defn- repair-until-valid-affected
  [file changes]
  (cfr/repair-file-until-valid
   file {}
   (fn [file applied]
     (cfv/validate-file-affected file {} (into applied changes)))))

(t/deftest repair-invalid-main-instance-name
  (t/testing "detect and repair a main instance whose name doesn't match the component's name"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (tho/add-simple-component-with-copy :component1 :root1 :child1 :copy1)
                      (tho/add-simple-component-with-copy :component2 :root2 :child2 :copy2)

                      ;; Update component paths and names
                      (thc/update-component :component1 {:path "Group / Subgroup" :name "Component1"})
                      (thc/update-component :component2 {:path "Group / Subgroup" :name "Component2"})

                      ;; Update component1 shapes name to have path structure
                      (ths/update-shape :root1 :name "Group / Subgroup / Component1")
                      (ths/update-shape :copy1 :name "Group / Subgroup / Component1")

                      ;; Update component2's name to have unnormalized path
                      ;; Note that the copy name is allowed to be different
                      (ths/update-shape :root2 :name "Group/Subgroup  /  Component2")
                      (ths/update-shape :copy2 :name "Group/Subgroup  /  Component2"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          root1'  (ths/get-shape file' :root1)
          child1' (ths/get-shape file' :child1)
          copy1'  (ths/get-shape file' :copy1)
          root2'  (ths/get-shape file' :root2)
          child2' (ths/get-shape file' :child2)
          copy2'  (ths/get-shape file' :copy2)]

      (t/is (= 1 (count errors)))
      (t/is (= :invalid-main-instance-name (:code (first errors))))
      (t/is (nil? errors'))

      (t/is (= "Group / Subgroup / Component1" (:name root1')))
      (t/is (= "Rect1" (:name child1')))
      (t/is (= "Group / Subgroup / Component1" (:name copy1')))

      (t/is (= "Group / Subgroup / Component2" (:name root2')))
      (t/is (= "Rect1" (:name child2')))
      (t/is (= "Group/Subgroup  /  Component2" (:name copy2'))))))

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
  (t/testing "detect and repair a variant main whose variant id does not match the container's id"
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
      (t/is (= (thi/id :variant1) (:variant-id root1')))))

  (t/testing "detect and repair a variant component whose variant id does not match the container's id"
    (let [file         (-> (thf/sample-file :file1 :page-label :page1)
                           (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                           (thc/update-component :component1 {:variant-id (uuid/next)}))

          errors       (cfv/validate-file file {})
          changes      (cfr/repair-file file {} errors)
          file'        (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors'      (cfv/validate-file file' {})

          component1'  (thc/get-component file' :component1)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-component-bad-id (:code (first errors))))

      (t/is (nil? errors'))
      (t/is (= (thi/id :variant1) (:variant-id component1'))))))

(t/deftest repair-invalid-variant-properties
  (t/testing "detect and repair a second variant component whose properties do not match the first variant component's properties"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Component1 has ["Property 1", "Property 2"], component2 gets ["Property 1", "Property 3"]
                      ;; The shared list keeps every name, in :shapes order [root2 root1].
                      (thc/update-component :component1 {:variant-properties [{:name "Property 1" :value "Value1"}
                                                                              {:name "Property 2" :value "ValueA"}]})
                      (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value "Value2"}
                                                                              {:name "Property 3" :value "ValueB"}]})
                      (ths/update-shape :root1 :variant-name "Value1, ValueA")
                      (ths/update-shape :root2 :variant-name "Value2, ValueB"))

          errors  (cfv/validate-file file {})
          result  (repair-until-valid file)
          file'   (:file result)
          errors' (cfv/validate-file file' {})

          comp1'  (thc/get-component file' :component1)
          comp2'  (thc/get-component file' :component2)
          root1'  (ths/get-shape file' :root1)
          root2'  (ths/get-shape file' :root2)]

      (t/is (= 1 (count errors)))
      (t/is (= :invalid-variant-properties (:code (first errors))))

      (t/is (nil? errors'))
      (t/is (nil? (:errors result)))

      ;; :shapes is [root2 root1], so the shared list is Property 1, Property 3, Property 2
      (t/is (= [{:name "Property 1" :value "Value2"}
                {:name "Property 3" :value "ValueB"}
                {:name "Property 2" :value ""}]
               (:variant-properties comp2')))

      (t/is (= [{:name "Property 1" :value "Value1"}
                {:name "Property 3" :value ""}
                {:name "Property 2" :value "ValueA"}]
               (:variant-properties comp1')))

      (t/is (= "Value2, ValueB" (:variant-name root2')))
      (t/is (= "Value1, ValueA" (:variant-name root1'))))))

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

      (t/is (= 3 (count errors)))  ;; The bad shape is not a qualifying member
      (t/is (= :variant-not-main (:code (nth errors 0))))
      (t/is (= :variant-component-bad-name (:code (nth errors 1))))
      (t/is (= :variant-component-bad-id (:code (nth errors 2))))
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

(t/deftest repair-variant-container-bad-name
  (t/testing "detect and repair a variant container who has an unnormalized name"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Variant container has a name that is not normalized and thus it cannot
                      ;; match the component names, that are separated in path and group.
                      (ths/update-shape :variant1 :name "Group  /  Subgroup/Component")
                      (ths/update-shape :root1 :name "Group  /  Subgroup/Component")
                      (ths/update-shape :root2 :name "Group  /  Subgroup/Component")
                      (thc/update-component :component1 {:path "Group / Subgroup" :name "Component"})
                      (thc/update-component :component2 {:path "Group/Subgroup" :name "Component"}))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          var1'   (ths/get-shape file' :variant1)
          root1'  (ths/get-shape file' :root1)
          comp1'  (thc/get-component file' :component1)
          root2'  (ths/get-shape file' :root2)
          comp2'  (thc/get-component file' :component2)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-container-bad-name (:code (first errors))))
      (t/is (nil? errors'))
      (t/is (= "Group / Subgroup / Component" (:name var1')))
      (t/is (= "Group / Subgroup / Component" (:name root1')))
      (t/is (= "Group / Subgroup" (:path comp1')))
      (t/is (= "Component" (:name comp1')))
      (t/is (= "Group / Subgroup / Component" (:name root2')))
      (t/is (= "Group / Subgroup" (:path comp2')))
      (t/is (= "Component" (:name comp2'))))))

(t/deftest repair-variant-main-bad-name
  (t/testing "detect and repair a main instance whose name doesn't match the variant container's name"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Change component's name so it doesn't match the container
                      (thc/update-component :component1 :name "WrongName")
                      (ths/update-shape :root1 :name "WrongName"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          root1'  (ths/get-shape file' :root1)
          comp1'  (thc/get-component file' :component1)]

      (t/is (= 2 (count errors)))
      (t/is (= :variant-main-bad-name (:code (first errors))))
      (t/is (= :variant-component-bad-name (:code (second errors))))
      (t/is (nil? errors'))
      (t/is (= "Board" (:name root1')))
      (t/is (= "Board" (:name comp1'))))))

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
                      (thc/update-component :component1 {:path "Group / Subgroup" :name "Component"})
                      ;; Break component2's name
                      (thc/update-component :component2 {:name "WrongName"}))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})
          root1'  (ths/get-shape file' :root1)
          comp1'  (thc/get-component file' :component1)
          root2'  (ths/get-shape file' :root2)
          comp2'  (thc/get-component file' :component2)]

      (t/is (= 1 (count errors)))
      (t/is (= :variant-component-bad-name (:code (first errors))))
      (t/is (nil? errors'))
      (t/is (= "Group / Subgroup / Component" (:name root1')))
      (t/is (= "Group / Subgroup" (:path comp1')))
      (t/is (= "Component" (:name comp1')))
      (t/is (= "Group / Subgroup / Component" (:name root2')))
      (t/is (= "Group / Subgroup" (:path comp2')))
      (t/is (= "Component" (:name comp2'))))))

(t/deftest repair-child-not-in-parent
  (t/testing "detect and repair shapes whose parent exists but does not list them"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (ths/add-sample-shape :frame1 :type :frame)
                      (ths/add-sample-shape :shape1 :parent-label :frame1)
                      (ths/add-sample-shape :shape2 :parent-label :frame1)
                      (ths/update-shape :frame1 :shapes []))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          frame1' (ths/get-shape file' :frame1 :page-label :page1)]

      (t/is (= #{:child-not-in-parent} (into #{} (map :code) errors)))
      (t/is (= #{(thi/id :shape1) (thi/id :shape2)} (into #{} (map :shape-id) errors)))

      (t/is (nil? errors'))
      (t/is (= #{(thi/id :shape1) (thi/id :shape2)} (set (:shapes frame1')))))))

(t/deftest repair-parent-not-found
  (t/testing "detect and repair shapes whose parent does not exist"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (ths/add-sample-shape :shape1)
                      (ths/add-sample-shape :shape2)
                      (ths/update-shape :shape1 :parent-id (uuid/next))
                      (ths/update-shape :shape2 :parent-id (uuid/next))
                      (ths/update-shape-by-id uuid/zero :shapes []))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          shape1' (ths/get-shape file' :shape1 :page-label :page1)
          shape2' (ths/get-shape file' :shape2 :page-label :page1)
          root'   (ths/get-shape-by-id file' uuid/zero :page-label :page1)]

      (t/is (= #{:parent-not-found} (into #{} (map :code) errors)))
      (t/is (= #{(thi/id :shape1) (thi/id :shape2)} (into #{} (map :shape-id) errors)))

      (t/is (nil? errors'))
      (t/is (= uuid/zero (:parent-id shape1')))
      (t/is (= uuid/zero (:parent-id shape2')))
      (t/is (= #{(thi/id :shape1) (thi/id :shape2)} (set (:shapes root')))))))

(defn- broken-frame-file
  []
  (-> (thf/sample-file :file1 :page-label :page1)
      (assoc :revn 3)
      (ths/add-sample-shape :shape1)
      (ths/update-shape :shape1 :frame-id (uuid/next))))

(t/deftest repair-file-until-valid-fixes-frame-not-found
  (t/testing "repairs a :frame-not-found shape and leaves :revn untouched"
    (let [file    (broken-frame-file)
          errors  (cfv/validate-file file {})
          result  (cfr/repair-file-until-valid
                   file {}
                   (fn [file _] (cfv/validate-file file {})))
          file'   (:file result)
          errors' (cfv/validate-file file' {})
          shape'  (ths/get-shape file' :shape1 :page-label :page1)]

      (t/is (= #{:frame-not-found} (into #{} (map :code) errors)))
      (t/is (pos? (count (:changes result))))
      (t/is (nil? (:errors result)))
      (t/is (nil? errors'))
      (t/is (= 3 (:revn file')))
      (t/is (some? (get-in file' [:data :pages-index (thi/id :page1) :objects (:frame-id shape')]))))))

(t/deftest repair-file-until-valid-healthy-file
  (t/testing "a valid file is returned unchanged"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (assoc :revn 2)
                     (ths/add-sample-shape :shape1))
          result (cfr/repair-file-until-valid
                  file {}
                  (fn [file _] (cfv/validate-file file {})))]
      (t/is (identical? file (:file result)))
      (t/is (= [] (:changes result)))
      (t/is (zero? (:iterations result)))
      (t/is (nil? (:errors result)))
      (t/is (= 2 (:revn (:file result)))))))

(t/deftest repair-file-until-valid-passes-applied-changes
  (t/testing "errors-fn receives the changes applied so far"
    (let [calls  (atom [])
          file   (broken-frame-file)
          result (cfr/repair-file-until-valid
                  file {}
                  (fn [file applied]
                    (swap! calls conj applied)
                    (cfv/validate-file file {})))]
      (t/is (nil? (:errors result)))
      (t/is (>= (count @calls) 2))
      (t/is (= [] (first @calls)))
      (t/is (pos? (count (last @calls)))))))

(t/deftest repair-file-until-valid-stops-on-noop-repair
  (t/testing "an empty repair batch stops after one iteration"
    (let [file   (broken-frame-file)
          errors (cfv/validate-file file {})]
      (with-redefs [cfr/repair-file (fn [_ _ _] [])]
        (let [result (cfr/repair-file-until-valid
                      file {}
                      (fn [file _] (cfv/validate-file file {})))]
          (t/is (= 1 (:iterations result)))
          (t/is (= [] (:changes result)))
          (t/is (= (mapv :code errors) (mapv :code (:errors result)))))))))

(t/deftest repair-file-until-valid-repairs-several-errors-on-one-shape
  (t/testing "three missing children of one frame are all removed although
              every pass reports the same [code shape-id]"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (ths/add-sample-shape :frame1 :type :frame)
                      (ths/update-shape :frame1 :shapes [(uuid/next) (uuid/next) (uuid/next)]))
          errors  (cfv/validate-file file {})
          result  (cfr/repair-file-until-valid
                   file {}
                   (fn [file _] (cfv/validate-file file {})))
          frame1' (ths/get-shape (:file result) :frame1 :page-label :page1)]
      (t/is (= [:child-not-found :child-not-found :child-not-found] (mapv :code errors)))
      (t/is (= #{(thi/id :frame1)} (into #{} (map :shape-id) errors)))
      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file (:file result) {})))
      (t/is (= [] (:shapes frame1'))))))

(t/deftest repair-file-until-valid-stops-when-repair-changes-nothing
  (t/testing "a non-empty repair that leaves data equal stops after one iteration"
    (let [file     (broken-frame-file)
          shape-id (thi/id :shape1)
          page-id  (thi/id :page1)
          cur-name (:name (ths/get-shape file :shape1 :page-label :page1))]
      (with-redefs [cfr/repair-file
                    (fn [_ _ _]
                      [{:type :mod-obj
                        :id shape-id
                        :page-id page-id
                        :operations [{:type :set :attr :name :val cur-name}]}])]
        (let [result (cfr/repair-file-until-valid
                      file {}
                      (fn [_ _] [{:code :frame-not-found :shape-id shape-id}])
                      :max-iterations 10)]
          (t/is (= 1 (:iterations result)))
          (t/is (= [] (:changes result)))
          (t/is (= [:frame-not-found] (mapv :code (:errors result)))))))))

(t/deftest repair-file-until-valid-stops-when-error-fingerprint-stable
  (t/testing "a mutating repair with an unchanged error report stops after two iterations"
    (let [file     (broken-frame-file)
          shape-id (thi/id :shape1)
          page-id  (thi/id :page1)
          pass     (atom 0)
          sticky   {:code :frame-not-found
                    :shape-id shape-id
                    :hint "stuck"
                    :args {:x 1}}]
      (with-redefs [cfr/repair-file
                    (fn [_ _ _]
                      [{:type :mod-obj
                        :id shape-id
                        :page-id page-id
                        :operations [{:type :set
                                      :attr :name
                                      :val (str "pass-" (swap! pass inc))}]}])]
        (let [result (cfr/repair-file-until-valid
                      file {}
                      (fn [_ _] [sticky])
                      :max-iterations 10)]
          (t/is (= 2 (:iterations result)))
          (t/is (= 1 (count (:changes result))))
          (t/is (= [:frame-not-found] (mapv :code (:errors result)))))))))

(t/deftest repair-file-until-valid-stops-at-max-iterations
  (t/testing "a repair that never empties errors stops at max-iterations"
    (let [file   (broken-frame-file)
          pass   (atom 0)
          shape-id (thi/id :shape1)
          page-id  (thi/id :page1)]
      (with-redefs [cfr/repair-file
                    (fn [_ _ _]
                      [{:type :mod-obj
                        :id shape-id
                        :page-id page-id
                        :operations [{:type :set
                                      :attr :name
                                      :val (str "pass-" (swap! pass inc))}]}])]
        (let [result (cfr/repair-file-until-valid
                      file {}
                      (fn [_ _]
                        [{:code :frame-not-found
                          :shape-id (uuid/next)}])
                      :max-iterations 3)]
          (t/is (= 3 (:iterations result)))
          (t/is (= 3 (count (:changes result))))
          (t/is (some? (:errors result))))))))

(t/deftest repair-file-until-valid-uses-initial-errors
  (t/testing "errors-fn is not called on the first iteration when :initial-errors is given"
    (let [calls  (atom 0)
          file   (broken-frame-file)
          seed   (cfv/validate-file file {})
          result (cfr/repair-file-until-valid
                  file {}
                  (fn [file applied]
                    (swap! calls inc)
                    (cfv/validate-file file {}))
                  :initial-errors seed)]
      (t/is (nil? (:errors result)))
      ;; First iteration used the seed; errors-fn runs only after the repair.
      (t/is (= 1 @calls)))))


(defn- lost-main-file
  "A component whose main is on page1 and its copy on page2, with the
  component's `:main-instance-id` pointing to a missing shape."
  []
  (-> (thf/sample-file :file1 :page-label :page1)
      (tho/add-simple-component :component1 :root1 :child1)
      (thf/add-sample-page :page2)
      (thc/instantiate-component :component1 :copy1)
      (thc/update-component :component1 :main-instance-id (uuid/next))))

(defn- edit-copy-changes
  []
  [{:type :mod-obj
    :page-id (thi/id :page2)
    :id (thi/id :copy1)
    :operations [{:type :set :attr :name :val "renamed"}]}])

(defn- repair-scoped
  [file changes]
  (cfr/repair-file-until-valid
   file {}
   (fn [file applied]
     (cfv/validate-file-affected file {} (into applied changes)))))

(t/deftest repair-ref-shape-not-found-fixes-lost-main-instance-id
  (t/testing "a repair scoped to the copy's page points the component back
              to its main and keeps the copy linked"
    (let [file       (lost-main-file)
          changes    (edit-copy-changes)
          errors     (cfv/validate-file-affected file {} changes)
          result     (repair-scoped file changes)
          file'      (:file result)
          copy1'     (ths/get-shape file' :copy1 :page-label :page2)
          component' (thc/get-component file' :component1)]

      (t/is (= #{:ref-shape-not-found} (into #{} (map :code) errors)))
      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (= (thi/id :root1) (:main-instance-id component')))
      (t/is (= (thi/id :component1) (:component-id copy1')))
      (t/is (= (thi/id :file1) (:component-file copy1')))
      (t/is (= (thi/id :root1) (:shape-ref copy1')))
      (t/is (true? (:component-root copy1'))))))

(t/deftest repair-whole-file-keeps-copy-of-lost-main
  (t/testing "a whole-file repair fixes the component and keeps the copy linked"
    (let [file    (lost-main-file)
          result  (cfr/repair-file-until-valid
                   file {}
                   (fn [file _] (cfv/validate-file file {})))
          file'   (:file result)
          copy1'  (ths/get-shape file' :copy1 :page-label :page2)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (= (thi/id :root1) (:main-instance-id (thc/get-component file' :component1))))
      (t/is (= (thi/id :root1) (:shape-ref copy1')))
      (t/is (= (thi/id :component1) (:component-id copy1'))))))

(t/deftest repair-ref-shape-not-found-detaches-copy-of-missing-main
  (t/testing "a copy is detached when no shape on the component's page is its main"
    (let [file    (-> (lost-main-file)
                      (ths/update-shape :root1 :main-instance nil :page-label :page1))
          changes (edit-copy-changes)
          result  (repair-scoped file changes)
          copy1'  (ths/get-shape (:file result) :copy1 :page-label :page2)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (:component-id copy1')))
      (t/is (nil? (:shape-ref copy1'))))))

(defn- add-deleted-objects-only-board
  [file props2 props1 & {:keys [label2 label1]
                         :or {label2 "stale" label1 "stale"}}]
  (let [owner-id (uuid/next)
        board-id (uuid/next)
        root2-id (uuid/next)
        root1-id (uuid/next)
        comp2-id (uuid/next)
        comp1-id (uuid/next)
        page-id  (:id (thf/current-page file))
        member   (fn [id root-id props label]
                   {:id                 id
                    :name               "Member"
                    :path               ""
                    :deleted            true
                    :variant-id         board-id
                    :main-instance-id   root-id
                    :main-instance-page page-id
                    :variant-properties props
                    :objects            {root-id {:id          root-id
                                                  :type        :frame
                                                  :variant-id  board-id
                                                  :variant-name label}}})]
    {:file (-> file
               (update :data
                       (fn [data]
                         (-> data
                             (assoc-in [:components owner-id]
                                       {:id                 owner-id
                                        :name               "Owner"
                                        :path               ""
                                        :deleted            true
                                        :main-instance-id   board-id
                                        :main-instance-page page-id
                                        :objects            {board-id {:id                   board-id
                                                                       :type                 :frame
                                                                       :name                 "Board"
                                                                       :is-variant-container true
                                                                       :shapes               [root2-id root1-id]}
                                                             root2-id {:id           root2-id
                                                                       :type         :frame
                                                                       :parent-id    board-id
                                                                       :variant-id   board-id
                                                                       :component-id comp2-id
                                                                       :main-instance true
                                                                       :variant-name label2}
                                                             root1-id {:id           root1-id
                                                                       :type         :frame
                                                                       :parent-id    board-id
                                                                       :variant-id   board-id
                                                                       :component-id comp1-id
                                                                       :main-instance true
                                                                       :variant-name label1}}})
                             (assoc-in [:components comp2-id] (member comp2-id root2-id props2 label2))
                             (assoc-in [:components comp1-id] (member comp1-id root1-id props1 label1))))))
     :owner-id owner-id
     :board-id board-id
     :comp2-id comp2-id
     :comp1-id comp1-id
     :root2-id root2-id
     :root1-id root1-id}))

(t/deftest repair-invalid-variant-properties-dirty-matching-text
  (t/testing "a board whose children both store dirty matching text is reported and repaired"
    (let [props   [{:name "Size=Big" :value "a=b," :hidden true}]
          file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (thc/update-component :component2 {:variant-properties props})
                      (thc/update-component :component1 {:variant-properties [{:name "Size=Big" :value "other" :hidden true}]}))
          errors  (cfv/validate-file file {})
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')
          comp2'  (thc/get-component file' :component2)
          root2'  (ths/get-shape file' :root2)]

      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= [{:name "SizeBig" :value "ab" :hidden true}]
               (:variant-properties comp2')))
      (t/is (true? (:hidden (first (:variant-properties comp2')))))
      (t/is (= "ab" (:variant-name root2')))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(t/deftest repair-invalid-variant-properties-one-pass-keeps-sanitized-label
  (t/testing "one repair-file call keeps a label that already matches the sanitized value"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value "a=b,"}]})
                      (ths/update-shape :root2 :variant-name "ab"))
          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          comp2'  (thc/get-component file' :component2)
          root2'  (ths/get-shape file' :root2)]

      (t/is (= "ab" (-> comp2' :variant-properties first :value)))
      (t/is (= "ab" (:variant-name root2'))))))

(t/deftest repair-invalid-variant-properties-empty-duplicate-value
  (t/testing "two empty values become \"\" then \"(1)\""
    (let [props   [{:name "Property 1" :value "=,="}]
          file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (thc/update-component :component2 {:variant-properties props})
                      (thc/update-component :component1 {:variant-properties props}))
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')
          comp2'  (thc/get-component file' :component2)
          comp1'  (thc/get-component file' :component1)
          root1'  (ths/get-shape file' :root1)]

      (t/is (= "" (-> comp2' :variant-properties first :value)))
      (t/is (= "(1)" (-> comp1' :variant-properties first :value)))
      (t/is (= "(1)" (:variant-name root1')))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(t/deftest repair-invalid-variant-properties-long-duplicate-name
  (t/testing "two 60-character copies of one name stay distinct and keep the value"
    (let [long-name (apply str (repeat 60 "n"))
          file      (-> (thf/sample-file :file1 :page-label :page1)
                        (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                        (thc/update-component :component2 {:variant-properties [{:name long-name :value "other"}
                                                                                {:name long-name :value "kept"}]})
                        (thc/update-component :component1 {:variant-properties [{:name long-name :value "x"}
                                                                                {:name long-name :value "y"}]}))
          result    (repair-until-valid file)
          file'     (:file result)
          props'    (:variant-properties (thc/get-component file' :component2))
          first-n   (:name (first props'))
          second    (second props')]

      (t/is (not= first-n (:name second)))
      (t/is (<= (count (:name second)) 60))
      (t/is (<= (count first-n) 60))
      (t/is (= "kept" (:value second)))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-non-string-value
  (t/testing "a non-string value becomes an empty string"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value 5}]}))
          result (repair-until-valid file)
          file'  (:file result)
          comp2' (thc/get-component file' :component2)
          root2' (ths/get-shape file' :root2)]

      (t/is (= "" (-> comp2' :variant-properties first :value)))
      (t/is (= "" (:variant-name root2')))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-same-value-tuple
  (t/testing "two members with the same clean value tuple yield the error and are numbered"
    (let [props  [{:name "Property 1" :value "red"}]
          file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (thc/update-component :component2 {:variant-properties props})
                     (thc/update-component :component1 {:variant-properties props})
                     (ths/update-shape :root2 :variant-name "red")
                     (ths/update-shape :root1 :variant-name "red"))
          errors (cfv/validate-file file {})
          result (repair-until-valid file)
          file'  (:file result)
          comp2' (thc/get-component file' :component2)
          comp1' (thc/get-component file' :component1)]

      (t/is (= :invalid-variant-properties (:code (first errors))))
      (t/is (= "red" (-> comp2' :variant-properties first :value)))
      (t/is (= "red (1)" (-> comp1' :variant-properties first :value)))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-clean-board
  (t/testing "a clean board yields no property error"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2))
          errors (cfv/validate-file file {})]

      (t/is (nil? (seq (filter #(= :invalid-variant-properties (:code %)) errors)))))))

(t/deftest repair-invalid-variant-properties-no-properties-key
  (t/testing "a board whose members have no :variant-properties key is left alone"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (ths/update-shape :root1 :variant-name "")
                      (ths/update-shape :root2 :variant-name "")
                      (update :data
                              (fn [data]
                                (-> data
                                    (update-in [:components (thi/id :component1)] dissoc :variant-properties)
                                    (update-in [:components (thi/id :component2)] dissoc :variant-properties)))))
          errors  (cfv/validate-file file {})
          result  (repair-until-valid file)
          file'   (:file result)]

      (t/is (nil? (seq (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= (:data file) (:data file')))
      (t/is (= "" (:variant-name (ths/get-shape file' :root1))))
      (t/is (= "" (:variant-name (ths/get-shape file' :root2)))))))

(t/deftest repair-invalid-variant-properties-ignores-detached-component
  (t/testing "a detached component with the same :variant-id does not add its names to the live set"
    (let [deleted-id (uuid/next)
          main-id    (uuid/next)
          file       (-> (thf/sample-file :file1 :page-label :page1)
                         (thv/add-variant :variant1 :component1 :root1 :component2 :root2))
          variant-id (thi/id :variant1)
          page-id    (:id (thf/current-page file))
          file       (update file :data
                             (fn [data]
                               (assoc-in data [:components deleted-id]
                                         {:id                 deleted-id
                                          :name               "Gone"
                                          :path               ""
                                          :deleted            true
                                          :variant-id         variant-id
                                          :main-instance-id   main-id
                                          :main-instance-page page-id
                                          :variant-properties [{:name "Legacy=Name" :value "a=b"}]
                                          :objects            {main-id {:id           main-id
                                                                        :type         :frame
                                                                        :name         "Gone"
                                                                        :variant-id   variant-id
                                                                        :variant-name "stale"}}})))
          result     (repair-until-valid file)
          file'      (:file result)
          live-names (mapv :name (:variant-properties (thc/get-component file' :component2)))
          deleted    (get-in file' [:data :components deleted-id])]

      (t/is (= ["Property 1"] live-names))
      (t/is (= "LegacyName" (-> deleted :variant-properties first :name)))
      (t/is (= "ab" (-> deleted :variant-properties first :value)))
      (t/is (= "ab" (get-in deleted [:objects main-id :variant-name])))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-objects-only-board
  (t/testing "a variant board that exists only in a component's objects is numbered as one set"
    (let [{:keys [file owner-id comp2-id comp1-id root2-id root1-id]}
          (add-deleted-objects-only-board
           (thf/sample-file :file1 :page-label :page1)
           [{:name "Size" :value "red"}]
           [{:name "Size" :value "red"}])
          errors  (cfv/validate-file file {})
          affected (cfv/validate-file-affected file {} [{:type :mod-component :id owner-id}])
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')
          comp2'  (get-in file' [:data :components comp2-id])
          comp1'  (get-in file' [:data :components comp1-id])]

      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) affected))))
      (t/is (= "red" (-> comp2' :variant-properties first :value)))
      (t/is (= "red (1)" (-> comp1' :variant-properties first :value)))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "red (1)" (get-in comp1' [:objects root1-id :variant-name])))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(t/deftest repair-invalid-variant-properties-objects-only-stale-label
  (t/testing "an objects-only board with clean properties and stale deleted-main labels is reported and repaired"
    (let [{:keys [file comp2-id comp1-id root2-id root1-id]}
          (add-deleted-objects-only-board
           (thf/sample-file :file1 :page-label :page1)
           [{:name "Property 1" :value "red"}]
           [{:name "Property 1" :value "blue"}])
          errors  (cfv/validate-file file {})
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')
          comp2'  (get-in file' [:data :components comp2-id])
          comp1'  (get-in file' [:data :components comp1-id])]

      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "blue" (get-in comp1' [:objects root1-id :variant-name])))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(t/deftest repair-invalid-variant-properties-live-component-objects
  (t/testing "a live component whose objects hold a variant board loses :objects and members are repaired"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (tho/add-simple-component :owner :owner-root :owner-child))
          owner-id (thi/id :owner)
          board-id (uuid/next)
          root2-id (uuid/next)
          root1-id (uuid/next)
          comp2-id (uuid/next)
          comp1-id (uuid/next)
          page-id  (:id (thf/current-page file))
          member   (fn [id root-id]
                     {:id                 id
                      :name               "Member"
                      :path               ""
                      :deleted            true
                      :variant-id         board-id
                      :main-instance-id   root-id
                      :main-instance-page page-id
                      :variant-properties [{:name "Size" :value "red"}]
                      :objects            {root-id {:id           root-id
                                                    :type         :frame
                                                    :variant-id   board-id
                                                    :variant-name "stale"}}})
          file     (update file :data
                           (fn [data]
                             (-> data
                                 (assoc-in [:components owner-id :objects]
                                           {board-id {:id                   board-id
                                                      :type                 :frame
                                                      :name                 "Board"
                                                      :is-variant-container true
                                                      :shapes               [root2-id root1-id]}
                                            root2-id {:id            root2-id
                                                      :type          :frame
                                                      :parent-id     board-id
                                                      :variant-id    board-id
                                                      :component-id  comp2-id
                                                      :main-instance true
                                                      :variant-name  "stale"}
                                            root1-id {:id            root1-id
                                                      :type          :frame
                                                      :parent-id     board-id
                                                      :variant-id    board-id
                                                      :component-id  comp1-id
                                                      :main-instance true
                                                      :variant-name  "stale"}})
                                 (assoc-in [:components comp2-id] (member comp2-id root2-id))
                                 (assoc-in [:components comp1-id] (member comp1-id root1-id)))))
          result   (repair-until-valid file)
          file'    (:file result)
          owner'   (thc/get-component file' :owner)
          comp2'   (get-in file' [:data :components comp2-id])
          comp1'   (get-in file' [:data :components comp1-id])]

      (t/is (nil? (:objects owner')))
      (t/is (= "red" (-> comp2' :variant-properties first :value)))
      (t/is (= "red (1)" (-> comp1' :variant-properties first :value)))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "red (1)" (get-in comp1' [:objects root1-id :variant-name])))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-solo-deleted
  (t/testing "a deleted variant that is not on a board is prepared alone"
    (let [deleted-id (uuid/next)
          main-id    (uuid/next)
          file       (-> (thf/sample-file :file1 :page-label :page1)
                         (thv/add-variant :variant1 :component1 :root1 :component2 :root2))
          live-id    (thi/id :variant1)
          other-id   (uuid/next)
          page-id    (:id (thf/current-page file))
          file       (update file :data
                             (fn [data]
                               (assoc-in data [:components deleted-id]
                                         {:id                 deleted-id
                                          :name               "Legacy"
                                          :path               ""
                                          :deleted            true
                                          :variant-id         other-id
                                          :main-instance-id   main-id
                                          :main-instance-page page-id
                                          :variant-properties [{:name "Legacy=Name" :value "a=b"}]
                                          :objects            {main-id {:id           main-id
                                                                        :type         :frame
                                                                        :variant-id   other-id
                                                                        :variant-name "stale"}}})))
          result     (repair-until-valid file)
          file'      (:file result)
          deleted    (get-in file' [:data :components deleted-id])
          live-names (mapv :name (:variant-properties (thc/get-component file' :component2)))]

      (t/is (= ["Property 1"] live-names))
      (t/is (= "LegacyName" (-> deleted :variant-properties first :name)))
      (t/is (= "ab" (-> deleted :variant-properties first :value)))
      (t/is (= "ab" (get-in deleted [:objects main-id :variant-name])))
      (t/is (not= live-id other-id))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-qualifying-member-not-solo
  (t/testing "a qualifying member is not repaired as a solo when the save does not see the board page"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (thc/update-component :component1 {:variant-properties [{:name "Size=Big" :value "a=b"}]}))
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          comp1'  (thc/get-component file' :component1)]

      (t/is (= "Size=Big" (-> comp1' :variant-properties first :name)))
      (t/is (= (:data file) (:data file'))))))

(t/deftest repair-invalid-variant-properties-solo-missing-key
  (t/testing "a solo variant whose :variant-properties key is already missing yields no property error"
    (let [variant-id (uuid/next)
          file       (-> (thf/sample-file :file1 :page-label :page1)
                         (ths/add-sample-shape :frame1 :type :frame :name "Ordinary")
                         (ths/add-sample-shape :root1 :type :frame :parent-label :frame1
                                               :name "SoloMain"
                                               :variant-id variant-id
                                               :variant-name "")
                         (thc/make-component :component1 :root1)
                         (thc/update-component :component1 {:variant-id variant-id})
                         (update :data
                                 (fn [data]
                                   (update-in data [:components (thi/id :component1)]
                                              dissoc :variant-properties))))
          errors     (cfv/validate-file file {})]

      (t/is (nil? (seq (filter #(= :invalid-variant-properties (:code %)) errors)))))))

(t/deftest repair-invalid-variant-properties-live-solo
  (t/testing "a live solo on an ordinary frame is repaired without adopting the frame"
    (let [variant-id (uuid/next)
          file       (-> (thf/sample-file :file1 :page-label :page1)
                         (ths/add-sample-shape :frame1 :type :frame :name "Ordinary")
                         (ths/add-sample-shape :rect1 :type :rect :parent-label :frame1)
                         (ths/add-sample-shape :root1 :type :frame :parent-label :frame1
                                               :name "SoloMain"
                                               :variant-id variant-id
                                               :variant-name "stale")
                         (thc/make-component :component1 :root1)
                         (thc/update-component :component1 {:variant-id variant-id
                                                            :path "Lib"
                                                            :name "Widget"
                                                            :variant-properties [{:name "Size=Big" :value "a=b"}]}))
          changes    [{:type :mod-component :id (thi/id :component1)}]
          result     (repair-until-valid-affected file changes)
          file'      (:file result)
          result2    (repair-until-valid-affected file' changes)
          frame'     (ths/get-shape file' :frame1)
          rect'      (ths/get-shape file' :rect1)
          root'      (ths/get-shape file' :root1)
          comp'      (thc/get-component file' :component1)]

      (t/is (= "ab" (:variant-name root')))
      (t/is (= [{:name "SizeBig" :value "ab"}] (:variant-properties comp')))
      (t/is (not (true? (:is-variant-container frame'))))
      (t/is (some? rect'))
      (t/is (= "Lib" (:path comp')))
      (t/is (= "Widget" (:name comp')))
      (t/is (= variant-id (:variant-id comp')))
      (t/is (= "SoloMain" (:name root')))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a live solo whose property vector is already equal still writes the label"
    (let [variant-id (uuid/next)
          file       (-> (thf/sample-file :file1 :page-label :page1)
                         (ths/add-sample-shape :frame1 :type :frame :name "Ordinary")
                         (ths/add-sample-shape :rect1 :type :rect :parent-label :frame1)
                         (ths/add-sample-shape :root1 :type :frame :parent-label :frame1
                                               :name "SoloMain"
                                               :variant-id variant-id
                                               :variant-name "stale")
                         (thc/make-component :component1 :root1)
                         (thc/update-component :component1 {:variant-id variant-id
                                                            :path "Lib"
                                                            :name "Widget"
                                                            :variant-properties [{:name "SizeBig" :value "ab"}]}))
          changes    [{:type :mod-component :id (thi/id :component1)}]
          result     (repair-until-valid-affected file changes)
          file'      (:file result)
          root'      (ths/get-shape file' :root1)
          frame'     (ths/get-shape file' :frame1)
          rect'      (ths/get-shape file' :rect1)
          comp'      (thc/get-component file' :component1)]

      (t/is (= "ab" (:variant-name root')))
      (t/is (= [{:name "SizeBig" :value "ab"}] (:variant-properties comp')))
      (t/is (not (true? (:is-variant-container frame'))))
      (t/is (some? rect'))
      (t/is (= "SoloMain" (:name root')))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-live-solo-mismatched-ids
  (t/testing "a live solo whose component id differs from the shape id does not adopt the frame"
    (let [shape-variant-id (uuid/next)
          comp-variant-id  (uuid/next)
          file             (-> (thf/sample-file :file1 :page-label :page1)
                               (ths/add-sample-shape :frame1 :type :frame :name "Ordinary")
                               (ths/add-sample-shape :rect1 :type :rect :parent-label :frame1)
                               (ths/add-sample-shape :root1 :type :frame :parent-label :frame1
                                                     :name "SoloMain"
                                                     :variant-id shape-variant-id
                                                     :variant-name "stale")
                               (thc/make-component :component1 :root1)
                               (thc/update-component :component1 {:variant-id comp-variant-id
                                                                  :path "Lib"
                                                                  :name "Widget"
                                                                  :variant-properties [{:name "Size=Big" :value "a=b"}]}))
          changes          [{:type :mod-component :id (thi/id :component1)}]
          result           (repair-until-valid-affected file changes)
          file'            (:file result)
          result2          (repair-until-valid-affected file' changes)
          frame'           (ths/get-shape file' :frame1)
          rect'            (ths/get-shape file' :rect1)
          root'            (ths/get-shape file' :root1)
          comp'            (thc/get-component file' :component1)]

      (t/is (not (true? (:is-variant-container frame'))))
      (t/is (some? rect'))
      (t/is (not= (:id frame') (:variant-id root')))
      (t/is (not= (:id frame') (:variant-id comp')))
      (t/is (= shape-variant-id (:variant-id root')))
      (t/is (= comp-variant-id (:variant-id comp')))
      (t/is (= "SoloMain" (:name root')))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(defn- property-errors
  [result]
  (filter #(= :invalid-variant-properties (:code %)) (:errors result)))

(defn- deleted-missing-main-file
  [objects props]
  (let [deleted-id (uuid/next)
        main-id    (uuid/next)
        other-id   (uuid/next)
        file       (thf/sample-file :file1 :page-label :page1)
        page-id    (thf/current-page-id file)
        component  (cond-> {:id                 deleted-id
                            :name               "Legacy"
                            :path               ""
                            :deleted            true
                            :variant-id         other-id
                            :main-instance-id   main-id
                            :main-instance-page page-id
                            :objects            objects}
                     (some? props)
                     (assoc :variant-properties props))]
    {:file       (update file :data #(assoc-in % [:components deleted-id] component))
     :deleted-id deleted-id
     :main-id    main-id}))

(defn- assert-deleted-text-rewritten
  [file deleted-id main-id]
  (let [deleted (get-in file [:data :components deleted-id])]
    (t/is (= "SizeBig" (-> deleted :variant-properties first :name)))
    (t/is (= "ab" (-> deleted :variant-properties first :value)))
    (t/is (= {} (:objects deleted)))
    (t/is (nil? (get (:objects deleted) main-id)))))

(t/deftest repair-invalid-variant-properties-deleted-missing-main
  (t/testing "a deleted solo with an empty objects map and a missing main rewrites text without a shape"
    (let [{:keys [file deleted-id main-id]}
          (deleted-missing-main-file {} [{:name "Size=Big" :value "a=b"}])
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')]
      (assert-deleted-text-rewritten file' deleted-id main-id)
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "the same empty objects map repaired from one component change"
    (let [{:keys [file deleted-id main-id]}
          (deleted-missing-main-file {} [{:name "Size=Big" :value "a=b"}])
          changes [{:type :mod-component :id deleted-id}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          result2 (repair-until-valid-affected file' changes)]
      (assert-deleted-text-rewritten file' deleted-id main-id)
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a deleted solo whose objects are nil ends with an empty map"
    (let [{:keys [file deleted-id main-id]}
          (deleted-missing-main-file nil [{:name "Size=Big" :value "a=b"}])
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')]
      (assert-deleted-text-rewritten file' deleted-id main-id)
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "the same nil objects repaired from one component change"
    (let [{:keys [file deleted-id main-id]}
          (deleted-missing-main-file nil [{:name "Size=Big" :value "a=b"}])
          changes [{:type :mod-component :id deleted-id}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          result2 (repair-until-valid-affected file' changes)]
      (assert-deleted-text-rewritten file' deleted-id main-id)
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a deleted solo with no properties and no main is left alone"
    (let [{:keys [file]} (deleted-missing-main-file {} nil)
          errors (cfv/validate-file file {})
          result (repair-until-valid file)]
      (t/is (nil? (seq (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= (:data file) (:data (:file result))))))

  (t/testing "the clean deleted solo repaired from one component change is left alone"
    (let [{:keys [file deleted-id]} (deleted-missing-main-file {} nil)
          changes [{:type :mod-component :id deleted-id}]
          result  (repair-until-valid-affected file changes)]
      (t/is (nil? (seq (property-errors result))))
      (t/is (= (:data file) (:data (:file result)))))))

(defn- live-solo-missing-main
  [props]
  (let [variant-id (uuid/next)
        file       (-> (thf/sample-file :file1 :page-label :page1)
                       (ths/add-sample-shape :frame1 :type :frame :name "Ordinary")
                       (ths/add-sample-shape :rect1 :type :rect :parent-label :frame1)
                       (ths/add-sample-shape :root1 :type :frame :parent-label :frame1
                                             :name "SoloMain"
                                             :variant-id variant-id
                                             :variant-name "stale")
                       (thc/make-component :component1 :root1)
                       (thc/update-component :component1 {:variant-id variant-id
                                                          :path "Lib"
                                                          :name "Widget"
                                                          :variant-properties props}))
        page-id    (thf/current-page-id file)
        root-id    (thi/id :root1)]
    (update-in file [:data :pages-index page-id :objects] dissoc root-id)))

(t/deftest repair-invalid-variant-properties-live-missing-main
  (t/testing "a live solo with clean text and a missing main does not fail the component save"
    (let [file    (live-solo-missing-main [{:name "SizeBig" :value "ab"}])
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)]
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (nil? (ths/get-shape (:file result) :root1)))
      (t/is (= (:data file) (:data (:file result))))))

  (t/testing "a full repair of that live solo does not create the missing main"
    (let [file   (live-solo-missing-main [{:name "SizeBig" :value "ab"}])
          result (repair-until-valid file)
          file'  (:file result)]
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (ths/get-shape file' :root1)))))

  (t/testing "a live solo with dirty text and a missing main rewrites the text and creates no shape"
    (let [file    (live-solo-missing-main [{:name "Size=Big" :value "a=b"}])
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          result2 (repair-until-valid-affected file' changes)
          comp'   (thc/get-component file' :component1)]
      (t/is (= [{:name "SizeBig" :value "ab"}] (:variant-properties comp')))
      (t/is (nil? (ths/get-shape file' :root1)))
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a full repair of that dirty live solo creates no shape"
    (let [file   (live-solo-missing-main [{:name "Size=Big" :value "a=b"}])
          result (repair-until-valid file)
          file'  (:file result)
          comp'  (thc/get-component file' :component1)]
      (t/is (= [{:name "SizeBig" :value "ab"}] (:variant-properties comp')))
      (t/is (nil? (ths/get-shape file' :root1)))
      (t/is (nil? (seq (property-errors result)))))))

