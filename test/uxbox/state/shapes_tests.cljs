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
                                   :type :builtin/group
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
                                   :type :builtin/group
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
                                   :type :builtin/group
                                   :items [2]}
                                2 {:id 3 :page 1 :group 1}}}

        expected (-> initial
                     (assoc-in [:pages-by-id 1 :shapes] [1 3])
                     (assoc-in [:shapes-by-id 3] {:id 3 :page 1
                                                  :type :builtin/group
                                                  :items [4]})
                     (assoc-in [:shapes-by-id 4] {:id 4 :page 1 :group 3}))]
    (with-redefs [cljs.core/random-uuid (constantly-inc 3)]
      (let [result (ssh/duplicate-shapes initial [1])]
        ;; (pprint expected)
        ;; (pprint result)
        (t/is (= result expected))))))
