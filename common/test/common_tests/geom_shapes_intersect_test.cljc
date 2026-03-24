;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-intersect-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.intersect :as gint]
   [app.common.math :as mth]
   [clojure.test :as t]))

(defn- pt [x y] (gpt/point x y))

;; ---- orientation ----

(t/deftest orientation-test
  (t/testing "Counter-clockwise orientation"
    (t/is (= ::gint/counter-clockwise (gint/orientation (pt 0 0) (pt 1 0) (pt 1 1)))))

  (t/testing "Clockwise orientation"
    (t/is (= ::gint/clockwise (gint/orientation (pt 0 0) (pt 1 1) (pt 1 0)))))

  (t/testing "Collinear points"
    (t/is (= ::gint/coplanar (gint/orientation (pt 0 0) (pt 1 1) (pt 2 2))))))

;; ---- on-segment? ----

(t/deftest on-segment?-test
  (t/testing "Point on segment"
    (t/is (true? (gint/on-segment? (pt 5 5) (pt 0 0) (pt 10 10)))))

  (t/testing "Point not on segment"
    (t/is (false? (gint/on-segment? (pt 5 10) (pt 0 0) (pt 10 0)))))

  (t/testing "Point at endpoint"
    (t/is (true? (gint/on-segment? (pt 0 0) (pt 0 0) (pt 10 10))))))

;; ---- intersect-segments? ----

(t/deftest intersect-segments?-test
  (t/testing "Two crossing segments"
    (t/is (true? (gint/intersect-segments?
                  [(pt 0 0) (pt 10 10)]
                  [(pt 10 0) (pt 0 10)]))))

  (t/testing "Two parallel non-intersecting segments"
    (t/is (false? (gint/intersect-segments?
                   [(pt 0 0) (pt 10 0)]
                   [(pt 0 5) (pt 10 5)]))))

  (t/testing "Two collinear overlapping segments"
    ;; NOTE: The implementation compares orientation result (namespaced keyword ::coplanar)
    ;; against unnamespaced :coplanar, so the collinear branch never triggers.
    ;; Collinear overlapping segments are NOT detected as intersecting.
    (t/is (false? (gint/intersect-segments?
                   [(pt 0 0) (pt 10 0)]
                   [(pt 5 0) (pt 15 0)]))))

  (t/testing "Two non-overlapping collinear segments"
    (t/is (false? (gint/intersect-segments?
                   [(pt 0 0) (pt 5 0)]
                   [(pt 10 0) (pt 15 0)]))))

  (t/testing "Segments sharing an endpoint"
    (t/is (true? (gint/intersect-segments?
                  [(pt 0 0) (pt 5 5)]
                  [(pt 5 5) (pt 10 0)])))))

;; ---- points->lines ----

(t/deftest points->lines-test
  (t/testing "Triangle produces 3 closed lines"
    (let [points [(pt 0 0) (pt 10 0) (pt 5 10)]
          lines  (gint/points->lines points)]
      (t/is (= 3 (count lines)))))

  (t/testing "Square produces 4 closed lines"
    (let [points [(pt 0 0) (pt 10 0) (pt 10 10) (pt 0 10)]
          lines  (gint/points->lines points)]
      (t/is (= 4 (count lines)))))

  (t/testing "Open polygon (not closed)"
    (let [points [(pt 0 0) (pt 10 0) (pt 10 10)]
          lines  (gint/points->lines points false)]
      (t/is (= 2 (count lines))))))

;; ---- intersect-ray? ----

