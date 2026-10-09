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

  Registry shape:

    {:specs {keyword spec} :spec-types {name keyword}
     :dynamic {keyword {:ast .. :spec ..}}}"
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
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

(defn- over
  "Applies `f` to the numeric value of `arg`, preserving its unit."
  [arg f function-name]
  (let [result (f (extract-number arg function-name))]
    (if (sym/number-with-unit? arg)
      (sym/number-with-unit result (:unit arg))
      (sym/number result))))

(defn- over-two
  [a b f function-name]
  (let [result (f (extract-number a function-name) (extract-number b function-name))]
    (if (sym/number-with-unit? a)
      (sym/number-with-unit result (:unit a))
      (sym/number result))))

(defn- over-many
  [args f function-name min-args]
  (when (and min-args (< (count args) min-args))
    (err/raise :functions/requires-min-arguments {:data {:function-name function-name :min-args min-args}}))
  (let [nums (mapv #(extract-number % function-name) args)
        result (f nums)
        unit-arg (first (filter sym/number-with-unit? args))]
    (if unit-arg
      (sym/number-with-unit result (:unit unit-arg))
      (sym/number result))))

(defn- out-of-range
  [function-name constraint]
  (err/raise :functions/argument-out-of-range {:data {:function-name function-name :constraint constraint}}))

(defn- expects-type
  [function-name expected position]
  (err/raise :functions/expects-type-argument
             {:data {:function-name function-name :expected-type expected :argument-position position}}))

(defn- hypot
  [nums]
  (Math/sqrt (reduce + 0.0 (map #(* % %) nums))))

(defn- first-arg
  [args]
  (first args))

(def math-functions
  "Built in math functions keyed by lower case name. Each takes the
  vector of argument symbols."
  {"min" (fn [args] (over-many args #(reduce min %) "min" 1))
   "max" (fn [args] (over-many args #(reduce max %) "max" 1))
   "mod" (fn [[a b]] (over-two a b (fn [x y]
                                     (when (zero? y)
                                       (err/raise :functions/division-by-zero {:data {:function-name "mod"}}))
                                     (rem (+ (rem x y) y) y))
                               "mod"))
   "average" (fn [args] (over-many args #(/ (reduce + 0.0 %) (count %)) "average" 1))
   "round" (fn [args] (over (first-arg args) u/js-round "round"))
   "abs" (fn [args] (over (first-arg args) #(Math/abs (double %)) "abs"))
   "sqrt" (fn [args] (over (first-arg args) #(Math/sqrt %) "sqrt"))
   "floor" (fn [args] (over (first-arg args) #(Math/floor %) "floor"))
   "ceil" (fn [args] (over (first-arg args) #(Math/ceil %) "ceil"))
   "cbrt" (fn [args] (over (first-arg args) #(Math/cbrt %) "cbrt"))
   "sign" (fn [args] (sym/number (u/js-sign (extract-number (first-arg args) "sign"))))
   "trunc" (fn [args] (over (first-arg args) u/js-trunc "trunc"))
   "exp" (fn [args] (over (first-arg args) #(Math/exp %) "exp"))
   "expm1" (fn [args] (over (first-arg args) #(Math/expm1 %) "expm1"))
   "pow" (fn [[a b]] (over-two a b u/pow "pow"))
   "round_to" (fn [[v precision]]
                (let [p (if (some? precision) (extract-number precision "round_to") 0)]
                  (over v
                        (if (zero? p)
                          u/js-round
                          (fn [x] (let [factor (u/pow 10 p)] (/ (u/js-round (* x factor)) factor))))
                        "round_to")))
   "hypot" (fn [args] (over-many args hypot "hypot" 1))
   "remainder" (fn [[a b]] (over-two a b (fn [x y]
                                           (when (zero? y)
                                             (err/raise :functions/division-by-zero {:data {:function-name "remainder"}}))
                                           (rem x y))
                                     "remainder"))
   "sin" (fn [args] (over (first-arg args) #(Math/sin %) "sin"))
   "cos" (fn [args] (over (first-arg args) #(Math/cos %) "cos"))
   "tan" (fn [args] (over (first-arg args) #(Math/tan %) "tan"))
   "atan" (fn [args] (over (first-arg args) #(Math/atan %) "atan"))
   "asin" (fn [args]
            (let [v (extract-number (first-arg args) "asin")]
              (when-not (<= -1 v 1) (out-of-range "asin" "between -1 and 1"))
              (over (first-arg args) #(Math/asin %) "asin")))
   "acos" (fn [args]
            (let [v (extract-number (first-arg args) "acos")]
              (when-not (<= -1 v 1) (out-of-range "acos" "between -1 and 1"))
              (over (first-arg args) #(Math/acos %) "acos")))
   "atan2" (fn [[y x]] (sym/number (Math/atan2 (extract-number y "atan2") (extract-number x "atan2"))))
   "sinh" (fn [args] (over (first-arg args) #(Math/sinh %) "sinh"))
   "cosh" (fn [args] (over (first-arg args) #(Math/cosh %) "cosh"))
   "tanh" (fn [args] (over (first-arg args) #(Math/tanh %) "tanh"))
   "asinh" (fn [args] (over (first-arg args) (fn [x] (Math/log (+ x (Math/sqrt (+ (* x x) 1))))) "asinh"))
   "acosh" (fn [args]
             (let [v (extract-number (first-arg args) "acosh")]
               (when (< v 1) (out-of-range "acosh" "greater than or equal to 1"))
               (over (first-arg args) (fn [x] (Math/log (+ x (Math/sqrt (- (* x x) 1))))) "acosh")))
   "atanh" (fn [args]
             (let [v (extract-number (first-arg args) "atanh")]
               (when (or (<= v -1) (>= v 1)) (out-of-range "atanh" "between -1 and 1 (exclusive)"))
               (over (first-arg args) (fn [x] (* 0.5 (Math/log (/ (+ 1 x) (- 1 x))))) "atanh")))
   "log" (fn [[arg base]]
           (let [v (extract-number arg "log")]
             (when (<= v 0) (out-of-range "log" "positive"))
             (if (nil? base)
               (over arg #(Math/log %) "log")
               (let [b (extract-number base "log")]
                 (when (or (<= b 0) (== b 1))
                   (err/raise :functions/invalid-base
                              {:data {:function-name "log" :constraint "positive and not equal to 1"}}))
                 (over arg (fn [x] (/ (Math/log x) (Math/log b))) "log")))))
   "ln" (fn [args]
          (let [v (extract-number (first-arg args) "ln")]
            (when (<= v 0) (out-of-range "ln" "positive"))
            (over (first-arg args) #(Math/log %) "ln")))
   "log10" (fn [args]
             (let [v (extract-number (first-arg args) "log10")]
               (when (<= v 0) (out-of-range "log10" "positive"))
               (over (first-arg args) #(Math/log10 %) "log10")))
   "log2" (fn [args]
            (let [v (extract-number (first-arg args) "log2")]
              (when (<= v 0) (out-of-range "log2" "positive"))
              (over (first-arg args) (fn [x] (/ (Math/log x) (Math/log 2))) "log2")))
   "log1p" (fn [args]
             (let [v (extract-number (first-arg args) "log1p")]
               (when (< v -1) (out-of-range "log1p" "greater than or equal to -1"))
               (over (first-arg args) (fn [x] (Math/log (+ 1 x))) "log1p")))
   "pi" (fn [_] (sym/number Math/PI))})

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

(def empty-registry
  {:specs {} :spec-types {} :dynamic {}})

(defn default-registry
  []
  empty-registry)

(defn- normalize-spec
  [name spec]
  (let [spec (walk/keywordize-keys spec)]
    (when-not (and (map? spec)
                   (string? (:name spec))
                   (string? (:keyword spec))
                   (string? (get-in spec [:script :script])))
      (err/raise :config/invalid-specification
                 {:data {:kind "function" :uri name :error "expected a map with :name, :keyword and :script"}}))
    spec))

(defn register
  "Registers a function specification. The keyword becomes the callable
  name."
  [registry name spec]
  (let [spec (normalize-spec name spec)
        keyword (str/lower-case (:keyword spec))
        ast (:ast (parser/parse (get-in spec [:script :script])))]
    (-> registry
        (assoc-in [:specs keyword] spec)
        (assoc-in [:spec-types (str/lower-case (:name spec))] keyword)
        (assoc-in [:dynamic keyword] {:ast ast :spec spec :keyword keyword}))))

(defn dynamic-function
  [registry name]
  (get (:dynamic registry) (str/lower-case name)))

(defn builtin
  [name]
  (get builtin-functions (str/lower-case name)))

(defn spec
  [registry name]
  (get (:specs registry) (str/lower-case name)))

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
