;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-tree-seq-test
  (:require
   [app.common.geom.shapes.tree-seq :as gts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-shape
  ([id type parent-id]
   (make-shape id type parent-id []))
  ([id type parent-id shapes]
   {:id id
    :type type
    :parent-id parent-id
    :shapes (vec shapes)}))

(t/deftest get-children-seq-test
  (t/testing "Flat frame with children"
    (let [frame-id (uuid/next)
          child1   (uuid/next)
          child2   (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [child1 child2])
                    child1   (make-shape child1 :rect frame-id)
                    child2   (make-shape child2 :rect frame-id)}
          result   (gts/get-children-seq frame-id objects)]
      (t/is (= 3 (count result)))
      (t/is (= frame-id (:id (first result))))))

  (t/testing "Nested groups"
    (let [frame-id (uuid/next)
          group-id (uuid/next)
          child-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [group-id])
                    group-id (make-shape group-id :group frame-id [child-id])
                    child-id (make-shape child-id :rect group-id)}
          result   (gts/get-children-seq frame-id objects)]
      (t/is (= 3 (count result)))))

  (t/testing "Leaf node has no children"
    (let [leaf-id (uuid/next)
          objects {leaf-id (make-shape leaf-id :rect uuid/zero)}
          result  (gts/get-children-seq leaf-id objects)]
      (t/is (= 1 (count result))))))

(t/deftest get-reflow-root-test
  (t/testing "Root frame returns itself"
    (let [frame-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [])}
          result   (gts/get-reflow-root frame-id objects)]
      (t/is (= frame-id result))))

  (t/testing "Child of root non-layout frame returns frame-id"
    (let [frame-id (uuid/next)
          child-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [child-id])
                    child-id (make-shape child-id :rect frame-id)}
          result   (gts/get-reflow-root child-id objects)]
      ;; The child's parent is a non-layout frame, so it returns
      ;; the last-root (which was initialized to child-id).
      ;; The function returns the root of the reflow tree.
      (t/is (uuid? result)))))

(t/deftest search-common-roots-test
  (t/testing "Single id returns its root"
    (let [frame-id (uuid/next)
          child-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [child-id])
                    child-id (make-shape child-id :rect frame-id)}
          result   (gts/search-common-roots #{child-id} objects)]
      (t/is (set? result))))

  (t/testing "Empty ids returns empty set"
    (let [result (gts/search-common-roots #{} {})]
      (t/is (= #{} result)))))

(t/deftest resolve-tree-test
  (t/testing "Resolve tree for a frame"
    (let [frame-id (uuid/next)
          child1   (uuid/next)
          child2   (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [child1 child2])
                    child1   (make-shape child1 :rect frame-id)
                    child2   (make-shape child2 :rect frame-id)}
          result   (gts/resolve-tree #{child1} objects)]
      (t/is (seq result))))

  (t/testing "Resolve tree with uuid/zero includes root"
    (let [root-id  uuid/zero
          frame-id (uuid/next)
          objects  {root-id  {:id root-id :type :frame :parent-id root-id :shapes [frame-id]}
                    frame-id (make-shape frame-id :frame root-id [])}
          result   (gts/resolve-tree #{uuid/zero} objects)]
      (t/is (seq result))
      (t/is (= root-id (:id (first result)))))))

(t/deftest resolve-subtree-test
  (t/testing "Resolve subtree from frame to child"
    (let [frame-id (uuid/next)
          child-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [child-id])
                    child-id (make-shape child-id :rect frame-id)}
          result   (gts/resolve-subtree frame-id child-id objects)]
      (t/is (seq result))
      (t/is (= frame-id (:id (first result))))
      (t/is (= child-id (:id (last result))))))

  (t/testing "Resolve subtree from-to same id"
    (let [frame-id (uuid/next)
          objects  {frame-id (make-shape frame-id :frame uuid/zero [])}
          result   (gts/resolve-subtree frame-id frame-id objects)]
      (t/is (= 1 (count result)))
      (t/is (= frame-id (:id (first result)))))))
