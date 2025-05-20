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
