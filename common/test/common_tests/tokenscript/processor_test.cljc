;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.processor-test
  (:require
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.processor :as proc]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-data :as td]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

(defn- process
  ([tokens] (process tokens (h/core-config)))
  ([tokens cfg] (proc/process-tokens tokens {:config cfg})))

(defn- value
  "String form of a resolved token."
  [res name]
  (let [v (get-in res [:tokens name])]
    (if (sym/symbol? v) (sym/to-string v (get-in res [:state :config])) v)))

(defn- codes
  [res name]
  (mapv :code (get-in res [:issues name])))

(t/deftest simple-resolution-test
  (let [res (process {"colors.primary" "#FF0000"
                      "colors.secondary" "{colors.primary}"
                      "spacing.base" "8"
                      "spacing.small" "{spacing.base} / 2"
                      "spacing.large" "{spacing.base} * 2px"
                      "a" "10" "b" "{a} + 5" "c" "{b} * 2"})]
    (t/is (= "#FF0000" (value res "colors.secondary")))
    (t/is (= "4" (value res "spacing.small")))
    (t/is (= "16px" (value res "spacing.large")))
    (t/is (= "30" (value res "c")))
    (t/is (nil? (:issues res)))
    (t/is (= #{"b"} (get-in res [:graph "c"])))
    (t/is (= #{} (get-in res [:graph "a"])))))

(t/deftest primitive-values-test
  (let [res (process {"n" 8 "b" true "nil" nil "kw" "uppercase" "ref" "{n} + 1"})]
    (t/is (= "8" (value res "n")))
    (t/is (= "Number" (sym/type-name (get-in res [:tokens "n"]))))
    (t/is (= "true" (value res "b")))
    (t/is (sym/null? (get-in res [:tokens "nil"])))
    (t/is (= "uppercase" (value res "kw")) "uninterpreted keywords stay strings")
    (t/is (= "9" (value res "ref")))))

(t/deftest errors-test
  (let [res (process {"a" "10"
                      "bad" "{a} + invalid syntax !@#"
                      "miss" "{nope}"
                      "miss2" "{miss} + 1"})]
    (t/is (= "10" (value res "a")))
    (t/is (= "{a} + invalid syntax !@#" (value res "bad")) "failed tokens keep their original value")
    (t/is (= [:lexer/invalid-character] (codes res "bad")))
    (t/is (= [:processor/dependency-error] (codes res "miss")))
    (t/is (= "miss → nope" (get-in res [:issues "miss" 0 :data :chain])))
    (t/is (= "Token 'nope' not found" (get-in res [:issues "miss" 0 :data :root-cause])))
    (t/is (= "miss2 → miss → nope" (get-in res [:issues "miss2" 0 :data :chain])) "chains accumulate")
    (t/is (not (contains? (:resolved res) "nope")) "missing tokens are not invented")
    (t/is (= #{"miss" "miss2"} (proc/broken-references res)))
    (t/is (err/error-map? (proc/token-error res "bad")))
    (t/is (proc/issue-with-code? res "miss" :processor/dependency-error))))

(t/deftest circular-dependencies-test
  (let [res (process {"x" "{y}" "y" "{x}" "z" "{x} + 1" "self" "{self}"})]
    (t/is (= [:processor/circular-dependency] (codes res "x")))
    (t/is (= [:processor/circular-dependency] (codes res "y")))
    (t/is (= [:processor/circular-dependency] (codes res "self")))
    (t/is (= [:processor/dependency-error] (codes res "z")) "dependents of a cycle get a dependency error")
    (t/is (= "Circular dependency detected: x" (get-in res [:issues "z" 0 :data :root-cause])))))

(t/deftest group-references-test
  (let [res (process {"color.primary" "#FF0000"
                      "color.secondary" "{color.primary}"
                      "color.nested.deep" "#000"
                      "group" "{color}"
                      "keys" "{color}.keys()"
                      "pick" "{color}.get(\"secondary\")"})]
    (t/is (= "Dictionary" (sym/type-name (get-in res [:tokens "group"]))))
    (t/is (= "primary, secondary" (value res "keys")) "only direct children are included")
    (t/is (= "#FF0000" (value res "pick")))))

(t/deftest dictionary-children-test
  (let [res (process {"color.primary" "#FF0000"
                      "dict" "{color}"
                      "virt" "{dict.primary}"
                      "missing" "{dict.nope}"})]
    (t/is (= "#FF0000" (value res "virt")) "keys of dictionary tokens are addressable")
    (t/is (= [:processor/dependency-error] (codes res "missing")))))

(t/deftest structured-tokens-test
  (let [res (process {"base" "4"
                      "shadow" {:value {:offset-x "{base} * 2" :offset-y 4 :blur "{missing}" :color "#000"}
                                :type "shadow"}
                      "shadows" {:value [{:blur "{base}px" :color "#fff"}] :type "shadow"}
                      "plain" {:value {:a 1 :b "static"} :type "custom"}
                      "ref" "{shadows[0].blur}"
                      "ref2" "{shadow.offset-x}"})]
    (t/is (= "Token.Shadow" (sym/full-type-name (get-in res [:tokens "shadows"]))))
    (t/is (= "{blur: 4px, color: #fff}" (value res "shadows")))
    (t/is (= [4.0 "#fff"] (let [d (sym/->data (get-in res [:tokens "shadows"]))]
                            [(double (get-in d [0 "blur" :value])) (get-in d [0 "color" :value])])))
    (t/is (= "4px" (value res "ref")) "fields of structured tokens are referenceable")
    (t/is (= "8" (value res "ref2")))
    (t/is (= "{a: 1, b: static}" (value res "plain")))
    (t/testing "a failing field fails the parent with the field path"
      (t/is (map? (value res "shadow")))
      (let [[field-issue parent-issue] (get-in res [:issues "shadow"])]
        (t/is (= ["blur"] (:path field-issue)))
        (t/is (= :processor/dependency-error (:code field-issue)))
        (t/is (= :error (:severity field-issue)))
        (t/is (= :processor/dependency-error (:code parent-issue)))))))

(t/deftest object-parsers-test
  (let [res (proc/process-tokens {"spacing" {:value {:small {:value 8 :unit "px"}} :type "spacing"}}
                                 {:config (h/core-config)
                                  :object-parsers [td/number-with-unit-parser]})]
    (t/is (= "{small: 8px}" (value res "spacing")))))

(t/deftest token-name-validation-test
  (let [res (process {"bad name" "1" "ok.name" "2"})]
    (t/is (= ["INVALID_TOKEN_NAME"] (codes res "bad name")))
    (t/is (= "1" (value res "bad name")) "the token still resolves")
    (t/is (nil? (get-in res [:issues "ok.name"])))))

(t/deftest nested-input-test
  (let [res (process {"color" {"red" {"$value" "#f00" "$type" "color"}
                               "dark" {"$value" "{color.red}"}}})]
    (t/is (= "#f00" (value res "color.red")))
    (t/is (= "#f00" (value res "color.dark")))
    (t/is (= "color" (get-in res [:state :tokens "color.red" :type])))))

(t/deftest inline-constants-test
  (let [res (process {"a" "red" "b" "{a}" "c" "lighten({b}, 0.1)"} (h/full-config))]
    (t/is (= "#FF0000" (value res "a")) "css color names come from the bundled constants")
    (t/is (= "Color.Oklch" (sym/full-type-name (get-in res [:tokens "c"]))))
    (t/is (nil? (:issues res)))))

(t/deftest type-validation-test
  (let [cfg (config/register-schema (h/core-config)
                                    {:uri "https://example.com/api/v1/schema/opacity/0/"
                                     :schema {:name "opacity"
                                              :type "token"
                                              :validation "if (is_number({input}) == false) [ return \"NOT_A_NUMBER\"; ]; if ({input} > 1) [ return \"OUT_OF_RANGE\"; ]; return true;"}})
        res (process {"ok" {:value "0.5" :type "opacity"}
                      "big" {:value "2" :type "opacity"}
                      "str" {:value "\"a\"" :type "opacity"}} cfg)]
    (t/is (nil? (get-in res [:issues "ok"])))
    (t/is (= ["OUT_OF_RANGE"] (codes res "big")))
    (t/is (= :warning (get-in res [:issues "big" 0 :severity])))
    (t/is (= ["NOT_A_NUMBER"] (codes res "str")))
    (t/is (= "2" (value res "big")) "validation issues do not fail the token")))

(t/deftest update-token-test
  (let [res (process {"a" "10" "b" "{a} + 5" "c" "{b} * 2" "d" "1"})
        st (:state res)
        upd (proc/update-token st {:token-path "a" :token-data {:value "20"}})]
    (t/is (= "20" (sym/to-string (:resolved upd))))
    (t/is (= "25" (value upd "b")))
    (t/is (= "50" (value upd "c")))
    (t/is (= #{"a" "b" "c"} (proc/affected-tokens upd)))
    (t/is (= #{"a" "b" "c"} (proc/modified-dependants (:tokens res) upd)))
    (t/is (= "1" (value upd "d")) "unaffected tokens keep their value")
    (t/is (:updated upd))
    (t/testing "updating an unknown token creates it"
      (let [upd2 (proc/update-token st {:token-path "e" :token-data {:value "{d} + 1"}})]
        (t/is (= "2" (value upd2 "e")))
        (t/is (false? (:updated upd2)))))
    (t/testing "a value can be broken and fixed again"
      (let [broken (proc/update-token st {:token-path "a" :token-data {:value "{zzz}"}})
            fixed (proc/update-token (:state broken) {:token-path "a" :token-data {:value "1"}})]
        (t/is (= #{"a" "b" "c"} (proc/broken-references broken)))
        (t/is (nil? (:issues fixed)))
        (t/is (= "12" (value fixed "c")))))))

(t/deftest rename-token-test
  (let [res (process {"a" "10" "b" "{a} + 5"})
        rn (proc/update-token (:state res) {:token-path "a" :token-path-renamed "alpha" :update-references? true})]
    (t/is (= "{alpha} + 5" (get-in rn [:state :tokens "b" :value])))
    (t/is (= "15" (value rn "b")))
    (t/is (not (contains? (:tokens rn) "a"))))
  (t/testing "without reference updates the dependents break"
    (let [res (process {"a" "10" "b" "{a} + 5"})
          rn (proc/update-token (:state res) {:token-path "a" :token-path-renamed "alpha"})]
      (t/is (= #{"b"} (proc/broken-references rn)))))
  (t/is (= :processor/token-already-exists
           (h/error-code #(proc/update-token (:state (process {"a" "1" "b" "2"}))
                                             {:token-path "a" :token-path-renamed "b"})))))

(t/deftest create-and-delete-token-test
  (let [res (process {"a" "10" "b" "{a} + 5"})
        cr (proc/create-token (:state res) {:token-path "c" :token-data {:value "{b} * 2"}})]
    (t/is (:created cr))
    (t/is (= "30" (value cr "c")))
    (t/is (= :processor/token-already-exists
             (h/error-code #(proc/create-token (:state cr) {:token-path "c" :token-data {:value "1"}}))))
    (let [del (proc/delete-token (:state cr) {:token-path "a"})]
      (t/is (not (contains? (:tokens del) "a")))
      (t/is (= #{"b" "c"} (proc/broken-references del)))
      (t/is (= #{"b" "c"} (proc/affected-tokens del)))
      (t/is (= :processor/token-not-found
               (h/error-code #(proc/delete-token (:state del) {:token-path "a"})))))))

(t/deftest resolve-value-test
  (let [st (:state (process {"a" "10" "b" "{a} + 5"}))]
    (t/is (= "16px" (sym/to-string (:resolved (proc/resolve-value st {:value "{b} + 1px"})))))
    (t/is (= [:interpreter/unknown-reference] (mapv :code (:issues (proc/resolve-value st {:value "{zzz}"})))))
    (t/is (= [:lexer/invalid-character] (mapv :code (:issues (proc/resolve-value st {:value "1 ´ 2"})))))
    (t/is (= {:resolved nil :issues []} (proc/resolve-value st {:value ""})))))

(t/deftest dependents-test
  (let [graph {"a" #{} "b" #{"a"} "c" #{"b"} "d" #{"a"} "e" #{}}
        {:keys [tokens subgraph]} (proc/dependents graph "a")]
    (t/is (= #{"a" "b" "c" "d"} tokens))
    (t/is (= {"a" #{} "b" #{"a"} "c" #{"b"} "d" #{"a"}} subgraph))))

(t/deftest outputs-test
  (let [res (process {"a" "8px" "c" "#fff" "l" "1, 2"})]
    (t/is (= {"a" "8px" "c" "#fff" "l" "1, 2"} (proc/tokens->strings res)))
    (t/is (= {"a" {:value 8.0 :unit "px"} "c" {:type "Hex" :value "#fff" :alpha nil} "l" [1.0 2.0]}
             (-> (proc/tokens->data res)
                 (update-in ["a" :value] double)
                 (update "l" #(mapv double %)))))
    (t/is (= {"x" {"y" 1}} (proc/nest-tokens {"x.y" 1})))))

(t/deftest facade-test
  (let [res (tsc/process-tokens {"a" "lighten(red, 0.1)"})]
    (t/is (= "Color.Oklch" (sym/full-type-name (get-in res [:tokens "a"]))))
    (t/is (= "Color.Hex" (sym/full-type-name (tsc/color->hex (get-in res [:tokens "a"])))))))
