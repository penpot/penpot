;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.comp-processors-test
  (:require
   [app.common.data :as d]
   [app.common.files.comp-processors :as cfcp]
   [app.common.files.swap-slots :as cfss]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [clojure.test :as t]))

(t/deftest test-remove-unneeded-objects-in-components

  (t/testing "nil file should return nil"
    (let [file  nil
          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file without components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with non deleted components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with deleted components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (tho/delete-shape :frame1))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with non deleted components with :objects nil should remove it"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (thc/update-component :component1 {:objects nil}))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {}}}}]

      (t/is (= expected-diff diff))))

  (t/testing "file with non deleted components with :objects should remove it"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (thc/update-component :component1 {:objects {:sample 777}}))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {:objects
                            [{:sample 777} nil]}}}}]

      (t/is (= expected-diff diff))))

  (t/testing "file with deleted components without :objects should add an empty one"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (tho/delete-shape :frame1)
              (ctf/update-file-data
               (fn [file-data]
                 (ctkl/update-component file-data (thi/id :component1) #(dissoc % :objects)))))

          file' (ctf/update-file-data file cfcp/remove-unneeded-objects-in-components)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {:objects
                            [nil {}]}}}}]

      (t/is (= expected-diff diff)))))

(t/deftest test-normalize-component-root

  (t/testing "nil file should return nil"
    (let [file  nil
          file' (ctf/update-file-data file cfcp/normalize-component-root)]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (ctf/update-file-data file cfcp/normalize-component-root)]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "shape with :component-root true should not be modified"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :main1-root :main1-child)
              (thc/instantiate-component :component1 :copy1-root))

          file' (ctf/update-file-data file cfcp/normalize-component-root)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "shape with :component-root false should have it removed"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                  :component2 :main2-root :nested-head
                                                  :copy2-root)
              (ths/update-shape :nested-head :component-root false))

          file' (ctf/update-file-data file cfcp/normalize-component-root)

          shape' (ths/get-shape file' :nested-head)]

      (t/is (not (contains? shape' :component-root))))))

(t/deftest test-migration-0022-fixes-component-root-false-mismatch

  (t/testing "a nested copy with :component-root false and a stale :component-id is fixed by the full migration sequence"
    (let [file
          ;; Reproduce the legacy corruption: a nested copy head has
          ;; :component-root false (instead of absent) AND a stale
          ;; component-id. Migrations 0019/0020 alone don't catch it
          ;; because subcopy-head? expects nil. After normalize-component-root,
          ;; sync-component-id-with-ref-shape can repair it.
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (ths/update-shape :copy2-nested-head :component-root false)
              (ths/update-shape :copy2-nested-head :component-id (thi/new-id! :wrong-id)))

          ;; Run the old sequence (0019/0020 without normalization): the bad shape is skipped.
          old-sequence
          (-> file
              (ctf/update-file-data #(cfcp/fix-missing-swap-slots % {}))
              (ctf/update-file-data #(cfcp/sync-component-id-with-ref-shape % {})))

          old-shape (ths/get-shape old-sequence :copy2-nested-head)

          ;; Run the new sequence (0022): normalize first, then 0019/0020 logic.
          new-sequence
          (-> file
              (ctf/update-file-data cfcp/normalize-component-root)
              (ctf/update-file-data #(cfcp/fix-missing-swap-slots % {}))
              (ctf/update-file-data #(cfcp/sync-component-id-with-ref-shape % {})))

          new-shape (ths/get-shape new-sequence :copy2-nested-head)]

      ;; Confirm the old sequence fails to repair (this guards against a future
      ;; subcopy-head? change accidentally fixing it and masking the regression).
      (t/is (= false (:component-root old-shape)))
      (t/is (= (thi/id :wrong-id) (:component-id old-shape)))

      ;; Confirm the new sequence repairs both the explicit false and the bad ref.
      (t/is (not (contains? new-shape :component-root)))
      (t/is (= (thi/id :component1) (:component-id new-shape))))))

(t/deftest test-fix-missing-swap-slots

  (t/testing "nil file should return nil"
    (let [file  nil
          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file without components should not need any action"
    (let [file
          ;; :frame1 [:name Frame1]
          ;;     :child1 [:name Rect1]
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with nested not swapped components should not need any action"
    (let [file
          ;; {:main1-root} [:name Frame1]      # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]      # [Component :component2]
          ;;     :nested-head [:name Frame1]   @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; :copy2-root [:name Frame2]        #--> [Component :component2] :main2-root
          ;;     <no-label> [:name Frame1]           @--> [Component :component1] :nested-head
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                  :component2 :main2-root :nested-head
                                                  :copy2-root))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with a normally swapped copy should not need any action"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]             ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; :copy2-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-first-child :copy2-root :component3 :as-slot? true))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with a swapped nested copy in a main should not need any action"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame3]        @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]             ---> :main3-child
          ;;
          ;; :copy2-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :nested-head
          ;;         <no-label> [:name Rect3]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-shape :nested-head :component3
                                           :propagate-fn #(tho/propagate-component-changes % :component2) :as-slot? true))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with a swapped copy with broken slot should have it repaired"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]             ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; :copy2-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             NO SWAP SLOT
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-first-child :copy2-root :component3 :as-slot? true)
              (ths/update-shape :copy2-nested-head :touched nil))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:touched
                              [nil
                               #{(cfss/build-swap-slot-group (str (thi/id :nested-head)))}]}}}}}}]

      (t/is (= expected-diff diff))))

  (t/testing "file with a swapped copy inside a main with broken slot has no effect since it cannot be distinguished"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame3]        @--> [Component :component3] :main3-root
          ;;                                             NO SWAP SLOT
          ;;         <no-label> [:name Rect3]             ---> :main3-child
          ;;
          ;; :copy2-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :nested-head
          ;;         <no-label> [:name Rect3]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-shape :nested-head :component3
                                           :propagate-fn #(tho/propagate-component-changes % :component2) :as-slot? true)
              (ths/update-shape :nested-head :touched nil))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with a two levels nested copy in a main swapped with broken slot should have it repaired"
    (let [file
          ;; {:main1-root} [:name Frame1]            # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]            # [Component :component2]
          ;;     :nested-head1 [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; {:main4-root} [:name Frame4]            # [Component :component4]
          ;;     :main4-child [:name Rect4]
          ;;
          ;; {:main3-root} [:name Frame3]            # [Component :component3]
          ;;     :nested-head2 [:name Frame2]        @--> [Component :component2] :main2-root
          ;;         :nested-subhead2 [:name Frame4] @--> [Component :component4] :main4-root
          ;;                                             NO SWAP SLOT
          ;;             <no-label> [:name Rect4]    ---> :main4-child
          ;;
          ;; :copy2-root [:name Frame3]              #--> [Component :component3] :main3-root
          ;;     <no-label> [:name Frame2]           @--> [Component :component2] :nested-head2
          ;;         <no-label> [:name Frame4]       @--> [Component :component4] :nested-subhead2
          ;;             <no-label> [:name Rect4]    ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-two-levels-nested-component-with-copy :component1 :main1-root :main1-child
                                                             :component2 :main2-root :nested-head1
                                                             :component3 :main3-root :nested-head2 :nested-subhead2
                                                             :copy2-root)
              (tho/add-simple-component :component4 :main4-root :main4-child
                                        :root-params {:name "Frame4"}
                                        :child-params {:name "Rect4"})
              (tho/swap-component-in-shape :nested-subhead2 :component4
                                           :propagate-fn #(tho/propagate-component-changes % :component3) :as-slot? true)
              (ths/update-shape :nested-subhead2 :touched nil))

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :nested-subhead2)
                             {:touched
                              [nil
                               #{(cfss/build-swap-slot-group (str (thi/id :nested-head1)))}]}}}}}}]

      (t/is (= expected-diff diff))))

  (t/testing "when components are in external libraries, the fix still works well"
    (let [library1
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested2-head [:name Frame1]       @--> [Component :component1] :main1-root
          ;;         :nested2-child [:name Rect1]   ---> :main1-child
          (-> (thf/sample-file :library1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested2-head
                                        :nested-head-params {:children-labels [:nested2-child]}))
          library2
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main4-root} [:name Frame4]           # [Component :component4]
          ;;     :nested4-head [:name Frame3]       @--> [Component :component1] :main3-root
          ;;         :nested4-child [:name Rect3]   ---> :main3-child
          (-> (thf/sample-file :library2)
              (tho/add-nested-component :component3 :main3-root :main3-child
                                        :component4 :main4-root :nested4-head
                                        :root1-params {:name "Frame3"}
                                        :main1-child-params {:name "Rect3"}
                                        :main2-root-params {:name "Frame4"}
                                        :nested-head-params {:children-labels [:nested4-child]}))

          file
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame4]  @--> [Component :component4] :main4-root
          ;;                                             NO SWAP SLOT
          ;;         <no-label> [:name Frame3]      @--> :nested4-head
          ;;             <no-label> [:name Rect3]   ---> :nested4-child
          (-> (thf/sample-file :file1)
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head]
                                         :library library1)
              (tho/swap-component-in-first-child :copy2 :component4 :library library2 :as-slot? true)
              (ths/update-shape :copy2-nested-head :touched nil))

          libraries {(:id library1) library1
                     (:id library2) library2}

          file' (ctf/update-file-data file #(cfcp/fix-missing-swap-slots % libraries))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:touched
                              [nil
                               #{(cfss/build-swap-slot-group (str (thi/id :nested2-head)))}]}}}}}}]

      (t/is (= expected-diff diff)))))

(t/deftest test-sync-component-id-with-ref-shape

  (t/testing "nil file should return nil"
    (let [file  nil
          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file without components should not need any action"
    (let [file
          ;; :frame1 [:name Frame1]
          ;;     :child1 [:name Rect1]
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with valid normal components should not need any action"
    (let [file
          ;; {:main1-root} [:name Frame1]            # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]            # [Component :component2]
          ;;     :nested-head1 [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]            # [Component :component3]
          ;;     :nested-head2 [:name Frame2]        @--> [Component :component2] :main2-root
          ;;         :nested-subhead2 [:name Frame1] @--> [Component :component1] :nested-head1
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          ;;
          ;; :copy2-root [:name Frame3]              #--> [Component :component3] :main3-root
          ;;     <no-label> [:name Frame2]           @--> [Component :component2] :nested-head2
          ;;         <no-label> [:name Frame1]       @--> [Component :component1] :nested-subhead2
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-two-levels-nested-component-with-copy :component1 :main1-root :main1-child
                                                             :component2 :main2-root :nested-head1
                                                             :component3 :main3-root :nested-head2 :nested-subhead2
                                                             :copy2-root))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))]

      #_(thf/dump-file file') ;; Uncomment to debug
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with valid swapped components should not need any action"
    (let [file
          ;; {:main1-root} [:name Frame1]      # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]      # [Component :component2]
          ;;     :nested-head [:name Frame1]   @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]      # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; :copy2-root [:name Frame2]        #--> [Component :component2] :main2-root
          ;;     <no-label> [:name Frame1]           @--> [Component :component1] :nested-head
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          ;;
          ;; :copy3-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy3-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                  :component2 :main2-root :nested-head
                                                  :copy2-root)
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (thc/instantiate-component :component2 :copy3-root :children-labels [:copy3-nested-head])
              (tho/swap-component-in-first-child :copy3-root :component3 :as-slot? true))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))]

      #_(thf/dump-file file') ;; Uncomment to debug
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with a non swapped copy with broken component id/file should have it repaired"
    (let [file
          ;; {:main1-root} [:name Frame1]      # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]      # [Component :component2]
          ;;     :nested-head [:name Frame1]   @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; :copy2-root [:name Frame2]        #--> [Component :component2] :main2-root
          ;;    :copy2-nested-head [:name Frame1]    @--> [Component <bad>] :nested-head  ## <- BAD component-id
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          ;;
          ;; :copy3-root [:name Frame2]        #--> [Component :component2] :main2-root
          ;;    :copy3-nested-head [:name Frame1]    @--> [Component <bad>] :nested-head  ## <- BAD component-file
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (thc/instantiate-component :component2 :copy3-root :children-labels [:copy3-nested-head])
              (ths/update-shape :copy2-nested-head :component-id (thi/new-id! :some-other-id))
              (ths/update-shape :copy3-nested-head :component-file (thi/new-id! :some-other-file)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:component-id
                              [(thi/id :some-other-id) (thi/id :component1)]}
                             (thi/id :copy3-nested-head)
                             {:component-file
                              [(thi/id :some-other-file) (thi/id :file1)]}}}}}}]

      #_(ctf/dump-tree file' (thf/current-page-id file') {(:id file') file'} {:show-ids true}) ;; Uncomment to debug
      (t/is (= expected-diff diff))))

  (t/testing "file with a copy of a swapped main with broken component id/file should have it repaired"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame3]        @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]       ---> :main3-child
          ;;
          ;; :copy2-root [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component: <bad>] :nested-head   ## <- BAD component-id/file
          ;;         <no-label> [:name Rect3]       ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-shape :nested-head :component3
                                           :propagate-fn #(tho/propagate-component-changes % :component2) :as-slot? true)
              (ths/update-shape :copy2-nested-head :component-id (thi/new-id! :some-other-id))
              (ths/update-shape :copy2-nested-head :component-file (thi/new-id! :some-other-file)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:component-id
                              [(thi/id :some-other-id) (thi/id :component3)]
                              :component-file
                              [(thi/id :some-other-file) (thi/id :file1)]}}}}}}]

      #_(ctf/dump-tree file' (thf/current-page-id file') {(:id file') file'} {:show-ids true}) ;; Uncomment to debug
      (t/is (= expected-diff diff))))

  (t/testing "file with multiple copies of same component should sync all"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (thc/instantiate-component :component1 :copy1-root :children-labels [:copy1-child])
              (thc/instantiate-component :component1 :copy2-root :children-labels [:copy2-child])
              (ths/update-shape :copy1-child :component-id (thi/new-id! :wrong-id1))
              (ths/update-shape :copy2-child :component-id (thi/new-id! :wrong-id2)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))

          diff (d/map-diff file file')]

      ;; Both copies should be corrected
      (t/is (contains? diff :data))
      (t/is (contains? (get-in diff [:data :pages-index]) (thf/current-page-id file)))))

  (t/testing "file with a copy root with broken component id/file cannot be repaired. But it's propagated to copies."
    (let [file
          ;; {:main1-root} [:name Frame1]      # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]      # [Component :component2]
          ;;     :nested-head [:name Frame1]   @--> [Component <bad>] :main1-root  ## <- BAD component-id/file
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; :copy2-root [:name Frame2]        #--> [Component :component2] :main2-root
          ;;    :copy2-nested-head [:name Frame1]    @--> [Component :component1] :nested-head
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2-root :children-labels [:copy2-nested-head])
              (ths/update-shape :nested-head :component-id (thi/new-id! :some-other-id))
              (ths/update-shape :nested-head :component-file (thi/new-id! :some-other-file)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:component-id
                              [(thi/id :component1) (thi/id :some-other-id)]
                              :component-file
                              [(thi/id :file1) (thi/id :some-other-file)]}}}}}}]

      (t/is (= expected-diff diff))))

  (t/testing "file with a 2nd nested copy inside a main with broken component/id should have it repaired, and propagated to copies"
    (let [file
          ;; {:main1-root} [:name Frame1]            # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]            # [Component :component2]
          ;;     :nested-head1 [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]            # [Component :component3]
          ;;     :nested-head2 [:name Frame2]        @--> [Component :component2] :main2-root
          ;;         :nested-subhead2 [:name Frame1] @--> [Component <bad>] :nested-head1  ## <- BAD component-id/file
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          ;;
          ;; :copy2-root [:name Frame3]              #--> [Component :component3] :main3-root
          ;;     <no-label> [:name Frame2]           @--> [Component :component2] :nested-head2
          ;;         <no-label> [:name Frame1]       @--> [Component :component1] :nested-subhead2
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-two-levels-nested-component-with-copy :component1 :main1-root :main1-child
                                                             :component2 :main2-root :nested-head1
                                                             :component3 :main3-root :nested-head2 :nested-subhead2
                                                             :copy2-root)
              (ths/update-shape :nested-subhead2 :component-id (thi/new-id! :some-other-id))
              (ths/update-shape :nested-subhead2 :component-file (thi/new-id! :some-other-file)))

          copy2-root (ths/get-shape file :copy2-root)
          copy2-root-child1 (ths/get-shape-by-id file (first (:shapes copy2-root)))
          copy2-root-child2 (ths/get-shape-by-id file (first (:shapes copy2-root-child1)))
          file (-> file
                   (ths/update-shape-by-id (:id copy2-root-child2) :component-id (thi/id :some-other-id))
                   (ths/update-shape-by-id (:id copy2-root-child2) :component-file (thi/id :some-other-file)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :nested-subhead2)
                             {:component-id
                              [(thi/id :some-other-id) (thi/id :component1)]
                              :component-file
                              [(thi/id :some-other-file) (thi/id :file1)]}
                             (:id copy2-root-child2)
                             {:component-id
                              [(thi/id :some-other-id) (thi/id :component1)]
                              :component-file
                              [(thi/id :some-other-file) (thi/id :file1)]}}}}}}]

      #_(ctf/dump-tree file' (thf/current-page-id file') {(:id file') file'} {:show-ids true}) ;; Uncomment to debug
      (t/is (= expected-diff diff))))

  (t/testing "when components are in external libraries, the fix still works well"
    (let [library1
          ;; {:main1-root} [:name Frame1]              # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]              # [Component :component2]
          ;;     :nested2-head [:name Frame4]          @--> [Component :component4] :main4-root
          ;;                                                {swap-slot :nested2-head}
          ;;         :nested4-head [:name Frame3]      @--> [Component: component3] :main3-root
          ;;             :nested4-child [:name Rect3]  ---> :nested4-child
          (-> (thf/sample-file :library1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested2-head
                                        :nested-head-params {:children-labels [:nested2-child]}))
          library2
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main4-root} [:name Frame4]           # [Component :component4]
          ;;     :nested4-head [:name Frame3]       @--> [Component :component1] :main3-root
          ;;         :nested4-child [:name Rect3]   ---> :main3-child
          (-> (thf/sample-file :library2)
              (tho/add-nested-component :component3 :main3-root :main3-child
                                        :component4 :main4-root :nested4-head
                                        :root1-params {:name "Frame3"}
                                        :main1-child-params {:name "Rect3"}
                                        :main2-root-params {:name "Frame4"}
                                        :nested-head-params {:children-labels [:nested4-child]}))

          library1
          (tho/swap-component-in-shape library1 :nested2-head :component4 :library library2 :as-slot? true)

          file
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame4]  @--> [Component <bad>] :main4-root   ## <- BAD component-id/file
          ;;         <no-label> [:name Frame3]      @--> :nested4-head
          ;;             <no-label> [:name Rect3]   ---> :nested4-child
          (-> (thf/sample-file :file1)
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head]
                                         :library library1)
              (ths/update-shape :copy2-nested-head :component-id (thi/new-id! :some-other-id))
              (ths/update-shape :copy2-nested-head :component-file (thi/new-id! :some-other-file)))

          libraries {(:id library1) library1
                     (:id library2) library2}

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % libraries))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:component-id
                              [(thi/id :some-other-id) (thi/id :component4)]
                              :component-file
                              [(thi/id :some-other-file) (thi/id :library2)]}}}}}}]

      #_(thf/dump-file library2) ;; Uncomment to debug
      (t/is (= expected-diff diff))))

  (t/testing "file with several broken ids should propagate to all copies"
    (let [file
          ;; {:main1-root} [:name Frame1]            # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]            # [Component :component2]
          ;;     :nested-head1 [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         <no-label> [:name Rect1]        ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]            # [Component :component3]
          ;;     :nested-head2 [:name Frame2]        @--> [Component <bad>] :main2-root    ## <- BAD component-id
          ;;         :nested-subhead2 [:name Frame1] @--> [Component <bad>] :nested-head1  ## <- BAD component-id
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          ;;
          ;; :copy2-root [:name Frame3]              #--> [Component :component3] :main3-root
          ;;     <no-label> [:name Frame2]           @--> [Component :component2] :nested-head2
          ;;         <no-label> [:name Frame1]       @--> [Component :component1] :nested-subhead2
          ;;             <no-label> [:name Rect1]    ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-two-levels-nested-component-with-copy :component1 :main1-root :main1-child
                                                             :component2 :main2-root :nested-head1
                                                             :component3 :main3-root :nested-head2 :nested-subhead2
                                                             :copy2-root)
              ;; Corrupt both levels
              (ths/update-shape :nested-head2 :component-id (thi/new-id! :wrong-comp2))
              (ths/update-shape :nested-subhead2 :component-id (thi/new-id! :wrong-comp3)))

          file' (ctf/update-file-data file #(cfcp/sync-component-id-with-ref-shape % {}))
          copy2-root (ths/get-shape file' :copy2-root)
          copy2-root-child1 (ths/get-shape-by-id file' (first (:shapes copy2-root)))
          copy2-root-child2 (ths/get-shape-by-id file' (first (:shapes copy2-root-child1)))

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(:id copy2-root-child1)
                             {:component-id [(thi/id :component2) (thi/id :wrong-comp2)]}
                             (:id copy2-root-child2)
                             {:component-id [(thi/id :component1) (thi/id :wrong-comp3)]}}}}}}]

      (t/is (= expected-diff diff)))))

