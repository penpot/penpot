;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.container-test
  (:require
   [app.common.types.container :as ctc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-shape
  "Build a realistic shape using setup-shape, so it has proper geometric
  data (selrect, points, transform, …) and follows project data standards."
  [id & {:as attrs}]
  (cts/setup-shape (merge {:type :rect
                           :x 0
                           :y 0
                           :width 100
                           :height 100}
                          attrs
                          {:id id})))

(defn- objects-map
  "Build an objects map from a seq of shapes."
  [& shapes]
  (into {} (map (juxt :id identity) shapes)))

;; The sentinel root shape (uuid/zero) recognised by cfh/root?
(def root-id uuid/zero)

(defn- root-shape
  "Create the page-root frame shape (id = uuid/zero, type :frame)."
  []
  (cts/setup-shape {:id root-id
                    :type :frame
                    :x 0
                    :y 0
                    :width 100
                    :height 100}))

;; ---------------------------------------------------------------------------
;; Tests – base cases
;; ---------------------------------------------------------------------------

(t/deftest find-component-main-nil-shape
  (t/testing "returns nil when shape is nil"
    (t/is (nil? (ctc/find-component-main {} nil)))))

(t/deftest find-component-main-root-shape
  (t/testing "returns nil when shape is the page root (uuid/zero)"
    (let [root   (root-shape)
          objects (objects-map root)]
      (t/is (nil? (ctc/find-component-main objects root))))))

(t/deftest find-component-main-no-parent-id
  (t/testing "returns the shape itself when parent-id is nil (v1 component root)"
    (let [id    (uuid/next)
          ;; Simulate a v1 component root: setup-shape produces a full shape,
          ;; then we explicitly clear :parent-id to nil, which is how legacy
          ;; component roots appear in deserialized data.
          shape (assoc (make-shape id) :parent-id nil)
          objects (objects-map shape)]
      (t/is (= shape (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-main-instance
  (t/testing "returns the shape when it is a main-instance"
    (let [parent-id (uuid/next)
          id        (uuid/next)
          parent    (make-shape parent-id)
          shape     (make-shape id :parent-id parent-id :main-instance true)
          objects   (objects-map parent shape)]
      (t/is (= shape (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-instance-head-stops-when-only-direct-child
  (t/testing "returns nil when hitting an instance-head that is not main (only-direct-child? true)"
    (let [parent-id   (uuid/next)
          id          (uuid/next)
          ;; instance-head? ← has :component-id but NOT :main-instance
          shape       (make-shape id
                                  :parent-id parent-id
                                  :component-id (uuid/next))
          parent      (make-shape parent-id)
          objects     (objects-map parent shape)]
      (t/is (nil? (ctc/find-component-main objects shape true))))))

(t/deftest find-component-main-instance-root-stops-when-not-only-direct-child
  (t/testing "returns nil when hitting an instance-root and only-direct-child? is false"
    (let [parent-id   (uuid/next)
          id          (uuid/next)
          ;; instance-root? ← has :component-root true
          shape       (make-shape id
                                  :parent-id parent-id
                                  :component-id (uuid/next)
                                  :component-root true)
          parent      (make-shape parent-id)
          objects     (objects-map parent shape)]
      (t/is (nil? (ctc/find-component-main objects shape false))))))

(t/deftest find-component-main-walks-to-main-ancestor
  (t/testing "traverses ancestors and returns the first main-instance found"
    (let [gp-id    (uuid/next)
          p-id     (uuid/next)
          child-id (uuid/next)
          grandparent (make-shape gp-id :parent-id nil :main-instance true)
          parent      (make-shape p-id  :parent-id gp-id)
          child       (make-shape child-id :parent-id p-id)
          objects     (objects-map grandparent parent child)]
      (t/is (= grandparent (ctc/find-component-main objects child))))))

;; ---------------------------------------------------------------------------
;; Tests – cycle detection (the bug fix)
;; ---------------------------------------------------------------------------

(t/deftest find-component-main-direct-self-loop
  (t/testing "returns nil (no crash) when a shape's parent-id points to itself"
    (let [id    (uuid/next)
          ;; deliberately malformed: parent-id == id (self-loop)
          shape (make-shape id :parent-id id)
          objects (objects-map shape)]
      (t/is (nil? (ctc/find-component-main objects shape))))))

(t/deftest find-component-main-two-node-cycle
  (t/testing "returns nil (no crash) for a two-node circular reference A→B→A"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-a)
          objects (objects-map shape-a shape-b)]
      (t/is (nil? (ctc/find-component-main objects shape-a))))))

(t/deftest find-component-main-multi-node-cycle
  (t/testing "returns nil (no crash) for a longer cycle A→B→C→A"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          id-c  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-c)
          shape-c (make-shape id-c :parent-id id-a)
          objects (objects-map shape-a shape-b shape-c)]
      (t/is (nil? (ctc/find-component-main objects shape-a))))))

(t/deftest find-component-main-only-direct-child-with-cycle
  (t/testing "cycle detection works correctly with only-direct-child? false as well"
    (let [id-a  (uuid/next)
          id-b  (uuid/next)
          shape-a (make-shape id-a :parent-id id-b)
          shape-b (make-shape id-b :parent-id id-a)
          objects (objects-map shape-a shape-b)]
      (t/is (nil? (ctc/find-component-main objects shape-a false))))))
