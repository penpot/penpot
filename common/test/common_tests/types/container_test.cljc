;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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

(t/deftest get-variant-boundary-id-recognizes-container-main-and-nested
  (let [page-id      uuid/zero
        container-id (uuid/next)
        main-id      (uuid/next)
        nested-id    (uuid/next)
        outside-id   (uuid/next)
        container    (make-shape container-id
                                 :parent-id page-id
                                 :type :frame
                                 :is-variant-container true)
        main         (make-shape main-id
                                 :parent-id container-id
                                 :type :frame
                                 :variant-id container-id)
        nested       (make-shape nested-id :parent-id main-id)
        outside      (make-shape outside-id :parent-id page-id)
        objects      (objects-map container main nested outside)]
    (t/is (= main-id (ctc/get-variant-boundary-id objects nested)))
    (t/is (= main-id (ctc/get-variant-boundary-id objects main)))
    (t/is (= container-id (ctc/get-variant-boundary-id objects container)))
    (t/is (nil? (ctc/get-variant-boundary-id objects outside)))))

(t/deftest crosses-variant-set-boundary?
  (let [page-id       uuid/zero
        container-id  (uuid/next)
        main-id       (uuid/next)
        main-2-id     (uuid/next)
        nested-id     (uuid/next)
        nested-2-id   (uuid/next)
        outside-id    (uuid/next)
        other-set-id  (uuid/next)
        other-main-id (uuid/next)
        other-nest-id (uuid/next)
        container     (make-shape container-id
                                  :parent-id page-id
                                  :type :frame
                                  :is-variant-container true)
        main          (make-shape main-id
                                  :parent-id container-id
                                  :type :frame
                                  :variant-id container-id)
        main-2        (make-shape main-2-id
                                  :parent-id container-id
                                  :type :frame
                                  :variant-id container-id)
        nested        (make-shape nested-id :parent-id main-id)
        nested-2      (make-shape nested-2-id :parent-id main-2-id)
        outside       (make-shape outside-id :parent-id page-id)
        other-set     (make-shape other-set-id
                                  :parent-id page-id
                                  :type :frame
                                  :is-variant-container true)
        other-main    (make-shape other-main-id
                                  :parent-id other-set-id
                                  :type :frame
                                  :variant-id other-set-id)
        other-nested  (make-shape other-nest-id :parent-id other-main-id)
        objects       (objects-map container main main-2 nested nested-2
                                   outside other-set other-main other-nested)]
    (t/testing "nested child + outside crosses"
      (t/is (true? (ctc/crosses-variant-set-boundary?
                    objects [nested outside]))))
    (t/testing "main + outside crosses"
      (t/is (true? (ctc/crosses-variant-set-boundary?
                    objects [main outside]))))
    (t/testing "container + outside crosses"
      (t/is (true? (ctc/crosses-variant-set-boundary?
                    objects [container outside]))))
    (t/testing "nested children of two variants in the same set cross"
      (t/is (true? (ctc/crosses-variant-set-boundary?
                    objects [nested nested-2]))))
    (t/testing "nested children from two different sets cross"
      (t/is (true? (ctc/crosses-variant-set-boundary?
                    objects [nested other-nested]))))
    (t/testing "two outside shapes do not cross"
      (let [outside-2 (make-shape (uuid/next) :parent-id page-id)]
        (t/is (false? (ctc/crosses-variant-set-boundary?
                       (assoc objects (:id outside-2) outside-2)
                       [outside outside-2])))))
    (t/testing "nested only does not cross"
      (t/is (false? (ctc/crosses-variant-set-boundary? objects [nested]))))
    (t/testing "main + nested under that main do not cross"
      (t/is (false? (ctc/crosses-variant-set-boundary?
                     objects [main nested]))))
    (t/testing "two nested under the same main do not cross"
      (let [nested-b (make-shape (uuid/next) :parent-id main-id)]
        (t/is (false? (ctc/crosses-variant-set-boundary?
                       (assoc objects (:id nested-b) nested-b)
                       [nested nested-b])))))))

(t/deftest ids-for-structure-op
  (let [page-id      uuid/zero
        container-id (uuid/next)
        main-id      (uuid/next)
        main-2-id    (uuid/next)
        nested-id    (uuid/next)
        nested-2-id  (uuid/next)
        outside-id   (uuid/next)
        outside-2-id (uuid/next)
        container    (make-shape container-id
                                 :parent-id page-id
                                 :type :frame
                                 :is-variant-container true)
        main         (make-shape main-id
                                 :parent-id container-id
                                 :type :frame
                                 :variant-id container-id)
        main-2       (make-shape main-2-id
                                 :parent-id container-id
                                 :type :frame
                                 :variant-id container-id)
        nested       (make-shape nested-id :parent-id main-id)
        nested-2     (make-shape nested-2-id :parent-id main-id)
        outside      (make-shape outside-id :parent-id page-id)
        outside-2    (make-shape outside-2-id :parent-id page-id)
        objects      (objects-map container main main-2 nested nested-2
                                  outside outside-2)]
    (t/testing "container-only is refused"
      (t/is (= [] (vec (ctc/ids-for-structure-op objects [container-id])))))
    (t/testing "nested + outside is refused"
      (t/is (= [] (vec (ctc/ids-for-structure-op objects [nested-id outside-id])))))
    (t/testing "main + outside is refused"
      (t/is (= [] (vec (ctc/ids-for-structure-op objects [main-id outside-id])))))
    (t/testing "two mains cross and are refused"
      (t/is (= [] (vec (ctc/ids-for-structure-op objects [main-id main-2-id])))))
    (t/testing "single main is dropped to empty"
      (t/is (= [] (vec (ctc/ids-for-structure-op objects [main-id])))))
    (t/testing "main + nested under that main keeps only the nested"
      (t/is (= [nested-id]
               (vec (ctc/ids-for-structure-op objects [main-id nested-id])))))
    (t/testing "two nested under one main are both kept"
      (t/is (= [nested-id nested-2-id]
               (vec (ctc/ids-for-structure-op objects [nested-id nested-2-id])))))
    (t/testing "two ordinary outside shapes are both kept"
      (t/is (= [outside-id outside-2-id]
               (vec (ctc/ids-for-structure-op objects [outside-id outside-2-id])))))))
