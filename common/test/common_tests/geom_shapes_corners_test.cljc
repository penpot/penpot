;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-corners-test
  (:require
   [app.common.geom.shapes.corners :as gco]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest fix-radius-single-value-test
  (t/testing "Radius fits within the shape"
    ;; width=100, height=50, r=10 → min(1, 100/20=5, 50/20=2.5) = 1 → no clamping
    (t/is (mth/close? 10.0 (gco/fix-radius 100 50 10)))
    (t/is (mth/close? 5.0 (gco/fix-radius 100 50 5))))

  (t/testing "Radius exceeds half the width → clamped"
    ;; width=10, height=50, r=100 → min(1, 10/200=0.05, 50/200=0.25) = 0.05 → r=5
    (t/is (mth/close? 5.0 (gco/fix-radius 10 50 100))))

  (t/testing "Radius exceeds half the height → clamped"
    ;; width=100, height=10, r=100 → min(1, 100/200=0.5, 10/200=0.05) = 0.05 → r=5
    (t/is (mth/close? 5.0 (gco/fix-radius 100 10 100))))

  (t/testing "Zero radius stays zero"
    (t/is (mth/close? 0.0 (gco/fix-radius 100 100 0))))

  (t/testing "Zero dimensions with nonzero radius → r becomes 0"
    (t/is (mth/close? 0.0 (gco/fix-radius 0 100 50)))))

(t/deftest fix-radius-four-values-test
  (t/testing "All radii fit"
    (let [[r1 r2 r3 r4] (gco/fix-radius 100 100 5 10 15 20)]
      (t/is (mth/close? 5.0 r1))
      (t/is (mth/close? 10.0 r2))
      (t/is (mth/close? 15.0 r3))
      (t/is (mth/close? 20.0 r4))))

  (t/testing "Radii exceed shape dimensions → proportionally reduced"
    (let [[r1 r2 r3 r4] (gco/fix-radius 10 10 50 50 50 50)]
      ;; width=10, r1+r2=100 → f=min(1, 10/100, 10/100, 10/100, 10/100)=0.1
      (t/is (mth/close? 5.0 r1))
      (t/is (mth/close? 5.0 r2))
      (t/is (mth/close? 5.0 r3))
      (t/is (mth/close? 5.0 r4))))

  (t/testing "Only one pair exceeds → reduce all proportionally"
    (let [[r1 r2 r3 r4] (gco/fix-radius 20 100 15 15 5 5)]
      ;; r1+r2=30 > width=20 → f=20/30=0.667
      (t/is (mth/close? (* 15.0 (/ 20.0 30.0)) r1))
      (t/is (mth/close? (* 15.0 (/ 20.0 30.0)) r2))
      (t/is (mth/close? (* 5.0 (/ 20.0 30.0)) r3))
      (t/is (mth/close? (* 5.0 (/ 20.0 30.0)) r4)))))

(t/deftest shape-corners-1-test
  (t/testing "Shape with single corner radius"
    (t/is (mth/close? 10.0 (gco/shape-corners-1 {:width 100 :height 50 :r1 10}))))

  (t/testing "Shape with nil r1"
    (t/is (= 0 (gco/shape-corners-1 {:width 100 :height 50 :r1 nil}))))

  (t/testing "Shape with r1=0"
    (t/is (= 0 (gco/shape-corners-1 {:width 100 :height 50 :r1 0})))))

(t/deftest shape-corners-4-test
  (t/testing "Shape with four corner radii"
    (let [[r1 r2 r3 r4] (gco/shape-corners-4 {:width 100 :height 100 :r1 5 :r2 10 :r3 15 :r4 20})]
      (t/is (mth/close? 5.0 r1))
      (t/is (mth/close? 10.0 r2))
      (t/is (mth/close? 15.0 r3))
      (t/is (mth/close? 20.0 r4))))

  (t/testing "Shape with nil corners returns [nil nil nil nil]"
    (let [result (gco/shape-corners-4 {:width 100 :height 100 :r1 nil :r2 nil :r3 nil :r4 nil})]
      (t/is (= [nil nil nil nil] result)))))

(t/deftest update-corners-scale-test
  (t/testing "Scale corner radii"
    (let [shape {:r1 10 :r2 20 :r3 30 :r4 40}
          scaled (gco/update-corners-scale shape 2)]
      (t/is (= 20 (:r1 scaled)))
      (t/is (= 40 (:r2 scaled)))
      (t/is (= 60 (:r3 scaled)))
      (t/is (= 80 (:r4 scaled)))))

  (t/testing "Scale by 1 keeps values the same"
    (let [shape {:r1 10 :r2 20 :r3 30 :r4 40}
          scaled (gco/update-corners-scale shape 1)]
      (t/is (= 10 (:r1 scaled)))
      (t/is (= 20 (:r2 scaled)))
      (t/is (= 30 (:r3 scaled)))
      (t/is (= 40 (:r4 scaled)))))

  (t/testing "Scale by 0 zeroes all radii"
    (let [shape {:r1 10 :r2 20 :r3 30 :r4 40}
          scaled (gco/update-corners-scale shape 0)]
      (t/is (= 0 (:r1 scaled)))
      (t/is (= 0 (:r2 scaled)))
      (t/is (= 0 (:r3 scaled)))
      (t/is (= 0 (:r4 scaled))))))
