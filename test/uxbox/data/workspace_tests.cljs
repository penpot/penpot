(ns uxbox.data.workspace-tests
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer (pprint)]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw]))

(t/deftest transfer-shape-test
  (t/testing "case 1: move shape before other shape"
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2 3]}}
                   :shapes-by-id {1 {:id 1 :page 1}
                                  2 {:id 2 :page 1}
                                  3 {:id 3 :page 1}}}
          expected (assoc-in initial [:pages-by-id 1 :shapes] [3 1 2])
          event (dw/transfer-shape 3 1 :before)
          result (rs/-apply-update event initial)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 2: move shape after other shape"
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2 3]}}
                   :shapes-by-id {1 {:id 1 :page 1}
                                  2 {:id 2 :page 1}
                                  3 {:id 3 :page 1}}}
          expected (assoc-in initial [:pages-by-id 1 :shapes] [1 3 2])
          event (dw/transfer-shape 3 1 :after)
          result (rs/-apply-update event initial)]
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 3: move shape before other shape that is part of group."
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                   :shapes-by-id {1 {:id 1 :page 1
                                     :type :builtin/group
                                     :items [2]}
                                  2 {:id 2 :page 1 :group 1}
                                  3 {:id 3 :page 1}
                                  4 {:id 4 :page 1}}}
          event (dw/transfer-shape 3 2 :before)
          expected (-> initial
                       (assoc-in [:pages-by-id 1 :shapes] [1 4])
                       (assoc-in [:shapes-by-id 1 :items] [3 2])
                       (assoc-in [:shapes-by-id 3 :group] 1))
          result (rs/-apply-update event initial)]
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 4: move shape inside group"
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 3 4]}}
                   :shapes-by-id {1 {:id 1 :page 1
                                     :type :builtin/group
                                     :items [2]}
                                  2 {:id 2 :page 1 :group 1}
                                  3 {:id 3 :page 1}
                                  4 {:id 4 :page 1}}}
          event (dw/transfer-shape 3 1 :inside)
          expected (-> initial
                       (assoc-in [:pages-by-id 1 :shapes] [1 4])
                       (assoc-in [:shapes-by-id 1 :items] [2 3])
                       (assoc-in [:shapes-by-id 3 :group] 1))
          result (rs/-apply-update event initial)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 5: move shape outside of group"
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 4]}}
                   :shapes-by-id {1 {:id 1 :page 1
                                     :type :builtin/group
                                     :items [2 3]}
                                  2 {:id 2 :page 1 :group 1}
                                  3 {:id 3 :page 1 :group 1}
                                  4 {:id 4 :page 1}}}
          event (dw/transfer-shape 3 4 :after)
          expected (-> initial
                       (assoc-in [:pages-by-id 1 :shapes] [1 4 3])
                       (assoc-in [:shapes-by-id 1 :items] [2])
                       (update-in [:shapes-by-id 3] dissoc :group))
          result (rs/-apply-update event initial)]
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 6: move group inside group"
    (let [initial {:pages-by-id {1 {:id 1 :shapes [1 2]}}
                   :shapes-by-id {1 {:id 1 :page 1
                                     :type :builtin/group
                                     :items [3]}
                                  2 {:id 2 :page 1
                                     :type :builtin/group
                                     :items [4]}
                                  3 {:id 3 :page 1 :group 1}
                                  4 {:id 4 :page 1 :group 2}}}
          event (dw/transfer-shape 2 3 :after)
          expected (-> initial
                       (assoc-in [:pages-by-id 1 :shapes] [1])
                       (assoc-in [:shapes-by-id 1 :items] [3 2])
                       (assoc-in [:shapes-by-id 2 :group] 1))

          result (rs/-apply-update event initial)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))

  (t/testing "case 7: move group outside group"
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
          event (dw/transfer-shape 2 1 :after)
          expected (-> initial
                       (assoc-in [:pages-by-id 1 :shapes] [2 3])
                       (update-in [:shapes-by-id] dissoc 1)
                       (update-in [:shapes-by-id 2] dissoc :group))
          result (rs/-apply-update event initial)]
      ;; (pprint expected)
      ;; (pprint result)
      (t/is (= result expected))
      (t/is (vector? (get-in result [:pages-by-id 1 :shapes])))))
)


