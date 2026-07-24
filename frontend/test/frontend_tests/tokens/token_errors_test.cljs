;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.tokens.token-errors-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.management.forms.controls.fonts-combobox :as fcb]
   [app.main.ui.workspace.tokens.management.forms.validators :as v]
   [beicon.v2.core :as rx]
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

;; ---------------------------------------------------------------------------
;; token-circular-reference? on composite token values
;; ---------------------------------------------------------------------------

(t/deftest circular-reference-through-typography-value
  (t/testing "detects a cycle that closes through the map value of a typography token"
    (let [tokens {"font.family.base" {:type :font-family
                                      :value ["{typography.body}"]}
                  "typography.body"  {:type :typography
                                      :value {:font-family "{font.family.base}"
                                              :font-size "16"}}}]
      (t/is (some? (cfo/token-circular-reference? tokens "font.family.base")))
      (t/is (some? (cfo/token-circular-reference? tokens "typography.body"))))))

(t/deftest circular-reference-through-font-family-vector
  (t/testing "detects a cycle that closes through the vector value of a font-family token"
    (let [tokens {"font.family.base" {:type :font-family
                                      :value ["Source Sans Pro" "{font.family.alias}"]}
                  "font.family.alias" {:type :font-family
                                       :value "{font.family.base}"}}]
      (t/is (some? (cfo/token-circular-reference? tokens "font.family.base"))))))

(t/deftest circular-reference-through-shadow-value
  (t/testing "detects a cycle that closes through the vector-of-maps value of a shadow token"
    (let [tokens {"color.brand" {:type :color
                                 :value "{shadow.card}"}
                  "shadow.card" {:type :shadow
                                 :value [{:offset-x "0" :offset-y "2" :blur "4"
                                          :spread "0" :color "{color.brand}"}]}}]
      (t/is (some? (cfo/token-circular-reference? tokens "color.brand"))))))

(t/deftest circular-reference-self-through-composite-value
  (t/testing "detects a typography token referencing itself from one of its fields"
    (let [tokens {"typography.body" {:type :typography
                                     :value {:font-family "{typography.body}"}}}]
      (t/is (= "typography.body"
               (cfo/token-circular-reference? tokens "typography.body"))))))

(t/deftest circular-reference-diamond-is-not-a-cycle
  (t/testing "does not report a cycle on a diamond dependency reached through composite values"
    (let [tokens {"typography.body" {:type :typography
                                     :value {:font-family "{font.family.base}"
                                             :font-size "{size.base}"}}
                  "font.family.base" {:type :font-family
                                      :value ["{font.family.fallback}"]}
                  "font.family.fallback" {:type :font-family
                                          :value ["Source Sans Pro"]}
                  "size.base" {:type :font-size
                               :value "16"}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "typography.body"))))))

(t/deftest circular-reference-on-degenerate-values
  (t/testing "tolerates nil, empty and non-string token values and dangling references"
    (let [tokens {"a" {:type :number :value nil}
                  "b" {:type :typography :value {}}
                  "c" {:type :shadow :value []}
                  "d" {:type :number :value "{missing.token}"}
                  "e" {:type :number :value {:font-size nil :line-height ["{a}"]}}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "a")))
      (t/is (nil? (cfo/token-circular-reference? tokens "b")))
      (t/is (nil? (cfo/token-circular-reference? tokens "c")))
      (t/is (nil? (cfo/token-circular-reference? tokens "d")))
      (t/is (nil? (cfo/token-circular-reference? tokens "e")))
      (t/is (nil? (cfo/token-circular-reference? tokens "not-there")))
      (t/is (nil? (cfo/token-circular-reference? {} "a"))))))

;; ---------------------------------------------------------------------------
;; validate-resolve-token guards the save path against circular references
;; ---------------------------------------------------------------------------

(t/deftest validate-resolve-token-circular-through-typography
  (t/async
    done
    (t/testing "returns a circular-reference error instead of recursing into StyleDictionary"
      (let [tokens     {"font.family.base"
                        (ctob/make-token {:name "font.family.base"
                                          :type :font-family
                                          :value ["Source Sans Pro"]})

                        "typography.body"
                        (ctob/make-token {:name "typography.body"
                                          :type :typography
                                          :value {:font-family "{font.family.base}"}})}
            prev-token (get tokens "font.family.base")
            token      {:name "font.family.base"
                        :type :font-family
                        :value ["{typography.body}"]}]
        (rx/subs! (fn [_]
                    (t/is false "expected a circular-reference error")
                    (done))
                  (fn [{:keys [errors]}]
                    (t/is (= :error.token/circular-reference
                             (:error/code (first errors))))
                    (done))
                  (v/validate-resolve-token token prev-token tokens))))))

;; ---------------------------------------------------------------------------
;; make-token-schema must validate the value being edited, not the stored one
;; ---------------------------------------------------------------------------

(defn- font-family-form-schema
  "Builds the same schema the font-family form builds (font_family.cljs)."
  [tokens-tree current-token-path]
  (-> (cfo/make-token-schema tokens-tree :font-family current-token-path)
      (sm/dissoc-key :id)
      (sm/assoc-key :value cfo/schema:token-value-generic)))

(t/deftest token-schema-does-not-lock-out-a-stored-cycle
  (t/testing "a value that breaks a stored cycle is accepted, a value that keeps it is rejected"
    (let [tokens {"fam"  (ctob/make-token {:name "fam"
                                           :type :font-family
                                           :value ["{body}"]})
                  "body" (ctob/make-token {:name "body"
                                           :type :typography
                                           :value {:font-family "{fam}"}})}
          schema (font-family-form-schema (ctob/tokens-tree tokens) ["fam"])]
      (t/is (sm/valid? schema {:name "fam" :type :font-family :value "Arial"}))
      (t/is (not (sm/valid? schema {:name "fam" :type :font-family :value "{body}"}))))))

(t/deftest token-schema-detects-a-cycle-typed-into-a-dotted-token
  (t/testing "the schema sees the edited value even when the token name is dotted"
    (let [tokens {"font.family.base" (ctob/make-token {:name "font.family.base"
                                                       :type :font-family
                                                       :value ["Source Sans Pro"]})
                  "typography.body"  (ctob/make-token {:name "typography.body"
                                                       :type :typography
                                                       :value {:font-family "{font.family.base}"}})}
          schema (font-family-form-schema (ctob/tokens-tree tokens)
                                          ["font" "family" "base"])]
      (t/is (sm/valid? schema {:name "font.family.base" :type :font-family :value "Arial"}))
      (t/is (not (sm/valid? schema {:name "font.family.base"
                                    :type :font-family
                                    :value "{typography.body}"}))))))

;; ---------------------------------------------------------------------------
;; the font-family input reports the cycle while editing (issue step 3)
;; ---------------------------------------------------------------------------

(t/deftest fonts-combobox-reports-circular-reference-while-editing
  (t/async
    done
    (t/testing "typing a reference that closes a cycle through a typography token"
      (let [tokens     {"font.family.base"
                        (ctob/make-token {:name "font.family.base"
                                          :type :font-family
                                          :value ["Source Sans Pro"]})

                        "typography.body"
                        (ctob/make-token {:name "typography.body"
                                          :type :typography
                                          :value {:font-family "{font.family.base}"}})}
            prev-token (get tokens "font.family.base")]
        (rx/subs! (fn [{:keys [error]}]
                    (t/is (= :error.token/circular-reference (:error/code error)))
                    (done))
                  (fn [_]
                    (t/is false "expected a circular-reference error")
                    (done))
                  (#'fcb/resolve-value tokens prev-token "font.family.base" "{typography.body}"))))))
