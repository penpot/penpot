;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.ds-controls-numeric-input-test
  (:require
   [app.main.ui.ds.controls.numeric-input :refer [next-focus-index parse-value]]
   [cljs.test :as t :include-macros true]))

(def ^:private sample-options
  [{:id "a" :type :item :name "Alpha"}
   {:id "b" :type :group :name "Group"}
   {:id "c" :type :item :name "Charlie"}
   {:id "d" :type :separator :name "---"}
   {:id "e" :type :item :name "Echo"}])

(t/deftest test-next-focus-index
  (t/testing "returns index of next focusable item going down"
    (t/is (= 2 (next-focus-index sample-options "a" :down))))

  (t/testing "returns index of next focusable item going up"
    (t/is (= 0 (next-focus-index sample-options "c" :up))))

  (t/testing "wraps around going down"
    (t/is (= 0 (next-focus-index sample-options "e" :down))))

  (t/testing "wraps around going up"
    (t/is (= 4 (next-focus-index sample-options "a" :up))))

  (t/testing "works when options is a delay"
    (let [delayed-options (delay sample-options)]
      (t/is (= 2 (next-focus-index delayed-options "a" :down)))))

  (t/testing "works with nil focused-id (no current selection)"
    (t/is (= 0 (next-focus-index sample-options nil :down)))))

;; Regression pins for https://github.com/penpot/penpot/issues/10638:
;; parse-value is the only source of committed values, and it must yield
;; numbers or nil — never strings. last-value* holds its numeric output.

(t/deftest test-parse-value-returns-numbers-or-nil
  (t/testing "plain numbers parse"
    (t/is (= 33 (parse-value "33" nil nil nil false)))
    (t/is (= 33.5 (parse-value "33.5" nil nil nil false))))

  (t/testing "decimal comma is accepted"
    (t/is (= 33.5 (parse-value "33,5" nil nil nil false))))

  (t/testing "invalid text yields nil, not a fallback string"
    (t/is (nil? (parse-value "abc" 33 nil nil false)))
    (t/is (nil? (parse-value "abc" 33 nil nil true))))

  (t/testing "unit suffixes are rejected"
    (t/is (nil? (parse-value "33px" 10 nil nil false))))

  (t/testing "empty input yields nil"
    (t/is (nil? (parse-value "" 33 nil nil false)))
    (t/is (nil? (parse-value nil nil nil nil true))))

  (t/testing "expressions evaluate at full precision"
    (t/is (= (/ 10 3) (parse-value "10/3" nil nil nil false))))

  (t/testing "relative expressions use the last committed NUMBER as base"
    (t/is (= 25 (parse-value "+5" 20 nil nil false)))
    (t/is (= 40 (parse-value "*2" 20 nil nil false)))
    (t/is (= 10 (parse-value "50%" 20 nil nil false))))

  (t/testing "relative expressions with no committed value fall back to 0"
    (t/is (= 5 (parse-value "+5" nil nil nil false))))

  (t/testing "whitespace-only input yields nil"
    (t/is (nil? (parse-value "   " 33 nil nil false))))

  (t/testing "division by zero yields nil, not Infinity"
    (t/is (nil? (parse-value "1/0" 5 nil nil false))))

  (t/testing "leading/trailing dot forms parse"
    (t/is (= 0.5 (parse-value ".5" nil nil nil false)))
    (t/is (= 33 (parse-value "33." nil nil nil false))))

  (t/testing "huge values clamp to the safe-int range"
    (t/is (= 1073741823.5 (parse-value "99999999999999999999" nil nil nil false))))

  (t/testing "min/max clamping produces numbers"
    (t/is (= 0 (parse-value "-5" nil 0 nil false)))
    (t/is (= 100 (parse-value "500" nil 0 100 false)))))
