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
          file' (cfcp/remove-unneeded-objects-in-components file)]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (cfcp/remove-unneeded-objects-in-components file)]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file without components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          file' (cfcp/remove-unneeded-objects-in-components file)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with non deleted components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1))

          file' (cfcp/remove-unneeded-objects-in-components file)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with deleted components should not need any action"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (tho/delete-shape :frame1))

          file' (cfcp/remove-unneeded-objects-in-components file)]

      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file with non deleted components with :objects nil should remove it"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (thc/update-component :component1 {:objects nil}))

          file' (cfcp/remove-unneeded-objects-in-components file)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {}}}}]

      (t/is (= diff expected-diff))))

  (t/testing "file with non deleted components with :objects should remove it"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (thc/update-component :component1 {:objects {:sample 777}}))

          file' (cfcp/remove-unneeded-objects-in-components file)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {:objects
                            [{:sample 777} nil]}}}}]

      (t/is (= diff expected-diff))))

  (t/testing "file with deleted components without :objects should add an empty one"
    (let [file
          (-> (thf/sample-file :file1)
              (tho/add-simple-component :component1 :frame1 :shape1)
              (tho/delete-shape :frame1)
              (ctf/update-file-data
               (fn [file-data]
                 (ctkl/update-component file-data (thi/id :component1) #(dissoc % :objects)))))

          file' (cfcp/remove-unneeded-objects-in-components file)

          diff (d/map-diff file file')

          expected-diff {:data
                         {:components
                          {(thi/id :component1)
                           {:objects
                            [nil {}]}}}}]

      (t/is (= diff expected-diff)))))

(t/deftest test-fix-missing-swap-slots

  (t/testing "nil file should return nil"
    (let [file  nil
          file' (cfcp/fix-missing-swap-slots file {})]
      (t/is (nil? file'))))

  (t/testing "empty file should not need any action"
    (let [file  (thf/sample-file :file1)
          file' (cfcp/fix-missing-swap-slots file {})]
      (t/is (empty? (d/map-diff file file')))))

  (t/testing "file without components should not need any action"
    (let [file
          ;; :frame1 [:name Frame1]
          ;;     :child1 [:name Rect1]
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))

          file' (cfcp/fix-missing-swap-slots file {})]

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
          ;; :copy2 [:name Frame2]             #--> [Component :component2] :main2-root
          ;;     <no-label> [:name Frame1]           @--> [Component :component1] :nested-head
          ;;         <no-label> [:name Rect1]        ---> <no-label>
          (-> (thf/sample-file :file1)
              (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                  :component2 :main2-root :nested-head
                                                  :copy2 :copy2-root))

          file' (cfcp/fix-missing-swap-slots file {})]

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
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             {swap-slot :nested-head}
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-first-child :copy2 :component3))

          file' (cfcp/fix-missing-swap-slots file {})]

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
          ;; :copy2 [:name Frame2]                  #--> [Component :component2] :main2-root
          ;;     :copy2-nested-head [:name Frame3]  @--> [Component :component3] :main3-root
          ;;                                             NO SWAP SLOT
          ;;         <no-label> [:name Rect3]        ---> :main3-child
          (-> (thf/sample-file :file1)
              (tho/add-nested-component :component1 :main1-root :main1-child
                                        :component2 :main2-root :nested-head)
              (thc/instantiate-component :component2 :copy2 :children-labels [:copy2-nested-head])
              (tho/add-simple-component :component3 :main3-root :main3-child
                                        :root-params {:name "Frame3"}
                                        :child-params {:name "Rect3"})
              (tho/swap-component-in-first-child :copy2 :component3)
              (ths/update-shape :copy2-nested-head :touched nil))

          file' (cfcp/fix-missing-swap-slots file {})

          diff (d/map-diff file file')

          expected-diff {:data
                         {:pages-index
                          {(thf/current-page-id file)
                           {:objects
                            {(thi/id :copy2-nested-head)
                             {:touched
                              [nil
                               #{(ctk/build-swap-slot-group (str (thi/id :nested-head)))}]}}}}}}]

      (t/is (= diff expected-diff)))))
