;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.modifiers-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest modifiers->transform
  (let [modifiers
        (-> (ctm/empty)
            (ctm/move (gpt/point 100 200))
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
            (ctm/move (gpt/point -100 -200))
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
            (ctm/rotation (gpt/point 0 0) -100)
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5)))

        transform (ctm/modifiers->transform modifiers)]

    (t/is (not (gmt/close? (gmt/matrix) transform)))))

;; ─── Helpers ──────────────────────────────────────────────────────────────────

(defn- make-shape
  "Build a minimal axis-aligned rect shape with the given geometry."
  ([width height]
   (make-shape 0 0 width height))
  ([x y width height]
   (cts/setup-shape {:type :rect :x x :y y :width width :height height})))

(defn- make-shape-with-proportion
  "Build a shape with a fixed proportion ratio and proportion-lock enabled."
  [width height]
  (assoc (make-shape width height)
         :proportion       (/ (float width) (float height))
         :proportion-lock  true))

(defn- resize-op
  "Extract the single resize GeometricOperation from geometry-child."
  [modifiers]
  (first (:geometry-child modifiers)))

;; ─── change-size ──────────────────────────────────────────────────────────────

(t/deftest change-size-basic
  (t/testing "scales both axes to the requested dimensions"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape 200 100)
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "origin is the first point of the shape (top-left)"
    (let [shape  (make-shape 10 20 100 50)
          mods   (ctm/change-size shape 200 50)
          origin (:origin (resize-op mods))]
      (t/is (mth/close? 10.0 (:x origin)))
      (t/is (mth/close? 20.0 (:y origin)))))

  (t/testing "nil width falls back to current width, keeping x-scale at 1"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape nil 100)
          op    (resize-op mods)]
      (t/is (mth/close? 1.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "nil height falls back to current height, keeping y-scale at 1"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape 200 nil)
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 1.0 (-> op :vector :y)))))

  (t/testing "both nil produces an identity resize (scale 1,1)"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape nil nil)
          op    (resize-op mods)]
      ;; Identity resize is optimized away; geometry-child should be empty.
      (t/is (empty? (:geometry-child mods)))))

  (t/testing "transform and transform-inverse on a plain shape are both the identity matrix"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape 200 100)
          op    (resize-op mods)]
      (t/is (gmt/close? (gmt/matrix) (:transform op)))
      (t/is (gmt/close? (gmt/matrix) (:transform-inverse op))))))

;; ─── change-dimensions-modifiers ──────────────────────────────────────────────

(t/deftest change-dimensions-modifiers-no-lock
  (t/testing "changing width only scales x-axis; y-scale stays 1"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-dimensions-modifiers shape :width 200)
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 1.0 (-> op :vector :y)))))

  (t/testing "changing height only scales y-axis; x-scale stays 1"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-dimensions-modifiers shape :height 100)
          op    (resize-op mods)]
      (t/is (mth/close? 1.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "origin is always the top-left point of the shape"
    (let [shape  (make-shape 30 40 100 50)
          mods   (ctm/change-dimensions-modifiers shape :width 200)
          origin (:origin (resize-op mods))]
      (t/is (mth/close? 30.0 (:x origin)))
      (t/is (mth/close? 40.0 (:y origin))))))

