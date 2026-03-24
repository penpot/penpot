;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-grid-test
  (:require
   [app.common.geom.grid :as gg]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest calculate-default-item-length-test
  (t/testing "Default item length with typical grid parameters"
    ;; frame-length=1200, margin=64, gutter=16, default-items=12
    ;; result = (1200 - (64 + 64 - 16) - 16*12) / 12
    ;;        = (1200 - 112 - 192) / 12 = 896/12 = 74.667
    (let [result (gg/calculate-default-item-length 1200 64 16)]
      (t/is (mth/close? (/ 896.0 12.0) result))))

  (t/testing "Zero margin and gutter"
    (let [result (gg/calculate-default-item-length 1200 0 0)]
      (t/is (mth/close? 100.0 result)))))

(t/deftest calculate-size-test
  (t/testing "Calculate size with explicit item-length"
    ;; frame-length=1000, item-length=100, margin=0, gutter=0
    ;; frame-length-no-margins = 1000
    ;; size = floor(1000 / 100) = 10
    (t/is (mth/close? 10.0 (gg/calculate-size 1000 100 0 0))))

  (t/testing "Calculate size with gutter"
    ;; frame-length=1000, item-length=100, margin=0, gutter=10
    ;; frame-length-no-margins = 1000
    ;; size = floor(1000 / 110) = 9
    (t/is (mth/close? 9.0 (gg/calculate-size 1000 100 0 10))))

  (t/testing "Calculate size with nil item-length uses default"
    (t/is (pos? (gg/calculate-size 1200 nil 64 16)))))

(t/deftest grid-area-points-test
  (t/testing "Converts rect to 4 points"
    (let [rect   {:x 10 :y 20 :width 100 :height 50}
          points (gg/grid-area-points rect)]
      (t/is (= 4 (count points)))
      (t/is (gpt/point? (first points)))
      (t/is (mth/close? 10.0 (:x (first points))))
      (t/is (mth/close? 20.0 (:y (first points))))
      (t/is (mth/close? 110.0 (:x (nth points 1))))
      (t/is (mth/close? 20.0 (:y (nth points 1))))
      (t/is (mth/close? 110.0 (:x (nth points 2))))
      (t/is (mth/close? 70.0 (:y (nth points 2))))
      (t/is (mth/close? 10.0 (:x (nth points 3))))
      (t/is (mth/close? 70.0 (:y (nth points 3)))))))

(t/deftest grid-areas-column-test
  (t/testing "Column grid generates correct number of areas"
    (let [frame {:x 0 :y 0 :width 300 :height 200}
          grid  {:type :column
                 :params {:size 3 :gutter 0 :margin 0 :item-length 100 :type :stretch}}
          areas (gg/grid-areas frame grid)]
      (t/is (= 3 (count areas)))
      (doseq [area areas]
        (t/is (contains? area :x))
        (t/is (contains? area :y))
        (t/is (contains? area :width))
        (t/is (contains? area :height))))))

(t/deftest grid-areas-square-test
  (t/testing "Square grid generates areas"
    (let [frame {:x 0 :y 0 :width 300 :height 200}
          grid  {:type :square :params {:size 50}}
          areas (gg/grid-areas frame grid)]
      (t/is (pos? (count areas)))
      (doseq [area areas]
        (t/is (= 50 (:width area)))
        (t/is (= 50 (:height area)))))))

(t/deftest grid-snap-points-test
  (t/testing "Square grid snap points on x-axis"
    (let [shape {:x 0 :y 0 :width 200 :height 100}
          grid  {:type :square :params {:size 50} :display true}
          points (gg/grid-snap-points shape grid :x)]
      (t/is (some? points))
      (t/is (every? gpt/point? points))))

  (t/testing "Grid without display returns nil"
    (let [shape {:x 0 :y 0 :width 200 :height 100}
          grid  {:type :square :params {:size 50} :display false}
          points (gg/grid-snap-points shape grid :x)]
      (t/is (nil? points))))

  (t/testing "Column grid snap points on y-axis returns nil"
    (let [shape {:x 0 :y 0 :width 300 :height 200}
          grid  {:type :column
                 :params {:size 3 :gutter 0 :margin 0 :item-length 100 :type :stretch}
                 :display true}
          points (gg/grid-snap-points shape grid :y)]
      (t/is (nil? points)))))
