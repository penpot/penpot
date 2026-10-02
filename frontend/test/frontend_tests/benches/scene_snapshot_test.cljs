;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.scene-snapshot-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [benches.render-wasm.snapshot :as common]
   [cljs.test :as t :include-macros true]))

(defn- sample-snapshot
  "Simple tiny scene: root frame with one rectangle."
  []
  (let [rect-id (uuid/custom 1 1)
        rect    (cts/setup-shape {:id rect-id :type :rect :name "Rect"
                                  :x 32 :y 48 :width 200 :height 120})
        root    (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                  :x 0 :y 0 :width 1920 :height 1080
                                  :shapes [rect-id]})]
    {:objects {uuid/zero root, rect-id rect}
     :refs    {:rect rect-id}}))

(defn- nested-instance
  "Root with two frames; frame A with two rectangles, frame B with one."
  []
  (let [a-id    (uuid/custom 2 1)
        b-id    (uuid/custom 2 2)
        a1-id   (uuid/custom 2 3)
        a2-id   (uuid/custom 2 4)
        b1-id   (uuid/custom 2 5)
        frame-a (cts/setup-shape {:id a-id :type :frame :name "A"
                                  :x 0 :y 0 :width 100 :height 100
                                  :parent-id uuid/zero :frame-id uuid/zero
                                  :shapes [a1-id a2-id]})
        frame-b (cts/setup-shape {:id b-id :type :frame :name "B"
                                  :x 200 :y 0 :width 100 :height 100
                                  :parent-id uuid/zero :frame-id uuid/zero
                                  :shapes [b1-id]})
        rect-a1 (cts/setup-shape {:id a1-id :type :rect :name "A1"
                                  :x 10 :y 10 :width 10 :height 10
                                  :parent-id a-id :frame-id a-id})
        rect-a2 (cts/setup-shape {:id a2-id :type :rect :name "A2"
                                  :x 30 :y 10 :width 10 :height 10
                                  :parent-id a-id :frame-id a-id})
        rect-b1 (cts/setup-shape {:id b1-id :type :rect :name "B1"
                                  :x 210 :y 10 :width 10 :height 10
                                  :parent-id b-id :frame-id b-id})
        root    (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                  :x 0 :y 0 :width 1920 :height 1080
                                  :parent-id uuid/zero :frame-id uuid/zero
                                  :shapes [a-id b-id]})]
    {:objects {uuid/zero root
               a-id frame-a
               b-id frame-b
               a1-id rect-a1
               a2-id rect-a2
               b1-id rect-b1}
     :refs    {:a a-id
               :a1 a1-id
               :b b-id}}))

(defn- validation-error
  "Returns the ex-data of the validation failure, or nil when it passes."
  [instance]
  (try
    (common/validate! instance)
    nil
    (catch :default cause
      (ex-data cause))))

(t/deftest canonical-example-is-valid
  (t/testing "validate! returns the same instance"
    (let [instance (sample-snapshot)]
      (t/is (identical? instance (common/validate! instance)))))

  (t/testing "the envelope schema accepts the example"
    (t/is (true? (sm/validate common/schema:snapshot (sample-snapshot))))))

(t/deftest nested-hierarchy-is-valid
  (let [instance (nested-instance)]
    (t/is (identical? instance (common/validate! instance)))))

(t/deftest root-only-instance-is-valid
  (let [root     (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                   :x 0 :y 0 :width 100 :height 100
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :shapes []})
        instance {:objects {uuid/zero root} :refs {}}]
    (t/is (identical? instance (common/validate! instance)))
    (t/is (= [uuid/zero] (mapv :id (common/upload-order instance))))))

(t/deftest upload-order-starts-at-root
  (let [instance (sample-snapshot)
        ids      (mapv :id (common/upload-order instance))]
    (t/is (= [uuid/zero (common/ref-id instance :rect)] ids))))

(t/deftest upload-order-is-parent-before-child
  (let [instance (nested-instance)
        order    (common/upload-order instance)
        names    (mapv :name order)
        position (into {} (map-indexed (fn [index shape] [(:id shape) index]) order))]
    (t/is (= ["Root Frame" "A" "A1" "A2" "B" "B1"] names))
    (t/is (every? (fn [shape]
                    (or (= uuid/zero (:id shape))
                        (< (position (:parent-id shape))
                           (position (:id shape)))))
                  order))))

