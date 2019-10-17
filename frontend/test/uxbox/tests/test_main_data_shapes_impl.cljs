(ns uxbox.tests.test-main-data-shapes-impl
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [uxbox.util.uuid :as uuid]
            [uxbox.main.data.shapes :as impl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Duplicate (one shape)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn constantly-inc
  [init]
  (let [v (atom init)]
    (fn [& args]
      (let [result @v]
        (swap! v inc)
        result))))

;; duplicate shape: duplicate simple shape
(t/deftest duplicate-shapes-test1
  (let [initial {:pages {1 {:id 1 :shapes [1]}}
                 :shapes {1 {:id 1 :page 1 :name "a"}}}

        expected (-> initial
                     (assoc-in [:pages 1 :shapes] [2 1])
                     (assoc-in [:shapes 2] {:id 2 :page 1 :name "a-copy-1"}))]

    (with-redefs [uxbox.util.uuid/random (constantly 2)]
      (let [result (impl/duplicate-shapes initial [1])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; duplicate shape: duplicate inside group
(t/deftest duplicate-shapes-test2
  (let [initial {:pages {1 {:id 1 :shapes [1]}}
                 :shapes {1 {:id 1 :name "1" :page 1
                                   :type :group
                                   :items [2 3]}
                                2 {:id 2 :name "2" :page 1 :group 1}
                                3 {:id 3 :name "3" :page 1 :group 1}}}

        expected (-> initial
                     (assoc-in [:shapes 1 :items] [5 4 2 3])
                     (assoc-in [:shapes 4] {:id 4 :name "3-copy-1" :page 1 :group 1})
                     (assoc-in [:shapes 5] {:id 5 :name "2-copy-1" :page 1 :group 1}))]
    (with-redefs [uxbox.util.uuid/random (constantly-inc 4)]
      (let [result (impl/duplicate-shapes initial [2 3])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; duplicate shape: duplicate mixed bag
(t/deftest duplicate-shapes-test3
  (let [initial {:pages {1 {:id 1 :shapes [1 4]}}
                 :shapes {1 {:id 1 :name "1" :page 1
                                   :type :group
                                   :items [2 3]}
                                2 {:id 2 :name "2" :page 1 :group 1}
                                3 {:id 3 :name "3" :page 1 :group 1}
                                4 {:id 4 :name "4" :page 1}}}

        expected (-> initial
                     (assoc-in [:pages 1 :shapes] [6 5 1 4])
                     (assoc-in [:shapes 5] {:id 5 :name "4-copy-1" :page 1})
                     (assoc-in [:shapes 6] {:id 6 :name "3-copy-1" :page 1}))]
    (with-redefs [uxbox.util.uuid/random (constantly-inc 5)]
      (let [result (impl/duplicate-shapes initial [3 4])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; duplicate shape: duplicate one group
(t/deftest duplicate-shapes-test4
  (let [initial {:pages {1 {:id 1 :shapes [1]}}
                 :shapes {1 {:id 1
                             :name "1"
                             :page 1
                             :type :group
                             :items [2]}
                          2 {:id 2
                             :name "2"
                             :page 1
                             :group 1}}}

        expected (-> initial
                     (assoc-in [:pages 1 :shapes] [3 1])
                     (assoc-in [:shapes 3] {:id 3
                                            :name "1-copy-1"
                                            :page 1
                                            :type :group
                                            :items [4]})
                     (assoc-in [:shapes 4] {:id 4
                                            :name "2-copy-1"
                                            :page 1
                                            :group 3}))]
    (with-redefs [uxbox.util.uuid/random (constantly-inc 3)]
      (let [result (impl/duplicate-shapes initial [1])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Delete Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; delete shape: delete from page
(t/deftest delete-shape-test1
  (let [initial {:pages {1 {:id 1 :shapes [1 3 4]}}
                 :shapes {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}

        shape (get-in initial [:shapes 4])
        expected {:pages {1 {:id 1 :shapes [1 3]}}
                  :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                           2 {:id 2 :page 1 :group 1}
                           3 {:id 3 :page 1}}}

        result (impl/dissoc-shape initial shape)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))
