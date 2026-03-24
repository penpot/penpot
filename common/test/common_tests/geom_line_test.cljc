;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-line-test
  (:require
   [app.common.geom.line :as gln]
   [clojure.test :as t]))

(defn- gpt [x y] {:x x :y y})

(t/deftest line-value-test
  (t/testing "line-value on a horizontal line y=0"
    (let [line [(gpt 0 0) (gpt 10 0)]]
      ;; For this line: a=0, b=-10, c=0 => -10y
      (t/is (zero? (gln/line-value line (gpt 5 0))))
      (t/is (pos? (gln/line-value line (gpt 5 -1))))
      (t/is (neg? (gln/line-value line (gpt 5 1))))))

  (t/testing "line-value on a vertical line x=0"
    (let [line [(gpt 0 0) (gpt 0 10)]]
      ;; For this line: a=10, b=0, c=0 => 10x
      (t/is (zero? (gln/line-value line (gpt 0 5))))
      (t/is (pos? (gln/line-value line (gpt 1 5))))
      (t/is (neg? (gln/line-value line (gpt -1 5))))))

  (t/testing "line-value at origin"
    (let [line [(gpt 0 0) (gpt 1 1)]]
      (t/is (zero? (gln/line-value line (gpt 0 0)))))))

(t/deftest is-inside-lines?-test
  (t/testing "Point where line values have opposite signs → inside"
    (let [;; Line 1: x-axis direction (value = -y)
          ;; Line 2: y-axis direction (value = x)
          ;; Inside means product of line values is negative
          line-1 [(gpt 0 0) (gpt 1 0)]
          line-2 [(gpt 0 0) (gpt 0 1)]]
      ;; Point (1, 1): lv1 = -1, lv2 = 1, product = -1 < 0 → true
      (t/is (true? (gln/is-inside-lines? line-1 line-2 (gpt 1 1))))))

  (t/testing "Point where line values have same sign → outside"
    (let [line-1 [(gpt 0 0) (gpt 1 0)]
          line-2 [(gpt 0 0) (gpt 0 1)]]
      ;; Point (-1, 1): lv1 = -1, lv2 = -1, product = 1 > 0 → false
      (t/is (false? (gln/is-inside-lines? line-1 line-2 (gpt -1 1))))))

  (t/testing "Point on one of the lines"
    (let [line-1 [(gpt 0 0) (gpt 1 0)]
          line-2 [(gpt 0 0) (gpt 0 1)]]
      ;; Point on the x-axis: lv1 = 0, product = 0, not < 0
      (t/is (false? (gln/is-inside-lines? line-1 line-2 (gpt 1 0))))))

  (t/testing "Point at the vertex"
    (let [line-1 [(gpt 0 0) (gpt 1 0)]
          line-2 [(gpt 0 0) (gpt 0 1)]]
      (t/is (false? (gln/is-inside-lines? line-1 line-2 (gpt 0 0))))))

  (t/testing "Another point with opposite-sign line values"
    (let [line-1 [(gpt 0 0) (gpt 1 0)]
          line-2 [(gpt 0 0) (gpt 0 1)]]
      ;; Point (1, -1): lv1 = 1, lv2 = 1, product = 1 > 0 → false
      (t/is (false? (gln/is-inside-lines? line-1 line-2 (gpt 1 -1)))))))