(t/deftest test-get-swap-slot
  (let [s1 (ths/sample-shape :s1)
        s2 (ths/sample-shape :s2 :touched #{:visibility-group})
        s3 (ths/sample-shape :s3 :touched #{:swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f})
        s4 (ths/sample-shape :s4 :touched #{:fill-group
                                            :swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f})
        s5 (ths/sample-shape :s5 :touched #{:swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f
                                            :content-group
                                            :geometry-group})
        s6 (ths/sample-shape :s6 :touched #{:swap-slot-9cc181fa})]
    (t/is (nil? (cfss/get-swap-slot s1)))
    (t/is (nil? (cfss/get-swap-slot s2)))
    (t/is (= (cfss/get-swap-slot s3) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (cfss/get-swap-slot s4) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (cfss/get-swap-slot s5) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (nil? (cfss/get-swap-slot s6)))))

(t/deftest test-find-near-match

  (t/testing "shapes not in a component have no near match"
    (let [file
          ;; :frame1 [:name Frame1]
          ;;     :child1 [:name Rect1]
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          page (thf/current-page file)

          frame1 (ths/get-shape file :frame1)
          shape1 (ths/get-shape file :shape1)

          near-match1 (cfcp/find-near-match file page {} frame1)
          near-match2 (cfcp/find-near-match file page {} shape1)]

      (t/is (nil? near-match1))
      (t/is (nil? near-match2))))

  (t/testing "shapes in a copy get the ref-shape"
    (let [file
          ;;  {:main-root} [:name Frame1]            # [Component :component1]
          ;;      :main-child1 [:name Rect1]
          ;;      :main-child2 [:name Rect2]
          ;;      :main-child3 [:name Rect3]
          ;;
          ;;  :copy-root [:name Frame1]              #--> [Component :component1] :main-root
          ;;      <no-label> [:name Rect1]            ---> :main-child1
          ;;      <no-label> [:name Rect2]            ---> :main-child2
          ;;      <no-label> [:name Rect3]            ---> :main-child3
          (-> (thf/sample-file :file1)
              (tho/add-component-with-many-children-and-copy :component1
                                                             :main-root [:main-child1 :main-child2 :main-child3]
                                                             :copy-root))

          page (thf/current-page file)

          main-root (ths/get-shape file :main-root)
          main-child1 (ths/get-shape file :main-child1)
          main-child2 (ths/get-shape file :main-child2)
          main-child3 (ths/get-shape file :main-child3)
          copy-root (ths/get-shape file :copy-root)
          copy-child1 (ths/get-shape-by-id file (nth (:shapes copy-root) 0))
          copy-child2 (ths/get-shape-by-id file (nth (:shapes copy-root) 1))
          copy-child3 (ths/get-shape-by-id file (nth (:shapes copy-root) 2))

          near-main-root (cfcp/find-near-match file page {} main-root)
          near-main-child1 (cfcp/find-near-match file page {} main-child1)
          near-main-child2 (cfcp/find-near-match file page {} main-child2)
          near-main-child3 (cfcp/find-near-match file page {} main-child3)
          near-copy-root (cfcp/find-near-match file page {} copy-root)
          near-copy-child1 (cfcp/find-near-match file page {} copy-child1)
          near-copy-child2 (cfcp/find-near-match file page {} copy-child2)
          near-copy-child3 (cfcp/find-near-match file page {} copy-child3)]

      (t/is (nil? near-main-root))
      (t/is (nil? near-main-child1))
      (t/is (nil? near-main-child2))
      (t/is (nil? near-main-child3))
      (t/is (nil? near-copy-root))
      (t/is (= (:id near-copy-child1) (thi/id :main-child1)))
      (t/is (= (:id near-copy-child2) (thi/id :main-child2)))
      (t/is (= (:id near-copy-child3) (thi/id :main-child3)))))

  (t/testing "shapes in nested not swapped copies get the ref-shape"
    (let [file
          ;; {:main1-root} [:name Frame1]      # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]      # [Component :component2]
          ;;     :nested-head [:name Frame1]   @--> [Component :component1] :main1-root
          ;;         :nested-child [:name Rect1]       ---> :main1-child
          ;;
          ;; :copy2 [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame1]     @--> [Component :component1] :nested-head
          ;;         :copy2-nested-child [:name Rect1] ---> :nested-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head
                                        :nested-head-params {:children-labels [:nested-child]})
              (thc/instantiate-component :component2 :copy2
                                         :children-labels [:copy2-nested-head :copy2-nested-child]))

          page (thf/current-page file)

          main1-root (ths/get-shape file :main1-root)
          main1-child (ths/get-shape file :main1-child)
          main2-root (ths/get-shape file :main2-root)
          nested-head (ths/get-shape file :nested-head)
          nested-child (ths/get-shape file :nested-child)
          copy2 (ths/get-shape file :copy2)
          copy2-nested-head (ths/get-shape file :copy2-nested-head)
          copy2-nested-child (ths/get-shape file :copy2-nested-child)

          near-main1-root (cfcp/find-near-match file page {} main1-root)
          near-main1-child (cfcp/find-near-match file page {} main1-child)
          near-main2-root (cfcp/find-near-match file page {} main2-root)
          near-nested-head (cfcp/find-near-match file page {} nested-head)
          near-nested-child (cfcp/find-near-match file page {} nested-child)
          near-copy2 (cfcp/find-near-match file page {} copy2)
          near-copy2-nested-head (cfcp/find-near-match file page {} copy2-nested-head)
          near-copy2-nested-child (cfcp/find-near-match file page {} copy2-nested-child)]

      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested-head))
      (t/is (= (:id near-nested-child) (thi/id :main1-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested-head)))
      (t/is (= (:id near-copy2-nested-child) (thi/id :nested-child)))))

  (t/testing "shapes in swapped copies get the swap slot"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested-head [:name Frame1]        @--> [Component :component1] :main1-root
          ;;         :nested-child [:name Rect1]            ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head
                                        :nested-head-params {:children-labels [:nested-child]})
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-first-child :copy2 :component3 :as-slot? true))

          page (thf/current-page file)

          main1-root (ths/get-shape file :main1-root)
          main1-child (ths/get-shape file :main1-child)
          main2-root (ths/get-shape file :main2-root)
          nested-head (ths/get-shape file :nested-head)
          nested-child (ths/get-shape file :nested-child)
          copy2 (ths/get-shape file :copy2)
          copy2-nested-head (ths/get-shape file :copy2-nested-head)
          copy2-nested-child (ths/get-shape-by-id file (first (:shapes copy2-nested-head)))

          near-main1-root (cfcp/find-near-match file page {} main1-root)
          near-main1-child (cfcp/find-near-match file page {} main1-child)
          near-main2-root (cfcp/find-near-match file page {} main2-root)
          near-nested-head (cfcp/find-near-match file page {} nested-head)
          near-nested-child (cfcp/find-near-match file page {} nested-child)
          near-copy2 (cfcp/find-near-match file page {} copy2)
          near-copy2-nested-head (cfcp/find-near-match file page {} copy2-nested-head)
          near-copy2-nested-child (cfcp/find-near-match file page {} copy2-nested-child)]

      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested-head))
      (t/is (= (:id near-nested-child) (thi/id :main1-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested-head)))
      (t/is (= (:id near-copy2-nested-child) (thi/id :main3-child)))))

  (t/testing "shapes in second level nested copies under swapped get the shape in the new main"
    (let [file
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested2-head [:name Frame1]       @--> [Component :component1] :main1-root
          ;;         :nested2-child [:name Rect1]   ---> :main1-child
          ;;
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main4-root} [:name Frame4]           # [Component :component4]
          ;;     :nested4-head [:name Frame3]       @--> [Component :component1] :main3-root
          ;;         :nested4-child [:name Rect3]   ---> :main3-child
          ;;
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame4]  @--> [Component :component4] :main4-root
          ;;                                             {swap-slot :nested2-head}
          ;;         <no-label> [:name Frame3]      @--> :nested4-head
          ;;             <no-label> [:name Rect3]   ---> :nested4-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested2-head
                                        :nested-head-params {:children-labels [:nested2-child]})
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head])
              (tho/add-nested-component :component3 :main3-root :main3-child
                                        :component4 :main4-root :nested4-head
                                        :root1-params {:name "Frame3"}
                                        :main1-child-params {:name "Rect3"}
                                        :main2-root-params {:name "Frame4"}
                                        :nested-head-params {:children-labels [:nested4-child]})
              (tho/swap-component-in-first-child :copy2 :component4 :as-slot? true))

          page (thf/current-page file)

          main1-root (ths/get-shape file :main1-root)
          main1-child (ths/get-shape file :main1-child)
          main2-root (ths/get-shape file :main2-root)
          nested2-head (ths/get-shape file :nested2-head)
          nested2-child (ths/get-shape file :nested2-child)
          main3-root (ths/get-shape file :main3-root)
          main3-child (ths/get-shape file :main3-child)
          main4-root (ths/get-shape file :main4-root)
          nested4-head (ths/get-shape file :nested4-head)
          nested4-child (ths/get-shape file :nested4-child)
          copy2 (ths/get-shape file :copy2)
          copy2-nested-head (ths/get-shape file :copy2-nested-head)
          copy2-nested4-head (ths/get-shape-by-id file (first (:shapes copy2-nested-head)))
          copy2-nested4-child (ths/get-shape-by-id file (first (:shapes copy2-nested4-head)))

          near-main1-root (cfcp/find-near-match file page {} main1-root)
          near-main1-child (cfcp/find-near-match file page {} main1-child)
          near-main2-root (cfcp/find-near-match file page {} main2-root)
          near-nested2-head (cfcp/find-near-match file page {} nested2-head)
          near-nested2-child (cfcp/find-near-match file page {} nested2-child)
          near-main3-root (cfcp/find-near-match file page {} main3-root)
          near-main3-child (cfcp/find-near-match file page {} main3-child)
          near-main4-root (cfcp/find-near-match file page {} main4-root)
          near-nested4-head (cfcp/find-near-match file page {} nested4-head)
          near-nested4-child (cfcp/find-near-match file page {} nested4-child)
          near-copy2 (cfcp/find-near-match file page {} copy2)
          near-copy2-nested-head (cfcp/find-near-match file page {} copy2-nested-head)
          near-copy2-nested4-head (cfcp/find-near-match file page {} copy2-nested4-head)
          near-copy2-nested4-child (cfcp/find-near-match file page {} copy2-nested4-child)]

      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested2-head))
      (t/is (= (:id near-nested2-child) (thi/id :main1-child)))
      (t/is (nil? near-main3-root))
      (t/is (nil? near-main3-child))
      (t/is (nil? near-main4-root))
      (t/is (nil? near-nested4-head))
      (t/is (= (:id near-nested4-child) (thi/id :main3-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested2-head)))
      (t/is (= (:id near-copy2-nested4-head) (thi/id :nested4-head)))
      (t/is (= (:id near-copy2-nested4-child) (thi/id :nested4-child)))))

  (t/testing "component in external libraries still work well"
    (let [library1
          ;; {:main1-root} [:name Frame1]           # [Component :component1]
          ;;     :main1-child [:name Rect1]
          ;;
          ;; {:main2-root} [:name Frame2]           # [Component :component2]
          ;;     :nested2-head [:name Frame1]       @--> [Component :component1] :main1-root
          ;;         :nested2-child [:name Rect1]   ---> :main1-child
          (-> (thf/sample-file :library1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested2-head
                                        :nested-head-params {:children-labels [:nested2-child]}))
          library2
          ;; {:main3-root} [:name Frame3]           # [Component :component3]
          ;;     :main3-child [:name Rect3]
          ;;
          ;; {:main4-root} [:name Frame4]           # [Component :component4]
          ;;     :nested4-head [:name Frame3]       @--> [Component :component1] :main3-root
          ;;         :nested4-child [:name Rect3]   ---> :main3-child
          (-> (thf/sample-file :library2)
              (tho/add-nested-component :component3 :main3-root :main3-child
                                        :component4 :main4-root :nested4-head
                                        :root1-params {:name "Frame3"}
                                        :main1-child-params {:name "Rect3"}
                                        :main2-root-params {:name "Frame4"}
                                        :nested-head-params {:children-labels [:nested4-child]}))

          file
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame4]  @--> [Component :component4] :main4-root
          ;;                                             {swap-slot :nested2-head}
          ;;         <no-label> [:name Frame3]      @--> :nested4-head
          ;;             <no-label> [:name Rect3]   ---> :nested4-child
          (-> (thf/sample-file :file1)
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head]
                                         :library library1)
              (tho/swap-component-in-first-child :copy2 :component4 :library library2 :as-slot? true))

          page-library1 (thf/current-page library1)
          page-library2 (thf/current-page library2)
          page-file (thf/current-page file)
          libraries {(:id library1) library1
                     (:id library2) library2}

          main1-root (ths/get-shape library1 :main1-root)
          main1-child (ths/get-shape library1 :main1-child)
          main2-root (ths/get-shape library1 :main2-root)
          nested2-head (ths/get-shape library1 :nested2-head)
          nested2-child (ths/get-shape library1 :nested2-child)
          main3-root (ths/get-shape library2 :main3-root)
          main3-child (ths/get-shape library2 :main3-child)
          main4-root (ths/get-shape library2 :main4-root)
          nested4-head (ths/get-shape library2 :nested4-head)
          nested4-child (ths/get-shape library2 :nested4-child)
          copy2 (ths/get-shape file :copy2)
          copy2-nested-head (ths/get-shape file :copy2-nested-head)
          copy2-nested4-head (ths/get-shape-by-id file (first (:shapes copy2-nested-head)))
          copy2-nested4-child (ths/get-shape-by-id file (first (:shapes copy2-nested4-head)))

          near-main1-root (cfcp/find-near-match file page-file libraries main1-root)
          near-main1-child (cfcp/find-near-match file page-file libraries main1-child)
          near-main2-root (cfcp/find-near-match file page-file libraries main2-root)
          near-nested2-head (cfcp/find-near-match library1 page-library1 libraries nested2-head)
          near-nested2-child (cfcp/find-near-match library1 page-library1 libraries nested2-child)
          near-main3-root (cfcp/find-near-match file page-file libraries main3-root)
          near-main3-child (cfcp/find-near-match file page-file libraries main3-child)
          near-main4-root (cfcp/find-near-match file page-file libraries main4-root)
          near-nested4-head (cfcp/find-near-match library2 page-library2 libraries nested4-head)
          near-nested4-child (cfcp/find-near-match library2 page-library2 libraries nested4-child)
          near-copy2 (cfcp/find-near-match file page-file libraries copy2)
          near-copy2-nested-head (cfcp/find-near-match file page-file libraries copy2-nested-head)
          near-copy2-nested4-head (cfcp/find-near-match file page-file libraries copy2-nested4-head)
          near-copy2-nested4-child (cfcp/find-near-match file page-file libraries copy2-nested4-child)]

      (t/is (some? main1-root))
      (t/is (some? main1-child))
      (t/is (some? main2-root))
      (t/is (some? nested2-head))
      (t/is (some? nested2-child))
      (t/is (some? main3-root))
      (t/is (some? main3-child))
      (t/is (some? main4-root))
      (t/is (some? nested4-head))
      (t/is (some? nested4-child))
      (t/is (some? copy2))
      (t/is (some? copy2-nested-head))
      (t/is (some? copy2-nested4-head))
      (t/is (some? copy2-nested4-child))

      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested2-head))
      (t/is (= (:id near-nested2-child) (thi/id :main1-child)))
      (t/is (nil? near-main3-root))
      (t/is (nil? near-main3-child))
      (t/is (nil? near-main4-root))
      (t/is (nil? near-nested4-head))
      (t/is (= (:id near-nested4-child) (thi/id :main3-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested2-head)))
      (t/is (= (:id near-copy2-nested4-head) (thi/id :nested4-head)))
      (t/is (= (:id near-copy2-nested4-child) (thi/id :nested4-child))))))
