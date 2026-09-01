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

;; ---------------------------------------------------------------------------
;; Tests – get-all-instance-roots
;; ---------------------------------------------------------------------------

(defn- make-tree
  "Build an objects map from a seq of [id parent-id attrs] triples. The
  :shapes of each parent are filled from the triples, in order. A nil
  parent-id means that the shape has no parent in the map."
  [specs]
  (let [children (reduce (fn [acc [id parent-id]]
                           (cond-> acc
                             (some? parent-id)
                             (update parent-id (fnil conj []) id)))
                         {}
                         specs)]
    (->> specs
         (map (fn [[id parent-id attrs]]
                (apply make-shape id
                       :parent-id parent-id
                       :shapes (get children id [])
                       (mapcat identity attrs))))
         (apply objects-map))))

(def ^:private root-attrs {:component-root true
                           :component-id   (uuid/next)})

(t/deftest get-all-instance-roots-empty-and-missing
  (t/testing "no ids returns an empty set"
    (t/is (= #{} (ctc/get-all-instance-roots {} []))))

  (t/testing "ids that are not in objects return an empty set"
    (t/is (= #{} (ctc/get-all-instance-roots {} [(uuid/next)])))))

(t/deftest get-all-instance-roots-no-roots
  (t/testing "returns an empty set when there is no instance root above or below"
    (let [[a b c] (repeatedly 3 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b]])]
      (doseq [id [a b c]]
        (t/is (= #{} (ctc/get-all-instance-roots objects [id])))))))

(t/deftest get-all-instance-roots-self-is-root
  (t/testing "a shape that is an instance root returns itself"
    (let [[a] (repeatedly 1 uuid/next)
          objects (make-tree [[a root-id root-attrs]])]
      (t/is (= #{a} (ctc/get-all-instance-roots objects [a])))))

  (t/testing "does not look down when the shape itself is an instance root"
    (let [[a b] (repeatedly 2 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a root-attrs]])]
      (t/is (= #{a} (ctc/get-all-instance-roots objects [a]))))))

(t/deftest get-all-instance-roots-ancestor-is-root
  (t/testing "finds the instance root among the ancestors"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a]
                              [c b]
                              [d c]])]
      (doseq [id [b c d]]
        (t/is (= #{a} (ctc/get-all-instance-roots objects [id]))))))

  (t/testing "returns the nearest ancestor root when there are several"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a root-attrs]
                              [c b]
                              [d c]])]
      (t/is (= #{b} (ctc/get-all-instance-roots objects [d])))))

  (t/testing "does not look down when an ancestor is an instance root"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a]
                              [c b root-attrs]
                              [d b root-attrs]])]
      (t/is (= #{a} (ctc/get-all-instance-roots objects [b]))))))

(t/deftest get-all-instance-roots-descendant-roots
  (t/testing "finds an instance root below a shape without roots above"
    (let [[a b c] (repeatedly 3 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b root-attrs]])]
      (t/is (= #{c} (ctc/get-all-instance-roots objects [a])))
      (t/is (= #{c} (ctc/get-all-instance-roots objects [b])))))

  (t/testing "finds a root that is a direct child of the starting shape"
    (let [[a b] (repeatedly 2 uuid/next)
          objects (make-tree [[a root-id]
                              [b a root-attrs]])]
      (t/is (= #{b} (ctc/get-all-instance-roots objects [a])))))

  (t/testing "finds roots in several branches"
    (let [[a b c d e f] (repeatedly 6 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b root-attrs]
                              [d a root-attrs]
                              [e a]
                              [f e root-attrs]])]
      (t/is (= #{c d f} (ctc/get-all-instance-roots objects [a])))))

  (t/testing "skips the subtree of a found root and continues with the siblings"
    (let [[a b c d e f] (repeatedly 6 uuid/next)
          objects (make-tree [[a root-id]
                              [b a root-attrs]
                              [c b root-attrs] ; nested in b, must be skipped
                              [d c root-attrs] ; nested in c, must be skipped
                              [e a]
                              [f e root-attrs]])]
      (t/is (= #{b f} (ctc/get-all-instance-roots objects [a])))))

  (t/testing "skips a root nested in a non-root shape inside another root"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id]
                              [b a root-attrs]
                              [c b]
                              [d c root-attrs]])]
      (t/is (= #{b} (ctc/get-all-instance-roots objects [a]))))))

(t/deftest get-all-instance-roots-page-root
  (t/testing "searching from the page root finds the top level instance roots"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (assoc (make-tree [[a root-id root-attrs]
                                     [b a root-attrs]
                                     [c root-id]
                                     [d c root-attrs]])
                         root-id
                         (assoc (root-shape) :shapes [a c]))]
      (t/is (= #{a d} (ctc/get-all-instance-roots objects [root-id]))))))

(t/deftest get-all-instance-roots-several-ids
  (t/testing "returns the union of the roots of every id"
    (let [[a b c d e] (repeatedly 5 uuid/next)
          objects (make-tree [[a root-id root-attrs] ; root with up search
                              [b a]
                              [c root-id]            ; no root above
                              [d c root-attrs]       ; found down from c
                              [e root-id]])]         ; nothing
      (t/is (= #{a d} (ctc/get-all-instance-roots objects [b c e])))))

  (t/testing "ids that overlap in the same tree do not duplicate or lose roots"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b root-attrs]
                              [d a root-attrs]])]
      (t/is (= #{c d} (ctc/get-all-instance-roots objects [a b c d])))
      (t/is (= #{c d} (ctc/get-all-instance-roots objects [b a d c])))
      (t/is (= #{c d} (ctc/get-all-instance-roots objects [a a])))))

  (t/testing "a shape below an already searched shape is not searched again"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b root-attrs]
                              [d a root-attrs]])]
      (t/is (= #{c d} (ctc/get-all-instance-roots objects [a b])))
      (t/is (= #{c d} (ctc/get-all-instance-roots objects [b a])))))

  (t/testing "a child of a shape with a root above does not search down"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a]
                              [c b root-attrs]
                              [d b]])]
      (t/is (= #{a} (ctc/get-all-instance-roots objects [b d])))
      (t/is (= #{a} (ctc/get-all-instance-roots objects [d b])))))

  (t/testing "siblings without a root above are both searched down"
    (let [[a b c d e] (repeatedly 5 uuid/next)
          objects (make-tree [[a root-id]
                              [b a]
                              [c b root-attrs]
                              [d a]
                              [e d root-attrs]])]
      (t/is (= #{c e} (ctc/get-all-instance-roots objects [b d])))
      (t/is (= #{c e} (ctc/get-all-instance-roots objects [c e])))))

  (t/testing "a shape inside a root and a shape outside any root"
    (let [[a b c d] (repeatedly 4 uuid/next)
          objects (make-tree [[a root-id root-attrs]
                              [b a]
                              [c root-id]
                              [d c]])]
      (t/is (= #{a} (ctc/get-all-instance-roots objects [b d]))))))

(t/deftest get-all-instance-roots-cycles
  (t/testing "does not loop on circular children references"
    (let [[a b] (repeatedly 2 uuid/next)
          objects (objects-map (make-shape a :shapes [b])
                               (make-shape b :shapes [a]))]
      (t/is (= #{} (ctc/get-all-instance-roots objects [a]))))))
