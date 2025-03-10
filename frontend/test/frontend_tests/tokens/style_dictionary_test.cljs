(ns frontend-tests.tokens.style-dictionary-test
  (:require
   [app.common.transit :as tr]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

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
        (-> (sd/resolve-tokens+ tokens)
            (p/finally
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

(t/deftest process-json-stream-test
  (t/async
    done
    (t/testing "process simple color token value"
      (let [json (-> {"core" {"color" {"$value" "red"
                                       "$type" "color"}}
                      "$metadata" {"tokenSetOrder" ["core"]}}
                     (tr/encode-str {:type :json-verbose}))]
        (->> (rx/of json)
             (sd/process-json-stream)
             (rx/subs! (fn [tokens-lib]
                         (t/is (instance? ctob/TokensLib tokens-lib))
                         (t/is (= "red" (-> (ctob/get-set tokens-lib "core")
                                            (ctob/get-token "color")
                                            (:value))))
                         (done))))))))

(t/deftest reference-errros-test
  (t/testing "Extracts reference errors from StyleDictionary errors"
    ;; Using unicode for the white-space after "Error: " as some editors might remove it and its more visible
    (t/is (=
           ["Some token references (2) could not be found."
            ""
            "foo.value tries to reference missing, which is not defined."
            "color.value tries to reference missing, which is not defined."]
           (sd/reference-errors "Error:\u0020
Reference Errors:
Some token references (2) could not be found.

foo.value tries to reference missing, which is not defined.
color.value tries to reference missing, which is not defined.")))
    (t/is (nil? (sd/reference-errors nil)))
    (t/is (nil? (sd/reference-errors "none")))))

(t/deftest process-empty-json-stream-test
  (t/async
    done
    (t/testing "processes empty json string"
      (->> (rx/of "{}")
           (sd/process-json-stream)
           (rx/subs! (fn [tokens-lib]
                       (t/is (instance? ctob/TokensLib tokens-lib))
                       (done)))))))

(t/deftest process-invalid-json-stream-test
  (t/async
    done
    (t/testing "fails on invalid json"
      (->> (rx/of "{,}")
           (sd/process-json-stream)
           (rx/subs!
            (fn []
              (throw (js/Error. "Should be an error")))
            (fn [err]
              (t/is (= :error.import/json-parse-error (:error/code (ex-data err))))
              (done)))))))

(t/deftest process-non-token-json-stream-test
  (t/async
    done
    (t/testing "fails on non-token json"
      (->> (rx/of "{\"foo\": \"bar\"}")
           (sd/process-json-stream)
           (rx/subs!
            (fn []
              (throw (js/Error. "Should be an error")))
            (fn [err]
              (t/is (= :error.import/invalid-json-data (:error/code (ex-data err))))
              (done)))))))

(t/deftest process-missing-references-json-test
  (t/async
    done
    (t/testing "fails on missing references in tokens"
      (let [json (-> {"core" {"color" {"$value" "{missing}"
                                       "$type" "color"}}
                      "$metadata" {"tokenSetOrder" ["core"]}}
                     (tr/encode-str {:type :json-verbose}))]
        (->> (rx/of json)
             (sd/process-json-stream)
             (rx/subs!
              (fn []
                (throw (js/Error. "Should be an error")))
              (fn [err]
                (t/is (= :error.import/style-dictionary-reference-errors (:error/code (ex-data err))))
                (done))))))))
