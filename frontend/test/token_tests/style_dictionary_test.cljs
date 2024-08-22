(ns token-tests.style-dictionary-test
  (:require
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]
   [app.main.ui.workspace.tokens.token :as wtt]))

(def border-radius-token
  {:id #uuid "8c868278-7c8d-431b-bbc9-7d8f15c8edb9"
   :value "12px"
   :name "borderRadius.sm"
   :type :border-radius})

(def reference-border-radius-token
  {:id #uuid "b9448d78-fd5b-4e3d-aa32-445904063f5b"
   :value "{borderRadius.sm} * 2"
   :name "borderRadius.md-with-dashes"
   :type :border-radius})

(def tokens {(:id border-radius-token) border-radius-token
             (:id reference-border-radius-token) reference-border-radius-token})

(t/deftest resolve-tokens-test
  (t/async
    done
    (t/testing "resolves tokens using style-dictionary from a ids map"
      (-> (sd/resolve-tokens+ tokens)
          (p/finally (fn [resolved-tokens]
                       (let [expected-tokens {"borderRadius.sm"
                                              (assoc border-radius-token
                                                     :resolved-value 12
                                                     :resolved-unit "px")
                                              "borderRadius.md-with-dashes"
                                              (assoc reference-border-radius-token
                                                     :resolved-value 24
                                                     :resolved-unit "px")}]
                         (t/is (= expected-tokens resolved-tokens))
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
                                                     :resolved-unit "px")
                                              "borderRadius.md-with-dashes"
                                              (assoc reference-border-radius-token
                                                     :resolved-value 24
                                                     :resolved-unit "px")}]
                         (t/is (= expected-tokens resolved-tokens))
                         (done))))))))
