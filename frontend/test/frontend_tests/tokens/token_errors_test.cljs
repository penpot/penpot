;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-errors-test
  (:require
   [app.main.data.workspace.tokens.errors :as wte]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

;; The test run loads no translations, so `tr` returns the bare key and
;; drops its arguments. Tests about the values in a message echo them with
;; this stub. Both arities, like `tr`.
(defn- tr-stub
  ([key] key)
  ([key & args] (str key "(" (str/join ", " args) ")")))

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

(t/deftest resolve-error-message-known-error
  (t/testing "a known error code shows its translated message"
    (t/is (= (tr "errors.tokens.invalid-color" "#zzz")
             (wte/resolve-error-message (wte/error-with-value :error.token/invalid-color "#zzz"))))))

(t/deftest resolve-error-message-without-error
  (t/testing "without an error the message is the unknown error"
    (t/is (= (tr "labels.unknown-error") (wte/resolve-error-message nil)))))

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

(t/deftest resolve-error-assoc-message-without-error
  (t/testing "without an error the message is the unknown error"
    (t/is (= {:message (tr "labels.unknown-error")}
             (wte/resolve-error-assoc-message nil)))))

;; ---------------------------------------------------------------------------
;; humanize-errors
;; ---------------------------------------------------------------------------

(t/deftest humanize-errors
  (t/testing "known error codes show their translated message"
    (t/is (= [(tr "errors.tokens.empty-input")
              (tr "errors.tokens.invalid-color" "#zzz")]
             (wte/humanize-errors [(wte/error-with-value :error.token/empty-input nil)
                                   (wte/error-with-value :error.token/invalid-color "#zzz")]))))

  (t/testing "incompatible units are listed in one message"
    (with-redefs [i18n/tr tr-stub]
      (t/is (= [(tr "errors.tokens.incompatible-units" "px, rem")]
               (wte/humanize-errors [(wte/error-with-value :error.token/incompatible-units ["px" "rem"])])))))

  (t/testing "an unknown error code shows the unknown error message"
    (t/is (= [(tr "labels.unknown-error")]
             (wte/humanize-errors [(wte/error-with-value :error.token/not-a-code "x")]))))

  (t/testing "reference errors on import show the first reference error after the import error"
    (t/is (= [(str (tr "errors.tokens.import-error") "\n\n" "Some token references could not be found")]
             (wte/humanize-errors [(wte/error-with-value :error.import/style-dictionary-reference-errors
                                                         ["Some token references could not be found"
                                                          "color.primary"])]))))

  (t/testing "an error that carries its own message shows it"
    (t/is (= ["Value must be positive"]
             (wte/humanize-errors [{:error/message "Value must be positive"}]))))

  (t/testing "an error that is already a message shows as is"
    (t/is (= ["Plain message"] (wte/humanize-errors ["Plain message"]))))

  (t/testing "no errors give no messages"
    (t/is (empty? (wte/humanize-errors [])))))

;; ---------------------------------------------------------------------------
;; detail-errors
;; ---------------------------------------------------------------------------

(t/deftest detail-errors
  (t/testing "errors without details give no details at all"
    (t/is (nil? (wte/detail-errors [(wte/error-with-value :error.token/empty-input nil)
                                    (wte/error-with-value :error.import/json-parse-error nil)])))
    (t/is (nil? (wte/detail-errors []))))

  (t/testing "an invalid token name on import names the token in the details, escaped for html"
    (with-redefs [i18n/tr tr-stub]
      (t/is (= [(tr "errors.tokens.invalid-json-token-name-detail" "color.&lt;bad&gt;")]
               (wte/detail-errors [(wte/error-with-value :error.import/invalid-token-name "color.<bad>")])))))

  (t/testing "reference errors on import list the remaining references, escaped for html"
    (t/is (= ["color.&lt;primary&gt;\n\nspacing &amp; sizing"]
             (wte/detail-errors [(wte/error-with-value :error.import/style-dictionary-reference-errors
                                                       ["Some token references could not be found"
                                                        "color.<primary>"
                                                        "spacing & sizing"])]))))

  (t/testing "only errors with details are listed"
    (with-redefs [i18n/tr tr-stub]
      (t/is (= [(tr "errors.tokens.invalid-json-token-name-detail" "a.b")]
               (wte/detail-errors [(wte/error-with-value :error.token/empty-input nil)
                                   (wte/error-with-value :error.import/invalid-token-name "a.b")]))))))
