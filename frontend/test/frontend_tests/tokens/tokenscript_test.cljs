;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.tokenscript-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.tokenscript :as ts]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
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

(t/deftest resolve-tokens-error-codes-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "zero" :value "1 / 0" :type :number}
                              {:name "mod-zero" :value "mod(4, 0)" :type :number}
                              {:name "units" :value "1px * 2em" :type :sizing}
                              {:name "fn" :value "foo(1)" :type :number}
                              {:name "ln" :value "ln(0)" :type :number}
                              {:name "ref" :value "{zero} + 1" :type :number}))
        error (fn [name] (select-keys (get-in resolved [name :errors 0]) [:error/code :error/value]))]
    (t/is (= {:error/code :error.token/division-by-zero :error/value nil} (error "zero")))
    (t/is (= {:error/code :error.token/division-by-zero :error/value nil} (error "mod-zero")))
    (t/is (= {:error/code :error.token/incompatible-units :error/value ["px" "em"]} (error "units")))
    (t/is (= {:error/code :error.token/unknown-function :error/value "foo"} (error "fn")))
    (t/is (= {:error/code :error.token/function-argument-out-of-range :error/value "ln"} (error "ln")))
    (t/is (= {:error/code :error.style-dictionary/invalid-token-value :error/value "{zero} + 1"} (error "ref"))
          "a token failing through its reference is an invalid value")))

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

;; The interpreter validates the kind of value of each token type: `1foo`
;; and `1 2` are lists, not numbers.
(t/deftest resolve-tokens-type-validation-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "size.glued" :value "1foo" :type :dimensions}
                              {:name "size.list" :value "1 2" :type :sizing}
                              {:name "size.ok" :value "1rem" :type :sizing}
                              {:name "number.glued" :value "1foo" :type :number}
                              {:name "number.unit" :value "10px" :type :number}
                              {:name "number.ok" :value "1.5" :type :number}
                              {:name "opacity.percent" :value "150%" :type :opacity}
                              {:name "typography.bad" :value {:font-size "1foo" :line-height "1.5"} :type :typography}
                              {:name "shadow.bad" :value "8" :type :shadow}))
        code (fn [name] (get-in resolved [name :errors 0 :error/code]))]
    (doseq [name ["size.glued" "size.list" "number.glued"]]
      (t/is (nil? (get-in resolved [name :resolved-value])))
      (t/is (= :error.style-dictionary/invalid-token-value (code name))))
    (t/is (= "1foo" (get-in resolved ["size.glued" :errors 0 :error/value])))
    (t/is (= :error.style-dictionary/value-with-units (code "number.unit")))
    (t/is (= :error.style-dictionary/invalid-token-value-opacity (code "opacity.percent")))
    (t/is (= :error.style-dictionary/invalid-token-value-typography (code "typography.bad")))
    (t/is (= :error.style-dictionary/invalid-token-value-shadow (code "shadow.bad")))
    (t/is (= 16 (get-in resolved ["size.ok" :resolved-value])))
    (t/is (= 1.5 (get-in resolved ["number.ok" :resolved-value])))))

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

(t/deftest resolve-draft-test
  (let [sets [[:core (tokens-map {:name "a" :value "8px" :type :border-radius})]]
        resolved (ts/resolve-draft sets {:name "b" :value "{a} * 2" :type :border-radius})]
    (t/is (= 16 (:resolved-value resolved)))))

;; Incremental resolution ------------------------------------------------------
;;
;; The adapter keeps the interpreter state of the last resolutions. A new
;; map re-resolves only the tokens that changed and their dependents; the
;; output entries of every other token are the previous objects.

(defn- token
  [attrs]
  (ctob/make-token attrs))

(defn- by-name
  [& tokens]
  (into {} (map (fn [t] [(:name t) t])) tokens))

(t/deftest resolve-tokens-incremental-test
  (let [base (token {:name "base" :value "8" :type :sizing})
        double (token {:name "double" :value "{base} * 2" :type :sizing})
        other (token {:name "other" :value "1" :type :sizing})
        first-run (ts/resolve-tokens (by-name base double other))
        edited (assoc base :value "10")
        second-run (ts/resolve-tokens (by-name edited double other))]
    (t/is (= 16 (get-in first-run ["double" :resolved-value])))
    (t/is (= 10 (get-in second-run ["base" :resolved-value])))
    (t/is (= 20 (get-in second-run ["double" :resolved-value])) "dependents are re-resolved")
    (t/is (identical? (get first-run "other") (get second-run "other"))
          "tokens the edit did not reach keep their output object")
    (t/is (not (identical? (get first-run "double") (get second-run "double"))))
    (t/is (identical? second-run (ts/resolve-tokens (by-name edited double other)))
          "the same input returns the cached output")))

