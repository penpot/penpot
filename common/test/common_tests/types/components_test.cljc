;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.components-test
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-valid-touched-group
  (t/is (ctk/valid-touched-group? :name-group))
  (t/is (ctk/valid-touched-group? :geometry-group))
  (t/is (ctk/valid-touched-group? :swap-slot-9cc181fa-5eef-8084-8004-7bb2ab45fd1f))
  (t/is (not (ctk/valid-touched-group? :this-is-not-a-group)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-xxxxxx)))
  (t/is (not (ctk/valid-touched-group? :swap-slot-9cc181fa-5eef-8084-8004)))
  (t/is (not (ctk/valid-touched-group? nil))))

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
    (t/is (nil? (ctk/get-swap-slot s1)))
    (t/is (nil? (ctk/get-swap-slot s2)))
    (t/is (= (ctk/get-swap-slot s3) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (ctk/get-swap-slot s4) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (= (ctk/get-swap-slot s5) #uuid "9cc181fa-5eef-8084-8004-7bb2ab45fd1f"))
    (t/is (nil? (ctk/get-swap-slot s6)))))

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
          near-match1 (ctf/find-near-match file page {} frame1)
          near-match2 (ctf/find-near-match file page {} shape1)]

      (t/is (nil? near-match1))
      (t/is (nil? near-match2))))

  (t/testing "shapes in nested not swapped components get the ref-shape"
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

          near-main1-root (ctf/find-near-match file page {} main1-root)
          near-main1-child (ctf/find-near-match file page {} main1-child)
          near-main2-root (ctf/find-near-match file page {} main2-root)
          near-nested-head (ctf/find-near-match file page {} nested-head)
          near-nested-child (ctf/find-near-match file page {} nested-child)
          near-copy2 (ctf/find-near-match file page {} copy2)
          near-copy2-nested-head (ctf/find-near-match file page {} copy2-nested-head)
          near-copy2-nested-child (ctf/find-near-match file page {} copy2-nested-child)]

      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested-head))
      (t/is (= (:id near-nested-child) (thi/id :main1-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested-head)))
      (t/is (= (:id near-copy2-nested-child) (thi/id :nested-child)))))

  (t/testing "shapes in nested swapped components get the swap slot"
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
              (tho/swap-component-in-first-child :copy2 :component3)
              (ths/update-shape :copy2-nested-head :touched nil))

          page (thf/current-page file)
          main1-root (ths/get-shape file :main1-root)
          main1-child (ths/get-shape file :main1-child)
          main2-root (ths/get-shape file :main2-root)
          nested-head (ths/get-shape file :nested-head)
          nested-child (ths/get-shape file :nested-child)
          copy2 (ths/get-shape file :copy2)
          copy2-nested-head (ths/get-shape file :copy2-nested-head)
          copy2-nested-child (ths/get-shape-by-id file (first (:shapes copy2-nested-head)))

          near-main1-root (ctf/find-near-match file page {} main1-root)
          near-main1-child (ctf/find-near-match file page {} main1-child)
          near-main2-root (ctf/find-near-match file page {} main2-root)
          near-nested-head (ctf/find-near-match file page {} nested-head)
          near-nested-child (ctf/find-near-match file page {} nested-child)
          near-copy2 (ctf/find-near-match file page {} copy2)
          near-copy2-nested-head (ctf/find-near-match file page {} copy2-nested-head)
          near-copy2-nested-child (ctf/find-near-match file page {} copy2-nested-child)]

      (thf/dump-file file :keys [:name :swap-slot-label] :show-refs? true)
      (t/is (nil? near-main1-root))
      (t/is (nil? near-main1-child))
      (t/is (nil? near-main2-root))
      (t/is (nil? near-nested-head))
      (t/is (= (:id near-nested-child) (thi/id :main1-child)))
      (t/is (nil? near-copy2))
      (t/is (= (:id near-copy2-nested-head) (thi/id :nested-head)))
      (t/is (= (:id near-copy2-nested-child) (thi/id :main3-child))))))
