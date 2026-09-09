;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.path-names-test
  (:require
   [app.common.path-names :as cpn]
   [clojure.test :as t]))

(t/deftest split-group-name
  (t/is (= ["foo" "bar"] (cpn/split-group-name "foo/bar")))
  (t/is (= ["" "foo"] (cpn/split-group-name "foo")))
  (t/is (= ["" "foo"] (cpn/split-group-name "/foo")))
  (t/is (= ["" ""] (cpn/split-group-name "")))
  (t/is (= ["" ""] (cpn/split-group-name nil))))

(t/deftest split-and-join-path
  (let [name "group/subgroup/name"
        path (cpn/split-path name :separator "/")
        name' (cpn/join-path path :separator "/" :with-spaces? false)]
    (t/is (= (first path) "group"))
    (t/is (= (second path) "subgroup"))
    (t/is (= (nth path 2) "name"))
    (t/is (= name' name))))

(t/deftest split-and-join-path-with-spaces
  (let [name "group / subgroup / name"
        path (cpn/split-path name :separator "/")]
    (t/is (= (first path) "group"))
    (t/is (= (second path) "subgroup"))
    (t/is (= (nth path 2) "name"))))

(defn- single-chain-terminal
  "Follows the first child down a single-token tree to its terminal node."
  [root]
  (loop [node root]
    (if (:children node)
      (recur (first (:children node)))
      node)))

(defn- tree-payloads
  "Collects every node and :leaf map in a tree for marker-leak checks."
  [node]
  (cons node
        (concat (when (:leaf node) [(:leaf node)])
                (mapcat tree-payloads (:children node)))))

(t/deftest build-tree-root-preserves-repeated-segments
  (t/testing "#11588: adjacent repeated path segments keep their depth"
    (let [tree     (cpn/build-tree-root [{:name "test.pointer.pointer" :id "t1"}] ".")
          terminal (single-chain-terminal (first tree))]
      (t/is (= "test" (-> tree first :name)))
      (t/is (= "pointer" (-> tree first :children first :name)))
      (t/is (nil? (-> tree first :children first :leaf)))
      (t/is (= "pointer" (:name terminal)))
      (t/is (= "test.pointer.pointer" (:path terminal)))
      (t/is (= {:name "pointer" :id "t1"} (:leaf terminal)))
      (t/is (nil? (:children terminal)))
      (t/is (every? #(not (contains? % :app.common.path-names/remaining?))
                    (tree-payloads (first tree))))))
  (t/testing "control case without repeated segments is unchanged"
    (let [tree     (cpn/build-tree-root [{:name "test.thing.other-thing" :id "t2"}] ".")
          terminal (single-chain-terminal (first tree))]
      (t/is (= "test" (-> tree first :name)))
      (t/is (= "thing" (-> tree first :children first :name)))
      (t/is (= "other-thing" (:name terminal)))
      (t/is (= {:name "other-thing" :id "t2"} (:leaf terminal)))))
  (t/testing "repeated and non-repeated siblings coexist"
    (let [tree (cpn/build-tree-root [{:name "test.pointer.pointer" :id "t1"}
                                     {:name "test.thing.other-thing" :id "t2"}] ".")
          children (-> tree first :children)]
      (t/is (= ["pointer" "thing"] (mapv :name children)))
      (t/is (= {:name "pointer" :id "t1"} (-> children first :children first :leaf)))))
  (t/testing "longer repeated runs keep every depth"
    (doseq [[path id] [["test.test.pointer" "t3"] ["test.test.test" "t4"]
                       ["a.a" "t5"] ["a.b.b" "t6"] ["a.a.a" "t8"]]]
      (let [segments (cpn/split-path path :separator ".")
            root     (first (cpn/build-tree-root [{:name path :id id}] "."))
            terminal (single-chain-terminal root)]
        (t/is (= (last segments) (:name terminal)) path)
        (t/is (= (inc (count segments)) (count (tree-payloads root))) path)
        (t/is (= {:name (last segments) :id id} (:leaf terminal)) path)))))
