;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.tokens.style-dictionary-test
  (:require
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(t/deftest resolve-tokens-test
  (t/async
    done
    (t/testing "resolves tokens using style-dictionary from a ids map"
      (let [tokens (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core-set)
                                                          :name "core"))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "borderRadius.sm"
                                                         :value "12px"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:value "{borderRadius.sm} * 2"
                                                         :name "borderRadius.md-with-dashes"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "borderRadius.large"
                                                         :value "123456789012345"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "borderRadius.largePx"
                                                         :value "123456789012345px"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "borderRadius.largeFn"
                                                         :value "{borderRadius.sm} * 200000000"
                                                         :type :border-radius}))
                       (ctob/get-all-tokens-map))]
        (-> (sd/resolve-tokens tokens)
            (rx/sub!
             (fn [resolved-tokens]
               (t/is (= 12 (get-in resolved-tokens ["borderRadius.sm" :resolved-value])))
               (t/is (= "px" (get-in resolved-tokens ["borderRadius.sm" :unit])))
               (t/is (= 24 (get-in resolved-tokens ["borderRadius.md-with-dashes" :resolved-value])))
               (t/is (= "px" (get-in resolved-tokens ["borderRadius.md-with-dashes" :unit])))
               (t/is (nil? (get-in resolved-tokens ["borderRadius.large" :resolved-value])))
               (t/is (= :error.token/number-too-large
                        (get-in resolved-tokens ["borderRadius.large" :errors 0 :error/code])))
               (t/is (nil? (get-in resolved-tokens ["borderRadius.largePx" :resolved-value])))
               (t/is (= :error.token/number-too-large
                        (get-in resolved-tokens ["borderRadius.largePx" :errors 0 :error/code])))
               (t/is (nil? (get-in resolved-tokens ["borderRadius.largeFn" :resolved-value])))
               (t/is (= :error.token/number-too-large
                        (get-in resolved-tokens ["borderRadius.largeFn" :errors 0 :error/code])))
               (done))))))))

;; Regression for #9584 — when one active set defines a token named
;; "a" and another defines "a.b", the tokens-tree builder collapses
;; them via assoc-in, so StyleDictionary only sees one. Previously
;; the other vanished from the sidebar entirely; now `resolve-tokens`
;; tags the dropped token with `:error.token/name-collision` so the
;; existing broken-pill rendering picks it up.

(t/deftest resolve-tokens-name-collision-test
  (t/async
    done
    (t/testing "tokens colliding with a token-group prefix survive resolution as broken pills"
      (let [tokens (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-1)
                                                          :name "set-1"))
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set-2)
                                                          :name "set-2"))
                       (ctob/add-token (cthi/id :set-1)
                                       (ctob/make-token {:name "a"
                                                         :value "8px"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :set-2)
                                       (ctob/make-token {:name "a.b"
                                                         :value "12px"
                                                         :type :border-radius}))
                       (ctob/get-all-tokens-map))]
        (-> (sd/resolve-tokens tokens)
            (rx/sub!
             (fn [resolved-tokens]
               (t/testing "both tokens are present in the resolved map"
                 (t/is (contains? resolved-tokens "a"))
                 (t/is (contains? resolved-tokens "a.b")))
               (t/testing "the colliding token carries the name-collision error"
                 (let [errors (or (get-in resolved-tokens ["a" :errors])
                                  (get-in resolved-tokens ["a.b" :errors]))]
                   (t/is (seq errors))
                   (t/is (= :error.token/name-collision
                            (-> errors first :error/code)))))
               (done))))))))

;; Regression: a composite typography token whose value is a plain
;; array (e.g. ["Roboto"]) instead of a map must not crash with
;; "No protocol method IMap.-dissoc defined for type object".
;; It should return an invalid-token-value-typography error instead.
(t/deftest resolve-tokens-typography-array-value-test
  (t/async
    done
    (t/testing "typography token with array value produces error instead of crashing"
      (let [tokens (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core-set)
                                                          :name "core"))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "typography.bad"
                                                         :value ["Roboto"]
                                                         :type :typography}))
                       (ctob/get-all-tokens-map))]
        (-> (sd/resolve-tokens tokens)
            (rx/sub!
             (fn [resolved-tokens]
               (t/is (contains? resolved-tokens "typography.bad"))
               (t/is (nil? (get-in resolved-tokens ["typography.bad" :resolved-value])))
               (t/is (= :error.style-dictionary/invalid-token-value-typography
                        (get-in resolved-tokens ["typography.bad" :errors 0 :error/code])))
               (done))))))))

(t/deftest resolve-tokens-interactive-test
  (t/async
    done
    (t/testing "resolves tokens interactively using backtrace ids map"
      (let [tokens (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :core-set)
                                                          :name "core"))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:name "borderRadius.sm"
                                                         :value "12px"
                                                         :type :border-radius}))
                       (ctob/add-token (cthi/id :core-set)
                                       (ctob/make-token {:value "{borderRadius.sm} * 2"
                                                         :name "borderRadius.md"
                                                         :type :border-radius}))
                       (ctob/get-all-tokens-map))]
        (-> (sd/resolve-tokens-interactive tokens)
            (rx/sub!
             (fn [resolved-tokens]
               (t/is (= 12 (get-in resolved-tokens ["borderRadius.sm" :resolved-value])))
               (t/is (= "px" (get-in resolved-tokens ["borderRadius.sm" :unit])))
               (t/is (= 24 (get-in resolved-tokens ["borderRadius.md" :resolved-value])))
               (t/is (= "px" (get-in resolved-tokens ["borderRadius.md" :unit])))
               (done))))))))