(t/deftest change-dimensions-modifiers-with-proportion-lock
  (t/testing "locking width also adjusts height by the inverse proportion"
    ;; shape 100x50 → proportion = 100/50 = 2
    ;; new width 200 → expected height = 200/2 = 100  →  scaley = 100/50 = 2
    (let [shape (make-shape-with-proportion 100 50)
          mods  (ctm/change-dimensions-modifiers shape :width 200)
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "locking height also adjusts width by the proportion"
    ;; shape 100x50 → proportion = 100/50 = 2
    ;; new height 100 → expected width = 100*2 = 200  →  scalex = 200/100 = 2
    (let [shape (make-shape-with-proportion 100 50)
          mods  (ctm/change-dimensions-modifiers shape :height 100)
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "ignore-lock? true bypasses proportion lock"
    (let [shape (make-shape-with-proportion 100 50)
          mods  (ctm/change-dimensions-modifiers shape :width 200 {:ignore-lock? true})
          op    (resize-op mods)]
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      ;; Height should remain unchanged (scale = 1).
      (t/is (mth/close? 1.0 (-> op :vector :y))))))

(t/deftest change-dimensions-modifiers-value-clamping
  (t/testing "value below 0.01 is clamped to 0.01"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-dimensions-modifiers shape :width 0.001)
          op    (resize-op mods)]
      ;; 0.01 / 100 = 0.0001
      (t/is (mth/close? 0.0001 (-> op :vector :x)))))

  (t/testing "value of exactly 0 is clamped to 0.01"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-dimensions-modifiers shape :height 0)
          op    (resize-op mods)]
      ;; 0.01 / 50 = 0.0002
      (t/is (mth/close? 0.0002 (-> op :vector :y))))))

(t/deftest change-dimensions-modifiers-end-to-end
  (t/testing "applying change-width modifier produces the expected selrect width"
    (let [shape  (make-shape 100 50)
          mods   (ctm/change-dimensions-modifiers shape :width 300)
          result (gsh/transform-shape (assoc shape :modifiers mods))]
      (t/is (mth/close? 300.0 (-> result :selrect :width)))
      (t/is (mth/close? 50.0  (-> result :selrect :height)))))

  (t/testing "applying change-height modifier produces the expected selrect height"
    (let [shape  (make-shape 100 50)
          mods   (ctm/change-dimensions-modifiers shape :height 200)
          result (gsh/transform-shape (assoc shape :modifiers mods))]
      (t/is (mth/close? 100.0 (-> result :selrect :width)))
      (t/is (mth/close? 200.0 (-> result :selrect :height))))))

;; ─── safe-size-rect fallbacks ─────────────────────────────────────────────────

(t/deftest safe-size-rect-fallbacks
  (t/testing "valid selrect is returned as-is"
    (let [shape (make-shape 100 50)
          mods  (ctm/change-size shape 200 100)
          op    (resize-op mods)]
      ;; scale 2,2 means the selrect was valid
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "zero-width selrect falls back to points, producing a valid rect"
    ;; Corrupt only the selrect dimensions; the shape's points remain valid.
    (let [base        (make-shape 100 50)
          bad-selrect (assoc (:selrect base) :width 0 :height 0)
          shape       (assoc base :selrect bad-selrect)
          mods        (ctm/change-size shape 200 100)
          op          (resize-op mods)]
      (t/is (cts/shape? shape))
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "NaN selrect falls back to points"
    (let [base        (make-shape 100 50)
          bad-selrect (assoc (:selrect base) :width ##NaN :height ##NaN)
          shape       (assoc base :selrect bad-selrect)
          mods        (ctm/change-size shape 200 100)
          op          (resize-op mods)]
      (t/is (cts/shape? shape))
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "selrect with dimensions exceeding max-safe-int falls back to points"
    (let [base        (make-shape 100 50)
          bad-selrect (assoc (:selrect base) :width (inc sm/max-safe-int) :height (inc sm/max-safe-int))
          shape       (assoc base :selrect bad-selrect)
          mods        (ctm/change-size shape 200 100)
          op          (resize-op mods)]
      (t/is (cts/shape? shape))
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "invalid selrect and no points falls back to top-level shape fields"
    ;; Null out both selrect and points; the top-level :x/:y/:width/:height
    ;; fields on the Shape record are still valid and serve as fallback 3.
    (let [shape (-> (make-shape 100 50)
                    (assoc :selrect nil)
                    (assoc :points  nil))
          mods  (ctm/change-size shape 200 100)
          op    (resize-op mods)]
      (t/is (cts/shape? shape))
      (t/is (mth/close? 2.0 (-> op :vector :x)))
      (t/is (mth/close? 2.0 (-> op :vector :y)))))

  (t/testing "all geometry missing: falls back to empty-rect (0.01 x 0.01)"
    ;; Null out selrect, points and the top-level dimension fields so that
    ;; every fallback is exhausted and empty-rect (0.01×0.01) is used.
    (let [shape (-> (make-shape 100 50)
                    (assoc :selrect nil)
                    (assoc :points  nil)
                    (assoc :width   nil)
                    (assoc :height  nil))
          mods  (ctm/change-size shape 200 100)
          op    (resize-op mods)]
      (t/is (cts/shape? shape))
      (t/is (mth/close? (/ 200 0.01) (-> op :vector :x)))
      (t/is (mth/close? (/ 100 0.01) (-> op :vector :y))))))

;; ─── Builder functions: geometry-child ────────────────────────────────────────

(t/deftest move-builder
  (t/testing "move adds an operation to geometry-child"
    (let [mods (ctm/move (ctm/empty) (gpt/point 10 20))]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (= :move (-> mods :geometry-child first :type)))))

  (t/testing "move with zero vector is optimised away"
    (let [mods (ctm/move (ctm/empty) (gpt/point 0 0))]
      (t/is (empty? (:geometry-child mods)))))

  (t/testing "two consecutive moves on the same axis are merged into one"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 10 0))
                   (ctm/move (gpt/point 5 0)))]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (mth/close? 15.0 (-> mods :geometry-child first :vector :x)))))

  (t/testing "move with x y arity delegates to vector arity"
    (let [mods (ctm/move (ctm/empty) 3 7)]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (mth/close? 3.0 (-> mods :geometry-child first :vector :x)))
      (t/is (mth/close? 7.0 (-> mods :geometry-child first :vector :y))))))

