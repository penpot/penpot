;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.interpreter
  "Tree walking evaluator for tokenscript ASTs.

    (interpret ast {:references {\"x\" 2} :config (config/make-config)})

  returns a symbol, nil (no value) or \"\" for an empty program. Errors
  are raised as `app.common.tokenscript.errors` exceptions.

  This namespace also executes the schema scripts registered in the
  config (color initializers and conversions, unit conversions, dynamic
  functions) since they need the evaluator."
  (:require
   [app.common.math :as mth]
   [app.common.tokenscript.colors :as colors]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.functions :as functions]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.operations :as ops]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.units :as units]
   [clojure.string :as str]))

(declare interpret)
(declare convert-to-common-format)
(declare color->type)

;; Context --------------------------------------------------------------------
;;
;; `refs` is a map (or any function) from reference path to value; `table`
;; is a volatile holding the script variables, keyed by lower case name.

(defrecord Context [config refs table])

(defn- make-ctx
  [{:keys [references config table]}]
  (->Context (or config (config/make-config))
             (or references {})
             (volatile! (or table {}))))

(defn- lookup-reference
  "Reference value as a symbol, nil when the path is not defined. A map
  entry holding nil or false is still a defined reference."
  [{:keys [refs]} path]
  (if (map? refs)
    (when-let [entry (find refs path)]
      (sym/data->symbol (val entry)))
    (when-some [v (refs path)]
      (sym/data->symbol v))))

(defn- lookup-var
  [{:keys [table]} name]
  (get @table (str/lower-case name)))

(defn- var-defined?
  [ctx name]
  (some? (lookup-var ctx name)))

(defn- set-var
  [{:keys [table]} name value]
  (vswap! table assoc (str/lower-case name) value))

(defn- delete-var
  [{:keys [table]} name]
  (vswap! table dissoc (str/lower-case name)))

;; Return signal ----------------------------------------------------------------
;;
;; `return` evaluates to a `Return` wrapper. Statement lists and loops stop
;; and pass it up unchanged, `if` and blocks pass it through, and the entry
;; points unwrap it. Throwing instead would capture a stack trace on every
;; return of every schema script.

(deftype Return [value])

(defn- return?
  [v]
  (instance? Return v))

(defn- unwrap-return
  [v]
  (if (return? v) (.-value ^Return v) v))

;; Runtime: schema script execution ---------------------------------------------

(defn- run-script
  "Interprets a registered script AST with the given references."
  [config ast references]
  (interpret ast {:references references :config config}))

(defn execute-initializer
  "Runs the color initializer registered for `keyword` with `args`."
  [config keyword args]
  (let [{:keys [ast]} (or (colors/initializer (:colors config) keyword)
                          (err/raise :color/initializer-not-found {:data {:keyword keyword}}))
        result (run-script config ast {"input" (sym/list args false)})]
    (if (sym/color? result)
      result
      (err/raise :color/initializer-crashed))))

(defn- run-color-conversion
  [config {:keys [ast target-uri]} color]
  (let [result (run-script config ast {"input" color})]
    (if (sym/color? result)
      result
      (let [target-spec (or (reg/spec (:colors config) target-uri)
                            (err/raise :color/conversion-target-not-found {:data {:target-uri target-uri}}))
            v (if (string? result) result (some-> result sym/value))]
        (sym/color v (:name target-spec) nil)))))

(defn- no-conversion-path
  [source-uri target-uri]
  (str "No conversion path found from '" source-uri "' to '" target-uri "'"))

