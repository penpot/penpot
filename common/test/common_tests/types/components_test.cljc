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

          near-main-root (ctf/find-near-match file page {} main-root)
          near-main-child1 (ctf/find-near-match file page {} main-child1)
          near-main-child2 (ctf/find-near-match file page {} main-child2)
          near-main-child3 (ctf/find-near-match file page {} main-child3)
          near-copy-root (ctf/find-near-match file page {} copy-root)
          near-copy-child1 (ctf/find-near-match file page {} copy-child1)
          near-copy-child2 (ctf/find-near-match file page {} copy-child2)
          near-copy-child3 (ctf/find-near-match file page {} copy-child3)]

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
              (tho/swap-component-in-first-child :copy2 :component3))

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
              (tho/swap-component-in-first-child :copy2 :component4))

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

          near-main1-root (ctf/find-near-match file page {} main1-root)
          near-main1-child (ctf/find-near-match file page {} main1-child)
          near-main2-root (ctf/find-near-match file page {} main2-root)
          near-nested2-head (ctf/find-near-match file page {} nested2-head)
          near-nested2-child (ctf/find-near-match file page {} nested2-child)
          near-main3-root (ctf/find-near-match file page {} main3-root)
          near-main3-child (ctf/find-near-match file page {} main3-child)
          near-main4-root (ctf/find-near-match file page {} main4-root)
          near-nested4-head (ctf/find-near-match file page {} nested4-head)
          near-nested4-child (ctf/find-near-match file page {} nested4-child)
          near-copy2 (ctf/find-near-match file page {} copy2)
          near-copy2-nested-head (ctf/find-near-match file page {} copy2-nested-head)
          near-copy2-nested4-head (ctf/find-near-match file page {} copy2-nested4-head)
          near-copy2-nested4-child (ctf/find-near-match file page {} copy2-nested4-child)]

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
              (tho/swap-component-in-first-child :copy2 :component4 :library library2))

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

          near-main1-root (ctf/find-near-match file page-file libraries main1-root)
          near-main1-child (ctf/find-near-match file page-file libraries main1-child)
          near-main2-root (ctf/find-near-match file page-file libraries main2-root)
          near-nested2-head (ctf/find-near-match library1 page-library1 libraries nested2-head)
          near-nested2-child (ctf/find-near-match library1 page-library1 libraries nested2-child)
          near-main3-root (ctf/find-near-match file page-file libraries main3-root)
          near-main3-child (ctf/find-near-match file page-file libraries main3-child)
          near-main4-root (ctf/find-near-match file page-file libraries main4-root)
          near-nested4-head (ctf/find-near-match library2 page-library2 libraries nested4-head)
          near-nested4-child (ctf/find-near-match library2 page-library2 libraries nested4-child)
          near-copy2 (ctf/find-near-match file page-file libraries copy2)
          near-copy2-nested-head (ctf/find-near-match file page-file libraries copy2-nested-head)
          near-copy2-nested4-head (ctf/find-near-match file page-file libraries copy2-nested4-head)
          near-copy2-nested4-child (ctf/find-near-match file page-file libraries copy2-nested4-child)]

      (thf/dump-file library1 :keys [:name :swap-slot-label] :show-refs? true)
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