(t/deftest resize-builder
  (t/testing "resize adds an operation to geometry-child"
    (let [mods (ctm/resize (ctm/empty) (gpt/point 2 3) (gpt/point 0 0))]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (= :resize (-> mods :geometry-child first :type)))))

  (t/testing "identity resize (scale 1,1) is optimised away"
    (let [mods (ctm/resize (ctm/empty) (gpt/point 1 1) (gpt/point 0 0))]
      (t/is (empty? (:geometry-child mods)))))

  (t/testing "precise? flag keeps near-identity resize"
    (let [mods (ctm/resize (ctm/empty) (gpt/point 1 1) (gpt/point 0 0)
                           nil nil {:precise? true})]
      (t/is (= 1 (count (:geometry-child mods))))))

  (t/testing "resize stores origin, transform and transform-inverse"
    (let [tf   (gmt/matrix)
          tfi  (gmt/matrix)
          mods (ctm/resize (ctm/empty) (gpt/point 2 2) (gpt/point 5 10) tf tfi)
          op   (-> mods :geometry-child first)]
      (t/is (mth/close? 5.0  (-> op :origin :x)))
      (t/is (mth/close? 10.0 (-> op :origin :y)))
      (t/is (gmt/close? tf  (:transform op)))
      (t/is (gmt/close? tfi (:transform-inverse op))))))

(t/deftest rotation-builder
  (t/testing "rotation adds ops to both geometry-child and structure-child"
    (let [mods (ctm/rotation (ctm/empty) (gpt/point 50 50) 45)]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (= 1 (count (:structure-child mods))))
      (t/is (= :rotation (-> mods :geometry-child first :type)))
      (t/is (= :rotation (-> mods :structure-child first :type)))))

  (t/testing "zero-angle rotation is optimised away"
    (let [mods (ctm/rotation (ctm/empty) (gpt/point 50 50) 0)]
      (t/is (empty? (:geometry-child mods)))
      (t/is (empty? (:structure-child mods))))))

;; ─── Builder functions: geometry-parent ───────────────────────────────────────