(t/deftest resolve-tokens-incremental-errors-test
  (t/testing "a token fixed by an edit and the tokens waiting for it recover"
    (let [ref (token {:name "ref" :value "{missing}" :type :sizing})
          first-run (ts/resolve-tokens (by-name ref))
          missing (token {:name "missing" :value "4" :type :sizing})
          second-run (ts/resolve-tokens (by-name ref missing))
          third-run (ts/resolve-tokens (by-name ref))]
      (t/is (= :error.style-dictionary/missing-reference (get-in first-run ["ref" :errors 0 :error/code])))
      (t/is (= 4 (get-in second-run ["ref" :resolved-value])))
      (t/is (= :error.style-dictionary/missing-reference (get-in third-run ["ref" :errors 0 :error/code]))
            "deleting the token breaks the reference again")))
  (t/testing "a token that loses its value is an empty input and its references break"
    (let [a (token {:name "a" :value "8" :type :sizing})
          b (token {:name "b" :value "{a}" :type :sizing})
          first-run (ts/resolve-tokens (by-name a b))
          second-run (ts/resolve-tokens (by-name (assoc a :value nil) b))]
      (t/is (= 8 (get-in first-run ["b" :resolved-value])))
      (t/is (= :error.token/empty-input (get-in second-run ["a" :errors 0 :error/code])))
      (t/is (= :error.style-dictionary/missing-reference (get-in second-run ["b" :errors 0 :error/code]))))))

