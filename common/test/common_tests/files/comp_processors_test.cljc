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
   [clojure.test :as t]))

(t/deftest test-fix-missing-swap-slots

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
    (println "==start test==================================================")
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

          copy2-nested-head' (ths/get-shape file' :copy2-nested-head)]

      (thf/dump-file file :keys [:name :swap-slot-label] :show-refs? true)
      (println "====================================================")
      (prn "diff" diff)
      (t/is (= (ctk/get-swap-slot copy2-nested-head') (thi/id :nested-head))))))