(t/deftest move-parent-builder
  (t/testing "move-parent adds an operation to geometry-parent, not geometry-child"
    (let [mods (ctm/move-parent (ctm/empty) (gpt/point 10 20))]
      (t/is (= 1 (count (:geometry-parent mods))))
      (t/is (empty? (:geometry-child mods)))
      (t/is (= :move (-> mods :geometry-parent first :type)))))

  (t/testing "move-parent with zero vector is optimised away"
    (let [mods (ctm/move-parent (ctm/empty) (gpt/point 0 0))]
      (t/is (empty? (:geometry-parent mods))))))

(t/deftest resize-parent-builder
  (t/testing "resize-parent adds an operation to geometry-parent, not geometry-child"
    (let [mods (ctm/resize-parent (ctm/empty) (gpt/point 2 3) (gpt/point 0 0))]
      (t/is (= 1 (count (:geometry-parent mods))))
      (t/is (empty? (:geometry-child mods)))
      (t/is (= :resize (-> mods :geometry-parent first :type))))))

;; ─── Builder functions: structure ─────────────────────────────────────────────

(t/deftest structure-builders
  (t/testing "add-children appends an add-children op to structure-parent"
    (let [id1  (uuid/next)
          id2  (uuid/next)
          mods (ctm/add-children (ctm/empty) [id1 id2] nil)]
      (t/is (= 1 (count (:structure-parent mods))))
      (t/is (= :add-children (-> mods :structure-parent first :type)))))

  (t/testing "add-children with empty list is a no-op"
    (let [mods (ctm/add-children (ctm/empty) [] nil)]
      (t/is (empty? (:structure-parent mods)))))

  (t/testing "remove-children appends a remove-children op to structure-parent"
    (let [id   (uuid/next)
          mods (ctm/remove-children (ctm/empty) [id])]
      (t/is (= 1 (count (:structure-parent mods))))
      (t/is (= :remove-children (-> mods :structure-parent first :type)))))

  (t/testing "remove-children with empty list is a no-op"
    (let [mods (ctm/remove-children (ctm/empty) [])]
      (t/is (empty? (:structure-parent mods)))))

  (t/testing "reflow appends a reflow op to structure-parent"
    (let [mods (ctm/reflow (ctm/empty))]
      (t/is (= 1 (count (:structure-parent mods))))
      (t/is (= :reflow (-> mods :structure-parent first :type)))))

  (t/testing "scale-content appends a scale-content op to structure-child"
    (let [mods (ctm/scale-content (ctm/empty) 2.0)]
      (t/is (= 1 (count (:structure-child mods))))
      (t/is (= :scale-content (-> mods :structure-child first :type)))))

  (t/testing "change-property appends a change-property op to structure-parent"
    (let [mods (ctm/change-property (ctm/empty) :opacity 0.5)]
      (t/is (= 1 (count (:structure-parent mods))))
      (t/is (= :change-property (-> mods :structure-parent first :type)))
      (t/is (= :opacity (-> mods :structure-parent first :property)))
      (t/is (= 0.5 (-> mods :structure-parent first :value))))))

;; ─── Convenience builders ─────────────────────────────────────────────────────

