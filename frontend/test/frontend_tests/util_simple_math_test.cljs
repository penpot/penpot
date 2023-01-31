;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-simple-math-test
  (:require
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]
   [app.common.math :as cm]
   [app.util.simple-math :as sm]))

(t/deftest test-parser-inst
  (t/testing "Evaluate an empty string"
    (let [result (sm/expr-eval "" 999)]
      (t/is (= result nil))))

  (t/testing "Evaluate a single number"
    (let [result (sm/expr-eval "10" 999)]
      (t/is (= result 10))))

  (t/testing "Evaluate an addition"
    (let [result (sm/expr-eval "10+3" 999)]
      (t/is (= result 13))))

  (t/testing "Evaluate an addition with spaces"
    (let [result (sm/expr-eval "100 + 35" 999)]
      (t/is (= result 135))))

  (t/testing "Evaluate some operations"
    (let [result (sm/expr-eval "100 + 35 - 10 * 2" 999)]
      (t/is (= result 115))))

  (t/testing "Evaluate some operations with parentheses"
    (let [result (sm/expr-eval "(100 + 35 - 10) * 2" 999)]
      (t/is (= result 250))))

  (t/testing "Evaluate some operations with nested parentheses"
    (let [result (sm/expr-eval "(100 + 35 - (20/2))*2" 999)]
      (t/is (= result 250))))

  (t/testing "Evaluate a relative addition"
    (let [result (sm/expr-eval "+10" 20)]
      (t/is (= result 30))))

  (t/testing "Evaluate a relative multiplication"
    (let [result (sm/expr-eval "*10" 20)]
      (t/is (= result 200))))

  (t/testing "Evaluate a negative number (not relative substraction)"
    (let [result (sm/expr-eval "-10" 20)]
      (t/is (= result -10))))

  (t/testing "Evaluate a relative complex operation"
    (let [result (sm/expr-eval "+(10*2 - 5)" 20)]
      (t/is (= result 35))))

  (t/testing "Evaluate a percentual operation"
    (let [result (sm/expr-eval "+50%" 20)]
      (t/is (= result 30))))

  (t/testing "Evaluate a complex operation with percents"
    (let [result (sm/expr-eval "5 + (25% * 2)" 100)]
      (t/is (= result 55))))

  (t/testing "Evaluate a complex operation with percents and relative"
    (let [result (sm/expr-eval "+ (25% * 2)" 100)]
      (t/is (= result 150))))

  (t/testing "Evaluate an addition with decimals"
    (let [result1 (sm/expr-eval "10 + 2.5" 999)
          result2 (sm/expr-eval "10 + 2,5" 999)]
      (t/is (= result1 result2 12.5))))

  (t/testing "Evaluate a relative operation with decimals"
    (let [result1 (sm/expr-eval "*.5" 20)
          result2 (sm/expr-eval "*,5" 20)]
      (t/is (= result1 result2 10))))

  (t/testing "Evaluate a percentual operation with decimals"
    (let [result1 (sm/expr-eval "+10.5%" 20)
          result2 (sm/expr-eval "+10,5%" 20)]
      (t/is (= result1 result2 22.1))))

  (t/testing "Evaluate a complex operation with decimals"
    (let [result1 (sm/expr-eval "(20.333 + 10%) * (1 / 3)" 20)
          result2 (sm/expr-eval "(20,333 + 10%) * (1 / 3)" 20)]
      (t/is (cm/close? result1 result2 7.44433333))))

  )

