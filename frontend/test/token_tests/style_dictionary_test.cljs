(ns token-tests.style-dictionary-test
  (:require
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.common.data :as d]))

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

(t/deftest resolve-tokens-names-map-test
  (t/async
    done
    (t/testing "resolves tokens using style-dictionary from a names map"
      (-> (vals tokens)
          (wtt/token-names-map)
          (sd/resolve-tokens+ {:names-map? true})
          (p/finally (fn [resolved-tokens]
                       (let [expected-tokens {"borderRadius.sm"
                                              (assoc border-radius-token
                                                     :resolved-value 12
                                                     :unit "px")
                                              "borderRadius.md-with-dashes"
                                              (assoc reference-border-radius-token
                                                     :resolved-value 24
                                                     :unit "px")}]
                         (t/is (= expected-tokens resolved-tokens))
                         (done))))))))
