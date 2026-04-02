;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-common-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest points->center-test
  (t/testing "Center of a unit square"
    (let [points [(gpt/point 0 0) (gpt/point 10 0)
                  (gpt/point 10 10) (gpt/point 0 10)]
          center (gco/points->center points)]
      (t/is (mth/close? 5.0 (:x center)))
      (t/is (mth/close? 5.0 (:y center)))))

  (t/testing "Center of a rectangle"
    (let [points [(gpt/point 0 0) (gpt/point 20 0)
                  (gpt/point 20 10) (gpt/point 0 10)]
          center (gco/points->center points)]
      (t/is (mth/close? 10.0 (:x center)))
      (t/is (mth/close? 5.0 (:y center)))))

  (t/testing "Center of a translated square"
    (let [points [(gpt/point 100 200) (gpt/point 150 200)
                  (gpt/point 150 250) (gpt/point 100 250)]
          center (gco/points->center points)]
      (t/is (mth/close? 125.0 (:x center)))
      (t/is (mth/close? 225.0 (:y center))))))

(t/deftest shape->center-test
  (t/testing "Center from shape selrect (proper rect record)"
    (let [shape {:selrect (grc/make-rect 10 20 100 50)}
          center (gco/shape->center shape)]
      (t/is (mth/close? 60.0 (:x center)))
      (t/is (mth/close? 45.0 (:y center))))))

(t/deftest transform-points-test
  (t/testing "Transform with identity matrix leaves points unchanged"
    (let [points [(gpt/point 0 0) (gpt/point 10 0)
                  (gpt/point 10 10) (gpt/point 0 10)]
          result (gco/transform-points points (gmt/matrix))]
      (doseq [[p r] (map vector points result)]
        (t/is (mth/close? (:x p) (:x r)))
        (t/is (mth/close? (:y p) (:y r))))))

  (t/testing "Transform with translation matrix"
    (let [points [(gpt/point 0 0) (gpt/point 10 0)
                  (gpt/point 10 10) (gpt/point 0 10)]
          mtx    (gmt/translate-matrix (gpt/point 5 10))
          result (gco/transform-points points mtx)]
      (t/is (mth/close? 5.0 (:x (first result))))
      (t/is (mth/close? 10.0 (:y (first result))))))

  (t/testing "Transform around a center point"
    (let [points [(gpt/point 0 0) (gpt/point 10 0)
                  (gpt/point 10 10) (gpt/point 0 10)]
          center (gco/points->center points)
          mtx    (gmt/scale-matrix (gpt/point 2 2))
          result (gco/transform-points points center mtx)]
      ;; Scaling around center (5,5) by 2x: (0,0)→(-5,-5)
      (t/is (mth/close? -5.0 (:x (first result))))
      (t/is (mth/close? -5.0 (:y (first result))))))

  (t/testing "Transform with nil matrix returns points unchanged"
    (let [points [(gpt/point 1 2) (gpt/point 3 4)]
          result (gco/transform-points points nil)]
      (t/is (= points result))))

  (t/testing "Transform empty points returns empty"
    (let [result (gco/transform-points [] (gmt/matrix))]
      (t/is (= [] result)))))

(t/deftest invalid-geometry?-test
  (t/testing "Valid geometry is not invalid"
    (let [shape {:selrect (grc/make-rect 0 0 100 50)
                 :points [(gpt/point 0 0) (gpt/point 100 0)
                          (gpt/point 100 50) (gpt/point 0 50)]}]
      (t/is (not (gco/invalid-geometry? shape)))))

  (t/testing "NaN x in selrect is invalid"
    (let [selrect (grc/make-rect 0 0 100 50)
          selrect (assoc selrect :x ##NaN)
          shape   {:selrect selrect
                   :points [(gpt/point 0 0) (gpt/point 100 0)
                            (gpt/point 100 50) (gpt/point 0 50)]}]
      (t/is (true? (gco/invalid-geometry? shape)))))

  (t/testing "NaN in points is invalid"
    (let [shape {:selrect (grc/make-rect 0 0 100 50)
                 :points [(gpt/point ##NaN 0) (gpt/point 100 0)
                          (gpt/point 100 50) (gpt/point 0 50)]}]
      (t/is (true? (gco/invalid-geometry? shape))))))

(t/deftest shape->points-test
  (t/testing "Identity transform uses reconstructed points from corners"
    (let [points [(gpt/point 10 20) (gpt/point 40 20)
                  (gpt/point 40 60) (gpt/point 10 60)]
          shape  {:transform (gmt/matrix) :points points}
          result (gco/shape->points shape)]
      (t/is (= 4 (count result)))
      ;; p0 and p2 are used to reconstruct p1 and p3
      (t/is (mth/close? 10.0 (:x (nth result 0))))
      (t/is (mth/close? 20.0 (:y (nth result 0))))
      (t/is (mth/close? 40.0 (:x (nth result 2))))
      (t/is (mth/close? 60.0 (:y (nth result 2))))))

  (t/testing "Non-identity transform returns points as-is"
    (let [points [(gpt/point 10 20) (gpt/point 40 20)
                  (gpt/point 40 60) (gpt/point 10 60)]
          shape  {:transform (gmt/translate-matrix (gpt/point 5 5)) :points points}
          result (gco/shape->points shape)]
      (t/is (= points result)))))

(t/deftest transform-selrect-test
  (t/testing "Transform selrect with identity matrix"
    (let [selrect (grc/make-rect 10 20 100 50)
          result  (gco/transform-selrect selrect (gmt/matrix))]
      (t/is (mth/close? 10.0 (:x result)))
      (t/is (mth/close? 20.0 (:y result)))
      (t/is (mth/close? 100.0 (:width result)))
      (t/is (mth/close? 50.0 (:height result)))))

  (t/testing "Transform selrect with translation"
    (let [selrect (grc/make-rect 0 0 100 50)
          mtx     (gmt/translate-matrix (gpt/point 10 20))
          result  (gco/transform-selrect selrect mtx)]
      (t/is (mth/close? 10.0 (:x result)))
      (t/is (mth/close? 20.0 (:y result))))))
