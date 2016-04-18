(ns uxbox.state.shapes-tests
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer (pprint)]
            [uxbox.state.shapes :as ssh]))

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
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1]}}
                 :shapes-by-id {1 {:id 1 :page 1}}}

        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 2])
                     (assoc-in [:shapes-by-id 2] {:id 2 :page 1}))]

    (with-redefs [cljs.core/random-uuid (constantly 2)]
      (let [result (ssh/duplicate-shapes initial [1])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))


;; duplicate shape: duplicate inside group
(t/deftest duplicate-shapes-test2
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2 3]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1 :group 1}}}

        expected (-> initial
                     (assoc-in [:shapes-by-id 1 :items] [2 3 4 5])
                     (assoc-in [:shapes-by-id 4] {:id 4 :page 1 :group 1})
                     (assoc-in [:shapes-by-id 5] {:id 5 :page 1 :group 1}))]
    (with-redefs [cljs.core/random-uuid (constantly-inc 4)]
      (let [result (ssh/duplicate-shapes initial [2 3])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))


;; duplicate shape: duplicate mixed bag
(t/deftest duplicate-shapes-test3
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2 3]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1 :group 1}
                                4 {:id 4 :page 1}}}

        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4 5 6])
                     (assoc-in [:shapes-by-id 5] {:id 5 :page 1})
                     (assoc-in [:shapes-by-id 6] {:id 6 :page 1}))]
    (with-redefs [cljs.core/random-uuid (constantly-inc 5)]
      (let [result (ssh/duplicate-shapes initial [3 4])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))


;; duplicate shape: duplicate one group
(t/deftest duplicate-shapes-test4
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 3 :page 1 :group 1}}}

        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 3])
                     (assoc-in [:shapes-by-id 3] {:id 3 :page 1
                                                  :type :group
                                                  :items [4]})
                     (assoc-in [:shapes-by-id 4] {:id 4 :page 1 :group 3}))]
    (with-redefs [cljs.core/random-uuid (constantly-inc 3)]
      (let [result (ssh/duplicate-shapes initial [1])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Drop Shape (drag and drop and sorted)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; drop shape: move shape before other shape
(t/deftest drop-shape-test1
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2 3]}}
                 :shapes-by-id {1 {:id 1 :page 1}
                                2 {:id 2 :page 1}
                                3 {:id 3 :page 1}}}
        expected (assoc-in initial [:pages-by-id 1 :shapes] [3 1 2])
        result (ssh/drop-shape initial 3 1 :before)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape after other shape
(t/deftest drop-shape-test2
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2 3]}}
                 :shapes-by-id {1 {:id 1 :page 1}
                                2 {:id 2 :page 1}
                                3 {:id 3 :page 1}}}
        expected (assoc-in initial [:pages-by-id 1 :shapes] [1 3 2])
        result (ssh/drop-shape initial 3 1 :after)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape before other shape that is part of group.
(t/deftest drop-shape-test3
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4])
                     (assoc-in [:shapes-by-id 1 :items] [3 2])
                     (assoc-in [:shapes-by-id 3 :group] 1))
        result (ssh/drop-shape initial 3 2 :before)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape inside group
(t/deftest drop-shape-test4
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4])
                     (assoc-in [:shapes-by-id 1 :items] [2 3])
                     (assoc-in [:shapes-by-id 3 :group] 1))
        result (ssh/drop-shape initial 3 1 :inside)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape outside of group
(t/deftest drop-shape-test5
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2 3]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1 :group 1}
                                4 {:id 4 :page 1}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4 3])
                     (assoc-in [:shapes-by-id 1 :items] [2])
                     (update-in [:shapes-by-id 3] dissoc :group))
        result (ssh/drop-shape initial 3 4 :after)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move group inside group
(t/deftest drop-shape-test6
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [3]}
                                2 {:id 2 :page 1
                                   :type :group
                                   :items [4]}
                                3 {:id 3 :page 1 :group 1}
                                4 {:id 4 :page 1 :group 2}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1])
                     (assoc-in [:shapes-by-id 1 :items] [3 2])
                     (assoc-in [:shapes-by-id 2 :group] 1))
        result (ssh/drop-shape initial 2 3 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move group outside group
(t/deftest drop-shape-test7
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1
                                   :group 1
                                   :type :group
                                   :items [4]}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1 :group 2}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [2 3])
                     (update-in [:shapes-by-id] dissoc 1)
                     (update-in [:shapes-by-id 2] dissoc :group))
        result (ssh/drop-shape initial 2 1 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape to neested group
(t/deftest drop-shape-test8
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 5 6]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1
                                   :group 1
                                   :type :group
                                   :items [3 4]}
                                3 {:id 3 :page 1 :group 2}
                                4 {:id 4 :page 1 :group 2}
                                5 {:id 5 :page 1}
                                6 {:id 6 :page 1}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 5])
                     (update-in [:shapes-by-id 2 :items] conj 6)
                     (update-in [:shapes-by-id 6] assoc :group 2))
        result (ssh/drop-shape initial 6 4 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; drop shape: move shape to neested group
(t/deftest drop-shape-test9
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2 5 6]}
                                2 {:id 2 :page 1
                                   :group 1
                                   :type :group
                                   :items [3 4]}
                                3 {:id 3 :page 1 :group 2}
                                4 {:id 4 :page 1 :group 2}
                                5 {:id 5 :page 1 :group 1}
                                6 {:id 6 :page 1 :group 1}}}
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1])
                     (assoc-in [:shapes-by-id 2 :items] [3 4 6])
                     (assoc-in [:shapes-by-id 1 :items] [2 5])
                     (update-in [:shapes-by-id 6] assoc :group 2))
        result (ssh/drop-shape initial 6 4 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Delete Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; delete shape: delete from page

(t/deftest delete-shape-test1
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}

        shape (get-in initial [:shapes-by-id 4])
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 3])
                     (update-in [:shapes-by-id] dissoc 4))
        result (ssh/dissoc-shape initial shape)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; delete shape: delete from group
(t/deftest delete-shape-test2
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        shape (get-in initial [:shapes-by-id 2])
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [3 4])
                     (update-in [:shapes-by-id] dissoc 2)
                     (update-in [:shapes-by-id] dissoc 1))
        result (ssh/dissoc-shape initial shape)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))
