;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.parser
  "Recursive descent parser producing the AST described in
  `app.common.tokenscript.ast`.

  Two modes exist:

    :script  full statement mode used for schema scripts (variables,
             if/while/for, return)
    :inline  expression only mode used for token `$value` fields. Enables
             greedy strings so values like `http://foo.bar` lex as one
             string.

  Entry point: `(parse text {:mode :inline})` returns
  `{:ast node-or-nil :references #{...} :incomplete [...]}`."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.lexer :as lex]
   [clojure.string :as str]))

;; Parser state ---------------------------------------------------------------
;;
;; The parser keeps its cursor in a volatile so the grammar functions
;; read like the grammar. Nothing escapes the `parse` call.

(defn- make-parser
  [lexer-state {:keys [tolerant?]}]
  (let [[tok lexer-state] (lex/next-token lexer-state)]
    (volatile! {:lexer lexer-state
                :current tok
                :references #{}
                :incomplete []
                :tolerant? (boolean tolerant?)})))

(defn- current
  [p]
  (:current @p))

(defn- current-type
  [p]
  (:type (current p)))

(defn- tolerant?
  [p]
  (:tolerant? @p))

(defn- peek-token
  "Next token after the current one, nil at EOF."
  [p]
  (let [[tok] (lex/next-token (:lexer @p))]
    (when-not (= :eof (:type tok)) tok)))

(defn- peek-tokens
  "Up to `n` tokens after the current one (stops at EOF), nil if none."
  [p n]
  (loop [st (:lexer @p) i 0 acc []]
    (if (= i n)
      (seq acc)
      (let [[tok st] (lex/next-token st)]
        (if (= :eof (:type tok))
          (seq acc)
          (recur st (inc i) (conj acc tok)))))))

(defn- add-reference
  [p ref]
  (vswap! p update :references conj ref))

(defn- add-incomplete
  [p info]
  (vswap! p update :incomplete conj info))

