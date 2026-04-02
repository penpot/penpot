;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-modif-tree-test
  (:require
   [app.common.geom.modif-tree :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest add-modifiers-empty-test
  (t/testing "Adding empty modifiers does not change the tree"
    (let [id   (uuid/next)
          tree (gmt/add-modifiers {} id (ctm/empty))]
      (t/is (empty? tree))))

  (t/testing "Adding empty modifiers to existing tree keeps it unchanged"
    (let [id1  (uuid/next)
          id2  (uuid/next)
          mods (ctm/move-modifiers (gpt/point 10 10))
          tree {id1 {:modifiers mods}}
          result (gmt/add-modifiers tree id2 (ctm/empty))]
      (t/is (= 1 (count result)))
      (t/is (contains? result id1)))))

(t/deftest add-modifiers-nonempty-test
  (t/testing "Adding non-empty modifiers creates entry"
    (let [id   (uuid/next)
          mods (ctm/move-modifiers (gpt/point 10 20))
          tree (gmt/add-modifiers {} id mods)]
      (t/is (= 1 (count tree)))
      (t/is (contains? tree id))
      (t/is (some? (get-in tree [id :modifiers])))))

  (t/testing "Adding modifiers to existing id merges them"
    (let [id     (uuid/next)
          mods1  (ctm/move-modifiers (gpt/point 10 10))
          mods2  (ctm/move-modifiers (gpt/point 5 5))
          tree   (gmt/add-modifiers {} id mods1)
          result (gmt/add-modifiers tree id mods2)]
      (t/is (= 1 (count result)))
      (t/is (contains? result id)))))

(t/deftest merge-modif-tree-test
  (t/testing "Merge two separate modif-trees"
    (let [id1   (uuid/next)
          id2   (uuid/next)
          tree1 (gmt/add-modifiers {} id1 (ctm/move-modifiers (gpt/point 10 10)))
          tree2 (gmt/add-modifiers {} id2 (ctm/move-modifiers (gpt/point 20 20)))
          result (gmt/merge-modif-tree tree1 tree2)]
      (t/is (= 2 (count result)))
      (t/is (contains? result id1))
      (t/is (contains? result id2))))

  (t/testing "Merge with overlapping ids merges modifiers"
    (let [id    (uuid/next)
          tree1 (gmt/add-modifiers {} id (ctm/move-modifiers (gpt/point 10 10)))
          tree2 (gmt/add-modifiers {} id (ctm/move-modifiers (gpt/point 5 5)))
          result (gmt/merge-modif-tree tree1 tree2)]
      (t/is (= 1 (count result)))
      (t/is (contains? result id))))

  (t/testing "Merge with empty tree returns original"
    (let [id    (uuid/next)
          tree1 (gmt/add-modifiers {} id (ctm/move-modifiers (gpt/point 10 10)))
          result (gmt/merge-modif-tree tree1 {})]
      (t/is (= tree1 result))))

  (t/testing "Merge empty with non-empty returns the non-empty"
    (let [id    (uuid/next)
          tree2 (gmt/add-modifiers {} id (ctm/move-modifiers (gpt/point 10 10)))
          result (gmt/merge-modif-tree {} tree2)]
      (t/is (= tree2 result)))))
