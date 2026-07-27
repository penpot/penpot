;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-simple-math-test
  (:require
   [app.common.math :as cm]
   [app.util.simple-math :as sm]
   [cljs.test :as t :include-macros true]))

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

  (t/testing "Evaluate a negative number (not relative subtraction)"
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
      (t/is (cm/close? result1 result2 7.44433333)))))

(t/deftest test-error-handling
  (t/testing "Division by zero should return nil"
    (let [result (sm/expr-eval "10/0" 999)]
      (t/is (= result nil))))

  (t/testing "Expression with division by zero should return nil"
    (let [result (sm/expr-eval "(10 + 5) / 0" 999)]
      (t/is (= result nil))))

  (t/testing "Invalid syntax should return nil"
    (let [result (sm/expr-eval "asdasd+2" 999)]
      (t/is (= result nil))))

  (t/testing "Empty expression with no init-value should return nil"
    (let [result (sm/expr-eval "" nil)]
      (t/is (= result nil))))

  (t/testing "Partial invalid expression should return nil"
    (let [result (sm/expr-eval "10 + abc" 100)]
      (t/is (= result nil)))))

(t/deftest test-relative-operators-only-at-top-level
  (t/testing "Two consecutive operators should return nil"
    (t/is (= nil (sm/expr-eval "10+*3" 100)))
    (t/is (= nil (sm/expr-eval "10 + *3" 100)))
    (t/is (= nil (sm/expr-eval "10+/3" 100)))
    (t/is (= nil (sm/expr-eval "10 + +5" 100)))
    (t/is (= nil (sm/expr-eval "10 * *5" 100)))
    (t/is (= nil (sm/expr-eval "10 * /5" 100))))

  (t/testing "Relative operators inside parentheses should return nil"
    (t/is (= nil (sm/expr-eval "(*3)" 100)))
    (t/is (= nil (sm/expr-eval "10 + (*3)" 100)))
    (t/is (= nil (sm/expr-eval "+(10 + *3)" 100))))

  (t/testing "A dangling operator should return nil"
    (t/is (= nil (sm/expr-eval "10 +" 100)))
    (t/is (= nil (sm/expr-eval "+" 100)))
    (t/is (= nil (sm/expr-eval "*" 100))))

  (t/testing "Valid relative expressions keep working"
    (t/is (= 130 (sm/expr-eval "+30" 100)))
    (t/is (= 300 (sm/expr-eval "*3" 100)))
    (t/is (= -30 (sm/expr-eval "-30" 100)))
    (t/is (= 50 (sm/expr-eval "/2" 100)))))

(t/deftest test-negative-operands
  (t/testing "A negative number is a valid operand"
    (t/is (= 7 (sm/expr-eval "10 + -3" 100)))
    (t/is (= 7 (sm/expr-eval "10+-3" 100)))
    (t/is (= 15 (sm/expr-eval "10 - -5" 100)))
    (t/is (= -30 (sm/expr-eval "10 * -3" 100)))
    (t/is (= 4 (sm/expr-eval "10 + -3 * 2" 100)))
    (t/is (= -20 (sm/expr-eval "-(10 + 10)" 100)))
    (t/is (= 5 (sm/expr-eval "10 - -5 * 1 - 10" 100))))

  (t/testing "A negative number is a valid relative operand"
    (t/is (= 70 (sm/expr-eval "+ -30" 100)))
    (t/is (= -300 (sm/expr-eval "* -3" 100))))

  (t/testing "A negative percentage is a valid operand"
    (t/is (= 75 (sm/expr-eval "100 + -25%" 100)))
    (t/is (= -25 (sm/expr-eval "-25%" 100)))))

(t/deftest test-operator-associativity
  (t/testing "Chained subtraction is evaluated left to right"
    (t/is (= 55 (sm/expr-eval "100 - 35 - 10" 999)))
    (t/is (= 65 (sm/expr-eval "100 - 35 + 10 - 10" 999)))
    (t/is (= 15 (sm/expr-eval "1 + 2 + 3 + 4 + 5" 999))))

  (t/testing "Chained division is evaluated left to right"
    (t/is (= 5 (sm/expr-eval "100 / 10 / 2" 999)))
    (t/is (= 20 (sm/expr-eval "100 / 10 * 2" 999)))
    (t/is (= 5 (sm/expr-eval "(100 / 10) / 2" 999))))

  (t/testing "Precedence and parentheses are preserved"
    (t/is (= 7 (sm/expr-eval "1 + 2 * 3" 999)))
    (t/is (= 9 (sm/expr-eval "(1 + 2) * 3" 999)))))

(t/deftest test-edge-cases
  (t/testing "Relative division by zero returns nil"
    (t/is (= nil (sm/expr-eval "/0" 100)))
    (t/is (= nil (sm/expr-eval "10 / 5 / 0" 100))))

  (t/testing "Relative operators fall back to a zero init-value"
    (t/is (= 10 (sm/expr-eval "+10" nil)))
    (t/is (= 0 (sm/expr-eval "*10" nil))))

  (t/testing "Percentages of the init-value"
    (t/is (= 25 (sm/expr-eval "25%" 100)))
    (t/is (= 125 (sm/expr-eval "+25%" 100))))

  (t/testing "Non numeric input returns nil"
    (t/is (= nil (sm/expr-eval " " 100)))
    (t/is (= nil (sm/expr-eval "10 20" 100)))
    (t/is (= nil (sm/expr-eval "(10" 100)))
    (t/is (= nil (sm/expr-eval "10€" 100)))
    (t/is (= nil (sm/expr-eval "🙂" 100)))))
