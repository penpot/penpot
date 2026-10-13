;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.tokenscript-test
  (:require
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tokenscript :as ts]
   [cljs.test :as t :include-macros true]))

(defn- tokens-map
  "Tokens map of a library with one set holding `tokens`."
  [& tokens]
  (let [lib (-> (ctob/make-tokens-lib)
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core-set) :name "core")))]
    (-> (reduce (fn [lib token] (ctob/add-token lib (cthi/id :core-set) (ctob/make-token token))) lib tokens)
        (ctob/get-all-tokens-map))))

(t/deftest resolve-tokens-test
  (t/testing "resolves references and converts units to pixels"
    (let [resolved (ts/resolve-tokens
                    (tokens-map {:name "borderRadius.sm" :value "12px" :type :border-radius}
                                {:name "borderRadius.md-with-dashes" :value "{borderRadius.sm} * 2" :type :border-radius}
                                {:name "borderRadius.rem" :value "2rem" :type :border-radius}
                                {:name "opacity.half" :value "50%" :type :opacity}
                                {:name "borderRadius.large" :value "123456789012345" :type :border-radius}
                                {:name "borderRadius.largePx" :value "123456789012345px" :type :border-radius}
                                {:name "borderRadius.largeFn" :value "{borderRadius.sm} * 200000000" :type :border-radius}))]
      (t/is (= 12 (get-in resolved ["borderRadius.sm" :resolved-value])))
      (t/is (= 24 (get-in resolved ["borderRadius.md-with-dashes" :resolved-value])))
      (t/is (= 32 (get-in resolved ["borderRadius.rem" :resolved-value])))
      (t/is (= 0.5 (get-in resolved ["opacity.half" :resolved-value])))
      (doseq [name ["borderRadius.large" "borderRadius.largePx" "borderRadius.largeFn"]]
        (t/is (nil? (get-in resolved [name :resolved-value])))
        (t/is (= :error.token/number-too-large (get-in resolved [name :errors 0 :error/code])))))))

(t/deftest resolve-tokens-colors-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "color.red" :value "#ff0000" :type :color}
                              {:name "color.alias" :value "{color.red}" :type :color}
                              {:name "color.alpha" :value "#ff000080" :type :color}
                              {:name "color.named" :value "red" :type :color}
                              {:name "color.light" :value "lighten({color.red}, 0.2)" :type :color}))]
    (t/is (= "#ff0000" (get-in resolved ["color.alias" :resolved-value])))
    (t/is (= "#ff000080" (get-in resolved ["color.alpha" :resolved-value])) "alpha is kept as hex8")
    (t/is (= "#FF0000" (get-in resolved ["color.named" :resolved-value])) "css names resolve")
    (t/is (string? (get-in resolved ["color.light" :resolved-value])) "color functions resolve to hex")))

(t/deftest resolve-tokens-errors-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "a" :value "{missing}" :type :border-radius}
                              {:name "b" :value "{a} + 1" :type :border-radius}
                              {:name "x" :value "{y}" :type :border-radius}
                              {:name "y" :value "{x}" :type :border-radius}
                              {:name "z" :value "{x} + 1" :type :border-radius}
                              {:name "bad" :value "1 ´ 2" :type :border-radius}))]
    (t/is (= :error.style-dictionary/missing-reference (get-in resolved ["a" :errors 0 :error/code])))
    (t/is (= ["missing"] (get-in resolved ["a" :errors 0 :error/value])))
    (t/is (= :error.style-dictionary/missing-reference (get-in resolved ["b" :errors 0 :error/code]))
          "dependents report the missing token")
    (t/is (= ["missing"] (get-in resolved ["b" :errors 0 :error/value])))
    (t/is (= :error.token/circular-reference (get-in resolved ["x" :errors 0 :error/code])))
    (t/is (= :error.token/circular-reference (get-in resolved ["y" :errors 0 :error/code])))
    (t/is (= :error.token/circular-reference (get-in resolved ["z" :errors 0 :error/code]))
          "dependents of a cycle report the cycle")
    (t/is (= :error.style-dictionary/invalid-token-value (get-in resolved ["bad" :errors 0 :error/code])))))

;; A token named "a" and a token named "a.b" can coexist: `{a}` resolves
;; to the token, `{a.b}` to the nested one (#9584).
(t/deftest resolve-tokens-name-collision-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "a" :value "8px" :type :border-radius}
                              {:name "a.b" :value "12px" :type :border-radius}
                              {:name "c" :value "{a} + {a.b}" :type :border-radius}))]
    (t/is (= 8 (get-in resolved ["a" :resolved-value])))
    (t/is (= 12 (get-in resolved ["a.b" :resolved-value])))
    (t/is (= 20 (get-in resolved ["c" :resolved-value])))))