(defn- format-error
  "Renders `message` with the source lines around the token and a
  pointer, like the other tokenscript implementations."
  [p message token]
  (let [{:keys [text line column]} (lex/source-info (:lexer @p))
        lines (str/split (or text "") #"\n" -1)
        token-line (or (:line token) 1)
        error-line-text (nth lines (dec token-line) "")
        col (if (= line token-line)
              column
              (let [idx (str/index-of error-line-text (str (or (:value token) "")))]
                (if (and idx (>= idx 0)) (inc idx) 1)))
        context 2
        start-line (max 0 (- token-line 1 context))
        end-line (min (dec (count lines)) (+ (dec token-line) context))
        body (apply str
                    (for [i (range start-line (inc end-line))
                          :let [line-num (inc i)
                                prefix (str (let [s (str line-num)]
                                              (str (apply str (repeat (- 3 (count s)) " ")) s))
                                            " | ")
                                line-text (nth lines i "")]]
                      (str prefix line-text "\n"
                           (when (= line-num token-line)
                             (str (apply str (repeat (+ (dec (count prefix)) (max 0 (dec col))) " ")) "^\n")))))]
    (str message "\n\n" body)))

(defn- parse-error
  [p code data]
  (let [tok (current p)
        message (or (some-> (:message data) str)
                    (if (= code :parser/invalid-syntax) "Invalid syntax" (err/code->compliance-code code)))]
    (err/raise code {:token tok
                     :data (assoc (or data {}) :message (format-error p message tok))})))

(defn- eat
  "Consumes the current token when it has `type`, returning it."
  [p type]
  (let [tok (current p)]
    (if (= (:type tok) type)
      (let [[next-tok lexer] (lex/next-token (:lexer @p))]
        (vswap! p assoc :current next-tok :lexer lexer)
        tok)
      (parse-error p :parser/expected-token-type
                   {:expected (lex/token-type-name type)
                    :got (lex/token-type-name (:type tok))}))))

(defn- identifier-token?
  "STRING and FORMAT tokens can both act as identifiers (variable,
  attribute and type names)."
  ([p] (identifier-token? p (current p)))
  ([_ tok]
   (contains? #{:string :format} (:type tok))))

(defn- eat-identifier
  [p]
  (if (identifier-token? p)
    (eat p (current-type p))
    (parse-error p :parser/unexpected-token {:token (str (:value (current p)))})))

(defn- identifier-node
  [tok]
  {:node :identifier :name (:value tok) :token tok})

;; Grammar ---------------------------------------------------------------------

(declare expr list-expr implicit-list-expr statement block)

(defn- try-recover-missing-operand
  "In tolerant mode, an operator followed by EOF yields a partial node."
  [p left op-token]
  (when (and (tolerant? p) (= :eof (current-type p)))
    (add-incomplete p {:type :missing-operand :start-pos (:pos op-token) :end-pos (:end-pos op-token)})
    {:node :partial-bin-op :left left :op-token op-token :token op-token}))

(defn- type-declaration
  [p]
  (let [base-tok (eat-identifier p)
        sub-types (loop [acc []]
                    (if (= :dot (current-type p))
                      (do (eat p :dot)
                          (recur (conj acc (identifier-node (eat-identifier p)))))
                      acc))]
    {:node :type-decl :base-type (identifier-node base-tok) :sub-types sub-types :token base-tok}))

(defn- statements-list
  [p]
  (let [tok (current p)
        statements (loop [acc []]
                     (if (contains? #{:eof :rblock} (current-type p))
                       acc
                       (let [acc (conj acc (statement p))]
                         (if (= :semicolon (current-type p))
                           (do (eat p :semicolon) (recur acc))
                           (if (contains? #{:eof :rblock} (current-type p))
                             acc
                             (recur acc))))))]
    (if (= 1 (count statements))
      (first statements)
      {:node :statement-list :statements statements :token tok})))

(defn- assign-variable
  [p]
  (let [tok (eat p :reserved-keyword)
        var-name (identifier-node (eat-identifier p))
        _ (eat p :colon)
        type-decl (type-declaration p)]
    (if (= :assign (current-type p))
      (do (eat p :assign)
          {:node :assign :var-name var-name :type-decl type-decl
           :assignment-expr (list-expr p) :token tok})
      {:node :assign :var-name var-name :type-decl type-decl :assignment-expr nil :token tok})))

(defn- reassign-variable
  ([p] (reassign-variable p nil))
  ([p name-tok]
   (let [name-tok (or name-tok (eat-identifier p))
         identifiers (loop [acc [(identifier-node name-tok)]]
                       (if (= :dot (current-type p))
                         (do (eat p :dot)
                             (recur (conj acc (identifier-node (eat-identifier p)))))
                         acc))]
     (eat p :assign)
     {:node :reassign :identifiers identifiers :value (list-expr p) :token name-tok})))

(defn- format-node
  [p node]
  (let [tok (eat p :format)]
    {:node :element-with-unit :ast-node node :unit (:value tok) :token tok}))

(defn- reference
  [p]
  (let [tok (current p)]
    (if (= :partial-reference (:type tok))
      (do (eat p :partial-reference)
          (add-incomplete p {:type :unclosed-reference :start-pos (:pos tok) :end-pos (:end-pos tok)
                             :partial-value (:value tok)})
          {:node :partial-reference :value (:value tok) :token tok})
      (let [node {:node :reference :value (:value tok) :token tok}]
        (eat p :reference)
        (add-reference p (:value tok))
        (if (= :format (current-type p))
          (format-node p node)
          node)))))

(defn- condition-expr
  [p]
  (when (= :string (current-type p))
    (when-let [nt (peek-token p)]
      (when (= :assign (:type nt))
        (err/raise :parser/condition-must-be-boolean {:token (current p)}))))
  (expr p))

(defn- return-statement
  [p]
  (let [tok (eat p :reserved-keyword)]
    {:node :return :expr (list-expr p) :token tok}))

(defn- while-statement
  [p]
  (let [tok (eat p :reserved-keyword)]
    (eat p :lparen)
    (let [condition (condition-expr p)]
      (eat p :rparen)
      {:node :while :condition condition :body (:statements (block p)) :token tok})))

(defn- expect-identifier-value
  [p expected]
  (when-not (identifier-token? p)
    (err/raise :parser/expected-token-type
               {:token (current p)
                :data {:expected expected :got (lex/token-type-name (current-type p))}}))
  (let [tok (eat p (current-type p))]
    (:value tok)))

(defn- for-statement
  [p]
  (let [tok (eat p :reserved-keyword)
        item-var (expect-identifier-value p "identifier")
        index-var (when (= :comma (current-type p))
                    (eat p :comma)
                    (expect-identifier-value p "identifier"))]
    (when-not (and (identifier-token? p) (= "in" (:value (current p))))
      (err/raise :parser/expected-token-type
                 {:token (current p)
                  :data {:expected "'in'" :got (str (:value (current p)))}}))
    (eat p (current-type p))
    (let [collection (expr p)
          body (:statements (block p))]
      (when (= :semicolon (current-type p))
        (eat p :semicolon))
      {:node :for-each :item-var item-var :index-var index-var
       :collection collection :body body :token tok})))

(defn- if-statement
  [p]
  (let [tok (eat p :reserved-keyword)
        condition-clause (fn []
                           (eat p :lparen)
                           (let [c (condition-expr p)]
                             (eat p :rparen)
                             {:node :if-condition :condition c :body (:statements (block p)) :token tok}))
        first-clause (condition-clause)
        conditions (loop [acc [first-clause]]
                     (let [cur (current p)]
                       (if (and (= :reserved-keyword (:type cur)) (= "elif" (:value cur)))
                         (do (eat p :reserved-keyword)
                             (recur (conj acc (condition-clause))))
                         acc)))
        else-body (let [cur (current p)]
                    (when (and (= :reserved-keyword (:type cur)) (= "else" (:value cur)))
                      (eat p :reserved-keyword)
                      (:statements (block p))))]
    {:node :if :conditions conditions :else-body else-body :token tok}))

(defn- block
  [p]
  (eat p :lblock)
  (let [statements (statements-list p)]
    (eat p :rblock)
    {:node :block :statements statements}))

(defn- explicit-list
  [p]
  (let [tok (eat p :lblock)
        elements (if (= :rblock (current-type p))
                   []
                   (loop [acc [(expr p)]]
                     (if (= :comma (current-type p))
                       (do (eat p :comma) (recur (conj acc (expr p))))
                       acc)))]
    (eat p :rblock)
    {:node :list :elements elements :token tok}))

(defn- implicit-list-expr
  [p]
  (let [tok (current p)
        elements (loop [acc [(expr p)]]
                   (if (contains? #{:comma :rparen :eof :semicolon} (current-type p))
                     acc
                     (recur (conj acc (expr p)))))]
    (if (= 1 (count elements))
      (first elements)
      {:node :implicit-list :elements elements :token tok})))

(defn- list-expr
  [p]
  (let [tok (current p)
        elements (loop [acc [(implicit-list-expr p)]]
                   (if (= :comma (current-type p))
                     (do (eat p :comma) (recur (conj acc (implicit-list-expr p))))
                     acc))]
    (if (= 1 (count elements))
      (first elements)
      {:node :list :elements elements :token tok})))

(defn- bin-op-loop
  "Left associative binary operator loop over `operand-fn` for tokens
  accepted by `op?`."
  [p operand-fn op?]
  (loop [node (operand-fn p)]
    (let [tok (current p)]
      (if (op? tok)
        (let [tok (eat p (:type tok))]
          (if-let [partial (try-recover-missing-operand p node tok)]
            partial
            (recur {:node :bin-op :left node :op-token tok :right (operand-fn p) :token tok})))
        node))))

(declare factor)

(defn- power
  [p]
  (bin-op-loop p factor #(and (= :operation (:type %)) (= "^" (:value %)))))

(defn- term
  [p]
  (bin-op-loop p power #(and (= :operation (:type %)) (contains? #{"*" "/"} (:value %)))))

(defn- comparison
  "Additive expression; binds tighter than the comparison operators."
  [p]
  (bin-op-loop p term #(and (= :operation (:type %)) (contains? #{"+" "-"} (:value %)))))

(defn- logic-term
  [p]
  (bin-op-loop p comparison #(contains? #{:is-eq :is-not-eq :is-gt :is-lt :is-gt-eq :is-lt-eq} (:type %))))

(defn- expr
  [p]
  (bin-op-loop p logic-term #(contains? #{:logic-and :logic-or} (:type %))))

(defn- function-call
  [p name-tok]
  (eat p :lparen)
  (loop [args []]
    (cond
      (= :rparen (current-type p))
      (do (eat p :rparen)
          {:node :function-call :name (:value name-tok) :args args :token name-tok})

      (and (tolerant? p) (= :eof (current-type p)))
      (do (add-incomplete p {:type :unclosed-function :start-pos (:pos name-tok)
                             :partial-value (:value name-tok)})
          {:node :partial-function-call :name (:value name-tok) :args args :token name-tok})

      :else
      (do (when (= :comma (current-type p)) (eat p :comma))
          (recur (conj args (implicit-list-expr p)))))))

(defn- attribute-access
  [p left]
  (loop [node left]
    (if (= :dot (current-type p))
      (let [dot-tok (eat p :dot)]
        (cond
          (and (tolerant? p) (= :eof (current-type p)))
          (do (add-incomplete p {:type :trailing-dot :start-pos (:pos dot-tok) :end-pos (:end-pos dot-tok)})
              node)

          (identifier-token? p)
          (let [nt (peek-token p)]
            (if (and nt (= :lparen (:type nt)))
              (let [method-tok (eat p (current-type p))
                    call (function-call p (assoc (current p) :value (:value method-tok)))]
                (recur {:node :attribute-access :left node :right call :token dot-tok}))
              (let [attr-tok (eat p (current-type p))]
                (recur {:node :attribute-access :left node :right (identifier-node attr-tok) :token dot-tok}))))

          :else
          (recur node)))
      node)))

(defn- number
  [p]
  (let [tok (eat p :number)
        node {:node :num :value (parse-double (:value tok)) :token tok}]
    (if (= :format (current-type p))
      (format-node p node)
      (attribute-access p node))))

(defn- template-string
  "Splits the raw template content into literal, `{ref}` and `${expr}`
  parts. Sub expressions are parsed with a fresh script mode parser."
  [p]
  (let [tok (eat p :template-string)
        raw (:value tok)
        n (count raw)
        parts (volatile! [])
        literal (volatile! "")
        flush-literal (fn []
                        (when (pos? (count @literal))
                          (vswap! parts conj {:node :string :value @literal :token (assoc tok :value @literal)})
                          (vreset! literal "")))
        ch (fn [i] (when (< i n) (subs raw i (inc i))))]
    (loop [i 0]
      (when (< i n)
        (let [c (ch i)
              nxt (ch (inc i))]
          (cond
            ;; escapes: \{ \` \\ \$ \${
            (and (= c "\\") (contains? #{"{" "`" "\\"} nxt))
            (do (vswap! literal str nxt) (recur (+ i 2)))

            (and (= c "\\") (= nxt "$"))
            (if (= "{" (ch (+ i 2)))
              (do (vswap! literal str "${") (recur (+ i 3)))
              (do (vswap! literal str "$") (recur (+ i 2))))

            ;; ${expr}
            (and (= c "$") (= nxt "{"))
            (let [start (+ i 2)
                  end (loop [j start depth 1]
                        (cond
                          (>= j n) nil
                          (= (ch j) "`")
                          (parse-error p :parser/invalid-syntax
                                       {:message "Nested template strings are not allowed inside ${...}"})
                          :else
                          (let [depth (case (ch j) "{" (inc depth) "}" (dec depth) depth)]
                            (if (zero? depth) j (recur (inc j) depth)))))]
              (when (nil? end)
                (parse-error p :parser/invalid-syntax {:message "Unterminated ${...} in template string"}))
              (flush-literal)
              (let [sub (make-parser (lex/make-lexer (subs raw start end)) nil)
                    node (expr sub)]
                (doseq [r (:references @sub)] (add-reference p r))
                (vswap! parts conj node))
              (recur (inc end)))

            ;; {ref}
            (= c "{")
            (let [start (inc i)
                  end (loop [j start]
                        (cond
                          (>= j n) nil
                          (= (ch j) "{")
                          (parse-error p :parser/invalid-syntax {:message "Nested braces in template reference"})
                          (= (ch j) "}") j
                          :else (recur (inc j))))]
              (when (nil? end)
                (parse-error p :parser/invalid-syntax {:message "Unterminated {ref} in template string"}))
              (flush-literal)
              (let [ref-path (str/replace (subs raw start end) #"[ \t]" "")]
                (when (= "" ref-path)
                  (parse-error p :parser/invalid-syntax {:message "Empty reference in template string"}))
                (add-reference p ref-path)
                (vswap! parts conj {:node :reference :value ref-path
                                    :token (assoc tok :type :reference :value ref-path)}))
              (recur (inc end)))

            :else
            (do (vswap! literal str c) (recur (inc i)))))))
    (flush-literal)
    (let [parts @parts]
      (if (and (= 1 (count parts)) (= :string (:node (first parts))))
        (first parts)
        {:node :template-string :parts parts :token tok}))))

(defn- factor
  [p]
  (let [tok (current p)
        type (:type tok)]
    (cond
      (= type :lblock)
      (attribute-access p (explicit-list p))

      (and (= type :operation) (contains? #{"+" "-" "!"} (:value tok)))
      (do (eat p :operation)
          (if (and (tolerant? p) (= :eof (current-type p)))
            (do (add-incomplete p {:type :missing-operand :start-pos (:pos tok) :end-pos (:end-pos tok)})
                {:node :partial-unary-op :op-token tok :token tok})
            {:node :unary-op :op-token tok :expr (factor p) :token tok}))

      (and (= type :reserved-keyword) (contains? #{"true" "false"} (:value tok)))
      (do (eat p :reserved-keyword)
          (attribute-access p {:node :boolean :value (= "true" (:value tok)) :token tok}))

      (and (= type :reserved-keyword) (= "null" (:value tok)))
      (do (eat p :reserved-keyword)
          (attribute-access p {:node :null :token tok}))

      (= type :number)
      (number p)

      (= type :lparen)
      (do (eat p :lparen)
          (if (and (tolerant? p) (= :eof (current-type p)))
            (do (add-incomplete p {:type :unclosed-paren :start-pos (:pos tok)})
                {:node :partial-paren :expr {:node :null :token tok} :token tok})
            (let [node (expr p)]
              (if (and (tolerant? p) (= :eof (current-type p)))
                (do (add-incomplete p {:type :unclosed-paren :start-pos (:pos tok)})
                    {:node :partial-paren :expr node :token tok})
                (do (eat p :rparen)
                    (if (= :format (current-type p))
                      (format-node p node)
                      (attribute-access p node)))))))

      (contains? #{:reference :partial-reference} type)
      (attribute-access p (reference p))

      (= type :hex-color)
      (do (eat p :hex-color)
          (attribute-access p {:node :hex-color :value (:value tok) :token tok}))

      (= type :partial-string)
      (do (eat p :partial-string)
          (let [text (:text (lex/source-info (:lexer @p)))
                quote (if (< (:pos tok) (count text)) (subs text (:pos tok) (inc (:pos tok))) "\"")]
            (add-incomplete p {:type :unclosed-string :start-pos (:pos tok) :end-pos (:end-pos tok)
                               :partial-value (:value tok)})
            {:node :partial-string :value (:value tok) :quote-type quote :token tok}))

      (identifier-token? p tok)
      (do (eat p type)
          (let [node (if (= :lparen (current-type p))
                       (function-call p tok)
                       (identifier-node tok))]
            (attribute-access p node)))

      (= type :template-string)
      (template-string p)

      (= type :explicit-string)
      (do (eat p :explicit-string)
          (attribute-access p {:node :string :value (:value tok) :token tok}))

      (and (tolerant? p) (= type :eof))
      {:node :null :token tok}

      :else
      (parse-error p :parser/unexpected-token {:token (str (:value tok))}))))

(defn- property-reassignment?
  "Looks ahead for `ident.prop = ...` style reassignments."
  [p]
  (when-let [toks (peek-tokens p 4)]
    (let [toks (vec toks)
          n (count toks)]
      (loop [i 0]
        (when (< i (dec n))
          (if (and (= :dot (:type (toks i)))
                   (identifier-token? p (toks (inc i)))
                   (< (+ i 2) n)
                   (= :assign (:type (toks (+ i 2)))))
            true
            (recur (+ i 2))))))))

(defn- statement
  [p]
  (let [tok (current p)]
    (cond
      (= :reserved-keyword (:type tok))
      (case (:value tok)
        "return" (return-statement p)
        "while" (while-statement p)
        "for" (for-statement p)
        "if" (if-statement p)
        "variable" (assign-variable p)
        (list-expr p))

      (identifier-token? p tok)
      (cond
        (property-reassignment? p)
        (let [name-tok (eat p (:type tok))]
          (reassign-variable p name-tok))

        (some-> (peek-token p) :type (= :assign))
        (reassign-variable p)

        :else
        (list-expr p))

      :else
      (list-expr p))))

;; Entry points ---------------------------------------------------------------

(defn- parse-with
  [p inline?]
  (when (and (tolerant? p) (not inline?))
    (parse-error p :parser/tolerant-requires-inline nil))
  (cond
    (= :eof (current-type p))
    nil

    inline?
    (do
      (let [tok (current p)]
        (when (and (= :reserved-keyword (:type tok))
                   (contains? lex/script-only-statement-keywords (:value tok)))
          (parse-error p :parser/unallowed-inline-syntax
                       {:original-error (str "Unexpected token: " (:value tok))})))
      (let [node (list-expr p)]
        (when (and (not (tolerant? p)) (not= :eof (current-type p)))
          (parse-error p :parser/unexpected-token {:token (str (:value (current p)))}))
        node))

    :else
    (let [node (statements-list p)]
      (when (not= :eof (current-type p))
        (parse-error p :parser/invalid-syntax {:message "Unexpected token at the end of input."}))
      node)))

(defn lexer-options
  "Lexer options for a parse mode, merged with `overrides`."
  ([mode] (lexer-options mode nil))
  ([mode overrides]
   (merge (if (= mode :inline) {:greedy-strings? true} {}) overrides)))

(defn parse
  "Parses `text`. Options:

    :mode      :script (default) or :inline
    :tolerant? recover from incomplete input (inline mode only)
    :lexer     extra lexer options

  Returns `{:ast node :references #{ref-path} :incomplete [info]}` where
  `:ast` is nil for empty input."
  ([text] (parse text nil))
  ([text {:keys [mode tolerant? lexer] :or {mode :script}}]
   (let [opts (lexer-options mode (cond-> lexer tolerant? (assoc :tolerant? true)))
         p (make-parser (lex/make-lexer text opts) {:tolerant? tolerant?})
         ast (parse-with p (= mode :inline))]
     {:ast ast
      :references (:references @p)
      :incomplete (:incomplete @p)})))

(defn parse-inline
  [text]
  (parse text {:mode :inline}))

(defn references
  "Reference paths required by the expression `text`."
  ([text] (references text nil))
  ([text opts] (:references (parse text opts))))