(t/deftest convenience-builders
  (t/testing "move-modifiers returns a fresh Modifiers with a move in geometry-child"
    (let [mods (ctm/move-modifiers (gpt/point 5 10))]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (= :move (-> mods :geometry-child first :type)))))

  (t/testing "move-modifiers accepts x y arity"
    (let [mods (ctm/move-modifiers 5 10)]
      (t/is (= 1 (count (:geometry-child mods))))))

  (t/testing "resize-modifiers returns a fresh Modifiers with a resize in geometry-child"
    (let [mods (ctm/resize-modifiers (gpt/point 2 2) (gpt/point 0 0))]
      (t/is (= 1 (count (:geometry-child mods))))
      (t/is (= :resize (-> mods :geometry-child first :type)))))

  (t/testing "reflow-modifiers returns a fresh Modifiers with a reflow in structure-parent"
    (let [mods (ctm/reflow-modifiers)]
      (t/is (= 1 (count (:structure-parent mods))))
      (t/is (= :reflow (-> mods :structure-parent first :type)))))

  (t/testing "rotation-modifiers returns move + rotation in geometry-child"
    (let [shape (make-shape 100 100)
          mods  (ctm/rotation-modifiers shape (gpt/point 50 50) 90)]
      ;; rotation adds a :rotation and a :move to compensate for off-center
      (t/is (pos? (count (:geometry-child mods))))
      (t/is (some #(= :rotation (:type %)) (:geometry-child mods))))))

;; ─── add-modifiers ────────────────────────────────────────────────────────────

(t/deftest add-modifiers-combinator
  (t/testing "combining two disjoint move modifiers sums the vectors"
    (let [m1     (ctm/move-modifiers (gpt/point 10 0))
          m2     (ctm/move-modifiers (gpt/point 5 0))
          result (ctm/add-modifiers m1 m2)]
      ;; Both are pure geometry-child moves → they get merged
      (t/is (= 1 (count (:geometry-child result))))
      (t/is (mth/close? 15.0 (-> result :geometry-child first :vector :x)))))

  (t/testing "nil first argument is treated as empty"
    (let [m2     (ctm/move-modifiers (gpt/point 3 4))
          result (ctm/add-modifiers nil m2)]
      (t/is (= 1 (count (:geometry-child result))))))

  (t/testing "nil second argument is treated as empty"
    (let [m1     (ctm/move-modifiers (gpt/point 3 4))
          result (ctm/add-modifiers m1 nil)]
      (t/is (= 1 (count (:geometry-child result))))))

  (t/testing "last-order is the sum of both modifiers' orders"
    (let [m1     (ctm/move-modifiers (gpt/point 1 0))
          m2     (ctm/move-modifiers (gpt/point 2 0))
          result (ctm/add-modifiers m1 m2)]
      (t/is (= (+ (:last-order m1) (:last-order m2))
               (:last-order result))))))

;; ─── Predicates ───────────────────────────────────────────────────────────────

(t/deftest predicate-empty?
  (t/testing "fresh empty modifiers is empty?"
    (t/is (ctm/empty? (ctm/empty))))

  (t/testing "modifiers with a move are not empty?"
    (t/is (not (ctm/empty? (ctm/move-modifiers (gpt/point 1 0))))))

  (t/testing "modifiers with only a structure op are not empty?"
    (t/is (not (ctm/empty? (ctm/reflow-modifiers))))))

(t/deftest predicate-child-modifiers?
  (t/testing "move in geometry-child → child-modifiers? true"
    (t/is (ctm/child-modifiers? (ctm/move-modifiers (gpt/point 1 0)))))

  (t/testing "scale-content in structure-child → child-modifiers? true"
    (t/is (ctm/child-modifiers? (ctm/scale-content (ctm/empty) 2.0))))

  (t/testing "move-parent in geometry-parent only → child-modifiers? false"
    (t/is (not (ctm/child-modifiers? (ctm/move-parent (ctm/empty) (gpt/point 1 0)))))))

(t/deftest predicate-has-geometry?
  (t/testing "move in geometry-child → has-geometry? true"
    (t/is (ctm/has-geometry? (ctm/move-modifiers (gpt/point 1 0)))))

  (t/testing "move-parent in geometry-parent → has-geometry? true"
    (t/is (ctm/has-geometry? (ctm/move-parent (ctm/empty) (gpt/point 1 0)))))

  (t/testing "only structure ops → has-geometry? false"
    (t/is (not (ctm/has-geometry? (ctm/reflow-modifiers))))))

(t/deftest predicate-has-structure?
  (t/testing "reflow op in structure-parent → has-structure? true"
    (t/is (ctm/has-structure? (ctm/reflow-modifiers))))

  (t/testing "scale-content in structure-child → has-structure? true"
    (t/is (ctm/has-structure? (ctm/scale-content (ctm/empty) 2.0))))

  (t/testing "only geometry ops → has-structure? false"
    (t/is (not (ctm/has-structure? (ctm/move-modifiers (gpt/point 1 0)))))))

(t/deftest predicate-has-structure-child?
  (t/testing "scale-content in structure-child → has-structure-child? true"
    (t/is (ctm/has-structure-child? (ctm/scale-content (ctm/empty) 2.0))))

  (t/testing "reflow in structure-parent only → has-structure-child? false"
    (t/is (not (ctm/has-structure-child? (ctm/reflow-modifiers))))))

(t/deftest predicate-only-move?
  (t/testing "pure move modifiers → only-move? true"
    (t/is (ctm/only-move? (ctm/move-modifiers (gpt/point 1 0)))))

  (t/testing "resize modifiers → only-move? false"
    (t/is (not (ctm/only-move? (ctm/resize-modifiers (gpt/point 2 2) (gpt/point 0 0))))))

  (t/testing "structure ops present → only-move? false"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 1 0))
                   (ctm/reflow))]
      (t/is (not (ctm/only-move? mods)))))

  (t/testing "empty modifiers → only-move? true (vacuously)"
    (t/is (ctm/only-move? (ctm/empty)))))

