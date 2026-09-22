;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.errors-test
  (:require
   [app.main.errors :as errors]
   [cljs.test :as t :include-macros true]))

(defn- make-error
  "Create a JS Error-like object with the given name, message, and optional
  stack. Without a stack, the error keeps the one the runtime gave it."
  [error-name message & {:keys [stack]}]
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

(t/deftest test-ignorable-injected-script
  (t/testing "Errors raised by code injected into the page are ignorable"
    (let [cause (make-error "TypeError" "Cannot read properties of undefined (reading 'get')"
                            :stack (str "TypeError: Cannot read properties of undefined (reading 'get')\n"
                                        "    at nativeVis (<anonymous>:5:35)\n"
                                        "    at HTMLDocument.<anonymous> (<anonymous>:21:64)\n"
                                        "    at <anonymous>:17:33"))]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-injected-script-calling-into-app
  (t/testing "Errors whose stack reaches application code are NOT ignorable"
    (let [cause (make-error "TypeError" "Cannot read properties of undefined (reading 'get')"
                            :stack (str "TypeError: Cannot read properties of undefined (reading 'get')\n"
                                        "    at nativeVis (<anonymous>:5:35)\n"
                                        "    at zLe (https://design.penpot.app/js/main.js:42:13)"))]
      (t/is (false? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-release-build-v8-stack
  (t/testing "Application errors from a release build are NOT ignorable (V8 stack)"
    ;; Frames as they appear in production: advanced-compiled names, an
    ;; asset URL carrying the version query string, and a trailing frame
    ;; without a function name.
    (let [cause (make-error "TypeError" "Cannot read properties of null (reading 'toString')"
                            :stack (str "TypeError: Cannot read properties of null (reading 'toString')\n"
                                        "    at $app$main$ui$workspace$shapes$path$editor$path_editor_STAR_$$ "
                                        "(https://design.penpot.app/js/main-workspace.js?version=2.18.0-RC5-1789051786:8580:99)\n"
                                        "    at $re (https://design.penpot.app/js/libs.js?version=2.18.0-RC5-1789051786:150:48345)\n"
                                        "    at https://design.penpot.app/js/libs.js?version=2.18.0-RC5-1789051786:150:125675"))]
      (t/is (false? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-release-build-spidermonkey-stack
  (t/testing "Application errors from a release build are NOT ignorable (SpiderMonkey/JSC stack)"
    ;; SpiderMonkey stacks carry no message line and name the script after
    ;; an @ instead of wrapping it in parentheses.
    (let [cause (make-error "InternalError" "too much recursion"
                            :stack (str "$APP.$JSCompiler_prototypeAlias$$.$inode_lookup$"
                                        "@https://design.penpot.app/js/shared.js?version=2.18.0-RC5-1789051786:23076:58\n"
                                        "$app$common$types$container$get_component_shape$cljs$0core$0IFn$0_invoke$0arity$03$$"
                                        "@https://design.penpot.app/js/shared.js?version=2.18.0-RC5-1789051786:7596:1"))]
      (t/is (false? (errors/is-ignorable-exception? cause))))))

(t/deftest test-ignorable-blank-stack
  (t/testing "Errors with a blank stack are ignorable"
    ;; Firefox raises "can't access dead object" with an empty stack when
    ;; code from an unloaded extension runs in the page.
    (let [cause (make-error "TypeError" "can't access dead object" :stack "")]
      (t/is (true? (errors/is-ignorable-exception? cause))))))

(t/deftest test-not-ignorable-message-only-stack
  (t/testing "Errors whose stack holds only the message line are NOT ignorable"
    ;; V8 gives native rejections such as a failed fetch no frames at all.
    (let [cause (make-error "TypeError" "Failed to fetch" :stack "TypeError: Failed to fetch")]
      (t/is (false? (errors/is-ignorable-exception? cause))))))
