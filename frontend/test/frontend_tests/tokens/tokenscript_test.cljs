;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.tokenscript-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.math :as mth]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.gradient :as wtg]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [tokenscript.importers.css :as tsi]))

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

;; A token cannot contain tokens: next to a token "a", the tokens "a.b"
;; and "a.b.c" get a name collision error and `{a.b}` is missing (#9584).
(t/deftest resolve-tokens-name-collision-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "a" :value "8px" :type :border-radius}
                              {:name "a.b" :value "12px" :type :border-radius}
                              {:name "a.b.c" :value "1px" :type :border-radius}
                              {:name "c" :value "{a} + {a.b}" :type :border-radius}))
        error (fn [name] (select-keys (get-in resolved [name :errors 0]) [:error/code :error/value]))]
    (t/is (= 8 (get-in resolved ["a" :resolved-value])))
    (t/is (nil? (get-in resolved ["a" :errors])))
    (t/is (nil? (get-in resolved ["a.b" :resolved-value])))
    (t/is (= {:error/code :error.token/name-collision :error/value "a.b"} (error "a.b")))
    (t/is (= {:error/code :error.token/name-collision :error/value "a.b.c"} (error "a.b.c")))
    (t/is (= {:error/code :error.style-dictionary/missing-reference :error/value ["a.b"]} (error "c"))))
  (t/testing "a new token below an existing one collides"
    (let [sets [[:core (tokens-map {:name "rad1" :value "8px" :type :border-radius})]]
          resolved (ts/resolve-draft sets {:name "rad1.bad" :value "10" :type :border-radius})]
      (t/is (= :error.token/name-collision (get-in resolved [:errors 0 :error/code]))))))

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

(t/deftest resolve-tokens-gradient-test
  (let [resolved (ts/resolve-tokens
                  (tokens-map {:name "color.brand" :value "#ff0000" :type :color}
                              {:name "middle" :value "0.25 * 2" :type :number}
                              {:name "gradient.brand" :type :gradient
                               :value {:kind "linear"
                                       :angle "90"
                                       :stops [{:color "{color.brand}" :position "0"}
                                               {:color "rgba(0, 0, 255, 0.5)" :position "{middle}"}
                                               {:color "#00ff00" :position "100%"}]}}
                              {:name "gradient.alias" :value "{gradient.brand}" :type :gradient}
                              {:name "gradient.radial" :type :gradient
                               :value {:kind "radial"
                                       :stops [{:color "{color.brand}" :position "0"}
                                               {:color "#0000ff" :position "1"}]}}
                              {:name "gradient.missing" :type :gradient
                               :value {:kind "linear"
                                       :stops [{:color "{color.missing}" :position "0"}
                                               {:color "#0000ff" :position "1"}]}}
                              {:name "gradient.conic" :type :gradient
                               :value {:kind "conic"
                                       :stops [{:color "#ff0000" :position "0"}
                                               {:color "#0000ff" :position "1"}]}}
                              {:name "gradient.one-stop" :type :gradient
                               :value {:kind "linear"
                                       :stops [{:color "#ff0000" :position "0"}]}}
                              {:name "gradient.string" :value "8" :type :gradient}
                              {:name "gradient.turned" :type :gradient
                               :value {:kind "linear"
                                       :angle "0.25turn"
                                       :stops [{:color "#ff0000" :position "0"}
                                               {:color "#0000ff" :position "1"}]}}
                              {:name "gradient.bad-angle" :type :gradient
                               :value {:kind "linear"
                                       :angle "red"
                                       :stops [{:color "#ff0000" :position "0"}
                                               {:color "#0000ff" :position "1"}]}}))
        code     (fn [name] (get-in resolved [name :errors 0 :error/code]))
        brand    {:kind "linear"
                  :angle 90
                  :stops [{:color "#ff0000" :position 0}
                          {:color "#0000ff80" :position 0.5}
                          {:color "#00ff00" :position 1}]}]
    (t/testing "stops resolve field by field"
      (t/is (= brand (get-in resolved ["gradient.brand" :resolved-value]))))
    (t/testing "a reference resolves to the gradient it points to"
      (t/is (= brand (get-in resolved ["gradient.alias" :resolved-value]))))
    (t/testing "a radial gradient has no angle"
      (t/is (= {:kind "radial"
                :stops [{:color "#ff0000" :position 0}
                        {:color "#0000ff" :position 1}]}
               (get-in resolved ["gradient.radial" :resolved-value]))))
    (t/testing "a stop with a missing reference"
      (t/is (= :error.style-dictionary/missing-reference (code "gradient.missing")))
      (t/is (= ["color.missing"] (get-in resolved ["gradient.missing" :errors 0 :error/value]))))
    (t/testing "gradients penpot can not draw are invalid"
      (doseq [name ["gradient.conic" "gradient.one-stop" "gradient.string"]]
        (t/is (nil? (get-in resolved [name :resolved-value])))
        (t/is (= :error.style-dictionary/invalid-token-value-gradient (code name)))))
    (t/testing "an angle in turns resolves to degrees"
      (t/is (= 90 (get-in resolved ["gradient.turned" :resolved-value :angle]))))
    (t/testing "an angle that is no rotation has an error of its own"
      (t/is (nil? (get-in resolved ["gradient.bad-angle" :resolved-value])))
      (t/is (= :error.style-dictionary/invalid-token-value-gradient-angle (code "gradient.bad-angle"))))))

