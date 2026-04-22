;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.errors-test
  (:require
   [app.main.errors :as errors]
   [cljs.test :as t :include-macros true]))

(defn- make-error
  "Create a JS Error-like object with the given name, message, and optional stack."
  [error-name message & {:keys [stack] :or {stack ""}}]
  (let [err (js/Error. message)]
    (set! (.-name err) error-name)
    (when (some? stack)
      (set! (.-stack err) stack))
    err))

;; ---------------------------------------------------------------------------
;; is-ignorable-exception? tests
;; ---------------------------------------------------------------------------

(t/deftest test-ignorable-chrome-extension
  (t/testing "Errors from Chrome extensions are ignorable"
    (let [cause (make-error "Error" "some error"
                            :stack "Error: some error\n    at chrome-extension://abc123/content.js:1:1")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-moz-extension
  (t/testing "Errors from Firefox extensions are ignorable"
    (let [cause (make-error "Error" "some error"
                            :stack "Error: some error\n    at moz-extension://abc123/content.js:1:1")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-posthog
  (t/testing "Errors from PostHog are ignorable"
    (let [cause (make-error "Error" "some error"
                            :stack "Error: some error\n    at https://app.posthog.com/static/array.js:1:1")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-debug-evaluate
  (t/testing "Debug-evaluate side-effect errors are ignorable"
    (let [cause (make-error "Error" "Possible side-effect in debug-evaluate")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-unexpected-end-of-input
  (t/testing "Unexpected end of input errors are ignorable"
    (let [cause (make-error "SyntaxError" "Unexpected end of input")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-invalid-props
  (t/testing "Invalid React props errors are ignorable"
    (let [cause (make-error "Error" "invalid props on component Foo")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-unexpected-token
  (t/testing "Unexpected token errors are ignorable"
    (let [cause (make-error "SyntaxError" "Unexpected token <")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-abort-error
  (t/testing "AbortError DOMException is ignorable"
    (let [cause (make-error "AbortError" "The operation was aborted")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-zone-js-tostring
  (t/testing "Zone.js toString read-only property error is ignorable"
    (let [cause (make-error "TypeError"
                            "Cannot assign to read only property 'toString' of function 'function () { [native code] }'")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-not-found-error-remove-child
  (t/testing "NotFoundError with removeChild message is ignorable"
    (let [cause (make-error "NotFoundError"
                            "Failed to execute 'removeChild' on 'Node': The node to be removed is not a child of this node."
                            :stack "NotFoundError: Failed to execute 'removeChild'\n    at zLe (libs.js:1:1)")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-not-found-error-other
  (t/testing "NotFoundError without removeChild is NOT ignorable"
    (let [cause (make-error "NotFoundError"
                            "Failed to execute 'insertBefore' on 'Node': something else")]
      (t/is (false? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-regular-error
  (t/testing "Regular application errors are NOT ignorable"
    (let [cause (make-error "Error" "Cannot read property 'x' of undefined")]
      (t/is (false? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-type-error
  (t/testing "Regular TypeError is NOT ignorable"
    (let [cause (make-error "TypeError" "undefined is not a function")]
      (t/is (false? (errors/is-ignorable-exception? cause))))))
