;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.lexer-test
  (:require
   [app.common.tokenscript.lexer :as lex]
   [clojure.string :as str]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

(defn- types-and-values
  ([text] (types-and-values text nil))
  ([text opts]
   (mapv (juxt :type :value) (butlast (lex/tokenize text opts)))))

(t/deftest numbers-test
  (t/is (= [[:number "123"]] (types-and-values "123")))
  (t/is (= [[:number "123.45"]] (types-and-values "123.45")))
  (t/is (= [[:number "0.5"]] (types-and-values ".5")) "leading dot gets a zero")
  (t/is (= [[:number "1"] [:dot "."] [:string "to_string"] [:lparen "("] [:rparen ")"]]
           (types-and-values "1.to_string()"))
        "a dot not followed by a digit is a method call")
  (t/is (= :lexer/multiple-decimal-points (h/error-code #(lex/tokenize "3.3.3")))))

(t/deftest positions-test
  (let [[n r] (lex/tokenize "12 {hello}")]
    (t/is (= {:type :number :value "12" :line 1 :pos 0 :end-pos 2} n))
    (t/is (= [1 4 9] [(:line r) (:pos r) (:end-pos r)]) "reference positions exclude the braces")))

(t/deftest references-test
  (t/is (= [[:reference "hello"]] (types-and-values "{hello}")))
  (t/is (= [[:reference "myVar"]] (types-and-values "{  myVar  }")) "whitespace inside is dropped")
  (t/is (= [[:reference "a.b"] [:format "rem"]] (types-and-values "{a.b}rem")))
  (t/is (= :lexer/unterminated-reference (h/error-code #(lex/tokenize "{abc"))))
  (t/is (= :lexer/unterminated-reference (h/error-code #(lex/tokenize "{a{b}"))))
  (t/is (= :lexer/empty-variable-name (h/error-code #(lex/tokenize "{}")))))

(t/deftest formats-test
  (t/testing "unit keywords are formats only right after a number, reference or closing paren"
    (t/is (= [[:number "10"] [:format "px"]] (types-and-values "10px")))
    (t/is (= [[:number "3"] [:format "s"]] (types-and-values "3s")))
    (t/is (= [[:lparen "("] [:number "1"] [:rparen ")"] [:format "rem"]] (types-and-values "(1)rem")))
    (t/is (= [[:number "10"] [:string "px"]] (types-and-values "10 px")))
    (t/is (= [[:string "s"]] (types-and-values "s")))
    (t/is (= [[:number "50"] [:format "%"]] (types-and-values "50%")))
    (t/is (= [[:number "10"] [:format "PX"]] (mapv (juxt :type #(str/upper-case (:value %)))
                                                   (butlast (lex/tokenize "10PX"))))
          "formats are case insensitive")))

(t/deftest words-test
  (t/is (= [[:string "myVariable"]] (types-and-values "myVariable")))
  (t/is (= [[:string "my-variable_name"]] (types-and-values "my-variable_name")))
  (t/is (= [[:reserved-keyword "variable"]] (types-and-values "variable")))
  (t/is (= [[:reserved-keyword "true"]] (types-and-values "TRUE")) "keywords are case insensitive")
  (t/is (= [[:string "😀"]] (types-and-values "😀")) "emoji are identifiers"))

(t/deftest greedy-strings-test
  (t/testing "inline mode keeps urls and dotted paths together"
    (t/is (= [[:string "http://foo.bar"]] (types-and-values "http://foo.bar" {:greedy-strings? true})))
    (t/is (= [[:string "http"] [:colon ":"]] (types-and-values "http://foo.bar"))
          "in script mode `//` starts a comment")
    (t/is (= [[:string "foo"] [:dot "."] [:string "bar"]] (types-and-values "foo.bar"))))
  (t/testing "units are still extracted after numbers"
    (t/is (= [[:number "10"] [:format "rem"] [:operation "^"] [:number "2"]]
             (types-and-values "10rem^2" {:greedy-strings? true})))))

(t/deftest strings-test
  (t/is (= [[:explicit-string "hello world"]] (types-and-values "\"hello world\"")))
  (t/is (= [[:explicit-string "test"]] (types-and-values "'test'")))
  (t/is (= [[:template-string "a {b} ${c}"]] (types-and-values "`a {b} ${c}`")))
  (t/is (= [[:template-string "\\{x"]] (types-and-values "`\\{x`")) "escapes are preserved raw")
  (t/is (= :lexer/unterminated-string (h/error-code #(lex/tokenize "\"abc"))))
  (t/is (= :lexer/unterminated-template-string (h/error-code #(lex/tokenize "`abc")))))

(t/deftest hex-colors-test
  (t/is (= [[:hex-color "#FF00AA"]] (types-and-values "#FF00AA")))
  (t/is (= [[:hex-color "#F0A"]] (types-and-values "#F0A")))
  (t/is (= [[:hex-color "#F0A8"]] (types-and-values "#F0A8")))
  (t/is (= [[:hex-color "#FF00AA80"]] (types-and-values "#FF00AA80")))
  (t/is (= :lexer/invalid-hex-color-format (h/error-code #(lex/tokenize "#FF00A")))))

(t/deftest operators-test
  (t/is (= [[:string "a"] [:is-eq "=="] [:string "b"]] (types-and-values "a == b")))
  (t/is (= [[:string "a"] [:assign "="] [:number "1"]] (types-and-values "a = 1")))
  (t/is (= [[:is-not-eq "!="] [:is-gt-eq ">="] [:is-lt-eq "<="] [:is-gt ">"] [:is-lt "<"]]
           (types-and-values "!= >= <= > <")))
  (t/is (= [[:logic-and "&&"] [:logic-or "||"] [:operation "!"]] (types-and-values "&& || !")))
  (t/is (= [[:operation "+"] [:operation "-"] [:operation "*"] [:operation "/"] [:operation "^"]]
           (types-and-values "+ - * / ^")))
  (t/is (= [[:lblock "["] [:rblock "]"] [:comma ","] [:semicolon ";"] [:colon ":"]]
           (types-and-values "[ ] , ; :"))))

(t/deftest comments-and-lines-test
  (t/is (= [[:number "1"] [:number "2"]] (types-and-values "1 // comment\n2")))
  (t/is (= 2 (:line (second (lex/tokenize "1\n2"))))))

(t/deftest invalid-character-test
  (t/is (= :lexer/invalid-character (h/error-code #(lex/tokenize "1 ´ 2")))))

(t/deftest tolerant-mode-test
  (t/is (= [[:partial-reference "ab"]] (types-and-values "{ab" {:tolerant? true})))
  (t/is (= [[:partial-string "ab"]] (types-and-values "\"ab" {:tolerant? true})))
  (t/is (= [[:hex-color "#FF"]] (types-and-values "#FF" {:tolerant? true})))
  (t/is (= [[:number "1"] [:number "2"]] (types-and-values "1 ´ 2" {:tolerant? true}))
        "invalid characters are skipped"))