(t/deftest upload-order-follows-flex-reverse
  (let [frame-id (uuid/custom 8 1)
        a-id     (uuid/custom 8 2)
        b-id     (uuid/custom 8 3)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Flex"
                                   :x 0 :y 0 :width 200 :height 100
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :layout :flex :layout-flex-dir :row-reverse
                                   :shapes [a-id b-id]})
        shape-a  (cts/setup-shape {:id a-id :type :rect :name "A"
                                   :x 0 :y 0 :width 10 :height 10
                                   :parent-id frame-id :frame-id frame-id})
        shape-b  (cts/setup-shape {:id b-id :type :rect :name "B"
                                   :x 20 :y 0 :width 10 :height 10
                                   :parent-id frame-id :frame-id frame-id})
        root     (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                   :x 0 :y 0 :width 1920 :height 1080
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :shapes [frame-id]})
        instance {:objects {uuid/zero root
                            frame-id frame
                            a-id shape-a
                            b-id shape-b}
                  :refs {}}]
    (t/is (identical? instance (common/validate! instance)))
    (t/is (= ["Root Frame" "Flex" "B" "A"]
             (mapv :name (common/upload-order instance))))))

(t/deftest ref-id-resolves-labels
  (let [instance (sample-snapshot)]
    (t/is (= (uuid/custom 1 1) (common/ref-id instance :rect)))
    (t/is (nil? (common/ref-id instance :missing)))))

(t/deftest missing-root-is-rejected
  (let [instance (sample-snapshot)
        data     (validation-error (update instance :objects dissoc uuid/zero))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= uuid/zero (:id data)))))

(t/deftest non-frame-root-is-rejected
  (let [instance (sample-snapshot)
        data     (validation-error (assoc-in instance [:objects uuid/zero :type] :rect))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= uuid/zero (:id data)))))

(t/deftest root-with-non-zero-parent-id-is-rejected
  (let [instance (sample-snapshot)
        data     (validation-error
                  (assoc-in instance [:objects uuid/zero :parent-id] (uuid/custom 9 9)))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= uuid/zero (:id data)))))

(t/deftest object-key-must-match-shape-id
  (let [instance (sample-snapshot)
        rect     (get-in instance [:objects (common/ref-id instance :rect)])
        data     (validation-error (assoc-in instance [:objects (uuid/custom 7 7)] rect))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= (:id rect) (:shape-id data)))))

(t/deftest missing-child-is-rejected
  (let [instance (sample-snapshot)
        bogus    (uuid/custom 3 3)
        data     (validation-error
                  (assoc-in instance [:objects uuid/zero :shapes] [bogus]))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= bogus (:child data)))))

(t/deftest child-not-listed-by-parent-is-rejected
  (let [instance (sample-snapshot)
        rect-id  (common/ref-id instance :rect)
        data     (validation-error (assoc-in instance [:objects uuid/zero :shapes] []))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "object is not reachable from the root" (:hint data)))
    (t/is (= rect-id (:id data)))))

(t/deftest mismatched-parent-id-is-rejected
  (let [instance (sample-snapshot)
        rect-id  (common/ref-id instance :rect)
        data     (validation-error
                  (assoc-in instance [:objects rect-id :parent-id] (uuid/custom 9 7)))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "shape :shapes lists a child with a different :parent-id" (:hint data)))
    (t/is (= rect-id (:child data)))))

(t/deftest duplicate-child-ids-are-rejected
  (let [instance (sample-snapshot)
        rect-id  (common/ref-id instance :rect)
        data     (validation-error
                  (assoc-in instance [:objects uuid/zero :shapes] [rect-id rect-id]))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "an object is reachable more than once" (:hint data)))
    (t/is (= rect-id (:id data)))))

(t/deftest shape-listing-itself-is-rejected
  (let [instance (sample-snapshot)
        data     (validation-error
                  (assoc-in instance [:objects uuid/zero :shapes] [uuid/zero]))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "root must not be a descendant of itself" (:hint data)))
    (t/is (= uuid/zero (:id data)))))

