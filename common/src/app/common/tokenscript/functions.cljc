;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.functions
  "Function registry: built in functions and schema defined (dynamic)
  functions.

  Built ins receive the evaluated argument symbols. Dynamic functions are
  tokenscript scripts registered from `:type \"function\"` schemas; their
  execution lives in the interpreter.

  `FunctionRegistry` fields:

    specs       {keyword spec}
    spec-types  {name keyword}
    dynamic     {keyword {:ast .. :spec .. :keyword ..}}"
  (:require
   [app.common.math :as mth]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.util :as u]
   [clojure.string :as str]
   [clojure.walk :as walk]))

;; Numeric helpers ------------------------------------------------------------

(defn- extract-number
  [arg function-name]
  (cond
    (nil? arg) (err/raise :functions/expects-number-arguments {:data {:function-name function-name}})
    (sym/numeric? arg) (sym/value arg)
    (number? (sym/value arg)) (sym/value arg)
    :else (err/raise :functions/expects-number-arguments {:data {:function-name function-name}})))

(defn- with-unit-of
  "Number symbol for `n`, with the unit of `arg` when it has one."
  [arg n]
  (if (sym/number-with-unit? arg)
    (sym/number-with-unit n (:unit arg))
    (sym/number n)))

(defn- out-of-range
  [function-name constraint]
  (err/raise :functions/argument-out-of-range {:data {:function-name function-name :constraint constraint}}))

(defn- expects-type
  [function-name expected position]
  (err/raise :functions/expects-type-argument
             {:data {:function-name function-name :expected-type expected :argument-position position}}))

(defn- check-divisor
  [function-name y]
  (when (zero? y)
    (err/raise :functions/division-by-zero {:data {:function-name function-name}})))

(defn- unary
  "Function of one number keeping its unit. Raises with `constraint` when
  `out-of-domain?` holds for the argument."
  ([name f] (unary name f nil nil))
  ([name f out-of-domain? constraint]
   (fn [[arg]]
     (let [v (extract-number arg name)]
       (when (and out-of-domain? (out-of-domain? v))
         (out-of-range name constraint))
       (with-unit-of arg (f v))))))

(defn- binary
  "Function of two numbers keeping the unit of the first."
  [name f]
  (fn [[a b]]
    (with-unit-of a (f (extract-number a name) (extract-number b name)))))

