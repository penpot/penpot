;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.utils-test
  (:require
   [app.plugins.utils :as plugins.utils]
   [cljs.test :as t :include-macros true]))

;; Access the private flattener for direct testing.
(def ^:private flatten-error-map @#'plugins.utils/flatten-error-map)

(t/deftest test-flatten-error-map-flat-input
  ;; Regression test for issue #9417.
  ;;
  ;; When a malli error path has a single element, `interpret-schema-problem`
  ;; produces a flat map. The flattener must pass that through unchanged.
  (let [result (flatten-error-map {:group {:message "must be string"}})]
    (t/is (= [["group" "must be string"]] result))))

(t/deftest test-flatten-error-map-nested-input
  ;; Regression test for issue #9417.
  ;;
  ;; When a malli error path has multiple elements, `interpret-schema-problem`
  ;; produces a nested map via `(assoc-in acc path …)`. The old plugin
  ;; consumer destructured assuming a flat shape, so the nested case rendered
  ;; the message text as the field name (`Field message is invalid`) instead
  ;; of the real validation reason. The flattener must descend until it finds
  ;; a leaf carrying a `:message`.
  (let [explain {:sets {0 {:name {:message "must not be empty"}}}}
        result  (set (flatten-error-map explain))]
    (t/is (= #{["sets.0.name" "must not be empty"]} result))))

(t/deftest test-flatten-error-map-multiple-fields
  ;; Multiple validation problems on the same explain produce multiple
  ;; entries, none of which clobber each other.
  (let [explain {:group {:message "must be string"}
                 :sets  {0 {:message "set not found"}}}
        result  (set (flatten-error-map explain))]
    (t/is (= #{["group" "must be string"]
               ["sets.0"  "set not found"]} result))))

(t/deftest test-flatten-error-map-mixed-key-types
  ;; Numeric indices (from vector positions in the malli path) must render
  ;; cleanly; string keys must also be accepted alongside keywords.
  (let [explain {:items {2 {"label" {:message "invalid label"}}}}
        [[path message]] (flatten-error-map explain)]
    (t/is (= "items.2.label" path))
    (t/is (= "invalid label" message))))

(t/deftest test-flatten-error-map-empty
  ;; No validation errors -> no output (callers join with ". " and would
  ;; otherwise emit an empty string, which is fine).
  (t/is (empty? (flatten-error-map {}))))

;; ---------------------------------------------------------------------
;; Issue #9692 — `handle-error` must surface a useful message instead of a
;; bare "Value not valid. Code: :error".
;;
;; `not-valid` is redefined to capture the rendered message directly, so the
;; assertions don't depend on `st/state` or the console.

(t/deftest test-handle-error-plain-js-error
  ;; A plain JS error has no `::sm/explain` and CLJS `ex-data` returns nil, so
  ;; the handler must fall back to the error's own message rather than nil.
  (let [captured (atom ::none)]
    (with-redefs [plugins.utils/not-valid (fn [_plugin-id _code value]
                                            (reset! captured value) nil)]
      ((plugins.utils/handle-error #uuid "00000000-0000-0000-0000-000000000000")
       (js/Error. "boom: not a function")))
    (t/is (= "boom: not a function" @captured))))

(t/deftest test-handle-error-empty-explain
  ;; An explain whose errors don't render any message must not produce an
  ;; empty string; the handler falls back to the raw explain.
  (let [captured (atom ::none)
        cause    (ex-info "invalid" {:app.common.schema/explain {:errors [] :value 1}})]
    (with-redefs [plugins.utils/not-valid (fn [_plugin-id _code value]
                                            (reset! captured value) nil)]
      ((plugins.utils/handle-error #uuid "00000000-0000-0000-0000-000000000000") cause))
    (t/is (string? @captured))
    (t/is (not= "" @captured))))

(t/deftest test-error-messages-empty-returns-nil
  ;; `error-messages` returns nil (not "") on an explain with no mappable
  ;; errors, so `handle-error` can distinguish "no message" from a real one.
  (t/is (nil? (plugins.utils/error-messages {:errors []}))))