(t/deftest resolve-sets-test
  (let [core-base (token {:name "base" :value "8" :type :sizing})
        double (token {:name "double" :value "{base} * 2" :type :sizing})
        other (token {:name "other" :value "1" :type :sizing})
        theme-base (token {:name "base" :value "10" :type :sizing})
        core (by-name core-base double other)
        theme (by-name theme-base)
        resolved (ts/resolve-sets [[:core core] [:theme theme]])]
    (t/testing "a later set wins and the result holds the winning tokens"
      (t/is (= 10 (get-in resolved ["base" :resolved-value])))
      (t/is (= (:id theme-base) (get-in resolved ["base" :id])) "the output token is the winning record")
      (t/is (= 20 (get-in resolved ["double" :resolved-value]))))
    (t/testing "editing a token a later set overrides changes nothing visible"
      (let [resolved' (ts/resolve-sets [[:core (by-name (assoc core-base :value "99") double other)] [:theme theme]])]
        (t/is (= 10 (get-in resolved' ["base" :resolved-value])))
        (t/is (identical? (get resolved "double") (get resolved' "double")))))
    (t/testing "activating a set in the middle re-resolves the names it wins and their dependents"
      (let [mid (by-name (token {:name "other" :value "5" :type :sizing})
                         (token {:name "new" :value "{other} + 1" :type :sizing}))
            resolved' (ts/resolve-sets [[:core core] [:mid mid] [:theme theme]])]
        (t/is (= 5 (get-in resolved' ["other" :resolved-value])))
        (t/is (= 6 (get-in resolved' ["new" :resolved-value])))
        (t/is (identical? (get resolved "base") (get resolved' "base")))
        (t/is (identical? (get resolved "double") (get resolved' "double")))))
    (t/testing "an empty value in a later set hides the earlier definition"
      (let [resolved' (ts/resolve-sets [[:core core] [:theme (by-name (assoc theme-base :value nil))]])]
        (t/is (= :error.token/empty-input (get-in resolved' ["base" :errors 0 :error/code])))
        (t/is (= :error.style-dictionary/missing-reference (get-in resolved' ["double" :errors 0 :error/code])))))
    (t/testing "a preview against the same sets does not change their cached result"
      (let [preview (ts/resolve-sets [[:core core] [:theme theme] [:draft (by-name (assoc core-base :value "1"))]])]
        (t/is (= 2 (get-in preview ["double" :resolved-value])))
        (t/is (identical? resolved (ts/resolve-sets [[:core core] [:theme theme]])))))))

;; The active sets go to the resolver unmerged; the result is the one of
;; the merged active tokens.
(t/deftest resolve-active-sets-test
  (let [lib (-> (ctob/make-tokens-lib)
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core) :name "core"))
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :dark) :name "dark"))
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :extra) :name "extra"))
                (ctob/add-token (cthi/id :core) (ctob/make-token {:name "base" :value "8" :type :sizing}))
                (ctob/add-token (cthi/id :core) (ctob/make-token {:name "double" :value "{base} * 2" :type :sizing}))
                (ctob/add-token (cthi/id :dark) (ctob/make-token {:name "base" :value "10" :type :sizing}))
                (ctob/add-token (cthi/id :extra) (ctob/make-token {:name "base" :value "1" :type :sizing})))
        status (ctos/make-tokens-status :active-set-ids #{(cthi/id :core) (cthi/id :dark)})
        values (fn [resolved] (update-vals resolved :resolved-value))
        by-sets (ts/resolve-sets (cfo/get-active-token-sets status lib))]
    (t/is (= {"base" 10 "double" 20} (values by-sets)))
    (t/is (= (values (ts/resolve-tokens (cfo/get-tokens-in-active-sets status lib))) (values by-sets)))
    (t/is (identical? by-sets (ts/resolve-sets (cfo/get-active-token-sets status lib)))
          "the same sets from the library return the cached output")
    (t/testing "a forced inactive set comes first, as in the merged variant"
      (let [forced (ts/resolve-sets (cfo/get-active-token-sets-force status lib (cthi/id :extra)))]
        (t/is (= (values (ts/resolve-tokens (cfo/get-tokens-in-active-sets-force status lib (cthi/id :extra))))
                 (values forced)))
        (t/is (= 10 (get-in forced ["base" :resolved-value])))))))

;; A token edited in a form resolves against every set of the library with
;; the selected set on top, and the draft on top of that.
(t/deftest resolve-edited-token-test
  (let [lib (-> (ctob/make-tokens-lib)
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :base) :name "base"))
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :brand) :name "brand"))
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :other) :name "other"))
                (ctob/add-token (cthi/id :base) (ctob/make-token {:name "size" :value "4" :type :sizing}))
                (ctob/add-token (cthi/id :base) (ctob/make-token {:name "double" :value "{size} * 2" :type :sizing}))
                (ctob/add-token (cthi/id :brand) (ctob/make-token {:name "size" :value "8" :type :sizing}))
                (ctob/add-token (cthi/id :other) (ctob/make-token {:name "size" :value "16" :type :sizing})))
        sets (cfo/get-token-sets-for-edit lib (cthi/id :brand))
        values (fn [resolved] (update-vals resolved :resolved-value))]
    (t/testing "the selected set is moved last and wins, as when merged over all tokens"
      (t/is (= [(cthi/id :base) (cthi/id :other) (cthi/id :brand)] (mapv first sets)))
      (t/is (= (values (ts/resolve-tokens (merge (ctob/get-all-tokens-map lib) (ctob/get-tokens lib (cthi/id :brand)))))
               (values (ts/resolve-sets sets))))
      (t/is (= 8 (get-in (ts/resolve-sets sets) ["size" :resolved-value]))))
    (t/testing "the draft wins over every set and its dependents see it"
      (t/is (= 3 (:resolved-value (ts/resolve-draft sets {:name "size" :value "3" :type :sizing}))))
      (t/is (= 14 (:resolved-value (ts/resolve-draft sets {:name "tmp" :value "{double} - 2" :type :sizing})))
            "each draft replaces the previous one"))
    (t/testing "a renamed token is left out of every set, so references to it break"
      (let [sets' (csu/without-token sets "size")]
        (t/is (not-any? #(contains? (second %) "size") sets'))
        (t/is (= :error.style-dictionary/missing-reference
                 (get-in (ts/resolve-draft sets' {:name "tmp" :value "{size}" :type :sizing}) [:errors 0 :error/code]))))
      (t/is (= [false true true] (mapv identical? sets (csu/without-token sets "double")))
            "sets without the token stay the same objects"))
    (t/testing "typing in a form does not push resolutions out of the cache"
      (let [active (cfo/get-active-token-sets (ctos/make-tokens-status :active-set-ids #{(cthi/id :base)}) lib)
            before (ts/resolve-sets active)]
        (doseq [v (range 20)]
          (ts/resolve-draft sets {:name "size" :value (str v) :type :sizing}))
        (t/is (identical? before (ts/resolve-sets active)))))))

(t/deftest resolve-sets-empty-test
  (t/is (= {} (ts/resolve-sets [])))
  (t/is (= {} (ts/resolve-tokens {}))))
