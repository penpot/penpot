;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.comp-processors-test
  (:require
  [app.common.files.comp-processors :as cfcp]
;;    [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
;;    [app.common.test-helpers.ids-map :as thi]
;;    [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/deftest test-fix-missing-swap-slots

  (t/testing "empty file should not need any action"
    (let [file (thf/sample-file :file1)]
      (t/is (= file (cfcp/fix-missing-swap-slots file)))))

  (t/testing "file without components should not need any action"
    (let [file
          ;; :frame1 [:name Frame1]
          ;;     :child1 [:name Rect1]
          (-> (thf/sample-file :file1)
              (tho/add-frame-with-child :frame1 :shape1))]

      (t/is (= file (cfcp/fix-missing-swap-slots file)))))
  
  (t/testing "file with not swapped components should not need any action"
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
                                                  :copy2 :copy2-root))]

      (t/is (= file (cfcp/fix-missing-swap-slots file)))))
  
  
  )
