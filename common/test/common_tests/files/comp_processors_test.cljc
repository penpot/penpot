;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.comp-processors-test
  (:require
   [app.common.data :as d]
   [app.common.files.comp-processors :as cfcp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
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
              (tho/swap-component-in-first-child :copy2-root :component3))

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
                                           :propagate-fn #(tho/propagate-component-changes % :component2)))

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
              (tho/swap-component-in-first-child :copy2-root :component3)
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
                               #{(ctk/build-swap-slot-group (str (thi/id :nested-head)))}]}}}}}}]

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
                                           :propagate-fn #(tho/propagate-component-changes % :component2))
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
                                           :propagate-fn #(tho/propagate-component-changes % :component3))
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
                               #{(ctk/build-swap-slot-group (str (thi/id :nested-head1)))}]}}}}}}]

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
              (tho/swap-component-in-first-child :copy2 :component4 :library library2)
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
                               #{(ctk/build-swap-slot-group (str (thi/id :nested2-head)))}]}}}}}}]

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
              (tho/swap-component-in-first-child :copy3-root :component3))

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
                                           :propagate-fn #(tho/propagate-component-changes % :component2))
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
          (tho/swap-component-in-shape library1 :nested2-head :component4 :library library2)

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

      (thf/dump-file file') ;; Uncomment to debug
      (t/is (= expected-diff diff)))))

