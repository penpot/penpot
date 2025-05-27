;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.style-dictionary-test
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(t/deftest resolve-tokens-test
  (t/async
    done
    (t/testing "resolves tokens using style-dictionary from a ids map"
      (let [tokens (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :name "core"))
                       (ctob/add-token-in-set "core" (ctob/make-token {:name "borderRadius.sm"
                                                                       :value "12px"
                                                                       :type :border-radius}))
                       (ctob/add-token-in-set "core" (ctob/make-token {:value "{borderRadius.sm} * 2"
                                                                       :name "borderRadius.md-with-dashes"
                                                                       :type :border-radius}))
                       (ctob/add-token-in-set "core" (ctob/make-token {:name "borderRadius.large"
                                                                       :value "123456789012345"
                                                                       :type :border-radius}))
                       (ctob/add-token-in-set "core" (ctob/make-token {:name "borderRadius.largePx"
                                                                       :value "123456789012345px"
                                                                       :type :border-radius}))
                       (ctob/add-token-in-set "core" (ctob/make-token {:name "borderRadius.largeFn"
                                                                       :value "{borderRadius.sm} * 200000000"
                                                                       :type :border-radius}))
                       (ctob/get-all-tokens))]
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

(defn evaluate-math [value]
  (let [platform-config #js {:mathFractionDigits 2}
        token #js {"value" value "type" "dimensions"}]
    (sd/check-and-evaluate-math token platform-config)))

(defn catch-evaluate-math [value]
  (try
    (evaluate-math value)
    (catch js/Error e (ex-data e))))

(defn math-error? [value]
  (= :error.style-dictionary/invalid-math-expression (:error/code value)))

(t/deftest evaluate-math-expressions-test
  (t/testing "evaluates math expressions directly using check-and-evaluate-math"
    ;; Multiplication tests
    (t/is (= 6 (evaluate-math "2 * 3")))
    (t/is (= "6px" (evaluate-math "2px * 3px")))

    ;; Division tests
    (t/is (= 1.33 (evaluate-math "4 / 3")))
    (t/is (= "1.33px" (evaluate-math "4px / 3px")))
    (t/is (= "1.33px" (evaluate-math "4 / 3px")))

    ;; Addition tests
    (t/is (= 5 (evaluate-math "2 + 3")))
    (t/is (= "5px" (evaluate-math "2 + 3px")))
    (t/is (= "5px" (evaluate-math "2px + 3")))

    ;; Subtraction tests
    (t/is (= -1 (evaluate-math "2 - 3")))
    (t/is (= "-1px" (evaluate-math "2 - 3px")))
    (t/is (= "-1px" (evaluate-math "2px - 3px")))

    (evaluate-math "1px + 1rem")

    ;; Invalid operations test
    (t/is (math-error? (catch-evaluate-math "2px * 3rem")))
    (t/is (math-error? (catch-evaluate-math "3rem * 2px")))
    (t/is (math-error? (catch-evaluate-math "4px / 3rem")))
    ;; TODO This wont throw yet in sd-transforms
    #_
    (t/is (math-error? (catch-evaluate-math "4 / 3rem")))
    (t/is (math-error? (catch-evaluate-math "4rem / 3px")))
    ;; TODO This doesnt work in check-and-evaluate-math
    #_
    (t/is (= "1.33rem" (evaluate-math "4rem / 3rem")))
    ;; TODO This wont throw yet in sd-transforms
    #_
    (t/is (= "2rem" (evaluate-math "4rem - 2")))
    ;; TODO This wont throw yet in sd-transforms
    #_
    (t/is (= "-4rem" (evaluate-math "2 - 4rem")))))

(comment
  (t/run-tests *ns*)
  nil)
