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
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.migrations :as cfm]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logic.variant-properties :as clvp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
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
      (t/is (= (thi/id :variant1) (:variant-id root1')))))

  (t/testing "detect and repair a deleted variant component whose main in its objects is not a variant"
    (let [deleted-id (uuid/next)
          main-id    (uuid/next)
          variant-id (uuid/next)
          file       (thf/sample-file :file1 :page-label :page1)
          page-id    (thf/current-page-id file)
          file       (update file :data
                             (fn [data]
                               (assoc-in data [:components deleted-id]
                                         {:id                 deleted-id
                                          :name               "Legacy"
                                          :path               ""
                                          :deleted            true
                                          :variant-id         variant-id
                                          :main-instance-id   main-id
                                          :main-instance-page page-id
                                          :variant-properties [{:name "Size" :value "big"}]
                                          :objects            {main-id {:id           main-id
                                                                        :type         :frame
                                                                        :variant-name "big"}}})))
          errors     (cfv/validate-file file {})
          result     (repair-until-valid file)
          file'      (:file result)]

      (t/is (= [:main-instance-not-a-variant] (mapv :code errors)))
      (t/is (= variant-id (get-in file' [:data :components deleted-id :objects main-id :variant-id])))
      (t/is (nil? (ths/get-shape-by-id file' main-id)))
      (t/is (nil? (:errors result))))))

(defn- variant-board-with-stray-group
  [file]
  (-> file
      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
      (tho/add-group :group1 :parent-label :variant1)
      (ths/add-sample-shape :child1 :parent-label :group1)))

(t/deftest validate-variant-container-child-not-main
  (t/testing "a plain shape inside a variant board reports its own code, not a main-instance one"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (variant-board-with-stray-group))
          errors (cfv/validate-file file {})
          error  (first errors)]
      (t/is (= 1 (count errors)))
      (t/is (= :variant-container-child-not-main (:code error)))
      (t/is (= (thi/id :group1) (:id (:shape error))))
      (t/is (= (thi/id :variant1) (:variant-id (:args error))))
      (t/is (= (str "Shape " (thi/id :group1)
                    " inside variant container " (thi/id :variant1)
                    " is not a variant main")
               (:hint error)))))

  (t/testing "a variant main that lost :main-instance and :variant-id is still reported as a main"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (ths/update-shape :root1 :variant-id nil)
                     (ths/update-shape :root1 :main-instance nil))
          codes  (->> (cfv/validate-file file {})
                      (filter #(= (thi/id :root1) (:id (:shape %))))
                      (map :code)
                      (set))]
      (t/is (contains? codes :main-instance-not-a-variant))
      (t/is (not (contains? codes :variant-container-child-not-main))))))

(t/deftest repair-variant-container-child-not-main
  (t/testing "a plain group inside a variant board moves out with its children"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (variant-board-with-stray-group))
          result  (repair-until-valid file)
          file'   (:file result)
          board'  (ths/get-shape file' :variant1)
          parent' (ths/get-shape-by-id file' (:parent-id board'))
          group'  (ths/get-shape file' :group1)
          child'  (ths/get-shape file' :child1)]
      (t/is (nil? (:errors result)))
      (t/is (some? group'))
      (t/is (some? child'))
      (t/is (= (:parent-id board') (:parent-id group')))
      (t/is (= (inc (d/index-of (:shapes parent') (:id board')))
               (d/index-of (:shapes parent') (:id group'))))
      (t/is (= (:frame-id board') (:frame-id group')))
      (t/is (= (:frame-id board') (:frame-id child')))
      (t/is (nil? (:variant-id group')))
      (t/is (= #{(thi/id :root1) (thi/id :root2)} (set (:shapes board'))))))

  (t/testing "a component copy inside a variant board moves out and nothing is deleted"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (tho/add-simple-component :component3 :main3 :main3-child)
                      (thc/instantiate-component :component3 :copy1 :parent-label :variant1))
          result  (repair-until-valid file)
          file'   (:file result)
          board'  (ths/get-shape file' :variant1)
          copy'   (ths/get-shape file' :copy1)]
      (t/is (nil? (:errors result)))
      (t/is (some? copy'))
      (t/is (= (:parent-id board') (:parent-id copy')))
      (t/is (= #{(thi/id :root1) (thi/id :root2)} (set (:shapes board'))))
      (t/is (some? (ths/get-shape file' :main3)))
      (t/is (some? (ths/get-shape file' :main3-child)))))

  (t/testing "a stray child repair appends when the variant board is missing from its parent's children"
    (let [file          (-> (thf/sample-file :file1 :page-label :page1)
                            (variant-board-with-stray-group))
          page-id       (thf/current-page-id file)
          board-id      (thi/id :variant1)
          parent-id     (get-in file [:data :pages-index page-id :objects board-id :parent-id])
          parent-shapes (get-in file [:data :pages-index page-id :objects parent-id :shapes])
          error         (first (filter #(= :variant-container-child-not-main (:code %))
                                       (cfv/validate-file file {})))
          file          (update-in file [:data :pages-index page-id :objects parent-id :shapes]
                                   #(d/removev #{board-id} %))
          changes       (cfr/repair-file file {} [error])
          file'         (thf/apply-changes file {:redo-changes changes} :validate? false)
          group'        (ths/get-shape file' :group1)
          parent'       (ths/get-shape-by-id file' parent-id)]
      (t/is (= (count (d/removev #{board-id} parent-shapes))
               (d/index-of (:shapes parent') (:id group'))))
      (t/is (= parent-id (:parent-id group'))))))

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
                      ;; The shared list keeps every name, in layers-panel order (root1 is the top variant).
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

      ;; :shapes is [root2 root1], so root1 is the top variant and the
      ;; shared list is Property 1, Property 2, Property 3
      (t/is (= [{:name "Property 1" :value "Value2"}
                {:name "Property 2" :value ""}
                {:name "Property 3" :value "ValueB"}]
               (:variant-properties comp2')))

      (t/is (= [{:name "Property 1" :value "Value1"}
                {:name "Property 2" :value "ValueA"}
                {:name "Property 3" :value ""}]
               (:variant-properties comp1')))

      (t/is (= "Value2, ValueB" (:variant-name root2')))
      (t/is (= "Value1, ValueA" (:variant-name root1'))))))

(t/deftest repair-two-errors-on-one-variant-component-in-one-pass
  (t/testing "the properties fix and the variant id fix of the same component both survive one repair pass"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; Component1 has a variant id that does not point to the container,
                      ;; so it belongs to no board and its dirty text is checked on its own.
                      (thc/update-component :component1 {:variant-id (uuid/next)
                                                         :variant-properties [{:name "Property 1" :value "a=b"}]})
                      (ths/update-shape :root1 :variant-name "a=b"))

          errors  (cfv/validate-file file {})
          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})

          comp1'  (thc/get-component file' :component1)]

      (t/is (= [:variant-component-bad-id :invalid-variant-properties] (mapv :code errors)))

      (t/is (nil? errors'))
      (t/is (= (thi/id :variant1) (:variant-id comp1')))
      (t/is (= [{:name "Property 1" :value "ab"}] (:variant-properties comp1'))))))

(t/deftest repair-two-errors-on-one-component-in-any-order
  (let [stray-id (uuid/next)
        file     (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (thc/update-component :component1 {:variant-id (uuid/next)
                                                        :objects {stray-id {:id stray-id :type :rect :name "Stray"}}}))
        errors   (cfv/validate-file file {})]

    (t/is (= [:variant-component-bad-id :non-deleted-component-cannot-have-objects]
             (mapv :code errors)))

    ;; Both repairs rewrite the whole component: whichever runs last must
    ;; keep the fix of the other one.
    (doseq [errors [errors (reverse errors)]]
      (t/testing (str "both fixes survive with the errors in order " (mapv :code errors))
        (let [changes (cfr/repair-file file {} errors)
              file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
              comp1'  (thc/get-component file' :component1)]
          (t/is (nil? (cfv/validate-file file' {})))
          (t/is (not (contains? comp1' :objects)))
          (t/is (= (thi/id :variant1) (:variant-id comp1'))))))))

(t/deftest repair-dirty-properties-and-stale-label-in-any-order
  (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                   (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                   (thc/update-component :component2 {:variant-properties [{:name "Property 1" :value "a=b"}]})
                   (ths/update-shape :root2 :variant-name "stale"))
        errors (cfv/validate-file file {})]

    (t/is (= [:invalid-variant-properties :variant-main-bad-variant-name]
             (mapv :code errors)))

    (doseq [errors [errors (reverse errors)]]
      (t/testing (str "both repairs agree with the errors in order " (mapv :code errors))
        (let [changes (cfr/repair-file file {} errors)
              file'   (thf/apply-changes file {:redo-changes changes} :validate? false)]
          (t/is (nil? (cfv/validate-file file' {})))
          (t/is (= "ab" (:variant-name (ths/get-shape file' :root2)))))))

    ;; The property repair rewrites the label in either order, so only the
    ;; label repair on its own shows that it writes the label of the
    ;; cleaned properties.
    (t/testing "the label repair alone writes the label of the cleaned properties"
      (let [label-errors (filterv #(= :variant-main-bad-variant-name (:code %)) errors)
            changes      (cfr/repair-file file {} label-errors)
            file'        (thf/apply-changes file {:redo-changes changes} :validate? false)]
        (t/is (= "ab" (:variant-name (ths/get-shape file' :root2))))))))

(t/deftest repair-errors-on-shapes-removed-by-an-earlier-repair
  (t/testing "repairs on shapes that an earlier repair of the same pass removed change nothing"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      ;; A variant that is not a main: its repair removes it with its children
                      (ths/add-sample-shape :stray :type :frame :parent-label :variant1
                                            :variant-id (thi/id :variant1) :variant-name "Value3")
                      (ths/add-sample-shape :inner :parent-label :stray)
                      (ths/update-shape :inner :frame-id (uuid/next)))

          errors  (cfv/validate-file file {})
          removed #{(thi/id :stray) (thi/id :inner)}
          later   (->> errors
                       (drop-while #(not= :variant-not-main (:code %)))
                       (rest))

          changes (cfr/repair-file file {} errors)
          file'   (thf/apply-changes file {:redo-changes changes} :validate? false)
          errors' (cfv/validate-file file' {})]

      (t/is (some #(= :frame-not-found (:code %)) later))
      (t/is (every? #(contains? removed (:shape-id %)) later))

      (t/is (nil? errors'))
      (t/is (nil? (ths/get-shape file' :stray)))
      (t/is (nil? (ths/get-shape file' :inner)))
      (t/is (not-any? removed (:shapes (ths/get-shape file' :variant1))))))

  ;; Guards against a crash: without the removed-shape check in the
  ;; :ref-shape-not-found repair, its lookups throw on a shape that the
  ;; :variant-not-main repair removed earlier in the same pass.
  (t/testing ":ref-shape-not-found on a shape that an earlier repair of the same pass removed"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     ;; A variant-like frame that claims :component1 but is not its main
                     (ths/add-sample-shape :stray :type :frame :parent-label :variant1
                                           :component-id (thi/id :component1)
                                           :component-file (thi/id :file1)
                                           :component-root true
                                           :variant-id (thi/id :variant1)
                                           :variant-name "Value3"))
          errors (cfv/validate-file file {})
          result (repair-until-valid file)]

      (t/is (= [[:variant-not-main (thi/id :stray)]
                [:variant-main-bad-variant-name (thi/id :stray)]
                [:ref-shape-not-found (thi/id :stray)]]
               (mapv (juxt :code :shape-id) errors)))
      (t/is (nil? (ths/get-shape (:file result) :stray)))
      (t/is (nil? (:errors result)))
      (t/is (true? (:main-instance (ths/get-shape (:file result) :root1))))
      (t/is (= (thi/id :root1)
               (:main-instance-id (thc/get-component (:file result) :component1)))))))

(t/deftest repair-nested-main-not-allowed
  (t/testing "a main in a plain board keeps its place and gets :component-root"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (ths/add-sample-shape :outer :type :frame)
                     (ths/add-sample-shape :root1 :type :frame :parent-label :outer)
                     (thc/make-component :component1 :root1)
                     (ths/update-shape :root1 :component-root nil))
          result (repair-until-valid file)
          root'  (ths/get-shape (:file result) :root1)]
      (t/is (nil? (:errors result)))
      (t/is (true? (:component-root root')))
      (t/is (= (thi/id :outer) (:parent-id root')))))

  (t/testing "a main inside another main moves to the page root"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (ths/add-sample-shape :outer :type :frame)
                     (thc/make-component :component1 :outer)
                     (ths/add-sample-shape :root1 :type :frame :parent-label :outer)
                     (thc/make-component :component2 :root1)
                     (ths/update-shape :root1 :component-root nil))
          result (repair-until-valid file)
          root'  (ths/get-shape (:file result) :root1)]
      (t/is (nil? (:errors result)))
      (t/is (true? (:component-root root')))
      (t/is (= uuid/zero (:parent-id root'))))))

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

(defn- stub-repair-error
  "Stand-in for `cfr/repair-error` that returns `(f)` as the redo changes.
  Multi-arity on purpose: the JS build calls the multimethod through its
  `cljs$core$IFn$_invoke$arity$4` property, which a single-arity fn lacks."
  [f]
  (fn
    ([_ _ _ _] {:redo-changes (f)})
    ([_ _ _ _ & _] {:redo-changes (f)})))

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
      (with-redefs [cfr/repair-error (stub-repair-error (constantly []))]
        (let [result (cfr/repair-file-until-valid
                      file {}
                      (fn [file _] (cfv/validate-file file {})))]
          (t/is (= 1 (:iterations result)))
          (t/is (= [] (:changes result)))
          (t/is (= (mapv :code errors) (mapv :code (:errors result)))))))))

(t/deftest repair-file-until-valid-repairs-several-errors-on-one-shape
  (t/testing "three missing children of one frame are all removed in one pass,
              although the three errors write the same :shapes attribute"
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
      (t/is (= 1 (:iterations result)))
      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file (:file result) {})))
      (t/is (= [] (:shapes frame1'))))))

(t/deftest repair-file-until-valid-stops-when-repair-changes-nothing
  (t/testing "a non-empty repair that leaves data equal stops after one iteration"
    (let [file     (broken-frame-file)
          shape-id (thi/id :shape1)
          page-id  (thi/id :page1)
          cur-name (:name (ths/get-shape file :shape1 :page-label :page1))]
      (with-redefs [cfr/repair-error
                    (stub-repair-error
                     (fn []
                       [{:type :mod-obj
                         :id shape-id
                         :page-id page-id
                         :operations [{:type :set :attr :name :val cur-name}]}]))]
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
      (with-redefs [cfr/repair-error
                    (stub-repair-error
                     (fn []
                       [{:type :mod-obj
                         :id shape-id
                         :page-id page-id
                         :operations [{:type :set
                                       :attr :name
                                       :val (str "pass-" (swap! pass inc))}]}]))]
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
      (with-redefs [cfr/repair-error
                    (stub-repair-error
                     (fn []
                       [{:type :mod-obj
                         :id shape-id
                         :page-id page-id
                         :operations [{:type :set
                                       :attr :name
                                       :val (str "pass-" (swap! pass inc))}]}]))]
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
  (t/testing "two values that clean to empty both stay \"\""
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
      (t/is (= "" (-> comp1' :variant-properties first :value)))
      (t/is (= "" (:variant-name root1')))
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
  (t/testing "two members with the same clean value tuple are valid and left alone"
    (let [props  [{:name "Property 1" :value "red"}]
          file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (thc/update-component :component2 {:variant-properties props})
                     (thc/update-component :component1 {:variant-properties props})
                     (ths/update-shape :root2 :variant-name "red")
                     (ths/update-shape :root1 :variant-name "red"))
          errors (cfv/validate-file file {})
          result (repair-until-valid file)]

      (t/is (nil? errors))
      (t/is (= (:data file) (:data (:file result))))))

  (t/testing "an editor value change that repeats another member's values passes validation"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2))
          comp1   (thc/get-component file :component1)
          value   (-> (thc/get-component file :component2) :variant-properties first :value)
          changes (-> (pcb/empty-changes nil (thf/current-page-id file))
                      (pcb/with-library-data (:data file))
                      (pcb/with-objects (:objects (thf/current-page file)))
                      (clvp/generate-update-property-value (:id comp1) 0 value))
          file'   (thf/apply-changes file changes :validate? false)
          errors  (cfv/validate-file file' {})
          affected (cfv/validate-file-affected file' {} (:redo-changes changes))]

      (t/is (= value (-> (thc/get-component file' :component1) :variant-properties first :value)))
      (t/is (nil? errors))
      (t/is (nil? affected)))))

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
  (t/testing "a variant board that exists only in a component's objects is repaired as one set"
    (let [{:keys [file owner-id comp2-id comp1-id root2-id root1-id]}
          (add-deleted-objects-only-board
           (thf/sample-file :file1 :page-label :page1)
           [{:name "Size" :value "red"}]
           [{:name "Color" :value "bl=ue"}])
          errors  (cfv/validate-file file {})
          affected (cfv/validate-file-affected file {} [{:type :mod-component :id owner-id}])
          result  (repair-until-valid file)
          file'   (:file result)
          result2 (repair-until-valid file')
          comp2'  (get-in file' [:data :components comp2-id])
          comp1'  (get-in file' [:data :components comp1-id])]

      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) errors))))
      (t/is (= 1 (count (filter #(= :invalid-variant-properties (:code %)) affected))))
      ;; :shapes is [root2 root1], so root1 is the top variant and its
      ;; name leads the set
      (t/is (= [{:name "Color" :value ""} {:name "Size" :value "red"}]
               (:variant-properties comp2')))
      (t/is (= [{:name "Color" :value "blue"} {:name "Size" :value ""}]
               (:variant-properties comp1')))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "blue" (get-in comp1' [:objects root1-id :variant-name])))
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
      (t/is (= "red" (-> comp1' :variant-properties first :value)))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "red" (get-in comp1' [:objects root1-id :variant-name])))
      (t/is (nil? (:errors result))))))

(t/deftest repair-invalid-variant-properties-matches-migration
  (t/testing "repair and migration 0038 give the same properties and labels for one dirty board"
    (let [file      (-> (thf/sample-file :file1 :page-label :page1)
                        (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                        (thc/update-component :component1 {:variant-properties [{:name "Size=Big" :value "a=b"}]})
                        (thc/update-component :component2 {:variant-properties [{:name "Color" :value "re,d"}]}))
          migrated  (assoc file :data (cfm/migrate-data (:data file) "0038-sanitize-variant-property-text"))
          repaired  (:file (repair-until-valid file))
          props-of  (fn [file label] (:variant-properties (thc/get-component file label)))
          label-of  (fn [file label] (:variant-name (ths/get-shape file label)))]

      (t/is (= (props-of migrated :component1) (props-of repaired :component1)))
      (t/is (= (props-of migrated :component2) (props-of repaired :component2)))
      (t/is (= (label-of migrated :root1) (label-of repaired :root1)))
      (t/is (= (label-of migrated :root2) (label-of repaired :root2))))))

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

(t/deftest repair-unflagged-board-member-save
  (let [file (-> (thf/sample-file :file1 :page-label :page1)
                 (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                 (ths/update-shape :variant1 :is-variant-container false)
                 (thc/update-component :component1 {:variant-properties [{:name "Size" :value "Big"}]})
                 (ths/update-shape :root1 :variant-name "Big"))]

    (t/testing "a save that touches only a member of a board that lost its flag is not stuck"
      (let [changes  [{:type :mod-component :id (thi/id :component1)}]
            affected (cfv/validate-file-affected file {} changes)
            result   (repair-until-valid-affected file changes)]
        ;; No set error on the frame that is no longer flagged as a board
        (t/is (not-any? #(and (= :invalid-variant-properties (:code %))
                              (= (thi/id :variant1) (:shape-id %)))
                        affected))
        (t/is (nil? (:errors result)))))

    (t/testing "a full repair restores the flag and then aligns the set"
      (let [result (repair-until-valid file)
            file'  (:file result)]
        (t/is (true? (:is-variant-container (ths/get-shape file' :variant1))))
        ;; :shapes is [root2 root1], so root1 is the top variant and its name leads
        (t/is (= [{:name "Size" :value "Big"} {:name "Property 1" :value ""}]
                 (:variant-properties (thc/get-component file' :component1))))
        (t/is (= [{:name "Size" :value ""} {:name "Property 1" :value "Value2"}]
                 (:variant-properties (thc/get-component file' :component2))))
        (t/is (nil? (:errors result)))))))

(t/deftest repair-invalid-variant-properties-member-checked-with-its-board
  (t/testing "a save that touches only a board member checks and repairs the whole set"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                       (thc/update-component :component1 {:variant-properties [{:name "Size=Big" :value "a=b"}]}))
          changes  [{:type :mod-component :id (thi/id :component1)}]
          prop-err (fn [errors] (filter #(= :invalid-variant-properties (:code %)) errors))
          affected (prop-err (cfv/validate-file-affected file {} changes))
          full     (prop-err (cfv/validate-file file {}))
          result   (repair-until-valid-affected file changes)
          file'    (:file result)]

      (t/is (= 1 (count affected)))
      (t/is (= 1 (count full)))
      ;; :shapes is [root2 root1], so root1 is the top variant and its name leads
      (t/is (= [{:name "SizeBig" :value "ab"} {:name "Property 1" :value ""}]
               (:variant-properties (thc/get-component file' :component1))))
      (t/is (= [{:name "SizeBig" :value ""} {:name "Property 1" :value "Value2"}]
               (:variant-properties (thc/get-component file' :component2))))
      (t/is (= "ab" (:variant-name (ths/get-shape file' :root1))))
      (t/is (= "Value2" (:variant-name (ths/get-shape file' :root2))))
      (t/is (nil? (:errors result)))))

  (t/testing "a save that touches only a member of an objects-only board checks that board"
    (let [{:keys [file owner-id comp2-id comp1-id root2-id root1-id]}
          (add-deleted-objects-only-board
           (thf/sample-file :file1 :page-label :page1)
           [{:name "Size" :value "re=d"}]
           [{:name "Size" :value "blue"}])
          changes  [{:type :mod-component :id comp1-id}]
          affected (filter #(= :invalid-variant-properties (:code %))
                           (cfv/validate-file-affected file {} changes))
          result   (repair-until-valid-affected file changes)
          comp2'   (get-in (:file result) [:data :components comp2-id])
          comp1'   (get-in (:file result) [:data :components comp1-id])]

      (t/is (= 1 (count affected)))
      (t/is (= owner-id (get-in (first affected) [:args :component-id])))
      (t/is (= [{:name "Size" :value "red"}] (:variant-properties comp2')))
      (t/is (= [{:name "Size" :value "blue"}] (:variant-properties comp1')))
      (t/is (= "red" (get-in comp2' [:objects root2-id :variant-name])))
      (t/is (= "blue" (get-in comp1' [:objects root1-id :variant-name])))
      (t/is (nil? (:errors result))))))

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

(defn- live-solo-file
  [shape-variant-id comp-variant-id props]
  (-> (thf/sample-file :file1 :page-label :page1)
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
                                         :variant-properties props})))

(defn- assert-live-solo-detached
  [file]
  (let [frame' (ths/get-shape file :frame1)
        rect'  (ths/get-shape file :rect1)
        root'  (ths/get-shape file :root1)
        comp'  (thc/get-component file :component1)]
    (t/is (not (true? (:is-variant-container frame'))))
    (t/is (some? rect'))
    (t/is (nil? (:variant-id root')))
    (t/is (nil? (:variant-name root')))
    (t/is (= "SoloMain / ab" (:name root')))
    (t/is (nil? (:variant-id comp')))
    (t/is (nil? (:variant-properties comp')))
    (t/is (= "SoloMain" (:path comp')))
    (t/is (= "ab" (:name comp')))))

(t/deftest repair-variant-not-main-claimed-by-component
  (t/testing "a variant main that lost :main-instance gets it back"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (ths/add-sample-shape :child1 :type :rect :parent-label :root1)
                     (ths/update-shape :root1 :main-instance nil))
          result (repair-until-valid file)
          root'  (ths/get-shape (:file result) :root1)]
      (t/is (nil? (:errors result)))
      ;; One pass: the :ref-shape-not-found of the same pass leaves the main alone
      (t/is (= 1 (:iterations result)))
      (t/is (true? (:main-instance root')))
      (t/is (nil? (:shape-ref root')))
      (t/is (= (thi/id :variant1) (:variant-id root')))
      (t/is (= [(thi/id :child1)] (:shapes root')))))

  (t/testing "a variant main whose component has a stale page gets :main-instance back"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (ths/add-sample-shape :child1 :type :rect :parent-label :root1)
                     (ths/update-shape :root1 :main-instance nil)
                     (thc/update-component :component1 {:main-instance-page (uuid/next)}))
          result (repair-until-valid file)
          root'  (ths/get-shape (:file result) :root1)
          comp'  (thc/get-component (:file result) :component1)]
      (t/is (nil? (:errors result)))
      (t/is (true? (:main-instance root')))
      (t/is (= [(thi/id :child1)] (:shapes root')))
      (t/is (= (thi/id :root1) (:main-instance-id comp')))
      (t/is (= (thi/id :page1) (:main-instance-page comp')))))

  (t/testing "a claimed variant main off its board is restored, then detached to a plain main"
    (let [file   (-> (live-solo-file (uuid/next) (uuid/next) [{:name "SizeBig" :value "ab"}])
                     (ths/update-shape :root1 :main-instance nil))
          result (repair-until-valid file)
          root'  (ths/get-shape (:file result) :root1)
          comp'  (thc/get-component (:file result) :component1)]
      (t/is (nil? (:errors result)))
      (t/is (true? (:main-instance root')))
      (t/is (nil? (:variant-id root')))
      (t/is (nil? (:variant-id comp')))))

  (t/testing "a variant main of a deleted component is removed with its children"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (ths/add-sample-shape :child1 :type :rect :parent-label :root1)
                     (ths/update-shape :root1 :main-instance nil)
                     (thc/update-component :component1 {:deleted true}))
          result (repair-until-valid file)]
      (t/is (nil? (:errors result)))
      (t/is (nil? (ths/get-shape (:file result) :root1)))
      (t/is (nil? (ths/get-shape (:file result) :child1)))
      (t/is (some? (ths/get-shape (:file result) :root2))))))

(t/deftest repair-variant-main-outside-board
  (t/testing "a live solo on an ordinary frame reports only :variant-main-outside-board among the variant codes"
    (let [variant-id (uuid/next)
          file       (live-solo-file variant-id variant-id [{:name "SizeBig" :value "ab"}])
          codes      (set (map :code (cfv/validate-file file {})))]
      (t/is (contains? codes :variant-main-outside-board))
      (t/is (not-any? #{:invalid-variant-properties
                        :parent-not-variant
                        :variant-main-bad-name
                        :variant-component-bad-name
                        :variant-component-bad-id}
                      codes))))

  (t/testing "a live solo with dirty text is detached without adopting the frame"
    (let [variant-id (uuid/next)
          file       (live-solo-file variant-id variant-id [{:name "Size=Big" :value "a=b"}])
          changes    [{:type :mod-component :id (thi/id :component1)}]
          result     (repair-until-valid-affected file changes)
          file'      (:file result)
          result2    (repair-until-valid-affected file' changes)]
      (assert-live-solo-detached file')
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a live solo with clean text and a stale label is detached by a full repair"
    (let [variant-id (uuid/next)
          file       (live-solo-file variant-id variant-id [{:name "SizeBig" :value "ab"}])
          result     (repair-until-valid file)]
      (assert-live-solo-detached (:file result))
      (t/is (nil? (:errors result)))))

  (t/testing "a live solo whose main also lost its :variant-id is fully detached"
    (let [variant-id (uuid/next)
          file       (-> (live-solo-file variant-id variant-id [{:name "SizeBig" :value "ab"}])
                         (ths/update-shape :root1 :variant-id nil))
          codes      (set (map :code (cfv/validate-file file {})))
          result     (repair-until-valid file)]
      (t/is (contains? codes :main-instance-not-a-variant))
      (t/is (not (contains? codes :variant-main-outside-board)))
      (assert-live-solo-detached (:file result))
      (t/is (nil? (:errors result)))))

  (t/testing "a live solo whose component id differs from the shape id is detached"
    (let [file    (live-solo-file (uuid/next) (uuid/next) [{:name "Size=Big" :value "a=b"}])
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          result2 (repair-until-valid-affected file' changes)]
      (assert-live-solo-detached file')
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2)))))))

(t/deftest repair-variant-main-outside-board-with-stray-objects
  (t/testing "a live solo outside its board that also holds stray objects is detached once"
    (let [variant-id (uuid/next)
          stray-id   (uuid/next)
          file       (-> (live-solo-file variant-id variant-id [{:name "SizeBig" :value "ab"}])
                         (assoc-in [:data :components (thi/id :component1) :objects]
                                   {stray-id {:id stray-id :type :rect :name "Stray"}}))
          result     (repair-until-valid file)
          file'      (:file result)]
      (assert-live-solo-detached file')
      (t/is (empty? (:objects (thc/get-component file' :component1))))
      (t/is (= 1 (:iterations result)))
      (t/is (nil? (:errors result)))))

  (t/testing "a live solo outside its board whose objects are nil is detached once"
    (let [variant-id (uuid/next)
          file       (-> (live-solo-file variant-id variant-id [{:name "SizeBig" :value "ab"}])
                         (assoc-in [:data :components (thi/id :component1) :objects] nil))
          result     (repair-until-valid file)]
      (assert-live-solo-detached (:file result))
      (t/is (= 1 (:iterations result)))
      (t/is (nil? (:errors result))))))

(t/deftest repair-variant-main-outside-board-page-only-move
  (t/testing "a save that moves a variant main off its board with page changes only is caught and repaired"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                       (ths/add-sample-shape :frame1 :type :frame :name "Ordinary"))
          page     (thf/current-page file)
          changes  (-> (pcb/empty-changes nil (:id page))
                       (pcb/with-objects (:objects page))
                       (pcb/change-parent (thi/id :frame1) [(ths/get-shape file :root1)]))
          file     (thf/apply-changes file changes :validate? false)
          redo     (:redo-changes changes)
          affected (cfv/validate-file-affected file {} redo)
          full     (cfv/validate-file file {})
          result   (repair-until-valid-affected file redo)
          file'    (:file result)
          root1'   (ths/get-shape file' :root1)
          comp1'   (thc/get-component file' :component1)]

      (t/is (every? #(not= :mod-component (:type %)) redo))
      (t/is (= 1 (count (filter #(= :variant-main-outside-board (:code %)) affected))))
      (t/is (= 1 (count (filter #(= :variant-main-outside-board (:code %)) full))))
      (t/is (nil? (:variant-id root1')))
      (t/is (nil? (:variant-id comp1')))
      (t/is (nil? (:variant-properties comp1')))
      (t/is (not (true? (:is-variant-container (ths/get-shape file' :frame1)))))
      (t/is (nil? (:errors result))))))

(t/deftest repair-deleted-solo-not-a-variant-with-stale-label
  (t/testing "a deleted solo whose main lost its :variant-id and has a stale label ends valid"
    (let [deleted-id (uuid/next)
          main-id    (uuid/next)
          variant-id (uuid/next)
          file       (thf/sample-file :file1 :page-label :page1)
          page-id    (thf/current-page-id file)
          file       (update file :data
                             (fn [data]
                               (assoc-in data [:components deleted-id]
                                         {:id                 deleted-id
                                          :name               "Legacy"
                                          :path               ""
                                          :deleted            true
                                          :variant-id         variant-id
                                          :main-instance-id   main-id
                                          :main-instance-page page-id
                                          :variant-properties [{:name "Size" :value "big"}]
                                          :objects            {main-id {:id           main-id
                                                                        :type         :frame
                                                                        :variant-name "stale"}}})))
          codes      (set (map :code (cfv/validate-file file {})))
          result     (repair-until-valid file)
          main'      (get-in (:file result) [:data :components deleted-id :objects main-id])]

      (t/is (contains? codes :main-instance-not-a-variant))
      (t/is (contains? codes :invalid-variant-properties))
      (t/is (= variant-id (:variant-id main')))
      (t/is (= "big" (:variant-name main')))
      (t/is (= 1 (:iterations result)))
      (t/is (nil? (:errors result))))))

(t/deftest repair-bad-variant-name-on-board-not-a-member
  (t/testing "a main on a board whose component points to another board ends valid with a clean label"
    (let [file   (-> (thf/sample-file :file1 :page-label :page1)
                     (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                     (thc/update-component :component1 {:variant-id (uuid/next)
                                                        :variant-properties [{:name "Property 1" :value "a=b"}]}))
          result (repair-until-valid file)
          file'  (:file result)
          comp1' (thc/get-component file' :component1)
          root1' (ths/get-shape file' :root1)]

      (t/is (= (thi/id :variant1) (:variant-id comp1')))
      (t/is (= [{:name "Property 1" :value "ab"}] (:variant-properties comp1')))
      (t/is (= "ab" (:variant-name root1')))
      (t/is (= 1 (:iterations result)))
      (t/is (nil? (:errors result))))))

(t/deftest objects-only-board-skips-a-board-already-on-a-page
  (t/testing "a board in a deleted component's objects with the id of a page board is not checked twice"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                       (thc/update-component :component1 {:variant-properties [{:name "Property 1" :value "a=b"}]}))
          owner-id (uuid/next)
          board-id (thi/id :variant1)
          page     (thf/current-page file)
          objects  (select-keys (:objects page) [board-id (thi/id :root1) (thi/id :root2)])
          file     (update file :data
                           (fn [data]
                             (assoc-in data [:components owner-id]
                                       {:id                 owner-id
                                        :name               "Owner"
                                        :path               ""
                                        :deleted            true
                                        :main-instance-id   board-id
                                        :main-instance-page (:id page)
                                        :objects            objects})))
          errors   (filter #(= :invalid-variant-properties (:code %)) (cfv/validate-file file {}))]

      (t/is (= 1 (count errors)))
      (t/is (nil? (get-in (first errors) [:args :component-id]))))))

(t/deftest objects-only-board-finds-its-page-through-its-members
  (t/testing "a board copy whose page board sits on its members' page, not the owner's, is skipped"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2)
                       (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                       (thc/update-component :component1 {:variant-properties [{:name "Property 1" :value "a=b"}]}))
          owner-id (uuid/next)
          board-id (thi/id :variant1)
          page2    (thf/get-page file :page2)
          objects  (select-keys (:objects page2) [board-id (thi/id :root1) (thi/id :root2)])
          file     (update file :data
                           (fn [data]
                             (assoc-in data [:components owner-id]
                                       {:id                 owner-id
                                        :name               "Owner"
                                        :path               ""
                                        :deleted            true
                                        :main-instance-id   board-id
                                        :main-instance-page (thi/id :page1)
                                        :objects            objects})))
          ;; This save does not walk page2, so only the members' page lookup
          ;; tells the owner's copy apart from an objects-only board
          affected (filter #(= :invalid-variant-properties (:code %))
                           (cfv/validate-file-affected file {} [{:type :mod-component :id owner-id}]))
          full     (filter #(= :invalid-variant-properties (:code %))
                           (cfv/validate-file file {}))]

      (t/is (empty? affected))
      (t/is (= 1 (count full)))
      (t/is (nil? (get-in (first full) [:args :component-id]))))))

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
  (t/testing "a live solo with clean text and a missing main is removed and does not fail the component save"
    (let [file    (live-solo-missing-main [{:name "SizeBig" :value "ab"}])
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)]
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (nil? (ths/get-shape (:file result) :root1)))
      (t/is (nil? (thc/get-component (:file result) :component1 :include-deleted? true)))))

  (t/testing "a full repair of that live solo does not create the missing main"
    (let [file   (live-solo-missing-main [{:name "SizeBig" :value "ab"}])
          result (repair-until-valid file)
          file'  (:file result)]
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (ths/get-shape file' :root1)))))

  (t/testing "a live solo with dirty text and a missing main is removed and creates no shape"
    (let [file    (live-solo-missing-main [{:name "Size=Big" :value "a=b"}])
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          result2 (repair-until-valid-affected file' changes)
          comp'   (thc/get-component file' :component1 :include-deleted? true)]
      (t/is (nil? comp'))
      (t/is (nil? (ths/get-shape file' :root1)))
      (t/is (nil? (seq (property-errors result))))
      (t/is (nil? (:errors result)))
      (t/is (= (:data file') (:data (:file result2))))))

  (t/testing "a full repair of that dirty live solo creates no shape"
    (let [file   (live-solo-missing-main [{:name "Size=Big" :value "a=b"}])
          result (repair-until-valid file)
          file'  (:file result)
          comp'  (thc/get-component file' :component1 :include-deleted? true)]
      (t/is (nil? comp'))
      (t/is (nil? (ths/get-shape file' :root1)))
      (t/is (nil? (seq (property-errors result)))))))


(defn- remove-main
  "Remove the shapes `labels` from the objects of the page `page-label` and
  from their parent's children, leaving the component in place."
  [file page-label & labels]
  (let [ids (into #{} (map thi/id) labels)]
    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page
        file-data
        (thi/id page-label)
        (fn [page]
          (update page :objects
                  (fn [objects]
                    (reduce-kv (fn [result id shape]
                                 (if (contains? ids id)
                                   result
                                   (assoc result id (update shape :shapes #(into [] (remove ids) %)))))
                               {}
                               objects)))))))))

(t/deftest repair-component-main-not-found-relinks-main-on-another-page
  (t/testing "a component pointing to no shape is relinked to its main on another page"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (thf/add-sample-page :page2)
                         (tho/add-simple-component :component1 :root1 :child1)
                         (thc/update-component :component1
                                               :main-instance-id (uuid/next)
                                               :main-instance-page (thi/id :page1)))
          changes    [{:type :mod-component :id (thi/id :component1)}]
          errors     (cfv/validate-file-affected file {} changes)
          result     (repair-until-valid-affected file changes)
          component' (thc/get-component (:file result) :component1)]

      (t/is (= [:component-main-not-found] (mapv :code errors)))
      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file (:file result) {})))
      (t/is (= (thi/id :root1) (:main-instance-id component')))
      (t/is (= (thi/id :page2) (:main-instance-page component')))
      (t/is (not (:deleted component'))))))

(t/deftest repair-component-main-not-found-relinks-main-on-component-page
  (t/testing "a component pointing to a missing shape is relinked to the main on its page"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (tho/add-simple-component :component1 :root1 :child1)
                         (thc/update-component :component1 :main-instance-id (uuid/next)))
          changes    [{:type :mod-component :id (thi/id :component1)}]
          result     (repair-until-valid-affected file changes)
          component' (thc/get-component (:file result) :component1)]

      (t/is (nil? (:errors result)))
      (t/is (= (thi/id :root1) (:main-instance-id component')))
      (t/is (= (thi/id :page1) (:main-instance-page component')))))

  (t/testing "a component with no main id is relinked to the main that claims it"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (tho/add-simple-component :component1 :root1 :child1)
                         (thc/update-component :component1 :main-instance-id nil))
          changes    [{:type :mod-component :id (thi/id :component1)}]
          result     (repair-until-valid-affected file changes)
          component' (thc/get-component (:file result) :component1)]

      (t/is (nil? (:errors result)))
      (t/is (= (thi/id :root1) (:main-instance-id component')))
      (t/is (= (thi/id :page1) (:main-instance-page component'))))))

(t/deftest repair-component-main-not-found-removes-component-without-main
  (t/testing "a component with no main anywhere is removed and its copy detached"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (tho/add-simple-component :component1 :root1 :child1)
                      (thf/add-sample-page :page2)
                      (thc/instantiate-component :component1 :copy1)
                      (remove-main :page1 :root1 :child1))
          result  (repair-until-valid file)
          file'   (:file result)
          copy1'  (ths/get-shape file' :copy1 :page-label :page2)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (nil? (thc/get-component file' :component1 :include-deleted? true)))
      (t/is (some? copy1'))
      (t/is (nil? (:component-id copy1')))
      (t/is (nil? (:shape-ref copy1'))))))

(t/deftest repair-component-main-not-found-removes-component-without-main-scoped
  (t/testing "an affected repair removes the component and detaches its copy on another page"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (tho/add-simple-component :component1 :root1 :child1)
                      (thf/add-sample-page :page2)
                      (thc/instantiate-component :component1 :copy1)
                      (remove-main :page1 :root1 :child1))
          changes [{:type :mod-component :id (thi/id :component1)}]
          result  (repair-until-valid-affected file changes)
          file'   (:file result)
          copy1'  (ths/get-shape file' :copy1 :page-label :page2)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (nil? (thc/get-component file' :component1 :include-deleted? true)))
      (t/is (nil? (:component-id copy1')))
      (t/is (nil? (:shape-ref copy1'))))))

(t/deftest repair-component-main-not-found-removes-variant-component-without-main
  (t/testing "a variant component with no main is removed and the other variant stays"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (thv/add-variant :variant1 :component1 :root1 :component2 :root2)
                      (remove-main :page1 :root1))
          result  (repair-until-valid file)
          file'   (:file result)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (nil? (thc/get-component file' :component1 :include-deleted? true)))
      (t/is (some? (thc/get-component file' :component2)))
      (t/is (some? (ths/get-shape file' :variant1)))
      (t/is (some? (ths/get-shape file' :root2))))))

(t/deftest repair-component-main-not-found-keeps-component-claimed-by-main
  (t/testing "a main that also gets :invalid-main-instance-id does not lead to a removal"
    (let [file    (lost-main-file)
          result  (cfr/repair-file-until-valid
                   file {}
                   (fn [file _] (cfv/validate-file file {}))
                   :max-iterations 1)
          file'   (:file result)]

      (t/is (nil? (:errors result)))
      (t/is (= (thi/id :root1) (:main-instance-id (thc/get-component file' :component1))))
      (t/is (some? (ths/get-shape file' :root1 :page-label :page1))))))

(t/deftest repair-component-main-not-found-relinks-when-main-id-is-nil
  (t/testing "a component with no main id is relinked and its copies keep following it"
    (let [file       (-> (thf/sample-file :file1 :page-label :page1)
                         (tho/add-simple-component :component1 :root1 :child1)
                         (thf/add-sample-page :page2)
                         (thc/instantiate-component :component1 :copy1)
                         (thc/update-component :component1 :main-instance-id nil))
          result     (repair-until-valid file)
          file'      (:file result)
          component' (thc/get-component file' :component1)
          copy1'     (ths/get-shape file' :copy1 :page-label :page2)]

      (t/is (nil? (:errors result)))
      (t/is (nil? (cfv/validate-file file' {})))
      (t/is (= (thi/id :root1) (:main-instance-id component')))
      (t/is (= (thi/id :page1) (:main-instance-page component')))
      (t/is (= (thi/id :component1) (:component-id copy1')))
      (t/is (some? (:shape-ref copy1'))))))
