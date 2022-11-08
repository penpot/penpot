;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-test
  (:require
   [clojure.test :as t]
   [app.common.math :as mth]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]))

(t/deftest point-constructors-test
  (t/testing "Create point with both coordinates"
    (let [p (gpt/point 1 2)]
      (t/is (= (:x p) 1))
      (t/is (= (:y p) 2))))

  (t/testing "Create point with single coordinate"
    (let [p (gpt/point 1)]
      (t/is (= (:x p) 1))
      (t/is (= (:y p) 1))))

  (t/testing "Create empty point"
    (let [p (gpt/point)]
      (t/is (= (:x p) 0))
      (t/is (= (:y p) 0)))))

(t/deftest point-add-test
  (t/testing "Adds two points together"
    (let [p1 (gpt/point 1 1)
          p2 (gpt/point 2 2)
          p3 (gpt/add p1 p2)]
      (t/is (= (:x p3) 3))
      (t/is (= (:y p3) 3)))))

(t/deftest point-subtract-test
  (t/testing "Point substraction"
    (let [p1 (gpt/point 3 3)
          p2 (gpt/point 2 2)
          p3 (gpt/subtract p1 p2)]
      (t/is (= (:x p3) 1))
      (t/is (= (:y p3) 1)))))

(t/deftest point-distance-test
  (let [p1 (gpt/point 0 0)
        p2 (gpt/point 10 0)
        d (gpt/distance p1 p2)]
    (t/is (number? d))
    (t/is (= d 10.0))))

(t/deftest point-length-test
  (let [p1 (gpt/point 10 0)
        ln (gpt/length p1)]
    (t/is (number? ln))
    (t/is (= ln 10.0))))

(t/deftest point-angle-test
  (t/testing "Get angle a 90 degree angle"
    (let [p1 (gpt/point 0 10)
          angle (gpt/angle p1)]
      (t/is (number? angle))
      (t/is (= angle 90.0))))

  (t/testing "Get 45 degree angle"
    (let [p1 (gpt/point 0 10)
          p2 (gpt/point 10 10)
          angle (gpt/angle-with-other p1 p2)]
      (t/is (number? angle))
      (t/is (mth/close? angle 45.0)))))

(t/deftest matrix-constructors-test
  (let [m (gmt/matrix)]
    (t/is (= 1.0 (.-a m)))
    (t/is (= 0.0 (.-b m)))
    (t/is (= 0.0 (.-c m)))
    (t/is (= 1.0 (.-d m)))
    (t/is (= 0.0 (.-e m)))
    (t/is (= 0.0 (.-f m))))
  (let [m (gmt/matrix 1 1 1 2 2 2)]
    (t/is (= 1.0 (.-a m)))
    (t/is (= 1.0 (.-b m)))
    (t/is (= 1.0 (.-c m)))
    (t/is (= 2.0 (.-d m)))
    (t/is (= 2.0 (.-e m)))
    (t/is (= 2.0 (.-f m)))))

(t/deftest matrix-translate-test
  (let [m (-> (gmt/matrix)
              (gmt/translate (gpt/point 2 10)))]
    (t/is (= m (gmt/matrix 1 0 0 1 2 10)))))

(t/deftest matrix-scale-test
  (let [m (-> (gmt/matrix)
              (gmt/scale (gpt/point 2)))]
    (t/is (= m (gmt/matrix 2 0 0 2 0 0)))))

(t/deftest matrix-rotate-test
  (let [m (-> (gmt/matrix)
              (gmt/rotate 10))]
    (t/is (= m (gmt/matrix 0.984807753012208 0.17364817766693033 -0.17364817766693033 0.984807753012208 0 0)))))