(defn- variadic
  "Function of the vector of one or more numbers keeping the first unit
  among them."
  [name f]
  (fn [args]
    (when (empty? args)
      (err/raise :functions/requires-min-arguments {:data {:function-name name :min-args 1}}))
    (let [result (f (mapv #(extract-number % name) args))]
      (with-unit-of (some #(when (sym/number-with-unit? %) %) args) result))))

(defn- log-fn
  "`log(x)` and `log(x, base)`."
  [[arg base]]
  (let [v (extract-number arg "log")]
    (when (<= v 0) (out-of-range "log" "positive"))
    (with-unit-of arg
      (if (nil? base)
        (Math/log v)
        (let [b (extract-number base "log")]
          (when (or (<= b 0) (== b 1))
            (err/raise :functions/invalid-base
                       {:data {:function-name "log" :constraint "positive and not equal to 1"}}))
          (/ (Math/log v) (Math/log b)))))))

(defn- round-to-fn
  [[arg precision]]
  (let [p (if (some? precision) (extract-number precision "round_to") 0)
        v (extract-number arg "round_to")]
    (with-unit-of arg
      (if (zero? p)
        (u/js-round v)
        (let [factor (mth/pow 10 p)]
          (/ (u/js-round (* v factor)) factor))))))

(def ^:private unary-functions
  "`[name f]` or `[name f out-of-domain? constraint]`, see `unary`."
  [["round" u/js-round]
   ["abs" mth/abs]
   ["sqrt" mth/sqrt]
   ["floor" mth/floor]
   ["ceil" mth/ceil]
   ;; Not mth/cubicroot: pow(x, 1/3) is inexact (27 -> 3.0000000000000004)
   ["cbrt" #(Math/cbrt %)]
   ["trunc" u/js-trunc]
   ["exp" #(Math/exp %)]
   ["expm1" #(Math/expm1 %)]
   ["sin" mth/sin]
   ["cos" mth/cos]
   ["tan" mth/tan]
   ["atan" #(Math/atan %)]
   ["asin" #(Math/asin %) #(not (<= -1 % 1)) "between -1 and 1"]
   ["acos" #(Math/acos %) #(not (<= -1 % 1)) "between -1 and 1"]
   ["sinh" #(Math/sinh %)]
   ["cosh" #(Math/cosh %)]
   ["tanh" #(Math/tanh %)]
   ["asinh" #(Math/log (+ % (Math/sqrt (+ (* % %) 1))))]
   ["acosh" #(Math/log (+ % (Math/sqrt (- (* % %) 1)))) #(< % 1) "greater than or equal to 1"]
   ["atanh" #(* 0.5 (Math/log (/ (+ 1 %) (- 1 %)))) #(or (<= % -1) (>= % 1)) "between -1 and 1 (exclusive)"]
   ["ln" #(Math/log %) #(<= % 0) "positive"]
   ["log10" #(Math/log10 %) #(<= % 0) "positive"]
   ["log2" #(/ (Math/log %) (Math/log 2)) #(<= % 0) "positive"]
   ["log1p" #(Math/log (+ 1 %)) #(< % -1) "greater than or equal to -1"]])

(def math-functions
  "Built in math functions keyed by lower case name. Each takes the
  vector of argument symbols."
  (into {"min" (variadic "min" #(reduce min %))
         "max" (variadic "max" #(reduce max %))
         "average" (variadic "average" #(/ (reduce + 0.0 %) (count %)))
         "hypot" (variadic "hypot" #(mth/sqrt (reduce + 0.0 (map (fn [x] (* x x)) %))))
         "mod" (binary "mod" (fn [x y] (check-divisor "mod" y) (rem (+ (rem x y) y) y)))
         "remainder" (binary "remainder" (fn [x y] (check-divisor "remainder" y) (rem x y)))
         "pow" (binary "pow" mth/pow)
         "round_to" round-to-fn
         "log" log-fn
         "sign" (fn [[arg]] (sym/number (u/js-sign (extract-number arg "sign"))))
         "atan2" (fn [[y x]] (sym/number (mth/atan2 (extract-number y "atan2") (extract-number x "atan2"))))
         "pi" (fn [_] (sym/number Math/PI))}
        (map (fn [[name & spec]] [name (apply unary name spec)]))
        unary-functions))

;; Other built ins -------------------------------------------------------------

(defn- parse-int-fn
  [[str-sym base-sym]]
  (when-not (sym/string? str-sym)
    (expects-type "parse_int" "string" "first"))
  (let [base (if (sym/number? base-sym) (sym/value base-sym) 10)
        parsed (u/parse-int (sym/value str-sym) base)]
    (when (nil? parsed)
      (err/raise :functions/parse-error
                 {:data {:function-name "parse_int" :value (str (sym/value str-sym)) :base base}}))
    (sym/number parsed)))

(defn- type-fn
  "Type name of a value. Units return the unit keyword and tokens the
  bare `token` type, matching the other runtimes."
  [[arg]]
  (cond
    (sym/number-with-unit? arg) (sym/string (:unit arg))
    (sym/token? arg) (sym/string "token")
    :else (sym/string (str/lower-case (sym/full-type-name arg)))))

(defn- type-predicate
  [type-name]
  (fn [[arg]] (sym/boolean (= type-name (sym/type-name arg)))))

(defn- range-fn
  [args]
  (when-not (<= 1 (count args) 2)
    (err/raise :functions/requires-min-arguments {:data {:function-name "range" :min-args 1}}))
  (let [[start end]
        (if (= 1 (count args))
          (let [[a] args]
            (when-not (sym/number? a) (expects-type "range" "Number" "first"))
            (let [v (sym/value a)]
              (when-not (and (u/integer-value? v) (<= 0 v 1000000))
                (out-of-range "range" (str "count must be a non-negative integer <= 1000000, got " (u/num->str v))))
              [0 v]))
          (let [[a b] args]
            (when-not (sym/number? a) (expects-type "range" "Number" "first"))
            (when-not (sym/number? b) (expects-type "range" "Number" "second"))
            (let [sv (sym/value a) ev (sym/value b)]
              (when-not (and (u/integer-value? sv) (u/integer-value? ev))
                (out-of-range "range" (str "arguments must be integers, got " (u/num->str sv) " and " (u/num->str ev))))
              (when (> (- ev sv) 1000000)
                (out-of-range "range" (str "size exceeds limit (1000000), got " (u/num->str (- ev sv)))))
              [sv ev])))]
    (if (< end start)
      (sym/list [] false)
      (sym/list (mapv #(sym/number (double %)) (range (long start) (long end))) false))))

(def builtin-functions
  "All built in functions without unit conversion needs."
  (merge math-functions
         {"parse_int" parse-int-fn
          "linear-gradient" (fn [args] (sym/string (str "linear-gradient(" (str/join ", " (map sym/to-string args)) ")")))
          "type" type-fn
          "is_null" (type-predicate "Null")
          "is_number" (type-predicate "Number")
          "is_string" (type-predicate "String")
          "is_boolean" (type-predicate "Boolean")
          "is_list" (type-predicate "List")
          "is_number_with_unit" (type-predicate "NumberWithUnit")
          "is_dictionary" (type-predicate "Dictionary")
          "is_token" (type-predicate "Token")
          "is_color" (type-predicate "Color")
          "range" range-fn}))

;; Registry -------------------------------------------------------------------

(defn- normalize-spec
  [uri spec]
  (let [spec (walk/keywordize-keys spec)]
    (when-not (and (map? spec)
                   (string? (:name spec))
                   (string? (:keyword spec))
                   (string? (get-in spec [:script :script])))
      (err/raise :config/invalid-specification
                 {:data {:kind "function" :uri uri :error "expected a map with :name, :keyword and :script"}}))
    spec))

(defn- register-spec
  "The keyword of the spec becomes the callable name."
  [registry uri spec]
  (let [spec (normalize-spec uri spec)
        keyword (u/name-key (:keyword spec))
        ast (:ast (parser/parse (get-in spec [:script :script])))]
    (-> registry
        (assoc-in [:specs keyword] spec)
        (assoc-in [:spec-types (u/name-key (:name spec))] keyword)
        (assoc-in [:dynamic keyword] {:ast ast :spec spec :keyword keyword}))))

(defrecord FunctionRegistry [specs spec-types dynamic]
  reg/ISchemaRegistry
  (register [this uri spec]
    (register-spec this uri spec)))

(defn empty-registry
  "Registry of schema functions. Specs are keyed by the `name-key` of
  their keyword; the builtins (`builtin-functions`) are not part of it."
  []
  (->FunctionRegistry {} {} {}))

(defn dynamic-function
  [registry name]
  (u/get-name (:dynamic registry) name))

(defn builtin
  [name]
  (u/get-name builtin-functions name))

(defn spec
  [registry name]
  (u/get-name (:specs registry) name))

(defn function-names
  [registry]
  (distinct (concat (keys (:dynamic registry)) (keys builtin-functions) ["sum"])))

(defn validate-args
  "Checks the argument count of a schema registered function against the
  required input properties of its spec."
  [registry name args token]
  (when-let [properties (get-in (spec registry name) [:input :properties])]
    (let [required (count (remove (fn [[_ prop]] (:optional prop)) properties))]
      (when (< (count args) required)
        (err/raise :functions/requires-min-arguments
                   {:token token :data {:function-name name :min-args required}})))))
