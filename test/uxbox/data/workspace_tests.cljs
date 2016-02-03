(ns uxbox.data.workspace-tests
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer (pprint)]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw]))

;; delete shape: delete from page
(t/deftest delete-shape-test1
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/delete-shape 4)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 3])
                     (update-in [:shapes-by-id] dissoc 4))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; delete shape: delete from group
(t/deftest delete-shape-test2
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/delete-shape 2)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [3 4])
                     (update-in [:shapes-by-id] dissoc 2)
                     (update-in [:shapes-by-id] dissoc 1))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))))

;; drop shape: move shape before other shape
(t/deftest drop-shape-test1
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2 3]}}
                 :shapes-by-id {1 {:id 1 :page 1}
                                2 {:id 2 :page 1}
                                3 {:id 3 :page 1}}}
        expected (assoc-in initial [:pages-by-id 1 :shapes] [3 1 2])
        event (dw/drop-shape 3 1 :before)
        result (rs/-apply-update event initial)]
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
        event (dw/drop-shape 3 1 :after)
        result (rs/-apply-update event initial)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape before other shape that is part of group.
(t/deftest drop-shape-test3
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/drop-shape 3 2 :before)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4])
                     (assoc-in [:shapes-by-id 1 :items] [3 2])
                     (assoc-in [:shapes-by-id 3 :group] 1))
        result (rs/-apply-update event initial)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape inside group
(t/deftest drop-shape-test4
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1}}}
        event (dw/drop-shape 3 1 :inside)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4])
                     (assoc-in [:shapes-by-id 1 :items] [2 3])
                     (assoc-in [:shapes-by-id 3 :group] 1))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move shape outside of group
(t/deftest drop-shape-test5
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 4]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2 3]}
                                2 {:id 2 :page 1 :group 1}
                                3 {:id 3 :page 1 :group 1}
                                4 {:id 4 :page 1}}}
        event (dw/drop-shape 3 4 :after)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 4 3])
                     (assoc-in [:shapes-by-id 1 :items] [2])
                     (update-in [:shapes-by-id 3] dissoc :group))
        result (rs/-apply-update event initial)]
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move group inside group
(t/deftest drop-shape-test6
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [3]}
                                2 {:id 2 :page 1
                                   :type :builtin/group
                                   :items [4]}
                                3 {:id 3 :page 1 :group 1}
                                4 {:id 4 :page 1 :group 2}}}
        event (dw/drop-shape 2 3 :after)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1])
                     (assoc-in [:shapes-by-id 1 :items] [3 2])
                     (assoc-in [:shapes-by-id 2 :group] 1))

        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

;; drop shape: move group outside group
(t/deftest drop-shape-test7
  (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3]}}
                 :shapes-by-id {1 {:id 1 :page 1
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 2 :page 1
                                   :group 1
                                   :type :builtin/group
                                   :items [4]}
                                3 {:id 3 :page 1}
                                4 {:id 4 :page 1 :group 2}}}
        event (dw/drop-shape 2 1 :after)
        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [2 3])
                     (update-in [:shapes-by-id] dissoc 1)
                     (update-in [:shapes-by-id 2] dissoc :group))
        result (rs/-apply-update event initial)]
    ;; (pprint expected)
    ;; (pprint result)
    (t/is (= result expected))
    (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))