;; ─── Projection functions ──────────────────────────────────────────────────────

(t/deftest projection-select-child
  (t/testing "select-child keeps geometry-child and structure-child, clears parent"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 1 0))
                   (ctm/move-parent (gpt/point 2 0))
                   (ctm/reflow)
                   (ctm/scale-content 2.0))
          result (ctm/select-child mods)]
      (t/is (= 1 (count (:geometry-child result))))
      (t/is (= 1 (count (:structure-child result))))
      (t/is (empty? (:geometry-parent result)))
      (t/is (empty? (:structure-parent result))))))

(t/deftest projection-select-parent
  (t/testing "select-parent keeps geometry-parent and structure-parent, clears child"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 1 0))
                   (ctm/move-parent (gpt/point 2 0))
                   (ctm/reflow)
                   (ctm/scale-content 2.0))
          result (ctm/select-parent mods)]
      (t/is (= 1 (count (:geometry-parent result))))
      (t/is (= 1 (count (:structure-parent result))))
      (t/is (empty? (:geometry-child result)))
      (t/is (empty? (:structure-child result))))))

(t/deftest projection-select-geometry
  (t/testing "select-geometry keeps both geometry lists, clears structure"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 1 0))
                   (ctm/move-parent (gpt/point 2 0))
                   (ctm/reflow)
                   (ctm/scale-content 2.0))
          result (ctm/select-geometry mods)]
      (t/is (= 1 (count (:geometry-child result))))
      (t/is (= 1 (count (:geometry-parent result))))
      (t/is (empty? (:structure-child result)))
      (t/is (empty? (:structure-parent result))))))

(t/deftest projection-select-child-structre-modifiers
  (t/testing "select-child-structre-modifiers keeps only structure-child"
    (let [mods (-> (ctm/empty)
                   (ctm/move (gpt/point 1 0))
                   (ctm/move-parent (gpt/point 2 0))
                   (ctm/reflow)
                   (ctm/scale-content 2.0))
          result (ctm/select-child-structre-modifiers mods)]
      (t/is (= 1 (count (:structure-child result))))
      (t/is (empty? (:geometry-child result)))
      (t/is (empty? (:geometry-parent result)))
      (t/is (empty? (:structure-parent result))))))

;; ─── added-children-frames ────────────────────────────────────────────────────

(t/deftest added-children-frames-test
  (t/testing "returns frame/shape pairs for add-children operations"
    (let [frame-id (uuid/next)
          shape-id (uuid/next)
          mods     (ctm/add-children (ctm/empty) [shape-id] nil)
          tree     {frame-id {:modifiers mods}}
          result   (ctm/added-children-frames tree)]
      (t/is (= 1 (count result)))
      (t/is (= frame-id (:frame (first result))))
      (t/is (= shape-id (:shape (first result))))))

  (t/testing "returns empty when there are no add-children operations"
    (let [frame-id (uuid/next)
          mods     (ctm/reflow-modifiers)
          tree     {frame-id {:modifiers mods}}
          result   (ctm/added-children-frames tree)]
      (t/is (empty? result))))

  (t/testing "returns empty for an empty modif-tree"
    (t/is (empty? (ctm/added-children-frames {})))))

