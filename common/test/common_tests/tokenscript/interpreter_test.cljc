;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.interpreter-test
  (:require
   [app.common.tokenscript.symbols :as sym]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

;; Expressions ----------------------------------------------------------------

(t/deftest basic-expressions-test
  (t/is (= "4" (h/ev "1 + {hello} + {world}" {"hello" 1 "world" 2})))
  (t/is (= "8" (h/ev "(1 + {hello}) * 2" {"hello" 3})))
  (t/is (= "4" (h/ev "((1 + {hello}) * 2) / 2" {"hello" 3})))
  (t/is (= "7" (h/ev "{hello} + {world} - 2 * 8 / -4" {"hello" 1 "world" 2})))
  (t/is (= "3rem" (h/ev "{hello} + {world}rem" {"hello" 1 "world" 2})))
  (t/is (= "3" (h/ev "SUM({hello}, {world})" {"hello" 1 "world" 2})) "function names are case insensitive")
  (t/is (= "6, 11, 6" (h/ev "{hello} + {world} + {test}, 5+6, {test}*2" {"hello" 1 "world" 2 "test" 3})))
  (t/is (= "1 2 3px" (h/ev "{hello} {test} 3px" {"hello" 1 "test" 2})))
  (t/is (= "1 1px, 2 1, 3px" (h/ev "{hello} 1px, {test} {hello}, 3px" {"hello" 1 "test" 2})))
  (t/is (= "0" (h/ev "0"))))

(t/deftest math-test
  (t/is (= "2" (h/ev "4 / 2")))
  (t/is (= "2.5" (h/ev "5 / 2")))
  (t/is (= "32" (h/ev "4 * 2^3")))
  (t/is (= "327680" (h/ev "4 ^ (2 ^ 3) * 5")))
  (t/is (= "104px" (h/ev "(10*10+4)px")))
  (t/is (= "100px" (h/ev "10^2px")))
  (t/is (= "100rem" (h/ev "10rem^2")))
  (t/is (= "0.30000000000000004" (h/ev "0.1 + 0.2")) "numbers are doubles")
  (t/is (= :operations/unit-exponent-not-supported (h/ev-error "10rem^2px")))
  (t/is (= :operations/division-by-zero (h/ev-error "4 / 0")))
  (t/is (= :interpreter/arithmetic-requires-number (h/ev-error "\"a\" + 1"))))

(t/deftest units-test
  (t/is (= "17px" (h/ev "1px + 1rem")) "units convert to the unit with the largest magnitude")
  (t/is (= "17px" (h/ev "1rem + 1px")) "regardless of operand order")
  (t/is (= "5px" (h/ev "50% * 10px")) "percentages act as factors")
  (t/is (= "11rem" (h/ev "10rem + 10%")) "relative units resolve against the other operand")
  (t/is (= "2500ms" (h/ev "2s + 500ms")))
  (t/is (= "2000s" (h/ev "(1500ms + 500ms)s")) "a trailing unit relabels the value without converting")
  (t/is (= "17px" (h/ev "sum(1px, 1rem)")))
  (t/is (= "36px" (h/ev "sum(10px, 10, 1rem)")))
  (t/is (= :operations/cannot-mix-units (h/ev-error "1px + 1deg")))
  (t/is (= "NumberWithUnit.Px" (h/ev-type "3px"))))

(t/deftest comparisons-and-logic-test
  (t/is (= "true" (h/ev "1 < 2")))
  (t/is (= "false" (h/ev "1 == 2")))
  (t/is (= "true" (h/ev "\"a\" == \"a\"")))
  (t/is (= "true" (h/ev "1px == 1px")))
  (t/is (= "true" (h/ev "true && !false")))
  (t/is (= "true" (h/ev "{a} != {b} && !{b}" {"a" true "b" false})) "false references are defined")
  (t/is (= :operations/incompatible-types (h/ev-error "\"a\" > 1")))
  (t/is (= :operations/cannot-compare-units (h/ev-error "1px == 1rem")))
  (t/is (= :operations/logical-requires-boolean (h/ev-error "1 && true")))
  (t/is (= :operations/ordering-with-null (h/ev-error "variable a: String; return a > \"x\";"))))

(t/deftest functions-test
  (t/is (= "5.141592653589793px" (h/ev "pi() + 2px")))
  (t/is (= "3" (h/ev "round(2.5)")) "rounds half up like JavaScript")
  (t/is (= "-2" (h/ev "round(-2.5)")))
  (t/is (= "3.14" (h/ev "round_to(3.14159, 2)")))
  (t/is (= "1" (h/ev "mod(-5, 3)")) "mod is always positive")
  (t/is (= "-2" (h/ev "remainder(-5, 3)")))
  (t/is (= "4px" (h/ev "max(1px, 4px, 2px)")))
  (t/is (= "255" (h/ev "parse_int(\"ff\", 16)")))
  (t/is (= "0, 1, 2" (h/ev "range(3)")))
  (t/is (= "2, 3" (h/ev "range(2, 4)")))
  (t/is (= "px" (h/ev "type(1px)")))
  (t/is (= "color.hex" (h/ev "type(#333)")))
  (t/is (= "list.implicit" (h/ev "type({a})" {"a" (sym/list [(sym/number 1)] true)})))
  (t/is (= "true" (h/ev "is_number(1) && is_string(\"a\") && is_color(#fff) && is_null(null)")))
  (t/is (= "linear-gradient(1, 2 5px, 3rem)" (h/ev "linear-gradient(1, 2 5px, 3rem)")))
  (t/is (= :interpreter/unknown-function (h/ev-error "unknown_function(1, 2, 3)")))
  (t/is (= :functions/expects-number-arguments (h/ev-error "abs(\"a\")")))
  (t/is (= :functions/requires-min-arguments (h/ev-error "min()")))
  (t/is (= :functions/argument-out-of-range (h/ev-error "asin(2)"))))

(t/deftest strings-test
  (t/is (= "HELLO" (h/ev "\"hello\".upper()")))
  (t/is (= "5" (h/ev "\"hello\".length()")))
  (t/is (= "ab" (h/ev "\"a\".concat(\"b\")")))
  (t/is (= "a, b, " (h/ev "\"a,b,\".split(\",\")")) "trailing empty parts are kept")
  (t/is (= "a, 😀, b" (h/ev "\"a😀b\".split()")) "splitting without delimiter uses code points")
  (t/is (= "ff" (h/ev "255.to_string(16)")))
  (t/is (= "7" (h/ev "7.5.to_string(16)")) "hex conversion rounds halves down")
  (t/is (= :symbols/invalid-radix (h/ev-error "1.to_string(99)"))))

(t/deftest implicit-strings-test
  (t/is (= "1px solid black" (h/ev-inline "1px solid black")))
  (t/is (= "List.Implicit" (sym/result-type-name (h/evaluate "1px solid black" nil {:mode :inline}))))
  (t/is (= "2 unknown" (h/ev-inline "1 + 1unknown")) "unknown suffixes are separate words")
  (t/is (= "Arial Helvetica sans-serif" (h/ev-inline "Arial Helvetica sans-serif")))
  (t/is (= "http://foo.bar" (h/ev-inline "http://foo.bar"))))

;; Statements -----------------------------------------------------------------

(t/deftest variables-test
  (t/is (= "7" (h/ev "variable x: Number = 5; if (x > 3) [ return x + 2; ];")))
  (t/is (= :interpreter/unknown-reference (h/ev-error "variable i: Number = {float_ref};")))
  (let [vars (h/variables "variable i: Number = 1; variable j: Number = 0.5; variable k: Number = i + j;")]
    (t/is (== 1.5 (sym/value (get vars "k")))))
  (t/is (= :interpreter/variable-already-defined (h/ev-error "variable x: Number = 1; variable x: Number = 2;")))
  (t/is (= :interpreter/invalid-variable-type (h/ev-error "variable x: Foo = 1;")))
  (t/is (= :interpreter/invalid-value-for-variable (h/ev-error "variable x: Number = \"a\";")))
  (t/is (= :interpreter/variable-not-found (h/ev-error "x = 1;")))
  (t/is (= :interpreter/type-mismatch (h/ev-error "variable x: Number = 1; x = \"a\";")))
  (t/is (= "null" (h/ev "variable x: String; return x;")) "declared variables are null")
  (t/is (= "true" (h/ev "variable x: Number; return x == null;")))
  (t/is (= "Number" (h/ev-type "variable x: Number; return x;"))))

(t/deftest control-flow-test
  (t/is (= "3" (h/ev "variable i: Number = 0; while (i < 3) [ i = i + 1; ]; return i;")))
  (t/is (= "2" (h/ev "variable x: Number = 2; if (x == 1) [ return 1; ] elif (x == 2) [ return 2; ] else [ return 3; ];")))
  (t/is (= "10, 21" (h/ev "for i, idx in [10, 20] [ i + idx; ];")) "for yields the body results")
  (t/is (= "6" (h/ev "variable sum: Number = 0; for n in range(4) [ sum = sum + n; ]; return sum;")))
  (t/is (= :interpreter/max-iterations-exceeded (h/ev-error "while (true) [ 1; ];")))
  (t/is (= :interpreter/if-condition-not-boolean (h/ev-error "if (1) [ 1; ];")))
  (t/is (= :interpreter/for-each-not-list (h/ev-error "for i in 1 [ i; ];")))
  (t/is (= :interpreter/for-each-variable-shadow (h/ev-error "variable i: Number = 1; for i in [1] [ i; ];"))))

(t/deftest lists-test
  (t/is (= "1-2-3-4-5" (h/ev "variable l: List = [1, 2]; l.append(3); l.extend([4, 5]); return l.join(\"-\");")))
  (t/is (= "3" (h/ev "variable l: List = 1, 2, 3; return l.length();")))
  (t/is (= "2" (h/ev "[1, 2, 3].get(1)")))
  (t/is (= "3" (h/ev "[1, 2, 3].get(-1)")) "negative indexes count from the end")
  (t/is (= "1" (h/ev "[1, 2, 3].index(2)")))
  (t/is (= "9, 2" (h/ev "variable l: List = [1, 2]; l.update(0, 9); return l;")))
  (t/is (= "1, 3" (h/ev "variable l: List = [1, 2, 3]; l.delete(1); return l;")))
  (t/is (= :symbols/index-out-of-range (h/ev-error "[1].get(5)")))
  (t/testing "containers store copies"
    (t/is (= "1" (h/ev "variable a: List = [1]; variable b: List = [0]; b.append(a); a.append(2); return b.get(1).length();")))))

(t/deftest dictionaries-test
  (t/is (= "{a: 1, b: x}" (h/ev "variable d: Dictionary; d.set(\"a\", 1); d.set(\"b\", \"x\"); return d;")))
  (t/is (= "1" (h/ev "variable d: Dictionary; d.set(a, 1); return d.get(a);")) "bare identifiers are string keys")
  (t/is (= "null" (h/ev "variable d: Dictionary; return d.get(\"missing\");")))
  (t/is (= "true" (h/ev "variable d: Dictionary; d.set(\"a\", 1); return d.key_exists(\"a\");")))
  (t/is (= "a, b" (h/ev "variable d: Dictionary; d.set(\"a\", 1); d.set(\"b\", 2); return d.keys();")))
  (t/is (= "0" (h/ev "variable d: Dictionary; d.set(\"a\", 1); d.clear(); return d.length();")))
  (t/is (= "1" (h/ev "variable a: Number = 1; variable x: Dictionary; x.set('a', a); a = 2; return x.get('a');")))
  (t/is (= "1" (h/ev "variable d: Dictionary; d.set(a, 1); d.set(dict, d); return d.get(dict).get(a);"))))

(t/deftest template-strings-test
  (t/is (= "size-4px-ref" (h/ev "variable n: Number = 2; return `size-${n * 2}px-{a}`;" {"a" "ref"})))
  (t/is (= "2" (h/ev "`${1 + 1}`")) "a single interpolation keeps its type")
  (t/is (= "Number" (h/ev-type "`${1 + 1}`")))
  (t/is (= :interpreter/template-invalid-type (h/ev-error "`a ${[1, 2]}`"))))

;; Colors ---------------------------------------------------------------------

(t/deftest hex-colors-test
  (t/is (= "#ff0000" (h/ev "#ff0000")))
  (t/is (= "Color.Hex" (h/ev-type "#ff0000")))
  (t/is (= "#ff000080" (h/ev "#ff000080")) "eight digit colors keep their alpha")
  (t/is (== 0.5019607843137255 (:alpha (h/evaluate "#ff000080"))))
  (t/is (= "#ffffff80" (h/ev "variable c: Color = #fff; c.alpha = 0.5; return c;")))
  (t/is (= "0.5019607843137255" (h/ev "#ff000080.alpha")))
  (t/is (= :color/invalid-alpha-value (h/ev-error "variable c: Color = #fff; c.alpha = 2; return c;")))
  (t/is (= "#FF5733" (h/ev "{c}.to_string()" {"c" "#FF5733"})) "hex strings in references are colors"))

(t/deftest color-conversions-test
  (let [opts {:config (h/full-config)}]
    (t/is (= "hsl(0, 1, 0.5)" (h/ev "#ff0000.to.hsl()" nil opts)))
    (t/is (= "#ff0000" (h/ev "#ff0000.to.hsl().to.hex()" nil opts)))
    (t/is (= "#00ff00" (h/ev "hsl(120, 1, 0.5).to.hex()" nil opts)))
    (t/is (= "hsl(0, 1, 0.5, 0.5)" (h/ev "#ff000080.to.hsl()" nil opts)) "alpha survives conversions")
    (t/is (= "Color.Oklch" (sym/result-type-name (h/evaluate "lighten(#ff0000, 0.2)" nil opts))))
    (t/is (= "#ff0000" (h/ev "#ff0000.to.oklch().to.hex()" nil opts)))
    (t/is (= "rgb(0, 255, 255)" (h/ev "invert(#ff0000)" nil opts)))
    (t/is (= :interpreter/method-not-found (h/error-code #(h/evaluate "#fff.to.hsl()"))) "without the hsl schema")
    (t/testing "conversions are cached, but -0.0 is not taken for 0.0"
      (let [lab->oklch #(str "variable c: Color.OKLab; c.l = 0.5; c.a = " % "; c.b = 0; return c.to.oklch();")]
        (t/is (= "oklch(0.5, 0, 0)" (h/ev (lab->oklch "0") nil opts)))
        (t/is (= "oklch(0.5, 0, 0)" (h/ev (lab->oklch "0") nil opts)))
        (t/is (= "oklch(0.5, 0, 180)" (h/ev (lab->oklch "-0") nil opts))))))
  (t/testing "named css colors are inline constants of the processor, not of the interpreter"
    (t/is (= "red" (h/ev "red" nil {:config (h/full-config)}))
          "bare identifiers fall back to strings")))

(t/deftest null-test
  (t/is (= "Null" (h/ev-type "null")))
  (t/is (= "true" (h/ev "null == null")))
  (t/is (= "false" (h/ev "variable l: List; return l == null;")) "lists are never null")
  (t/is (= :symbols/value-is-null (h/ev-error "variable s: String; return s.upper();"))))

(t/deftest references-test
  (t/is (= :interpreter/unknown-reference (h/ev-error "{missing}")))
  (t/is (= "a, b" (h/ev "{l}" {"l" ["a" "b"]})) "vectors become lists")
  (t/is (= "x" (h/ev "{d}.get(\"k\")" {"d" {"k" "x"}})) "maps become dictionaries")
  (t/is (= "" (h/ev "")) "empty programs produce an empty string"))
