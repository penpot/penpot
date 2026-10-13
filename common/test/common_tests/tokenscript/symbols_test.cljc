;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.symbols-test
  (:require
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.util :as u]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

(t/deftest number-formatting-test
  (t/is (= "3" (u/num->str 3.0)))
  (t/is (= "2.5" (u/num->str 2.5)))
  (t/is (= "-0.5" (u/num->str -0.5)))
  (t/is (= "1e+21" (u/num->str 1e21)))
  (t/is (= "1e-7" (u/num->str 1e-7)))
  (t/is (= "0.000001" (u/num->str 1e-6)))
  (t/is (= "123456789" (u/num->str 123456789.0)))
  (t/is (= "0.30000000000000004" (u/num->str (+ 0.1 0.2)))))

(t/deftest js-helpers-test
  (t/is (== 255 (u/parse-int "ff" 16)))
  (t/is (== 12 (u/parse-int "12abc" 10)) "parses the valid prefix")
  (t/is (nil? (u/parse-int "abc" 10)))
  (t/is (= "50.13" (u/to-fixed 50.125 2)))
  (t/is (= ["a" "b" ""] (u/split "a,b," ",")))
  (t/is (= ["a" "😀" "b"] (u/code-points "a😀b")))
  (t/is (== 3 (u/js-round 2.5)))
  (t/is (== -2 (u/js-round -2.5))))

(t/deftest type-names-test
  (t/is (= "Number" (sym/type-name (sym/number 1))))
  (t/is (= "List.Implicit" (sym/full-type-name (sym/list [] true))))
  (t/is (= "List" (sym/full-type-name (sym/list []))))
  (t/is (= "NumberWithUnit.Px" (sym/full-type-name (sym/number-with-unit 1 "px"))))
  (t/is (= "Color.Hex" (sym/full-type-name (sym/color "#fff"))))
  (t/is (= "Token.Shadow" (sym/full-type-name (sym/token "shadow" {})))))

(t/deftest to-string-test
  (t/is (= "1, a, true, null" (sym/to-string (sym/list [(sym/number 1) (sym/string "a") (sym/boolean true) (sym/null)]))))
  (t/is (= "1 2" (sym/to-string (sym/list [(sym/number 1) (sym/number 2)] true))))
  (t/is (= "{k: 2}" (sym/to-string (sym/dictionary {"k" (sym/number 2)}))))
  (t/is (= "#ff000080" (sym/to-string (sym/color "#ff0000" "Hex" 0.5019607843137255))))
  (t/is (= "hsl(0, 1, 0.5)" (sym/to-string (sym/color {"h" (sym/number 0) "s" (sym/number 1) "l" (sym/number 0.5)} "HSL")
                                           (h/full-config)))
        "dynamic colors use the channel order of their schema")
  (t/is (= "" (sym/to-string (sym/color nil) (h/core-config))) "null colors render empty"))

(t/deftest data-conversion-test
  (t/is (= {"k" 2.0 "l" [1.0 "a"]}
           (-> (sym/dictionary {"k" (sym/number 2) "l" (sym/list [(sym/number 1) (sym/string "a")])})
               (sym/->data)
               (update "k" double)
               (update-in ["l" 0] double))))
  (t/is (= {:value 8.0 :unit "px"} (update (sym/->data (sym/number-with-unit 8 "px")) :value double)))
  (t/is (= "8px" (sym/->data (sym/number-with-unit 8 "px") {:stringify? true})))
  (t/is (= {:type "Hex" :value "#fff" :alpha nil} (sym/->data (sym/color "#fff"))))
  (t/is (nil? (sym/->data (sym/null))))
  (t/testing "data->symbol"
    (t/is (sym/color? (sym/data->symbol "#fff")))
    (t/is (sym/string? (sym/data->symbol "text")))
    (t/is (sym/list? (sym/data->symbol [1 2])))
    (t/is (sym/dictionary? (sym/data->symbol {:a 1})))
    (t/is (= "1" (sym/to-string (sym/get-key (sym/data->symbol {:a 1}) "a"))) "keyword keys become strings")
    (t/is (sym/null? (sym/data->symbol nil)))))

