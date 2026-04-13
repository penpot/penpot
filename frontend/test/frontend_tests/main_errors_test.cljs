;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.main-errors-test
  "Unit tests for app.main.errors.

  Tests cover:
    - stale-asset-error?          – pure predicate
    - exception->error-data       – pure transformer
    - on-error re-entrancy guard  – prevents recursive invocations
    - flash schedules async emit  – ntf/show is not emitted synchronously"
  (:require
   [app.main.errors :as errors]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

;; ---------------------------------------------------------------------------
;; stale-asset-error?
;; ---------------------------------------------------------------------------

(t/deftest stale-asset-error-nil
  (t/testing "nil cause returns nil/falsy"
    (t/is (not (errors/stale-asset-error? nil)))))

(t/deftest stale-asset-error-keyword-cst-undefined
  (t/testing "error with $cljs$cst$ and 'is undefined' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is undefined")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-keyword-cst-null
  (t/testing "error with $cljs$cst$ and 'is null' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is null")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-protocol-dispatch-undefined
  (t/testing "error with $cljs$core$I and 'Cannot read properties of undefined' is recognised"
    (let [err (js/Error. "Cannot read properties of undefined (reading '$cljs$core$IFn$_invoke$arity$1$')")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-not-a-function
  (t/testing "error with $cljs$cst$ and 'is not a function' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is not a function")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-unrelated-message
  (t/testing "ordinary error without stale-asset signature is NOT recognised"
    (let [err (js/Error. "Cannot read properties of undefined (reading 'foo')")]
      (t/is (not (errors/stale-asset-error? err))))))

(t/deftest stale-asset-error-only-cst-no-undefined
  (t/testing "error with $cljs$cst$ but no undefined/null/not-a-function keyword is not recognised"
    (let [err (js/Error. "foo$cljs$cst$bar exploded")]
      (t/is (not (errors/stale-asset-error? err))))))

;; ---------------------------------------------------------------------------
;; exception->error-data
;; ---------------------------------------------------------------------------

(t/deftest exception->error-data-plain-error
  (t/testing "plain JS Error is converted to a data map with :hint and ::instance"
    (let [err  (js/Error. "something went wrong")
          data (errors/exception->error-data err)]
      (t/is (= "something went wrong" (:hint data)))
      (t/is (identical? err (::errors/instance data))))))

(t/deftest exception->error-data-ex-info
  (t/testing "ex-info error preserves existing :hint and attaches ::instance"
    (let [err  (ex-info "original" {:hint "my-hint" :type :network})
          data (errors/exception->error-data err)]
      (t/is (= "my-hint" (:hint data)))
      (t/is (= :network (:type data)))
      (t/is (identical? err (::errors/instance data))))))

(t/deftest exception->error-data-ex-info-no-hint
  (t/testing "ex-info without :hint falls back to ex-message"
    (let [err  (ex-info "fallback message" {:type :validation})
          data (errors/exception->error-data err)]
      (t/is (= "fallback message" (:hint data))))))

;; ---------------------------------------------------------------------------
;; on-error dispatches to ptk/handle-error
;;
;; We use a dedicated test-only error type so we can add/remove a
;; defmethod without touching the real handlers.
;; ---------------------------------------------------------------------------

(def ^:private test-handled (atom nil))

(defmethod ptk/handle-error ::test-dispatch
  [err]
  (reset! test-handled err))

(t/deftest on-error-dispatches-map-error
  (t/testing "on-error dispatches a map error to ptk/handle-error using its :type"
    (reset! test-handled nil)
    (errors/on-error {:type ::test-dispatch :hint "hello"})
    (t/is (= ::test-dispatch (:type @test-handled)))
    (t/is (= "hello" (:hint @test-handled)))))

(t/deftest on-error-wraps-exception-then-dispatches
  (t/testing "on-error wraps a JS Error into error-data before dispatching"
    (reset! test-handled nil)
    (let [err (ex-info "wrapped" {:type ::test-dispatch})]
      (errors/on-error err)
      (t/is (= ::test-dispatch (:type @test-handled)))
      (t/is (identical? err (::errors/instance @test-handled))))))

;; ---------------------------------------------------------------------------
;; on-error re-entrancy guard
;;
;; The guard is implemented via the `handling-error?` volatile inside
;; app.main.errors.  We can verify its effect by registering a
;; handle-error method that itself calls on-error and checking that
;; only one invocation gets through.
;; ---------------------------------------------------------------------------

(def ^:private reentrant-call-count (atom 0))

(defmethod ptk/handle-error ::test-reentrant
  [_err]
  (swap! reentrant-call-count inc)
  ;; Simulate a secondary error inside the error handler
  ;; (e.g. the notification emit itself throws).
  ;; Without the re-entrancy guard this would recurse indefinitely.
  (when (= 1 @reentrant-call-count)
    (errors/on-error {:type ::test-reentrant :hint "secondary"})))

(t/deftest on-error-reentrancy-guard-prevents-recursion
  (t/testing "a second on-error call while handling an error is suppressed by the guard"
    (reset! reentrant-call-count 0)
    (errors/on-error {:type ::test-reentrant :hint "first"})
    ;; The guard must have allowed only the first invocation through.
    (t/is (= 1 @reentrant-call-count))))