(defn convert-color
  "Converts `color` to the color space registered under `target-uri`,
  chaining conversions when needed. Alpha is preserved."
  [config color target-uri]
  (let [registry (:colors config)
        source-uri (or (when-let [sub (:sub-type color)] (colors/uri-by-type registry sub))
                       (err/raise :color/source-uri-not-found {:data {:color-type (or (:sub-type color) "unknown")}}))
        steps (or (reg/conversion-steps registry source-uri target-uri)
                  (err/raise :color/conversion-error {:data {:error (no-conversion-path source-uri target-uri)}}))]
    (try
      (-> (reduce (fn [c step] (run-color-conversion config step c)) color steps)
          (assoc :alpha (:alpha color)))
      (catch #?(:clj Throwable :cljs :default) e
        (err/raise :color/conversion-error {:data {:error (err/describe e)}})))))

(defn color->type
  "Converts `color` to the color space named `target-type` (e.g. `hsl`).
  Returns the same color for identity conversions."
  [config color target-type]
  (if (= (some-> (:sub-type color) str/lower-case) (str/lower-case target-type))
    color
    (let [target-uri (or (colors/uri-by-type (:colors config) target-type)
                         (err/raise :color/target-uri-not-found {:data {:color-type target-type}}))]
      (convert-color config color target-uri))))

(defn- run-unit-conversion
  [config {:keys [ast]} unit]
  (let [result (run-script config ast {"input" unit})]
    (if (sym/number-with-unit? result)
      result
      (err/raise :unit/conversion-crashed
                 {:data {:error "Unit conversion function must return a NumberWithUnitSymbol"}}))))

(defn convert-unit
  "Converts a number with unit to the unit registered under `target-uri`."
  [config unit target-uri]
  (let [registry (:units config)
        source-uri (or (units/uri-by-keyword registry (:unit unit))
                       (err/raise :unit/source-uri-not-found {:data {:unit (:unit unit)}}))
        steps (or (reg/conversion-steps registry source-uri target-uri)
                  (err/raise :unit/conversion-crashed {:data {:error (no-conversion-path source-uri target-uri)}}))]
    (try
      (reduce (fn [u step] (run-unit-conversion config step u)) unit steps)
      (catch #?(:clj Throwable :cljs :default) e
        (err/raise :unit/conversion-crashed {:data {:error (err/describe e)}})))))

(defn- relative-unit?
  [config s]
  (and (sym/number-with-unit? s) (units/relative? (:units config) (:unit s))))

