(ns app.util.range-tree-test
  (:require
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]
   [app.common.geom.point :as gpt]
   [app.util.range-tree :as rt]))

(defn check-max-height [tree num-nodes])
(defn check-sorted [tree])

(defn create-random-tree [num-nodes])

(t/deftest test-insert-and-retrieve-data
  (t/testing "Retrieve on empty tree"
      (let [tree (rt/make-tree)]
        (t/is (= (rt/get tree 100) nil))))

  (t/testing "First insert/retrieval"
      (let [tree (-> (rt/make-tree)
                     (rt/insert 100 :a))]
        (t/is (= (rt/get tree 100) [:a]))
        (t/is (= (rt/get tree 200) nil))))

  (t/testing "Insert best case scenario"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50 :b)
                   (rt/insert 200 :c))]
      (t/is (= (rt/get tree 100) [:a]))
      (t/is (= (rt/get tree 50) [:b]))
      (t/is (= (rt/get tree 200) [:c]))))

  (t/testing "Insert duplicate entry"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50 :b)
                   (rt/insert 200 :c)
                   (rt/insert 50 :d)
                   (rt/insert 200 :e))]
      (t/is (= (rt/get tree 100) [:a]))
      (t/is (= (rt/get tree 50) [:b :d]))
      (t/is (= (rt/get tree 200) [:c :e])))))

(t/deftest test-remove-elements
  (t/testing "Insert and delete data but not the node"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 100 :b)
                   (rt/insert 100 :c)
                   (rt/remove 100 :b))]
      (t/is (= (rt/get tree 100) [:a :c]))))

  (t/testing "Try to delete data not in the node is noop"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 100 :b)
                   (rt/insert 100 :c)
                   (rt/remove 100 :xx))]
      (t/is (= (rt/get tree 100) [:a :b :c]))))

  (t/testing "Delete data and node"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 200 :b)
                   (rt/insert 300 :c)
                   (rt/remove 200 :b))]
      (t/is (= (rt/get tree 200) nil))))

  (t/testing "Delete root node the new tree should be correct"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50  :b)
                   (rt/insert 150 :c)
                   (rt/insert 25  :d)
                   (rt/insert 75  :e)
                   (rt/insert 125 :f)
                   (rt/insert 175 :g)
                   (rt/remove 100 :a))]

      (t/is (= (rt/get tree 100) nil))
      (t/is (= (rt/get tree 50)  [:b]))
      (t/is (= (rt/get tree 150) [:c]))
      (t/is (= (rt/get tree 25)  [:d]))
      (t/is (= (rt/get tree 75)  [:e]))
      (t/is (= (rt/get tree 125) [:f]))
      (t/is (= (rt/get tree 175) [:g]))))

  (t/testing "Adds a bunch of nodes and then delete. The tree should be empty"
    ;; Try an increase range
    (let [size 10000
          tree (rt/make-tree)
          tree (reduce #(rt/insert %1 %2 :x) tree (range 0 (dec size)))
          tree (reduce #(rt/remove %1 %2 :x) tree (range 0 (dec size)))]
      (t/is (rt/empty? tree)))

    ;; Try a decreleasing range
    (let [size 10000
          tree (rt/make-tree)
          tree (reduce #(rt/insert %1 %2 :x) tree (range (dec size) -1 -1))
          tree (reduce #(rt/remove %1 %2 :x) tree (range (dec size) -1 -1))]
      (t/is (rt/empty? tree)))))

(t/deftest test-update-elements
  (t/testing "Updates an element"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50  :b)
                   (rt/insert 150 :c)
                   (rt/insert 50  :d)
                   (rt/insert 50  :e)
                   (rt/update 50 :d :xx))]
      (t/is (= (rt/get tree 50) [:b :xx :e]))))

  (t/testing "Try to update non-existing element"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50  :b)
                   (rt/insert 150 :c)
                   (rt/insert 50  :d)
                   (rt/insert 50  :e)
                   (rt/update 50 :zz :xx))]
      (t/is (= (rt/get tree 50) [:b :d :e])))))

(t/deftest test-range-query
  (t/testing "Creates a tree and test different range queries"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 0   :a)
                   (rt/insert 25  :b)
                   (rt/insert 50  :c)
                   (rt/insert 75  :d)
                   (rt/insert 100 :e)
                   (rt/insert 100 :f)
                   (rt/insert 125 :g)
                   (rt/insert 150 :h)
                   (rt/insert 175 :i)
                   (rt/insert 200 :j)
                   (rt/insert 200 :k))]
      (t/is (= (rt/range-query tree 0 200)
               [[0   [:a]]
                [25  [:b]]
                [50  [:c]]
                [75  [:d]]
                [100 [:e :f]]
                [125 [:g]]
                [150 [:h]]
                [175 [:i]]
                [200 [:j :k]]]))
      (t/is (= (rt/range-query tree 0 100)
               [[0   [:a]]
                [25  [:b]]
                [50  [:c]]
                [75  [:d]]
                [100 [:e :f]]]))
      (t/is (= (rt/range-query tree 100 200)
               [[100 [:e :f]]
                [125 [:g]]
                [150 [:h]]
                [175 [:i]]
                [200 [:j :k]]]))
      (t/is (= (rt/range-query tree 10 60)
               [[25  [:b]]
                [50  [:c]]]))
      (t/is (= (rt/range-query tree 199.5 200.5)
               [[200 [:j :k]]]))))

  (t/testing "Empty range query"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 100 :a)
                   (rt/insert 50  :b)
                   (rt/insert 150 :c)
                   (rt/insert 25  :d)
                   (rt/insert 75  :e)
                   (rt/insert 125 :f)
                   (rt/insert 175 :g))]
      (t/is (= (rt/range-query tree -100 0) []))
      (t/is (= (rt/range-query tree 200 300) []))
      (t/is (= (rt/range-query tree 200 0) []))))

  (t/testing "Range query over null should return empty"
    (t/is (= (rt/range-query nil 0 100) []))))

(t/deftest test-balanced-tree
  (t/testing "Creates a worst-case BST and probes for a balanced height"
    (let [size 1024
          tree (reduce #(rt/insert %1 %2 :x) (rt/make-tree) (range 0 (dec size)))
          height (rt/height tree)]
      (t/is (= height (inc (js/Math.log2 size)))))))

(t/deftest test-to-string
  (t/testing "Creates a tree and prints it"
    (let [tree (-> (rt/make-tree)
                   (rt/insert 50  :a)
                   (rt/insert 25  :b)
                   (rt/insert 25  :c)
                   (rt/insert 100 :d)
                   (rt/insert 75  :e))
          result (str tree)]
      (t/is (= result "25: [:b, :c], 50: [:a], 75: [:e], 100: [:d]")))))
