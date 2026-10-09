;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.parser-test
  (:require
   [app.common.tokenscript.ast :as ast]
   [app.common.tokenscript.parser :as parser]
   [clojure.test :as t]
   [clojure.walk :as walk]
   [common-tests.tokenscript.helpers :as h]))

(defn- strip
  "Removes tokens from an AST so structures can be compared."
  [node]
  (walk/postwalk (fn [x] (if (map? x) (dissoc x :token :op-token) x)) node))

(defn- parse
  ([text] (parse text nil))
  ([text opts] (strip (:ast (parser/parse text opts)))))

(defn- op-tree
  "Compact `[op left right]` rendering of binary operations."
  [node]
  (case (:node node)
    :bin-op [(:value (:op-token node)) (op-tree (:left node)) (op-tree (:right node))]
    :num (:value node)
    :reference (str "{" (:value node) "}")
    :element-with-unit [(op-tree (:ast-node node)) (:unit node)]
    :unary-op [(:value (:op-token node)) (op-tree (:expr node))]
    (:node node)))

(t/deftest precedence-test
  (let [tree #(op-tree (:ast (parser/parse % {:mode :inline})))]
    (t/is (= ["+" 1.0 ["*" 2.0 3.0]] (tree "1 + 2 * 3")))
    (t/is (= ["*" ["+" 1.0 2.0] 3.0] (tree "(1 + 2) * 3")))
    (t/is (= ["*" 4.0 ["^" 2.0 3.0]] (tree "4 * 2^3")))
    (t/is (= ["-" ["+" 1.0 2.0] 3.0] (tree "1 + 2 - 3")) "left associative")
    (t/is (= ["==" ["+" 1.0 2.0] 3.0] (tree "1 + 2 == 3")) "arithmetic binds tighter than comparison")
    (t/is (= ["&&" ["==" 1.0 1.0] ["<" 2.0 3.0]] (tree "1 == 1 && 2 < 3")))
    (t/is (= ["+" "{a}" [3.0 "px"]] (tree "{a} + 3px")))
    (t/is (= ["-" ["-" 1.0]] (tree "--1")) "unary operators nest")))

(t/deftest lists-test
  (t/is (= {:node :list :elements [{:node :num :value 1.0} {:node :num :value 2.0}]}
           (parse "1, 2" {:mode :inline})))
  (t/is (= {:node :implicit-list
            :elements [{:node :element-with-unit :ast-node {:node :num :value 1.0} :unit "px"}
                       {:node :identifier :name "solid"}
                       {:node :identifier :name "black"}]}
           (parse "1px solid black" {:mode :inline})))
  (t/is (= {:node :list
            :elements [{:node :implicit-list :elements [{:node :num :value 1.0} {:node :num :value 2.0}]}
                       {:node :num :value 3.0}]}
           (parse "1 2, 3" {:mode :inline}))
        "commas separate explicit elements, whitespace builds implicit lists")
  (t/is (= {:node :list :elements [{:node :num :value 1.0} {:node :num :value 2.0}]}
           (parse "[1, 2]" {:mode :inline})))
  (t/is (= {:node :list :elements []} (parse "[]" {:mode :inline}))))

(t/deftest references-test
  (t/is (= #{"a.b" "c"} (:references (parser/parse "{a.b} + {c}" {:mode :inline}))))
  (t/is (= #{"x"} (:references (parser/parse "`v-{x}`" {:mode :inline}))) "template references count"))

(t/deftest inline-mode-test
  (t/is (= {:node :identifier :name "http://foo.bar"} (parse "http://foo.bar" {:mode :inline})))
  (t/is (= :parser/unallowed-inline-syntax (h/parse-error "variable x: Number = 1" {:mode :inline})))
  (t/is (= :parser/unexpected-token (h/parse-error "1 + 2; 3" {:mode :inline}))
        "statements are rejected in inline mode")
  (t/is (nil? (:ast (parser/parse "" {:mode :inline}))) "empty input has no AST"))

(t/deftest statements-test
  (let [tree (parse "variable x: Color.Hex = #fff; x.alpha = 0.5; return x;")]
    (t/is (= :statement-list (:node tree)))
    (t/is (= [:assign :reassign :return] (mapv :node (:statements tree))))
    (t/is (= "Color.Hex" (ast/type-decl-name (:type-decl (first (:statements tree))))))
    (t/is (= ["x" "alpha"] (map :name (:identifiers (second (:statements tree))))))))

(t/deftest control-flow-test
  (let [tree (parse "if (a > 1) [ return 1; ] elif (b) [ return 2; ] else [ return 3; ];")]
    (t/is (= :if (:node tree)))
    (t/is (= 2 (count (:conditions tree))))
    (t/is (some? (:else-body tree))))
  (let [tree (parse "for i, idx in [1, 2] [ i; ];")]
    (t/is (= :for-each (:node tree)))
    (t/is (= ["i" "idx"] [(:item-var tree) (:index-var tree)])))
  (t/is (= :while (:node (parse "while (i < 3) [ i = i + 1; ];"))))
  (t/is (= :parser/condition-must-be-boolean (h/parse-error "if (a = 1) [ 1; ];" nil))))

(t/deftest attribute-access-test
  (t/is (= {:node :attribute-access
            :left {:node :attribute-access
                   :left {:node :reference :value "c"}
                   :right {:node :identifier :name "to"}}
            :right {:node :function-call :name "hsl" :args []}}
           (parse "{c}.to.hsl()"))
        "chained attribute access nests left to right")
  (t/is (= {:node :function-call :name "to.hsl" :args []}
           (:right (parse "{c}.to.hsl()" {:mode :inline})))
        "inline mode lexes dotted names as one greedy string")
  (t/is (= {:node :function-call :name "to_string" :args [{:node :num :value 16.0}]}
           (:right (parse "255.to_string(16)" {:mode :inline})))))

(t/deftest template-strings-test
  (t/is (= {:node :template-string
            :parts [{:node :string :value "size-"}
                    {:node :bin-op :left {:node :identifier :name "n"} :right {:node :num :value 2.0}}
                    {:node :string :value "px-"}
                    {:node :reference :value "a"}]}
           (parse "`size-${n * 2}px-{a}`" {:mode :inline})))
  (t/is (= {:node :string :value "plain {x} $"} (parse "`plain \\{x} \\$`" {:mode :inline}))
        "escaped braces produce a plain string")
  (t/is (= :parser/invalid-syntax (h/parse-error "`${1 + `" {:mode :inline}))))

(t/deftest errors-test
  (t/is (= :parser/expected-token-type (h/parse-error "1 + (2" nil)))
  (t/is (= :parser/unexpected-token (h/parse-error "1 +" nil)))
  (t/is (= :parser/invalid-syntax (h/parse-error "1; ]" nil))))

(t/deftest tolerant-test
  (let [{:keys [ast incomplete]} (parser/parse "1 + " {:mode :inline :tolerant? true})]
    (t/is (= :partial-bin-op (:node ast)))
    (t/is (= [:missing-operand] (mapv :type incomplete))))
  (let [{:keys [ast incomplete]} (parser/parse "{col" {:mode :inline :tolerant? true})]
    (t/is (= :partial-reference (:node ast)))
    (t/is (= "col" (:partial-value (first incomplete)))))
  (t/is (= :parser/tolerant-requires-inline (h/parse-error "1" {:tolerant? true}))))

(t/deftest rename-references-test
  (let [text "{a} + {b} * {a}"
        tree (:ast (parser/parse text {:mode :inline}))]
    (t/is (= "{x} + {b} * {x}" (ast/rename-references text tree {"a" "x"})))
    (t/is (= 3 (count (ast/reference-nodes tree))))
    (t/is (= 2 (count (ast/reference-nodes tree "a"))))))
