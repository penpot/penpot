(ns token-tests.style-dictionary-test
  (:require
   [app.common.data :as d]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(def border-radius-token
  {:value "12px"
   :name "borderRadius.sm"
   :type :border-radius})

(def reference-border-radius-token
  {:value "{borderRadius.sm} * 2"
   :name "borderRadius.md-with-dashes"
   :type :border-radius})

(def tokens (d/ordered-map
             (:name border-radius-token) border-radius-token
             (:name reference-border-radius-token) reference-border-radius-token))
(t/deftest resolve-tokens-test
  (t/async
    done
    (t/testing "resolves tokens using style-dictionary from a ids map"
      (-> (sd/resolve-tokens+ tokens)
          (p/finally
            (fn [resolved-tokens]
              (let [expected-tokens {"borderRadius.sm"
                                     (assoc border-radius-token
                                            :resolved-value 12
                                            :resolved-unit "px")
                                     "borderRadius.md-with-dashes"
                                     (assoc reference-border-radius-token
                                            :resolved-value 24
                                            :resolved-unit "px")}]
                (t/is (= 12 (get-in resolved-tokens ["borderRadius.sm" :resolved-value])))
                (t/is (= "px" (get-in resolved-tokens ["borderRadius.sm" :unit])))
                (t/is (= 24 (get-in resolved-tokens ["borderRadius.md-with-dashes" :resolved-value])))
                (t/is (= "px" (get-in resolved-tokens ["borderRadius.md-with-dashes" :unit])))
                (done))))))))