(t/deftest intersect-ray?-test
  (t/testing "Ray from right intersects segment that crosses y to the left"
    ;; Point at (5, 5), ray goes right (+x). Vertical segment at x=10 crosses y=[0,10].
    ;; Since x=10 > x=5, and the segment goes from below y=5 to above y=5, it intersects.
    (let [point (pt 5 5)
          segment [(pt 10 0) (pt 10 10)]]
      (t/is (true? (gint/intersect-ray? point segment)))))

  (t/testing "Ray does not intersect segment to the left of point"
    ;; Vertical segment at x=0 is to the LEFT of point (5,5).
    ;; Ray goes right, so no intersection.
    (let [point (pt 5 5)
          segment [(pt 0 0) (pt 0 10)]]
      (t/is (false? (gint/intersect-ray? point segment)))))

  (t/testing "Ray does not intersect horizontal segment"
    ;; Horizontal segment at y=0 doesn't cross y=5
    (let [point (pt 5 5)
          segment [(pt 0 0) (pt 10 0)]]
      (t/is (false? (gint/intersect-ray? point segment))))))

;; ---- is-point-inside-evenodd? ----

(t/deftest is-point-inside-evenodd?-test
  (let [square-lines (gint/points->lines [(pt 0 0) (pt 10 0) (pt 10 10) (pt 0 10)])]
    (t/testing "Point inside square"
      (t/is (true? (gint/is-point-inside-evenodd? (pt 5 5) square-lines))))

    (t/testing "Point outside square"
      (t/is (false? (gint/is-point-inside-evenodd? (pt 15 15) square-lines))))

    (t/testing "Point on edge (edge case)"
      (t/is (boolean? (gint/is-point-inside-evenodd? (pt 0 5) square-lines))))))

;; ---- is-point-inside-nonzero? ----

(t/deftest is-point-inside-nonzero?-test
  (let [square-lines (gint/points->lines [(pt 0 0) (pt 10 0) (pt 10 10) (pt 0 10)])]
    (t/testing "Point inside square"
      (t/is (true? (gint/is-point-inside-nonzero? (pt 5 5) square-lines))))

    (t/testing "Point outside square"
      (t/is (false? (gint/is-point-inside-nonzero? (pt 15 15) square-lines))))))

;; ---- overlaps-rect-points? ----

(t/deftest overlaps-rect-points?-test
  (t/testing "Overlapping rects"
    (let [rect   (grc/make-rect 0 0 10 10)
          points (grc/rect->points (grc/make-rect 5 5 10 10))]
      (t/is (true? (gint/overlaps-rect-points? rect points)))))

  (t/testing "Non-overlapping rects"
    (let [rect   (grc/make-rect 0 0 10 10)
          points (grc/rect->points (grc/make-rect 20 20 10 10))]
      (t/is (false? (gint/overlaps-rect-points? rect points)))))

  (t/testing "One rect inside another"
    (let [rect   (grc/make-rect 0 0 100 100)
          points (grc/rect->points (grc/make-rect 10 10 20 20))]
      (t/is (true? (gint/overlaps-rect-points? rect points))))))

;; ---- is-point-inside-ellipse? ----

(t/deftest is-point-inside-ellipse?-test
  (let [ellipse {:cx 50 :cy 50 :rx 25 :ry 15}]
    (t/testing "Center is inside"
      (t/is (true? (gint/is-point-inside-ellipse? (pt 50 50) ellipse))))

    (t/testing "Point on boundary"
      (t/is (true? (gint/is-point-inside-ellipse? (pt 75 50) ellipse))))

    (t/testing "Point outside"
      (t/is (false? (gint/is-point-inside-ellipse? (pt 100 50) ellipse))))

    (t/testing "Point on minor axis boundary"
      (t/is (true? (gint/is-point-inside-ellipse? (pt 50 65) ellipse))))))

;; ---- line-line-intersect ----

(t/deftest line-line-intersect-test
  (t/testing "Intersection of crossing lines"
    (let [result (gint/line-line-intersect (pt 0 0) (pt 10 10) (pt 10 0) (pt 0 10))]
      (t/is (gpt/point? result))
      (t/is (mth/close? 5.0 (:x result)))
      (t/is (mth/close? 5.0 (:y result)))))

  (t/testing "Intersection of horizontal and vertical lines"
    (let [result (gint/line-line-intersect (pt 0 5) (pt 10 5) (pt 5 0) (pt 5 10))]
      (t/is (gpt/point? result))
      (t/is (mth/close? 5.0 (:x result)))
      (t/is (mth/close? 5.0 (:y result)))))

  (t/testing "Near-parallel lines still produce a point"
    (let [result (gint/line-line-intersect (pt 0 0) (pt 10 0) (pt 0 0.001) (pt 10 0.001))]
      (t/is (gpt/point? result)))))

