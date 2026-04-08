;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.token-errors-test
  (:require
   [app.main.data.workspace.tokens.errors :as wte]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; resolve-error-message
;; ---------------------------------------------------------------------------

(t/deftest resolve-error-message-with-error-fn
  (t/testing "calls :error/fn with :error/value when both keys are present"
    (let [error {:error/fn    (fn [v] (str "bad value: " v))
                 :error/value "abc"}]
      (t/is (= "bad value: abc" (wte/resolve-error-message error))))))

(t/deftest resolve-error-message-without-error-fn
  (t/testing "returns :message when :error/fn is absent (schema-validation error)"
    (let [error {:message "This field is required"}]
      (t/is (= "This field is required" (wte/resolve-error-message error))))))

(t/deftest resolve-error-message-nil-error-fn
  (t/testing "returns :message when :error/fn is explicitly nil"
    (let [error {:error/fn nil :message "fallback message"}]
      (t/is (= "fallback message" (wte/resolve-error-message error))))))

;; ---------------------------------------------------------------------------
;; resolve-error-assoc-message
;; ---------------------------------------------------------------------------

(t/deftest resolve-error-assoc-message-with-error-fn
  (t/testing "assocs :message produced by :error/fn into the error map"
    (let [error {:error/fn    (fn [v] (str "invalid: " v))
                 :error/value "42"
                 :error/code  :error.token/invalid-color}]
      (let [result (wte/resolve-error-assoc-message error)]
        (t/is (= "invalid: 42" (:message result)))
        (t/is (= :error.token/invalid-color (:error/code result)))))))

(t/deftest resolve-error-assoc-message-without-error-fn
  (t/testing "returns the error map unchanged when :error/fn is absent"
    (let [error {:message "This field is required"}]
      (t/is (= error (wte/resolve-error-assoc-message error))))))

(t/deftest resolve-error-assoc-message-nil-error-fn
  (t/testing "returns the error map unchanged when :error/fn is explicitly nil"
    (let [error {:error/fn nil :message "fallback"}]
      (t/is (= error (wte/resolve-error-assoc-message error))))))