;; ─── apply-modifier and apply-structure-modifiers ─────────────────────────────

(t/deftest apply-modifier-test
  (t/testing "rotation op increments shape :rotation field"
    (let [shape  (make-shape 100 50)
          op     (-> (ctm/rotation (ctm/empty) (gpt/point 50 25) 90)
                     :structure-child
                     first)
          result (ctm/apply-modifier shape op)]
      (t/is (mth/close? 90.0 (:rotation result)))))

  (t/testing "rotation wraps around 360"
    (let [shape  (assoc (make-shape 100 50) :rotation 350)
          op     (-> (ctm/rotation (ctm/empty) (gpt/point 50 25) 20)
                     :structure-child
                     first)
          result (ctm/apply-modifier shape op)]
      (t/is (mth/close? 10.0 (:rotation result)))))

  (t/testing "add-children op appends ids to shape :shapes"
    (let [id1   (uuid/next)
          id2   (uuid/next)
          shape (assoc (make-shape 100 50) :shapes [])
          op    (-> (ctm/add-children (ctm/empty) [id1 id2] nil)
                    :structure-parent
                    first)
          result (ctm/apply-modifier shape op)]
      (t/is (= [id1 id2] (:shapes result)))))

  (t/testing "add-children op with index inserts at the given position"
    (let [id-existing (uuid/next)
          id-new      (uuid/next)
          shape       (assoc (make-shape 100 50) :shapes [id-existing])
          op          (-> (ctm/add-children (ctm/empty) [id-new] 0)
                          :structure-parent
                          first)
          result      (ctm/apply-modifier shape op)]
      (t/is (= id-new (first (:shapes result))))))

  (t/testing "remove-children op removes given ids from shape :shapes"
    (let [id1   (uuid/next)
          id2   (uuid/next)
          shape (assoc (make-shape 100 50) :shapes [id1 id2])
          op    (-> (ctm/remove-children (ctm/empty) [id1])
                    :structure-parent
                    first)
          result (ctm/apply-modifier shape op)]
      (t/is (= [id2] (:shapes result)))))

  (t/testing "change-property op sets the property on the shape"
    (let [shape  (make-shape 100 50)
          op     (-> (ctm/change-property (ctm/empty) :opacity 0.5)
                     :structure-parent
                     first)
          result (ctm/apply-modifier shape op)]
      (t/is (= 0.5 (:opacity result)))))

  (t/testing "unknown op type returns shape unchanged"
    (let [shape  (make-shape 100 50)
          result (ctm/apply-modifier shape {:type :unknown})]
      (t/is (= shape result)))))

(t/deftest apply-structure-modifiers-test
  (t/testing "applies structure-parent and structure-child ops in order"
    (let [id    (uuid/next)
          shape (assoc (make-shape 100 50) :shapes [])
          mods  (-> (ctm/empty)
                    (ctm/add-children [id] nil)
                    (ctm/scale-content 1.0))
          result (ctm/apply-structure-modifiers shape mods)]
      (t/is (= [id] (:shapes result)))))

  (t/testing "empty modifiers returns shape unchanged"
    (let [shape  (make-shape 100 50)
          result (ctm/apply-structure-modifiers shape (ctm/empty))]
      (t/is (= shape result))))

  (t/testing "change-property in structure-parent is applied"
    (let [shape  (make-shape 100 50)
          mods   (ctm/change-property (ctm/empty) :opacity 0.3)
          result (ctm/apply-structure-modifiers shape mods)]
      (t/is (= 0.3 (:opacity result)))))

  (t/testing "rotation in structure-child is applied"
    (let [shape  (make-shape 100 50)
          mods   (ctm/rotation (ctm/empty) (gpt/point 50 25) 45)
          result (ctm/apply-structure-modifiers shape mods)]
      (t/is (mth/close? 45.0 (:rotation result))))))
