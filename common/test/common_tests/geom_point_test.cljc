;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-point-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest add-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/add p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 3 (:x rs)))
    (t/is (mth/close? 5 (:y rs)))))

(t/deftest substract-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/subtract p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? -1 (:x rs)))
    (t/is (mth/close? -1 (:y rs)))))

(t/deftest multiply-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/multiply p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 2 (:x rs)))
    (t/is (mth/close? 6 (:y rs)))))

(t/deftest divide-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 5)
        rs (gpt/divide p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 0.5 (:x rs)))
    (t/is (mth/close? 0.4 (:y rs)))))

(t/deftest min-point
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 5)]

    (let [rs (gpt/min)]
      (t/is (nil? rs)))

    (let [rs (gpt/min p1)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/min nil p1)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/min p1 nil)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/min p1 p2)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/min p2 p1)]
      (t/is (gpt/close? rs p1)))))

(t/deftest max-point
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 5)]

    (let [rs (gpt/max)]
      (t/is (nil? rs)))

    (let [rs (gpt/max p1)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/max nil p1)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/max p1 nil)]
      (t/is (gpt/close? rs p1)))

    (let [rs (gpt/max p1 p2)]
      (t/is (gpt/close? rs p2)))

    (let [rs (gpt/max p2 p1)]
      (t/is (gpt/close? rs p2)))))

(t/deftest inverse-point
  (let [p1 (gpt/point 1 2)
        rs (gpt/inverse p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1 (:x rs)))
    (t/is (mth/close? 0.5 (:y rs)))))

(t/deftest negate-point
  (let [p1 (gpt/point 1 2)
        rs (gpt/negate p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? -1 (:x rs)))
    (t/is (mth/close? -2 (:y rs)))))

(t/deftest distance-between-two-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 4 6)
        rs (gpt/distance p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? 5 rs))))

(t/deftest distance-vector-between-two-points
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/distance-vector p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1 (:x rs)))
    (t/is (mth/close? 1 (:y rs)))))

(t/deftest point-length
  (let [p1 (gpt/point 1 10)
        rs (gpt/length p1)]
    (t/is (number? rs))
    (t/is (mth/close? 10.04987562112089 rs))))

(t/deftest point-angle-1
  (let [p1 (gpt/point 1 3)
        rs (gpt/angle p1)]
    (t/is (number? rs))
    (t/is (mth/close? 71.56505117707799 rs))))

(t/deftest point-angle-2
  (let [p1 (gpt/point 1 3)
        p2 (gpt/point 2 4)
        rs (gpt/angle p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? -135 rs))))

(t/deftest point-angle-with-other
  (let [p1 (gpt/point 1 3)
        p2 (gpt/point 1 5)
        rs (gpt/angle-with-other p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? 7.125016348901757 rs))))

(t/deftest point-angle-sign
  (let [p1 (gpt/point 1 3)
        p2 (gpt/point 1 5)
        rs (gpt/angle-sign p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? 1 rs)))

  (let [p1 (gpt/point -11 -3)
        p2 (gpt/point 1 5)
        rs (gpt/angle-sign p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? -1 rs))))

(t/deftest update-angle
  (let [p1 (gpt/point 1 3)
        rs (gpt/update-angle p1 10)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 3.1142355569111246 (:x rs)))
    (t/is (mth/close? 0.5491237529650835 (:y rs)))))


(t/deftest point-quadrant
  (let [p1 (gpt/point 1 3)
        rs (gpt/quadrant p1)]
    (t/is (number? rs))
    (t/is (mth/close? 1 rs)))

  (let [p1 (gpt/point 1 -3)
        rs (gpt/quadrant p1)]
    (t/is (number? rs))
    (t/is (mth/close? 4 rs)))

  (let [p1 (gpt/point -1 3)
        rs (gpt/quadrant p1)]
    (t/is (number? rs))
    (t/is (mth/close? 2 rs)))

  (let [p1 (gpt/point -1 -3)
        rs (gpt/quadrant p1)]
    (t/is (number? rs))
    (t/is (mth/close? 3 rs))))

(t/deftest round-point
  (let [p1 (gpt/point 1.34567 3.34567)
        rs (gpt/round p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1 (:x rs)))
    (t/is (mth/close? 3 (:y rs))))

  (let [p1 (gpt/point 1.34567 3.34567)
        rs (gpt/round p1 2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1.35 (:x rs)))
    (t/is (mth/close? 3.35 (:y rs)))))

(t/deftest halft-round-point
  (let [p1 (gpt/point 1.34567 3.34567)
        rs (gpt/round-step p1 0.5)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1.5 (:x rs)))
    (t/is (mth/close? 3.5 (:y rs)))))

(t/deftest ^:kaocha/skip transform-point
  ;;todo
  )

(t/deftest scale-point
  (let [p1 (gpt/point 1.5 3)
        rs (gpt/scale p1 2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 3 (:x rs)))
    (t/is (mth/close? 6 (:y rs)))))

(t/deftest dot-point
  (let [p1 (gpt/point 1.5 3)
        p2 (gpt/point 2 6)
        rs (gpt/dot p1 p2)]
    (t/is (number? rs))
    (t/is (mth/close? 21 rs))))

(t/deftest unit-point
  (let [p1 (gpt/point 2 3)
        rs (gpt/unit p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 0.5547001962252291 (:x rs)))
    (t/is (mth/close? 0.8320502943378437 (:y rs)))))

(t/deftest project-point
  (let [p1 (gpt/point 1 3)
        p2 (gpt/point 1 6)
        rs (gpt/project p1 p2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 0.5135135135135135 (:x rs)))
    (t/is (mth/close? 3.081081081081081 (:y rs)))))

(t/deftest center-points
  (let [points [(gpt/point 0.5 0.5)
                (gpt/point -1 -2)
                (gpt/point 20 65.2)
                (gpt/point 12 -10)]
        rs     (gpt/center-points points)]
    (t/is (mth/close? 7.875 (:x rs)))
    (t/is (mth/close? 13.425 (:y rs)))))

(t/deftest normal-left-point
  (let [p1 (gpt/point 2 3)
        rs (gpt/normal-left p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? -0.8320502943378437 (:x rs)))
    (t/is (mth/close? 0.5547001962252291 (:y rs)))))

(t/deftest normal-right-point
  (let [p1 (gpt/point 2 3)
        rs (gpt/normal-right p1)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 0.8320502943378437 (:x rs)))
    (t/is (mth/close? -0.5547001962252291 (:y rs)))))

(t/deftest point-line-distance
  (let [p1 (gpt/point 2 -3)
        p2 (gpt/point -1 4)
        p3 (gpt/point 5 6)
        rs (gpt/point-line-distance p1 p2 p3)]
    (t/is (number? rs))
    (t/is (mth/close? 7.58946638440411 rs))))

(t/deftest almost-zero-predicate
  (let [p1 (gpt/point 0.000001 0.0000002)
        p2 (gpt/point 0.001 -0.0003)]
    (t/is (gpt/almost-zero? p1))
    (t/is (not (gpt/almost-zero? p2)))))

(t/deftest lerp-point
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/lerp p1 p2 2)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 3 (:x rs)))
    (t/is (mth/close? 4 (:y rs)))))

(t/deftest rotate-point
  (let [p1 (gpt/point 1 2)
        p2 (gpt/point 2 3)
        rs (gpt/rotate p1 p2 11)]
    (t/is (gpt/point? rs))
    (t/is (mth/close? 1.2091818119288809 (:x rs)))
    (t/is (mth/close? 1.8275638211757912 (:y rs)))))

