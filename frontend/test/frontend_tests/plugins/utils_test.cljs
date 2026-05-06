;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.utils-test
  "Regression coverage for the schema-explain walker that backs the
   ``[PENPOT PLUGIN] Value not valid: …`` console error.

   Pre-fix the walker collapsed every leaf to its inner ``:message`` key,
   producing the literal string ``\"Field message is invalid: \"`` for any
   single-level schema failure (issue #9290 — ``applyToken`` on color /
   dimension / opacity tokens). The replacement function is pure and exercised
   here against the *post-reduce* shape returned by
   ``app.common.schema.messages/interpret-schema-problem``."
  (:require
   [app.plugins.utils :as pu]
   [cljs.test :as t :include-macros true]))

(defn- as-leaves
  "Convert the lazy seq ``collect-schema-error-leaves`` returns into a stable
   set of vectors so test assertions are order-independent."
  [m]
  (->> (pu/collect-schema-error-leaves m)
       (map vec)
       set))

;; ---------------------------------------------------------------------------
;; Single-level field paths — the regression case behind issue #9290
;; ---------------------------------------------------------------------------

(t/deftest test-collect-leaves-single-level-keyword-path
  ;; What ``interpret-schema-problem`` produces for an ``error/field`` named
  ;; ``:applyToken`` is ``{:applyToken {:message "..."}}``. The pre-fix walker
  ;; turned this into ``[:message "..."]`` pairs and lost the field name; the
  ;; replacement keeps the full path.
  (let [errors {:applyToken {:message "Not a valid token attribute"}}]
    (t/is (= #{[[:applyToken] "Not a valid token attribute"]}
             (as-leaves errors)))))

(t/deftest test-collect-leaves-single-level-numeric-tuple-position
  ;; Tuple-shaped schemas (e.g. the ``applyToken`` arity validator) report
  ;; ``in [<index>]`` for the offending positional argument. The walker must
  ;; survive integer path segments — calling ``name`` on them used to crash.
  (let [errors {1 {:message "Expected a set of token attributes"}}]
    (t/is (= #{[[1] "Expected a set of token attributes"]}
             (as-leaves errors)))))

(t/deftest test-collect-leaves-single-level-string-path
  (let [errors {"width" {:message "Out of range"}}]
    (t/is (= #{[["width"] "Out of range"]}
             (as-leaves errors)))))

;; ---------------------------------------------------------------------------
;; Nested field paths
;; ---------------------------------------------------------------------------

(t/deftest test-collect-leaves-nested-keyword-path
  (let [errors {:applyToken {:properties {:message "Unknown property"}}}]
    (t/is (= #{[[:applyToken :properties] "Unknown property"]}
             (as-leaves errors)))))

(t/deftest test-collect-leaves-nested-mixed-path
  (let [errors {:applyToken {1 {:message "Element type mismatch"}}}]
    (t/is (= #{[[:applyToken 1] "Element type mismatch"]}
             (as-leaves errors)))))

(t/deftest test-collect-leaves-multiple-sibling-errors
  (let [errors {:applyToken {:message "Token argument missing"}
                :properties {:message "Property list invalid"}}]
    (t/is (= #{[[:applyToken] "Token argument missing"]
               [[:properties] "Property list invalid"]}
             (as-leaves errors)))))

(t/deftest test-collect-leaves-deep-and-shallow-mix
  (let [errors {:a {:message "Top-level fail"}
                :b {:c {:message "Nested fail"}
                    :d {:e {:message "Deeper fail"}}}}]
    (t/is (= #{[[:a]      "Top-level fail"]
               [[:b :c]   "Nested fail"]
               [[:b :d :e] "Deeper fail"]}
             (as-leaves errors)))))

;; ---------------------------------------------------------------------------
;; Empty / degenerate inputs
;; ---------------------------------------------------------------------------

(t/deftest test-collect-leaves-empty-input
  (t/is (empty? (pu/collect-schema-error-leaves {}))))

(t/deftest test-collect-leaves-skips-nil-leaves
  ;; ``interpret-schema-problem`` only emits ``{:message <string>}`` shapes,
  ;; but defensive coverage keeps us safe if a future caller hands us a
  ;; partial node.
  (let [errors {:applyToken nil
                :properties {:message "Real failure"}}]
    (t/is (= #{[[:properties] "Real failure"]}
             (as-leaves errors)))))

;; ---------------------------------------------------------------------------
;; ``error-messages`` — minimal smoke that the public entry point is still
;; total. ``interpret-schema-problem`` is invoked over an empty ``:errors``
;; sequence so we get a deterministic empty string without depending on the
;; malli runtime to synthesize a realistic explain in the test environment.
;; ---------------------------------------------------------------------------

(t/deftest test-error-messages-empty-explain-returns-empty-string
  (t/is (= "" (pu/error-messages {:errors []}))))