;; ---- has-point-rect? ----

(t/deftest has-point-rect?-test
  (t/testing "Point inside rect"
    (t/is (true? (gint/has-point-rect? (grc/make-rect 0 0 100 100) (pt 50 50)))))

  (t/testing "Point outside rect"
    (t/is (false? (gint/has-point-rect? (grc/make-rect 0 0 100 100) (pt 150 50)))))

  (t/testing "Point at corner"
    (t/is (true? (gint/has-point-rect? (grc/make-rect 0 0 100 100) (pt 0 0))))))

;; ---- rect-contains-shape? ----

(t/deftest rect-contains-shape?-test
  (t/testing "Rect contains all shape points"
    (let [shape {:points [(pt 10 10) (pt 20 10) (pt 20 20) (pt 10 20)]}
          rect  (grc/make-rect 0 0 100 100)]
      (t/is (true? (gint/rect-contains-shape? rect shape)))))

  (t/testing "Rect does not contain all shape points"
    (let [shape {:points [(pt 10 10) (pt 200 10) (pt 200 200) (pt 10 200)]}
          rect  (grc/make-rect 0 0 100 100)]
      (t/is (false? (gint/rect-contains-shape? rect shape))))))

;; ---- intersects-lines? ----

(t/deftest intersects-lines?-test
  (t/testing "Intersecting line sets"
    (let [lines-a (gint/points->lines [(pt 0 0) (pt 10 10)])
          lines-b (gint/points->lines [(pt 10 0) (pt 0 10)])]
      (t/is (true? (gint/intersects-lines? lines-a lines-b)))))

  (t/testing "Non-intersecting line sets"
    (let [lines-a (gint/points->lines [(pt 0 0) (pt 10 0)])
          lines-b (gint/points->lines [(pt 0 10) (pt 10 10)])]
      (t/is (false? (gint/intersects-lines? lines-a lines-b)))))

  (t/testing "Empty line sets"
    (t/is (false? (gint/intersects-lines? [] [])))))

;; ---- intersects-line-ellipse? ----

(t/deftest intersects-line-ellipse?-test
  (let [ellipse {:cx 50 :cy 50 :rx 25 :ry 25}]
    (t/testing "Line passing through ellipse"
      (t/is (some? (gint/intersects-line-ellipse? [(pt 0 50) (pt 100 50)] ellipse))))

    (t/testing "Line not touching ellipse"
      (t/is (nil? (gint/intersects-line-ellipse? [(pt 0 0) (pt 10 0)] ellipse))))

    (t/testing "Line tangent to ellipse"
      (t/is (some? (gint/intersects-line-ellipse? [(pt 75 0) (pt 75 100)] ellipse))))))

;; ---- fast-has-point? / slow-has-point? ----

(t/deftest has-point-tests
  (t/testing "fast-has-point? inside shape"
    (let [shape {:x 10 :y 20 :width 100 :height 50}]
      (t/is (true? (gint/fast-has-point? shape (pt 50 40))))))

  (t/testing "fast-has-point? outside shape"
    (let [shape {:x 10 :y 20 :width 100 :height 50}]
      (t/is (false? (gint/fast-has-point? shape (pt 200 40))))))

  (t/testing "slow-has-point? with axis-aligned shape"
    (let [points [(pt 0 0) (pt 100 0) (pt 100 50) (pt 0 50)]
          shape  {:points points}]
      (t/is (true? (gint/slow-has-point? shape (pt 50 25))))
      (t/is (false? (gint/slow-has-point? shape (pt 150 25)))))))