;; Angles in turn, rad and grad resolve to degrees for every token, not
;; only for gradients: a rotation token in turns rotates a shape by the
;; degrees it is.
(t/deftest resolve-tokens-angle-units-test
  (let [gradient (fn [name angle]
                   {:name name :type :gradient
                    :value {:kind "linear"
                            :angle angle
                            :stops [{:color "#ff0000" :position "0"}
                                    {:color "#0000ff" :position "1"}]}})
        resolved (ts/resolve-tokens
                  (tokens-map {:name "rotation.deg" :value "45deg" :type :rotation}
                              {:name "rotation.turn" :value "0.25turn" :type :rotation}
                              {:name "rotation.rad" :value "1rad" :type :rotation}
                              {:name "rotation.grad" :value "100grad" :type :rotation}
                              {:name "rotation.alias" :value "{rotation.turn} + 10deg" :type :rotation}
                              (gradient "gradient.rad" "1rad")
                              (gradient "gradient.grad" "50grad")))
        value    (fn [name] (get-in resolved [name :resolved-value]))
        angle    (fn [name] (get-in resolved [name :resolved-value :angle]))]
    (t/testing "a rotation token resolves to degrees in any angle unit"
      (t/is (= 45 (value "rotation.deg")))
      (t/is (= 90 (value "rotation.turn")))
      (t/is (mth/close? (/ 180 js/Math.PI) (value "rotation.rad")))
      (t/is (mth/close? 90 (value "rotation.grad"))))
    (t/testing "a rotation that references one in turns adds up in degrees"
      (t/is (mth/close? 100 (value "rotation.alias"))))
    (t/testing "a gradient angle in rad or grad resolves to degrees"
      (t/is (mth/close? (/ 180 js/Math.PI) (angle "gradient.rad")))
      (t/is (mth/close? 45 (angle "gradient.grad"))))))

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
        edited-tokens (by-name edited double other)
        second-run (ts/resolve-tokens edited-tokens)]
    (t/is (= 16 (get-in first-run ["double" :resolved-value])))
    (t/is (= 10 (get-in second-run ["base" :resolved-value])))
    (t/is (= 20 (get-in second-run ["double" :resolved-value])) "dependents are re-resolved")
    (t/is (identical? (get first-run "other") (get second-run "other"))
          "tokens the edit did not reach keep their output object")
    (t/is (not (identical? (get first-run "double") (get second-run "double"))))
    (t/is (identical? second-run (ts/resolve-tokens edited-tokens))
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
      (let [sets' (cfo/without-token sets "size")]
        (t/is (not-any? #(contains? (second %) "size") sets'))
        (t/is (= :error.style-dictionary/missing-reference
                 (get-in (ts/resolve-draft sets' {:name "tmp" :value "{size}" :type :sizing}) [:errors 0 :error/code]))))
      (t/is (= [false true true] (mapv identical? sets (cfo/without-token sets "double")))
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

;; Renaming a token, with or without updating the references to it (see
;; `remap/build-remap-changes`), resolves as with StyleDictionary. The
;; renamed library is resolved from the interpreter state of the library
;; before the rename, as in the workspace.

(defn- rename-token
  [lib set-name old-name new-name]
  (let [set-id (ctob/get-id (ctob/get-set-by-name lib set-name))
        token  (ctob/get-token-by-name lib set-name old-name)]
    (ctob/update-token lib set-id (:id token) #(assoc % :name new-name))))

(defn- update-references
  [lib old-name new-name]
  (reduce (fn [lib token-set]
            (let [set-id (ctob/get-id token-set)]
              (reduce (fn [lib token]
                        (ctob/update-token lib set-id (:id token)
                                           #(update % :value cto/update-token-value-references old-name new-name)))
                      lib
                      (vals (ctob/get-tokens lib set-id)))))
          lib
          (ctob/get-sets lib)))

(defn- resolved-values
  [resolved]
  (update-vals resolved (fn [token]
                          (if-let [errors (seq (:errors token))]
                            (mapv :error/code errors)
                            (:resolved-value token)))))

(t/deftest rename-token-as-style-dictionary-test
  (t/async
    done
    (let [lib (-> (ctob/make-tokens-lib)
                  (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core) :name "core"))
                  (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :theme) :name "theme"))
                  (ctob/add-token (cthi/id :core) (ctob/make-token {:name "size" :value "4" :type :sizing}))
                  (ctob/add-token (cthi/id :core) (ctob/make-token {:name "double" :value "{size} * 2" :type :sizing}))
                  (ctob/add-token (cthi/id :core) (ctob/make-token {:name "brand.color" :value "#ff0000" :type :color}))
                  (ctob/add-token (cthi/id :core) (ctob/make-token {:name "accent" :value "{brand.color}" :type :color}))
                  (ctob/add-token (cthi/id :core) (ctob/make-token {:name "radius" :value "{size} + 1" :type :border-radius}))
                  (ctob/add-token (cthi/id :theme) (ctob/make-token {:name "size" :value "8" :type :sizing})))
          status (ctos/make-tokens-status :active-set-ids #{(cthi/id :core) (cthi/id :theme)})
          cases  [["the visible definition, references updated"
                   #(-> % (rename-token "theme" "size" "space") (update-references "size" "space"))]
                  ["the visible definition, references kept: the earlier one shows through"
                   #(rename-token % "theme" "size" "space")]
                  ["a shadowed definition, references updated"
                   #(-> % (rename-token "core" "size" "space") (update-references "size" "space"))]
                  ["a shadowed definition, references kept"
                   #(rename-token % "core" "size" "space")]
                  ["a referenced token, references updated"
                   #(-> % (rename-token "core" "brand.color" "brand.primary") (update-references "brand.color" "brand.primary"))]
                  ["a referenced token, references kept: they break"
                   #(rename-token % "core" "brand.color" "brand.primary")]
                  ["to a name an earlier set defines"
                   #(rename-token % "theme" "size" "double")]
                  ["and back"
                   #(-> % (rename-token "theme" "size" "space") (rename-token "theme" "space" "size"))]]
          ;; each case resolves the library first, so the renamed one is
          ;; resolved incrementally from its state
          results (mapv (fn [[label rename]]
                          (let [lib' (rename lib)]
                            (ts/resolve-sets (cfo/get-active-token-sets status lib))
                            {:label label
                             :tokens (cfo/get-tokens-in-active-sets status lib')
                             :tokenscript (resolved-values (ts/resolve-sets (cfo/get-active-token-sets status lib')))}))
                        cases)]
      (->> (rx/from results)
           (rx/mapcat (fn [{:keys [tokens] :as result}]
                        (rx/map #(assoc result :style-dictionary (resolved-values %))
                                (sd/resolve-tokens tokens))))
           (rx/reduce conj [])
           (rx/subs!
            (fn [results]
              (doseq [{:keys [label tokenscript style-dictionary]} results]
                (t/is (= style-dictionary tokenscript) (str "renaming " label)))
              (let [[updated kept _ _ _ broken] (mapv :tokenscript results)]
                (t/is (= {"size" 4 "space" 8 "double" 16 "radius" 9}
                         (select-keys updated ["size" "space" "double" "radius"])))
                (t/is (= {"size" 4 "space" 8 "double" 8 "radius" 5}
                         (select-keys kept ["size" "space" "double" "radius"])))
                (t/is (= [:error.style-dictionary/missing-reference] (get broken "accent"))))
              (t/is (= 8 (count results)))
              (done))
            (fn [cause]
              (t/is (nil? cause))
              (done)))))))

(t/deftest resolve-tokens-gradient-function-test
  (let [css-gradients
        ["linear-gradient(to right, #5500ff, #00ff55)"
         "linear-gradient(135deg, red 0%, blue 100%)"
         "linear-gradient(90deg, rgba(255, 0, 0, 0.5) 25%, #00ff55)"
         "linear-gradient(0.25turn, hsl(120, 100%, 50%), rgb(0, 0, 255))"
         "linear-gradient(red 0% 50%, blue 50% 100%)"
         "linear-gradient(45deg, {color.brand}, {color.brand} 40%, #0000ff)"
         "linear-gradient(90deg, {color.brand} {position.start}, #0000ff 100%)"]

        resolved
        (->> css-gradients
             (map-indexed (fn [index css]
                            {:name (str "gradient.css" index)
                             :type :gradient
                             :value (tsi/linear-gradient->tokenscript css)}))
             (concat [{:name "color.brand" :value "#ff0000" :type :color}
                      {:name "position.start" :value "20%" :type :number}
                      {:name "gradient.raw" :type :gradient
                       :value "linear-gradient(to right, #5500ff, #00ff55)"}
                      {:name "gradient.not-css" :value "linear-gradient(to nowhere, red, blue)" :type :gradient}])
             (apply tokens-map)
             (ts/resolve-tokens))]
    (t/testing "tokenscript written for a CSS gradient resolves to a gradient"
      (doseq [index (range (count css-gradients))]
        (let [{:keys [errors resolved-value]} (get resolved (str "gradient.css" index))]
          (t/is (empty? errors) (nth css-gradients index))
          (t/is (some? (wtg/value->gradient resolved-value)) (nth css-gradients index)))))

    (t/testing "tokenscript reads most CSS as it is"
      (t/is (= {:kind "linear"
                :angle 90
                :stops [{:color "#5500ff" :position 0}
                        {:color "#00ff55" :position 1}]}
               (get-in resolved ["gradient.raw" :resolved-value]))))

    (t/testing "angles, colors with opacity and positions come out as written"
      (t/is (= {:kind "linear"
                :angle 90
                :stops [{:color "#ff000080" :position 0.25}
                        {:color "#00ff55" :position 1}]}
               (get-in resolved ["gradient.css2" :resolved-value]))))

    (t/testing "references resolve, also the ones of positions"
      (t/is (= {:kind "linear"
                :angle 90
                :stops [{:color "#ff0000" :position 0.2}
                        {:color "#0000ff" :position 1}]}
               (get-in resolved ["gradient.css6" :resolved-value]))))

    (t/testing "text that is no linear gradient is invalid"
      (t/is (nil? (get-in resolved ["gradient.not-css" :resolved-value])))
      (t/is (= :error.style-dictionary/invalid-token-value-gradient
               (get-in resolved ["gradient.not-css" :errors 0 :error/code]))))))