(t/deftest equality-test
  (t/is (sym/equals? (sym/number 1) (sym/number 1.0)))
  (t/is (not (sym/equals? (sym/number 1) (sym/string "1"))))
  (t/is (sym/equals? (sym/number-with-unit 1 "px") (sym/number-with-unit 1 "px")))
  (t/is (not (sym/equals? (sym/number-with-unit 1 "px") (sym/number-with-unit 1 "rem"))))
  (t/is (sym/equals? (sym/color "#fff") (sym/color "#fff")))
  (t/is (not (sym/equals? (sym/color "#fff") (sym/color "#fff" "Hex" 0.5))))
  (let [l (sym/list [(sym/number 1)])]
    (t/is (sym/equals? l l))
    (t/is (not (sym/equals? l (sym/deep-copy l))) "containers compare by identity")))

(t/deftest mutation-test
  (let [l (sym/list [(sym/number 1)])
        d (sym/dictionary nil)]
    (sym/append! l (sym/number 2))
    (sym/set-key! d "l" l)
    (sym/append! l (sym/number 3))
    (t/is (= "1, 2, 3" (sym/to-string l)))
    (t/is (= "{l: 1, 2}" (sym/to-string d)) "stored values are copies")))

(t/deftest methods-test
  (t/is (sym/has-method? (sym/string "a") "upper" []))
  (t/is (sym/has-method? (sym/string "a") "upper" [(sym/number 1)]) "methods without declared arguments accept any")
  (t/is (not (sym/has-method? (sym/string "a") "concat" [(sym/string "b") (sym/string "c")])) "too many arguments")
  (t/is (not (sym/has-method? (sym/string "a") "concat" [])) "missing arguments")
  (t/is (sym/has-method? (sym/list []) "extend" [(sym/number 1) (sym/number 2)]) "variadic methods")
  (t/is (= "A" (sym/to-string (sym/call-method (sym/string "a") "UPPER" [] nil))) "method names are case insensitive")
  (t/is (= "A" (sym/to-string (sym/call-method (sym/string "a") "upper" [(sym/number 1)] nil)))
        "extra arguments of methods without declared arguments are ignored")
  (t/is (= :symbols/method-not-found (h/error-code #(sym/call-method (sym/string "a") "nope" [] nil)))))

(t/deftest token-content-test
  (let [record (sym/token "shadow" {"blur" (sym/number 1)})
        items (sym/token "shadow" [(sym/number 1)])]
    (t/is (= "1" (sym/to-string (sym/call-method record "get" [(sym/string "blur")] nil))))
    (t/is (= "1" (sym/to-string (sym/call-method items "get" [(sym/number 0)] nil))))
    (t/is (= "1, 2" (sym/to-string (sym/call-method items "append" [(sym/number 2)] nil))))
    (t/is (= "1, 2" (sym/to-string (sym/call-method items "values" [] nil))))
    (t/is (= :symbols/cannot-operation-on-token-type
             (h/error-code #(sym/call-method record "append" [(sym/number 2)] nil)))
          "list operations need a token holding a list")
    (t/is (= :symbols/cannot-operation-on-token-type
             (h/error-code #(sym/call-method items "keys" [] nil)))
          "dictionary operations need a token holding a record")))

(t/deftest attributes-test
  (t/is (= "1" (sym/to-string (sym/get-attribute (sym/number-with-unit 1 "px") "value"))))
  (t/is (sym/has-attribute? (sym/token "shadow" {"blur" (sym/number 1)}) "blur"))
  (t/is (= "shadow" (sym/to-string (sym/get-attribute (sym/token "shadow" {}) "subType"))))
  (t/is (= "0.5" (sym/to-string (sym/get-attribute (sym/color "#fff" "Hex" 0.5) "alpha"))))
  (t/is (= :symbols/attribute-not-found (h/error-code #(sym/get-attribute (sym/color "#fff") "r")))))

(t/deftest hex-parsing-test
  (t/is (= {:color "#ff0000" :alpha nil} (sym/parse-hex-with-alpha "#ff0000")))
  (t/is (= {:color "#ffffff" :alpha 1.0} (update (sym/parse-hex-with-alpha "#ffff") :alpha double)))
  (t/is (= "#ffffff80" (sym/format-hex8 "#fff" 0.5)))
  (t/is (sym/valid-hex? "#AbC"))
  (t/is (not (sym/valid-hex? "#ggg")))
  (t/testing "3, 4, 6 and 8 digits are valid, other lengths are not"
    (t/is (every? sym/valid-hex? ["#abc" "#abcd" "#abcdef" "#abcdef12"]))
    (t/is (not-any? sym/valid-hex? ["#" "#a" "#ab" "#abcde" "#abcdef1" "#abcdef123" "abc" "#abc " nil])))
  (t/is (= :symbols/invalid-hex-color (h/error-code #(sym/color "#ggg")))))