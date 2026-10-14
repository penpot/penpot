;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.compliance-test
  "Runs the external tokenscript compliance suite when
  TOKENSCRIPT_COMPLIANCE_DIR points at a checkout of it. Skipped
  otherwise."
  (:require
   [clojure.test :as t]
   [common-tests.tokenscript.compliance :as compliance]))

(def known-divergences
  "Cases where the suite expectations differ from the behaviour of the
  reference TypeScript interpreter this implementation reproduces. Each
  entry is the case name; see
  docs/technical-guide/developer/subsystems/tokenscript/testing.md."
  #{;; Python style float formatting (`7.0`) and banker's rounding
    "math with mixed float and integer"
    "round down on .5 to even number"
    ;; the reference prints list strings without quotes
    "Mixed txped list"
    ;; `type()` returns the qualified type (`color.hex`) like the Go runtime
    "type function color hex variable"
    ;; null colors/units render as `` / `nullpx` in the reference
    "declare NumberWithUnit variable without assignment"
    "declare Color variable without assignment"
    "declare Color.Hex variable without assignment"
    "null coercion from dictionary to NumberWithUnit"
    "null coercion from dictionary to Color"
    "null coercion from dictionary to Color.Hex"
    "Null coercion to Color"
    "Null coercion to NumberWithUnit"
    ;; empty lists render as an empty string
    "list and dictionary non-nullable - List has empty array value"})

(t/deftest compliance-suite-test
  (if-let [[tests-dir specs-dir] (compliance/suite-dirs)]
    (let [report (compliance/run-suite tests-dir specs-dir)
          failures (compliance/failures report)
          unexpected (remove #(contains? known-divergences (get % "name")) failures)]
      (t/is (pos? (:passed report)))
      (t/is (empty? unexpected)
            (str "unexpected compliance failures: " (pr-str (map #(get % "name") unexpected)))))
    ;; Without an assertion kaocha reports the skipped test as failed.
    (t/is true "compliance suite skipped: TOKENSCRIPT_COMPLIANCE_DIR not set")))