(t/deftest resolve-tokens-composites-test
  (t/testing "typography and shadow tokens resolve field by field"
    (let [resolved (ts/resolve-tokens
                    (tokens-map {:name "size" :value "16" :type :font-size}
                                {:name "typo" :type :typography
                                 :value {:font-family ["Roboto" "Arial"]
                                         :font-size "{size} * 2"
                                         :font-weight "700 Italic"
                                         :line-height "48px"}}
                                {:name "shadow" :type :shadow
                                 :value [{:offset-x "2" :offset-y "{size}" :blur "4" :spread "0"
                                          :color "#000" :inset false}]}))]
      (t/is (= {:font-family ["Roboto" "Arial"] :font-size 32 :font-weight "700 Italic" :line-height 1.5}
               (get-in resolved ["typo" :resolved-value]))
            "line heights in pixels become a ratio of the font size")
      (t/is (= [{:offset-x 2 :offset-y 16 :blur 4 :spread 0 :color "#000" :inset false}]
               (get-in resolved ["shadow" :resolved-value])))))
  (t/testing "font families referencing a font family token are flattened"
    (let [resolved (ts/resolve-tokens
                    (tokens-map {:name "families" :value ["Arial" "sans-serif"] :type :font-family}
                                {:name "single" :value "Roboto" :type :font-family}
                                {:name "typo" :value {:font-family ["{families}"]} :type :typography}))]
      (t/is (= ["Arial" "sans-serif"] (get-in resolved ["families" :resolved-value])))
      (t/is (= ["Roboto"] (get-in resolved ["single" :resolved-value])))
      (t/is (= ["Arial" "sans-serif"] (get-in resolved ["typo" :resolved-value :font-family])))))
  (t/testing "a typography token with an array value is invalid"
    (let [resolved (ts/resolve-tokens (tokens-map {:name "typography.bad" :value ["Roboto"] :type :typography}))]
      (t/is (nil? (get-in resolved ["typography.bad" :resolved-value])))
      (t/is (= :error.style-dictionary/invalid-token-value-typography
               (get-in resolved ["typography.bad" :errors 0 :error/code]))))))

(t/deftest resolve-tokens-value-validation-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "opacity.bad" :value "2" :type :opacity}
                              {:name "stroke.bad" :value "-1" :type :stroke-width}
                              {:name "case.ok" :value "uppercase" :type :text-case}
                              {:name "case.bad" :value "wat" :type :text-case}
                              {:name "weight.ok" :value "bold" :type :font-weight}
                              {:name "weight.bad" :value "heavyish" :type :font-weight}))]
    (t/is (= :error.style-dictionary/invalid-token-value-opacity (get-in resolved ["opacity.bad" :errors 0 :error/code])))
    (t/is (= :error.style-dictionary/invalid-token-value-stroke-width (get-in resolved ["stroke.bad" :errors 0 :error/code])))
    (t/is (= "uppercase" (get-in resolved ["case.ok" :resolved-value])))
    (t/is (= :error.style-dictionary/invalid-token-value-text-case (get-in resolved ["case.bad" :errors 0 :error/code])))
    (t/is (= "bold" (get-in resolved ["weight.ok" :resolved-value])))
    (t/is (= :error.style-dictionary/invalid-token-value-font-weight (get-in resolved ["weight.bad" :errors 0 :error/code])))))

;; A token with a `nil` value (e.g. a composite typography token saved via
;; the workspace form with no fields filled in) is tagged with an empty
;; input error and does not affect the other tokens.
(t/deftest resolve-tokens-nil-value-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "typography.empty" :value nil :type :typography}
                              {:name "borderRadius.sm" :value "12px" :type :border-radius}))]
    (t/is (nil? (get-in resolved ["typography.empty" :resolved-value])))
    (t/is (= :error.token/empty-input (get-in resolved ["typography.empty" :errors 0 :error/code])))
    (t/is (= 12 (get-in resolved ["borderRadius.sm" :resolved-value])))))

(t/deftest resolve-token-test
  (let [tokens (tokens-map {:name "a" :value "8px" :type :border-radius})
        resolved (ts/resolve-token tokens {:name "b" :value "{a} * 2" :type :border-radius})]
    (t/is (= 16 (:resolved-value resolved)))))