(defn- convert-relative
  "Resolves a relative operand (`50%`) against the other operand using
  the unit's `to_absolute` script."
  [config numbers]
  (when (not= 2 (count numbers))
    (err/raise :unit/relative-requires-two-numbers))
  (let [relatives (filter #(relative-unit? config %) numbers)]
    (if (not= 1 (count relatives))
      numbers
      (let [relative (first relatives)
            idx (if (identical? relative (first numbers)) 0 1)
            base (nth numbers (- 1 idx))
            spec (units/spec-by-keyword (:units config) (:unit relative))
            ast (get-in spec [:to_absolute :ast])]
        (when-not ast
          (err/raise :unit/no-to-absolute-script {:data {:unit (:unit relative)}}))
        (let [result (interpret ast {:references {"relative_value" (sym/number (:value relative))
                                                  "other_value" (sym/number (sym/value base))}})
              v (cond
                  (sym/number? result) (sym/value result)
                  (number? result) result
                  :else (err/raise :unit/to-absolute-must-return-number))
              converted (if (sym/number-with-unit? base)
                          (sym/number-with-unit v (:unit base))
                          (sym/number v))]
          (assoc (vec numbers) idx converted))))))

(defn convert-to-common-format
  "Converts a vector of numeric symbols to a shared unit. One relative
  operand is resolved against the other; otherwise the unit with the
  largest converted magnitude wins. Plain numbers pass through."
  [config inputs]
  (let [with-units (filter sym/number-with-unit? inputs)]
    (cond
      (empty? inputs) []
      (empty? with-units) (vec inputs)

      (= 1 (count (filter #(relative-unit? config %) inputs)))
      (do (when (> (count inputs) 2)
            (err/raise :unit/cannot-convert-multiple-relative))
          (convert-relative config inputs))

      :else
      (let [registry (:units config)
            candidates (for [unit (distinct (map #(str/upper-case (:unit %)) with-units))
                             :let [unit-uri (units/uri-by-keyword registry unit)]
                             :when unit-uri
                             :let [converted (reduce (fn [acc inp]
                                                       (if-not (sym/number-with-unit? inp)
                                                         (conj acc inp)
                                                         (let [input-uri (units/uri-by-keyword registry (:unit inp))]
                                                           (cond
                                                             (nil? input-uri) (reduced nil)
                                                             (= input-uri unit-uri) (conj acc inp)
                                                             :else (let [r (try (convert-unit config inp unit-uri)
                                                                                (catch #?(:clj Throwable :cljs :default) _ nil))]
                                                                     (if r (conj acc r) (reduced nil)))))))
                                                     []
                                                     inputs)]
                             :when (seq converted)]
                         [(reduce max 0.0 (map #(mth/abs (or (sym/value %) 0)) converted)) converted])]
        (if (empty? candidates)
          (err/raise :unit/no-valid-conversion-path)
          (second (reduce (fn [best c] (if (> (first c) (first best)) c best)) (first candidates) (rest candidates))))))))

(defn- sum-fn
  [config args]
  (when (< (count args) 2)
    (err/raise :functions/requires-min-arguments {:data {:function-name "sum" :min-args 2}}))
  (let [numeric (fn [a] (cond (sym/numeric? a) (sym/value a)
                              (number? (sym/value a)) (sym/value a)
                              :else (err/raise :functions/expects-number-arguments {:data {:function-name "sum"}})))]
    (if-not (some sym/number-with-unit? args)
      (sym/number (reduce + 0.0 (map numeric args)))
      (do
        (when-not (every? sym/numeric? args)
          (err/raise :functions/expects-number-arguments {:data {:function-name "sum"}}))
        (let [converted (try
                          (convert-to-common-format config (vec args))
                          (catch #?(:clj Throwable :cljs :default) e
                            (err/raise :functions/unit-conversion-failed
                                       {:data {:function-name "sum" :error (err/describe e)}})))
              total (reduce + 0.0 (map sym/value converted))]
          (if-let [unit-arg (first (filter sym/number-with-unit? converted))]
            (sym/number-with-unit total (:unit unit-arg))
            (sym/number total)))))))

(defn- call-dynamic-function
  [config {:keys [ast keyword]} args]
  (try
    (let [result (interpret ast {:references {"input" (sym/list args false)} :config config})]
      (cond
        (nil? result) (err/raise :functions/function-returned-null {:data {:function-name keyword}})
        (string? result) (sym/string result)
        :else result))
    (catch #?(:clj Throwable :cljs :default) e
      (err/raise :functions/execution-error {:data {:function-name keyword :error (err/describe e)}}))))

(defn resolve-function
  "Callable for `name`: dynamic functions first, then built ins.
  Returns a fn of the argument vector or nil."
  [config name]
  (let [lower (str/lower-case name)]
    (if-let [dynamic (functions/dynamic-function (:functions config) lower)]
      (fn [args] (call-dynamic-function config dynamic args))
      (if (= lower "sum")
        (fn [args] (sum-fn config args))
        (functions/builtin lower)))))

(defn has-function?
  [config name]
  (some? (resolve-function config name)))

;; Color attribute assignment -----------------------------------------------------

(defn- set-color-attribute
  "`c.alpha = 0.5` / `c.r = 255` on a color variable. Returns the updated
  color."
  [config color {:keys [identifiers token]} value]
  (let [[base & attributes] (map :name identifiers)
        identifier (str/join "." (cons base attributes))
        color-type (or (:sub-type color) "unknown")
        chain-data {:attributes (str/join "." attributes) :identifier identifier :color-type color-type}]
    (when (not= 1 (count attributes))
      (err/raise :color/attribute-chain-too-long {:token token :data chain-data}))
    (let [attr (first attributes)]
      (if (= attr "alpha")
        (do
          (when-not (contains? #{"Number" "Null"} (sym/type-name value))
            (err/raise :color/invalid-attribute-type
                       {:token token :data {:attribute-type (sym/type-name value) :valid-types "Number or Null"}}))
          (let [alpha (sym/value value)]
            (sym/ensure-valid-alpha alpha)
            (assoc color :alpha alpha)))
        (do
          (when (string? (:value color))
            (err/raise :color/string-value-assignment {:token token :data chain-data}))
          (let [spec (or (colors/spec-from-color (:colors config) color)
                         (err/raise :color/missing-spec
                                    {:token token :data {:identifier identifier :color-type color-type}}))
                schema (or (:schema spec)
                           (err/raise :color/missing-schema {:token token :data {:color-type color-type}}))
                attr-schema (or (get-in schema [:properties (keyword attr)])
                                (err/raise :color/missing-attribute-schema
                                           {:token token
                                            :data {:attribute attr :identifier identifier :color-type color-type}}))]
            (when-not (sym/type-equals? (:type attr-schema) (sym/type-name value))
              (err/raise :color/invalid-attribute-type
                         {:token token
                          :data {:attribute-type (sym/type-name value)
                                 :valid-types (str/join ", " colors/valid-schema-types)}}))
            (assoc color :value (assoc (or (:value color) {}) attr value))))))))

;; Evaluation ---------------------------------------------------------------------

(declare eval-node)

(defn- eval-bin-op
  [ctx {:keys [left right op-token] :as node}]
  (let [l (eval-node ctx left)
        r (eval-node ctx right)
        op (:value op-token)
        type (:type op-token)
        config (:config ctx)]
    (cond
      (ops/logical-operator? op)
      (ops/logical op l r)

      (ops/math-operator? op)
      (do
        (when-not (and (sym/numeric? l) (sym/numeric? r))
          (err/raise :interpreter/arithmetic-requires-number
                     {:token op-token
                      :data {:operator op :left-type (sym/type-name l) :right-type (sym/type-name r)}}))
        (ops/math op l r config #(convert-to-common-format config %)))

      (ops/comparison-operator? type)
      (ops/comparison type l r)

      :else
      (err/raise :interpreter/unknown-binary-operator
                 {:token op-token :data {:operator op :token-type (lex/token-type-name type)}}))))

(defn- validate-template-value
  [v node]
  (let [bad (cond
              (sym/list? v) "List"
              (sym/dictionary? v) "Dictionary"
              (and (sym/color? v) (not (sym/hex-color? v))) (str "Color." (:sub-type v))
              :else nil)]
    (when bad
      (err/raise :interpreter/template-invalid-type {:token (:token node) :data {:value-type bad}}))))

(defn- eval-template-string
  [ctx {:keys [parts] :as node}]
  (cond
    (empty? parts) (sym/string "")
    (= 1 (count parts)) (let [v (eval-node ctx (first parts))]
                          (validate-template-value v node)
                          v)
    :else (sym/string (apply str (map (fn [part]
                                        (let [v (eval-node ctx part)]
                                          (validate-template-value v node)
                                          (sym/to-string v (:config ctx))))
                                      parts)))))

(defn- eval-identifier
  "Bare identifiers resolve to variables, then references, then to
  themselves as strings."
  [ctx {:keys [name]}]
  (or (lookup-var ctx name)
      (lookup-reference ctx name)
      (sym/string name)))

(defn- eval-unary-op
  [ctx {:keys [op-token expr]}]
  (let [v (eval-node ctx expr)
        op (:value op-token)]
    (case op
      "-" (cond
            (sym/number? v) (if (nil? (:value v))
                              (err/raise :interpreter/unary-minus-null {:token op-token :data {:symbol-type "NumberSymbol"}})
                              (sym/number (- (:value v))))
            (sym/number-with-unit? v) (if (nil? (:value v))
                                        (err/raise :interpreter/unary-minus-null
                                                   {:token op-token :data {:symbol-type "NumberWithUnitSymbol"}})
                                        (sym/number-with-unit (- (:value v)) (:unit v)))
            :else (err/raise :interpreter/unary-plus-non-number
                             {:token op-token :data {:value (sym/to-string v (:config ctx))}}))
      "+" (if (sym/numeric? v)
            v
            (err/raise :interpreter/unary-plus-non-number
                       {:token op-token :data {:value (sym/to-string v (:config ctx))}}))
      "!" (if (sym/boolean? v)
            (sym/boolean (not (:value v)))
            (err/raise :interpreter/unary-not-non-boolean
                       {:token op-token :data {:value (sym/to-string v (:config ctx))}}))
      (err/raise :interpreter/unknown-unary-operator {:token op-token :data {:operator op}}))))

(defn- eval-reference
  [ctx {:keys [value token]}]
  (or (lookup-reference ctx value)
      (err/raise :interpreter/unknown-reference {:token token :data {:reference value}})))

(defn- eval-function-call
  [ctx {:keys [name args token]}]
  (let [config (:config ctx)
        fn-name (str/lower-case name)
        arg-values (mapv #(eval-node ctx %) args)]
    (if-let [f (resolve-function config fn-name)]
      (do (functions/validate-args (:functions config) fn-name arg-values token)
          (f arg-values))
      (cond
        (colors/has-initializer? (:colors config) fn-name)
        (execute-initializer config fn-name arg-values)

        (contains? lex/uninterpreted-keyword-set fn-name)
        (sym/string (str fn-name "(" (str/join ", " (map #(sym/to-string % config) arg-values)) ")"))

        :else
        (err/raise :interpreter/unknown-function {:token token :data {:function-name name}})))))

(defn- coerce-value
  [constructor value]
  (cond
    (sym/null? value) constructor
    (sym/symbol-type-equals? constructor value) value
    :else nil))

(defn- eval-assign
  [ctx {:keys [var-name type-decl assignment-expr]}]
  (let [name (:name var-name)
        token (:token var-name)
        config (:config ctx)]
    (when (some #(str/includes? name %) ["." "[" "-"])
      (err/raise :interpreter/invalid-variable-name {:token token :data {:name name}}))
    (when (var-defined? ctx name)
      (err/raise :interpreter/variable-already-defined {:token token :data {:name name}}))
    (let [base-type (:name (:base-type type-decl))
          sub-type (:name (first (:sub-types type-decl)))]
      (when-not (config/type-defined? config base-type sub-type)
        (err/raise :interpreter/invalid-variable-type
                   {:token token
                    :data {:type-name (sym/qualified-type-name base-type sub-type)
                           :valid-types (str/join ", " config/valid-type-names)}}))
      (let [constructor (config/get-type config base-type sub-type)
            value (if assignment-expr (eval-node ctx assignment-expr) constructor)
            coerced (coerce-value constructor value)]
        (when (nil? coerced)
          (err/raise :interpreter/invalid-value-for-variable
                     {:token token
                      :data {:value (sym/to-string value config) :type base-type :name name}}))
        (set-var ctx name coerced)
        nil))))

(defn- eval-reassign
  [ctx {:keys [identifiers value] :as node}]
  (let [base (first identifiers)
        existing (or (lookup-var ctx (:name base))
                     (err/raise :interpreter/variable-not-found {:token (:token base) :data {:name (:name base)}}))
        v (eval-node ctx value)
        identifier (str/join "." (map :name identifiers))]
    (when (nil? v)
      (err/raise :interpreter/assignment-value-null {:token (:token value) :data {:identifier identifier}}))
    (if (next identifiers)
      (when (sym/color? existing)
        (set-var ctx (:name base) (set-color-attribute (:config ctx) existing node v)))
      (do
        (when-not (or (sym/list? v) (sym/type-equals? (sym/type-name existing) (sym/type-name v)))
          (err/raise :interpreter/type-mismatch
                     {:token (:token base)
                      :data {:value (sym/to-string v (:config ctx))
                             :found-type (sym/type-name v)
                             :expected-type (sym/type-name existing)
                             :identifier identifier}}))
        (when-not (sym/valid-value? existing v)
          (err/raise :interpreter/cannot-assign-type
                     {:token (:token value)
                      :data {:value-type (sym/type-name v) :identifier identifier :variable-type (sym/type-name existing)}}))
        (set-var ctx (:name base) v)))
    nil))

(defn- color-method?
  [config left method-name]
  (and (sym/color? left) (colors/has-initializer? (:colors config) method-name)))

(defn- eval-attribute-access
  [ctx {:keys [left right token]}]
  (let [config (:config ctx)
        l (eval-node ctx left)]
    (when-not (sym/symbol? l)
      (err/raise :interpreter/cannot-access-attributes {:token token :data {:type (sym/result-type-name l)}}))
    (case (:node right)
      :function-call
      (let [method-name (:name right)
            args (mapv #(eval-node ctx %) (:args right))
            result (cond
                     (sym/has-method? l method-name args)
                     (sym/call-method l method-name args config)

                     (color-method? config l method-name)
                     (color->type config l method-name)

                     :else
                     (err/raise :interpreter/method-not-found
                                {:token token
                                 :data {:method-name method-name :value (sym/to-string l config) :type (sym/type-name l)}}))]
        (when (nil? result)
          (err/raise :interpreter/method-returned-null {:token token :data {:method-name method-name}}))
        result)

      :identifier
      (let [attr (:name right)]
        (if (sym/has-attribute? l attr)
          (let [result (sym/get-attribute l attr)]
            (when (nil? result)
              (err/raise :interpreter/attribute-returned-null {:token token :data {:attribute-name attr}}))
            result)
          (err/raise :interpreter/attribute-not-found
                     {:token token
                      :data {:attribute-name attr
                             :value (if (sym/color? l) "Color" (sym/to-string l config))
                             :type (sym/type-name l)}})))

      (err/raise :interpreter/cannot-access-attributes {:token token :data {:type (sym/type-name l)}}))))

(defn- eval-statement-list
  [ctx {:keys [statements]}]
  (reduce (fn [result statement]
            (let [v (eval-node ctx statement)]
              (cond
                (return? v) (reduced v)
                (some? v) v
                :else result)))
          nil
          statements))

(defn- eval-while
  [ctx {:keys [condition body token]}]
  (let [max-iterations (config/max-iterations (:config ctx))]
    (loop [iterations 1]
      (when (> iterations max-iterations)
        (err/raise :interpreter/max-iterations-exceeded {:token token}))
      (let [c (eval-node ctx condition)]
        (when-not (sym/boolean? c)
          (err/raise :interpreter/while-condition-not-boolean {:token (:token condition)}))
        (when (:value c)
          (let [r (eval-node ctx body)]
            (if (return? r)
              r
              (recur (inc iterations)))))))))

(defn- eval-for-each
  [ctx {:keys [item-var index-var collection body token]}]
  (when (var-defined? ctx item-var)
    (err/raise :interpreter/for-each-variable-shadow {:token token :data {:name item-var}}))
  (when (some? index-var)
    (when (= index-var item-var)
      (err/raise :interpreter/for-each-duplicate-vars {:token token :data {:name item-var}}))
    (when (var-defined? ctx index-var)
      (err/raise :interpreter/for-each-variable-shadow {:token token :data {:name index-var}})))
  (let [coll (eval-node ctx collection)]
    (when-not (sym/list? coll)
      (err/raise :interpreter/for-each-not-list
                 {:token token :data {:actual-type (if (some? coll) (sym/type-name coll) "null")}}))
    (let [results (try
                    (reduce (fn [acc [idx item]]
                              (set-var ctx item-var item)
                              (when index-var (set-var ctx index-var (sym/number (double idx))))
                              (let [r (eval-node ctx body)]
                                (if (return? r)
                                  (reduced r)
                                  (conj acc (or r (sym/null))))))
                            []
                            (map-indexed vector (sym/value coll)))
                    (finally
                      (delete-var ctx item-var)
                      (when index-var (delete-var ctx index-var))))]
      (if (return? results)
        results
        (sym/list results false)))))

(defn- eval-if
  [ctx {:keys [conditions else-body]}]
  (loop [conditions conditions]
    (if-let [{:keys [condition body]} (first conditions)]
      (let [c (eval-node ctx condition)]
        (when-not (sym/boolean? c)
          (err/raise :interpreter/if-condition-not-boolean {:token (:token condition)}))
        (if (:value c)
          (eval-node ctx body)
          (recur (rest conditions))))
      (when else-body
        (eval-node ctx else-body)))))

(defn- eval-node
  [ctx node]
  (when (some? node)
    (case (:node node)
      :bin-op (eval-bin-op ctx node)
      :num (sym/number (:value node))
      :string (sym/string (:value node))
      :template-string (eval-template-string ctx node)
      :identifier (eval-identifier ctx node)
      :unary-op (eval-unary-op ctx node)
      (:list :implicit-list) (sym/list (mapv #(eval-node ctx %) (:elements node)) (= :implicit-list (:node node)))
      :reference (eval-reference ctx node)
      :hex-color (let [{:keys [color alpha]} (sym/parse-hex-with-alpha (:value node))]
                   (sym/color color "Hex" alpha))
      :boolean (sym/boolean (:value node))
      :null (sym/null)
      :element-with-unit (let [v (eval-node ctx (:ast-node node))]
                           (sym/number-with-unit (some-> v sym/value) (:unit node)))
      :function-call (eval-function-call ctx node)
      :assign (eval-assign ctx node)
      :reassign (eval-reassign ctx node)
      :attribute-access (eval-attribute-access ctx node)
      :statement-list (eval-statement-list ctx node)
      :block (eval-node ctx (:statements node))
      :return (Return. (eval-node ctx (:expr node)))
      :while (eval-while ctx node)
      :for-each (eval-for-each ctx node)
      :if (eval-if ctx node)
      :no-op nil
      (err/raise :interpreter/unknown-node-type {:token (:token node) :data {:node-type (name (:node node))}}))))

;; Entry points ------------------------------------------------------------------

(defn interpret
  "Evaluates `ast`. Options:

    :references  map (or fn) from reference path to value or symbol
    :config      interpreter config (defaults to `config/make-config`)
    :table       initial variable table (lower case name -> symbol)

  Returns the result symbol, nil when the program produced no value, or
  \"\" for a nil AST."
  ([ast] (interpret ast nil))
  ([ast opts]
   (if (nil? ast)
     ""
     (case (:node ast)
       ;; Most token values are a single reference or literal: evaluate
       ;; them without building a context (config, variable table).
       :reference (eval-reference (->Context nil (or (:references opts) {}) nil) ast)
       (:num :string :hex-color :boolean :null) (eval-node nil ast)
       (unwrap-return (eval-node (make-ctx opts) ast))))))

(defn interpret-with-table
  "Like `interpret` but also returns the final variable table:
  `{:result .. :table {name symbol}}`."
  ([ast] (interpret-with-table ast nil))
  ([ast opts]
   (let [ctx (make-ctx opts)
         result (if (nil? ast)
                  ""
                  (unwrap-return (eval-node ctx ast)))]
     {:result result :table @(:table ctx)})))

(defn evaluate
  "Parses and evaluates `text`. Options are those of `interpret` plus
  `:mode` (`:script` default, or `:inline`)."
  ([text] (evaluate text nil))
  ([text {:keys [mode] :or {mode :script} :as opts}]
   (let [{:keys [ast]} (parser/parse text {:mode mode})]
     (interpret ast opts))))
