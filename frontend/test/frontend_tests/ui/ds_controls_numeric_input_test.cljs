;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.ds-controls-numeric-input-test
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.controls.numeric-input :refer [next-focus-index]]
   [app.main.ui.formats :as fmt]
   [cljs.test :as t :include-macros true]))

;; ── format-number / parse-double roundtrip ──
;; These tests guard against the contamination chain that caused issue #10638:
;;   format-number returns string → last-value* contaminated → mth/finite? on
;;   CLJS accepts strings via js/isFinite → backend Malli rejects → 500.

(t/deftest test-format-number-returns-string
  (t/testing "format-number returns a string, not a number"
    (let [result (fmt/format-number 16)]
      (t/is (string? result))
      (t/is (not (number? result))))))

(t/deftest test-parse-double-roundtrip
  (t/testing "parse-double of a formatted number returns a number"
    (let [formatted (fmt/format-number 16)
          reparsed  (d/parse-double formatted)]
      (t/is (number? reparsed))
      (t/is (= 16 reparsed))))

  (t/testing "parse-double of empty string returns nil"
    (t/is (nil? (d/parse-double ""))))

  (t/testing "parse-double of nil returns nil"
    (t/is (nil? (d/parse-double nil))))

  (t/testing "parse-double of non-numeric string returns nil"
    (t/is (nil? (d/parse-double "abc"))))

  (t/testing "parse-double is idempotent for numbers"
    (let [result (d/parse-double (d/parse-double (fmt/format-number 42)))]
      (t/is (number? result))
      (t/is (= 42 result)))))

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
