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
;; Drop Shape (drag and drop and sorted)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; drop shape: move shape before other shape

(t/deftest drop-shape-test1
  (let [initial {:pages {1 {:id 1 :shapes [1 2 3]}}
                 :shapes {1 {:id 1 :page 1}
                                2 {:id 2 :page 1}
                                3 {:id 3 :page 1}}}
        expected {:pages {1 {:id 1, :shapes [3 1 2]}},
                  :shapes {1 {:id 1, :page 1},
                           2 {:id 2, :page 1},
                           3 {:id 3, :page 1}}}
        result (impl/drop-shape initial 3 1 :before)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move shape after other shape

(t/deftest drop-shape-test2
  (let [initial {:pages {1 {:id 1 :shapes [1 2 3]}}
                 :shapes {1 {:id 1 :page 1}
                          2 {:id 2 :page 1}
                          3 {:id 3 :page 1}}}

        expected {:pages {1 {:id 1, :shapes [1 3 2]}},
                  :shapes {1 {:id 1, :page 1},
                           2 {:id 2, :page 1},
                           3 {:id 3, :page 1}}}
        result (impl/drop-shape initial 3 1 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move shape before other shape that is part of group.

(t/deftest drop-shape-test3
  (let [initial {:pages {1 {:id 1 :shapes [1 3 4]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :group 1}
                          3 {:id 3 :page 1}
                          4 {:id 4 :page 1}}}

        expected {:pages {1 {:id 1, :shapes [1 4]}},
                  :shapes {1 {:id 1, :page 1, :type :group, :items [3 2]},
                           2 {:id 2, :page 1, :group 1},
                           3 {:id 3, :page 1, :group 1},
                           4 {:id 4, :page 1}}}

        result (impl/drop-shape initial 3 2 :before)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move shape inside group

(t/deftest drop-shape-test4
  (let [initial {:pages {1 {:id 1 :shapes [1 3 4]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :group 1}
                          3 {:id 3 :page 1}
                          4 {:id 4 :page 1}}}
        expected {:pages {1 {:id 1, :shapes [1 4]}},
                  :shapes {1 {:id 1, :page 1, :type :group, :items [2 3]},
                           2 {:id 2, :page 1, :group 1},
                           3 {:id 3, :page 1, :group 1},
                           4 {:id 4, :page 1}}}
        result (impl/drop-shape initial 3 1 :inside)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move shape outside of group

(t/deftest drop-shape-test5
  (let [initial {:workspace {:selected #{1}}
                 :pages {1 {:id 1 :shapes [1 3]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :group 1}
                          3 {:id 3 :page 1}}}
        expected {:workspace {:selected #{}}
                  :pages {1 {:id 1, :shapes [3 2]}},
                  :shapes {2 {:id 2, :page 1},
                           3 {:id 3, :page 1}}}
        result (impl/drop-shape initial 2 3 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move group inside group

(t/deftest drop-shape-test6
  (let [initial {:pages {1 {:id 1 :shapes [1 2]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [3]}
                          2 {:id 2 :page 1 :type :group :items [4]}
                          3 {:id 3 :page 1 :group 1}
                          4 {:id 4 :page 1 :group 2}}}
        expected {:pages {1 {:id 1, :shapes [1]}},
                  :shapes {1 {:id 1, :page 1, :type :group, :items [3 2]},
                           2 {:id 2, :page 1, :type :group, :items [4], :group 1},
                           3 {:id 3, :page 1, :group 1},
                           4 {:id 4, :page 1, :group 2}}}
        result (impl/drop-shape initial 2 3 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move group outside group

(t/deftest drop-shape-test7
  (let [initial {:workspace {:selected #{}}
                 :pages {1 {:id 1 :shapes [1 3]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :group 1 :type :group :items [4]}
                          3 {:id 3 :page 1}
                          4 {:id 4 :page 1 :group 2}}}

        expected {:workspace {:selected #{}},
                  :pages {1 {:id 1, :shapes [2 3]}},
                  :shapes {2 {:id 2, :page 1, :type :group, :items [4]},
                           3 {:id 3, :page 1},
                           4 {:id 4, :page 1, :group 2}}}
        result (impl/drop-shape initial 2 1 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages 1 :shapes])))))

;; drop shape: move shape to neested group

(t/deftest drop-shape-test8
  (let [initial {:pages {1 {:id 1 :shapes [1 5 6]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :type :group :group 1 :items [3 4]}
                          3 {:id 3 :page 1 :group 2}
                          4 {:id 4 :page 1 :group 2}
                          5 {:id 5 :page 1}
                          6 {:id 6 :page 1}}}

        expected {:pages {1 {:id 1, :shapes [1 5]}},
                  :shapes {1 {:id 1, :page 1, :type :group, :items [2]},
                           2 {:id 2, :page 1, :type :group, :group 1, :items [3 4 6]},
                           3 {:id 3, :page 1, :group 2},
                           4 {:id 4, :page 1, :group 2},
                           5 {:id 5, :page 1},
                           6 {:id 6, :page 1, :group 2}}}
        result (impl/drop-shape initial 6 4 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; drop shape: move shape to neested group

(t/deftest drop-shape-test9
  (let [initial {:pages {1 {:id 1 :shapes [1]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2 5 6]}
                          2 {:id 2 :page 1 :type :group :group 1 :items [3 4]}
                          3 {:id 3 :page 1 :group 2}
                          4 {:id 4 :page 1 :group 2}
                          5 {:id 5 :page 1 :group 1}
                          6 {:id 6 :page 1 :group 1}}}
        expected {:pages {1 {:id 1, :shapes [1]}},
                  :shapes {1 {:id 1, :page 1, :type :group, :items [2 5]},
                           2 {:id 2, :page 1, :type :group, :group 1, :items [3 4 6]},
                           3 {:id 3, :page 1, :group 2},
                           4 {:id 4, :page 1, :group 2},
                           5 {:id 5, :page 1, :group 1},
                           6 {:id 6, :page 1, :group 2}}}
        result (impl/drop-shape initial 6 4 :after)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

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

;; delete shape: delete from group

(t/deftest delete-shape-test2
  (let [initial {:workspace {:selected #{}}
                 :pages {1 {:id 1 :shapes [1 3 4]}}
                 :shapes {1 {:id 1 :page 1
                                   :type :group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        shape (get-in initial [:shapes 2])
        expected {:workspace {:selected #{}}
                  :pages {1 {:id 1 :shapes [3 4]}}
                  :shapes {3 {:id 3 :page 1}
                           4 {:id 4 :page 1}}}
        result (impl/dissoc-shape initial shape)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Group Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; group a shape

(t/deftest group-shapes-1
  (let [initial {:pages {1 {:id 1 :shapes [1 2 3]}}
                 :shapes {1 {:id 1 :page 1}
                          2 {:id 2 :page 1}
                          3 {:id 3 :page 1}}}

        expected {:pages {1 {:id 1 :shapes [1 4 3]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1 :group 4}
                           3 {:id 3 :page 1}
                           4 {:type :group :name "Group-1" :items [2] :id 4 :page 1}}
                  :workspace {:selected #{4}}}]
    (with-redefs [uxbox.util.uuid/random (constantly 4)]
      (let [result (impl/group-shapes initial [2] 1)]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; group two shapes

(t/deftest group-shapes-2
  (let [initial {:pages {1 {:id 1 :shapes [1 2 3]}}
                 :shapes {1 {:id 1 :page 1}
                                2 {:id 2 :page 1}
                                3 {:id 3 :page 1}}}


        expected {:pages {1 {:id 1 :shapes [1 4]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1 :group 4}
                           3 {:id 3 :page 1 :group 4}
                           4 {:type :group :name "Group-1" :items [2 3] :id 4 :page 1}}
                  :workspace {:selected #{4}}}]
    (with-redefs [uxbox.util.uuid/random (constantly 4)]
      (let [result (impl/group-shapes initial [2 3] 1)]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; group group

(t/deftest group-shapes-3
  (let [initial {:pages {1 {:id 1 :shapes [1 2 3]}}
                 :shapes {1 {:id 1 :page 1}
                          2 {:id 2 :page 1}
                          3 {:id 3 :page 1 :type :group}}}
        expected {:pages {1 {:id 1 :shapes [1 4]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1 :group 4}
                           3 {:id 3 :page 1 :type :group :group 4}
                           4 {:type :group :name "Group-1" :items [2 3] :id 4 :page 1}}
                  :workspace {:selected #{4}}}]
    (with-redefs [uxbox.util.uuid/random (constantly 4)]
      (let [result (impl/group-shapes initial [2 3] 1)]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; group shapes inside a group

(t/deftest group-shapes-4
  (let [initial {:pages {1 {:id 1 :shapes [1 3]}}
                 :shapes {1 {:id 1 :page 1}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :type :group}}}

        expected {:pages {1 {:id 1 :shapes [1 3]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1 :group 4}
                           3 {:id 3 :page 1 :type :group :items [4]}
                           4 {:type :group
                              :name "Group-1"
                              :items [2]
                              :id 4
                              :page 1
                              :group 3}}
                  :workspace {:selected #{4}}}]
    (with-redefs [uxbox.util.uuid/random (constantly 4)]
      (let [result (impl/group-shapes initial [2] 1)]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;; group shapes in multiple groups

(t/deftest group-shapes-5
  (let [initial {:pages {1 {:id 1 :shapes [3 4]}}
                 :shapes {1 {:id 1 :page 1 :group 4}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :type :group :items [2]}
                          4 {:id 4 :page 1 :type :group :imtes [3]}}}

        expected (-> initial
                     (assoc-in [:workspace :selected] #{5})
                     (assoc-in [:pages 1 :shapes] [5])
                     (assoc-in [:shapes 1 :group] 5)
                     (assoc-in [:shapes 2 :group] 5)
                     (assoc-in [:shapes 5] {:type :group :name "Group-1"
                                            :items [1 2] :id 5 :page 1})
                     (update-in [:shapes] dissoc 3)
                     (update-in [:shapes] dissoc 4))]
    (with-redefs [uxbox.util.uuid/random (constantly 5)]
      (let [result (impl/group-shapes initial [1 2] 1)]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Degroups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; degroup a single group

(t/deftest degroup-shapes-1-0
  (let [initial {:pages {1 {:id 1 :shapes [3]}}
                 :shapes {1 {:id 1 :page 1 :group 3}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :type :group :items [1 2]}}}
        expected {:workspace {:selected #{1 2}}
                  :pages {1 {:id 1 :shapes [1 2]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1}}}]
    (let [result (impl/degroup-shapes initial [3] 1)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected)))))

;; degroup single shape from group

(t/deftest degroup-shapes-1-1
  (let [initial {:pages {1 {:id 1 :shapes [3]}}
                 :shapes {1 {:id 1 :page 1 :group 3}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :type :group :items [1 2]}}}
        expected {:workspace {:selected #{1}}
                  :pages {1 {:id 1 :shapes [1 3]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1 :group 3}
                           3 {:id 3 :page 1 :type :group :items [2]}}}
        result (impl/degroup-shapes initial [1] 1)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))


;; degroup all shapes from group

(t/deftest degroup-shapes-1-2
  (let [initial {:pages {1 {:id 1 :shapes [3]}}
                 :shapes {1 {:id 1 :page 1 :group 3}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :type :group :items [1 2]}}}
        expected {:workspace {:selected #{1 2}}
                  :pages {1 {:id 1 :shapes [1 2]}}
                  :shapes {1 {:id 1 :page 1}
                           2 {:id 2 :page 1}}}
        result (impl/degroup-shapes initial [1 2] 1)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))


;; degroup all shapes from neested group

(t/deftest degroup-shapes-1-3
  (let [initial {:pages {1 {:id 1 :shapes [4]}}
                 :shapes {1 {:id 1 :page 1 :group 3}
                          2 {:id 2 :page 1 :group 3}
                          3 {:id 3 :page 1 :group 4 :type :group :items [1 2]}
                          4 {:id 4 :page 1 :type :group :items [3]}}}
        expected {:workspace {:selected #{1 2}}
                  :pages {1 {:id 1 :shapes [4]}}
                  :shapes {1 {:id 1 :page 1 :group 4}
                           2 {:id 2 :page 1 :group 4}
                           4 {:id 4 :page 1 :type :group :items [1 2]}}}
        result (impl/degroup-shapes initial [1 2] 1)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; degroup group inside a group

(t/deftest degroup-shapes-2
  (let [initial {:pages {1 {:id 1 :shapes [1]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [2]}
                          2 {:id 2 :page 1 :type :group :items [3] :group 1}
                          3 {:id 3 :page 1 :group 2}}}

        expected {:pages {1 {:id 1 :shapes [1]}}
                  :shapes {1 {:id 1 :page 1 :type :group :items [3]}
                           3 {:id 3 :page 1 :group 1}}
                  :workspace {:selected #{3}}}]
    (let [result (impl/degroup-shapes initial [2] 1)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected)))))

;; degroup multiple groups not nested

(t/deftest degroup-shapes-3
  (let [initial {:pages {1 {:id 1 :shapes [1 2]}}
                 :shapes {1 {:id 1 :page 1 :type :group :items [3]}
                          2 {:id 2 :page 1 :type :group :items [4]}
                          3 {:id 3 :page 1 :group 1}
                          4 {:id 4 :page 1 :group 2}}}

        expected {:pages {1 {:id 1 :shapes [3 4]}}
                  :shapes {3 {:id 3 :page 1} 4 {:id 4 :page 1}}
                  :workspace {:selected #{4 3}}}]
    (let [result (impl/degroup-shapes initial [1 2] 1)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected)))))
