(ns uxbox.test-util-range-tree
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.range-tree :as rt]))

(defn check-max-height [tree num-nodes])
(defn check-sorted [tree])

(defn create-random-tree [num-nodes])

(t/deftest test-insert-and-retrive-data
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
      (t/is (= (rt/get tree 175) [:g])))))

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
      (t/is (= (rt/range-query tree 0 200) [:a :b :c :d :e :f :g :h :i :j :k]))
      (t/is (= (rt/range-query tree 0 100) [:a :b :c :d :e :f]))
      (t/is (= (rt/range-query tree 100 200) [:e :f :g :h :i :j :k]))
      (t/is (= (rt/range-query tree 10 60) [:b :c]))
      (t/is (= (rt/range-query tree 199.5 200.5) [:j :k]))))

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
      (t/is (= (rt/range-query tree 200 0) [])))))