(t/deftest detached-cycle-is-rejected
  (let [c1-id  (uuid/custom 5 1)
        c2-id  (uuid/custom 5 2)
        shape1 (cts/setup-shape {:id c1-id :type :rect :name "C1"
                                 :x 0 :y 0 :width 10 :height 10
                                 :parent-id c2-id :frame-id c1-id
                                 :shapes [c2-id]})
        shape2 (cts/setup-shape {:id c2-id :type :rect :name "C2"
                                 :x 0 :y 0 :width 10 :height 10
                                 :parent-id c1-id :frame-id c2-id
                                 :shapes [c1-id]})
        data   (validation-error
                (assoc (sample-snapshot) :objects
                       (assoc (get (sample-snapshot) :objects)
                              c1-id shape1
                              c2-id shape2)))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "object is not reachable from the root" (:hint data)))
    (t/is (contains? #{c1-id c2-id} (:id data)))))

(t/deftest reachable-cycle-is-rejected
  (let [a-id     (uuid/custom 4 1)
        b-id     (uuid/custom 4 2)
        shape-a  (cts/setup-shape {:id a-id :type :rect :name "A"
                                   :x 0 :y 0 :width 10 :height 10
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :shapes [b-id]})
        shape-b  (cts/setup-shape {:id b-id :type :rect :name "B"
                                   :x 0 :y 0 :width 10 :height 10
                                   :parent-id a-id :frame-id uuid/zero
                                   :shapes [a-id]})
        instance (-> (sample-snapshot)
                     (assoc-in [:objects uuid/zero :shapes] [a-id])
                     (assoc-in [:objects a-id] shape-a)
                     (assoc-in [:objects b-id] shape-b))
        data     (validation-error instance)]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "shape :shapes lists a child with a different :parent-id" (:hint data)))
    (t/is (= a-id (:child data)))))

(t/deftest non-vector-children-are-rejected
  (let [instance (sample-snapshot)
        rect-id  (common/ref-id instance :rect)
        data     (validation-error (assoc-in instance [:objects rect-id :shapes] 5))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= "shape :shapes must be a vector" (:hint data)))
    (t/is (= rect-id (:id data)))))

(t/deftest frame-id-mismatch-is-rejected
  (let [instance (nested-instance)
        a1-id    (common/ref-id instance :a1)
        data     (validation-error
                  (assoc-in instance [:objects a1-id :frame-id] uuid/zero))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= :invalid-frame (:code data)))
    (t/is (= a1-id (:shape-id data)))))

(t/deftest missing-ref-target-is-rejected
  (let [instance (sample-snapshot)
        data     (validation-error
                  (assoc-in instance [:refs :rect] (uuid/custom 6 6)))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (= :rect (:label data)))))

(t/deftest non-uuid-ref-value-is-rejected
  (let [data (validation-error (assoc-in (sample-snapshot) [:refs :rect] "not-a-uuid"))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (some? (::sm/explain data)))))

(t/deftest missing-refs-key-is-rejected
  (let [data (validation-error (dissoc (sample-snapshot) :refs))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (some? (::sm/explain data)))))

(t/deftest non-canonical-shape-is-rejected
  (let [instance (sample-snapshot)
        rect-id  (common/ref-id instance :rect)
        data     (validation-error (assoc-in instance [:objects rect-id] {:id rect-id}))]
    (t/is (= ::common/invalid-snapshot (:type data)))
    (t/is (some? (::sm/explain data)))))

(t/deftest malformed-envelopes-are-rejected
  (t/testing "empty objects map"
    (let [data (validation-error {:objects {} :refs {}})]
      (t/is (= ::common/invalid-snapshot (:type data)))
      (t/is (= uuid/zero (:id data)))))

  (t/testing "nil instance"
    (let [data (validation-error nil)]
      (t/is (= ::common/invalid-snapshot (:type data)))
      (t/is (some? (::sm/explain data)))))

  (t/testing "nil shape value"
    (let [instance (sample-snapshot)
          rect-id  (common/ref-id instance :rect)
          data     (validation-error (assoc-in instance [:objects rect-id] nil))]
      (t/is (= ::common/invalid-snapshot (:type data)))
      (t/is (some? (::sm/explain data)))))

  (t/testing "nil ref value"
    (let [data (validation-error (assoc-in (sample-snapshot) [:refs :rect] nil))]
      (t/is (= ::common/invalid-snapshot (:type data)))
      (t/is (some? (::sm/explain data))))))
