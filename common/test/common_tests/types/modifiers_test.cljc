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
