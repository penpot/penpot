;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.operations
  "Binary operators: arithmetic with unit handling, comparisons and
  boolean logic.

  Arithmetic needs unit conversion (`1rem + 2px`), which runs scripts
  through the interpreter, so `math` takes the converter function as an
  argument instead of requiring the interpreter."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.units :as units]))

;; Arithmetic -----------------------------------------------------------------

(defn- decompose
  [operand]
  (cond
    (sym/number-with-unit? operand) [(:value operand) (:unit operand)]
    (sym/number? operand) [(:value operand) nil]
    :else (err/raise :operations/unsupported-operand-type {:data {:type (sym/type-name operand)}})))

(defn- recompose
  [v units]
  (let [valid (remove nil? units)]
    (cond
      (empty? valid) (sym/number v)
      (> (count (distinct valid)) 1) (err/raise :operations/cannot-mix-units {:data {:units (vec valid)}})
      :else (sym/number-with-unit v (first valid)))))

(defn- apply-math
  "Applies `f` to two numeric operands after trying to convert them to a
  common unit with `convert` (a fn of a vector of operands). Conversion
  failures fall back to the raw values."
  [f a b convert]
  (let [converted (when convert
                    (try
                      (let [c (convert [a b])]
                        (when (= 2 (count c)) c))
                      (catch #?(:clj Throwable :cljs :default) _ nil)))
        [a b] (or converted [a b])
        [va ua] (decompose a)
        [vb ub] (decompose b)]
    (recompose (f va vb) [ua ub])))

(defn- apply-power
  [f a b]
  (let [[va ua] (decompose a)
        [vb ub] (decompose b)]
    (when (and ua ub)
      (err/raise :operations/unit-exponent-not-supported {:data {:unit-a ua :unit-b ub}}))
    (recompose (f va vb) [ua ub])))

(defn- apply-multiplicative
  "Multiplication and division treat a single relative (percent) operand
  as a factor of the other operand."
  [f a b config convert]
  (let [relative? (fn [x] (and (sym/number-with-unit? x) (units/relative? (:units config) (:unit x))))
        a-rel? (relative? a)
        b-rel? (relative? b)]
    (cond
      (and a-rel? (not b-rel?))
      (let [v (f (/ (:value a) 100.0) (:value b))]
        (if (sym/number-with-unit? b) (sym/number-with-unit v (:unit b)) (sym/number v)))

      (and b-rel? (not a-rel?))
      (let [v (f (:value a) (/ (:value b) 100.0))]
        (if (sym/number-with-unit? a) (sym/number-with-unit v (:unit a)) (sym/number v)))

      :else
      (apply-math f a b convert))))

(defn- checked-divide
  [a b]
  (when (zero? b)
    (err/raise :operations/division-by-zero))
  (/ a b))

(defn math
  "Evaluates the arithmetic operator `op` (`+`, `-`, `*`, `/`, `^`) on two
  numeric symbols. `convert` converts a vector of operands to a common
  unit (may be nil)."
  [op a b config convert]
  (case op
    "+" (apply-math + a b convert)
    "-" (apply-math - a b convert)
    "*" (apply-multiplicative * a b config convert)
    "/" (apply-multiplicative checked-divide a b config convert)
    "^" (apply-power #(Math/pow (double %1) (double %2)) a b)
    nil))

(defn math-operator?
  [op]
  (contains? #{"+" "-" "*" "/" "^"} op))

;; Comparisons ----------------------------------------------------------------

(defn- raw-value
  [s]
  (if (or (sym/list? s) (sym/dictionary? s) (sym/token? s))
    (:value s)
    (sym/value s)))

(defn- values-equal?
  [a b]
  (let [va (raw-value a) vb (raw-value b)]
    (cond
      (or (volatile? va) (volatile? vb)) (identical? va vb)
      (and (map? va) (map? vb)) (identical? va vb)
      (and (number? va) (number? vb)) (== va vb)
      :else (= va vb))))

(defn- check-compatible
  [a b]
  (let [numeric-a (sym/numeric? a) numeric-b (sym/numeric? b)
        string-a (sym/string? a) string-b (sym/string? b)
        boolean-a (sym/boolean? a) boolean-b (sym/boolean? b)]
    (when (or (and numeric-a (not numeric-b))
              (and string-a (not string-b))
              (and boolean-a (not boolean-b)))
      (err/raise :operations/incompatible-types {:data {:type-a (sym/type-name a) :type-b (sym/type-name b)}}))
    (when (and (sym/number-with-unit? a) (sym/number-with-unit? b) (not= (:unit a) (:unit b)))
      (err/raise :operations/cannot-compare-units {:data {:unit-a (:unit a) :unit-b (:unit b)}}))))

(defn equality
  "`==` / `!=`."
  [op a b]
  (when-not (or (sym/null? a) (sym/null? b))
    (check-compatible a b))
  (let [eq (values-equal? a b)]
    (sym/boolean (if (= op :is-eq) eq (not eq)))))

(defn- order
  [va vb]
  (cond
    (and (number? va) (number? vb)) (compare va vb)
    (and (string? va) (string? vb)) (compare va vb)
    (and (boolean? va) (boolean? vb)) (compare (if va 1 0) (if vb 1 0))
    :else (compare (str va) (str vb))))

(defn ordering
  "`>`, `<`, `>=`, `<=`."
  [op a b]
  (when (or (sym/null? a) (sym/null? b) (nil? (sym/value a)) (nil? (sym/value b)))
    (err/raise :operations/ordering-with-null))
  (check-compatible a b)
  (let [c (order (sym/value a) (sym/value b))]
    (sym/boolean (case op
                   :is-gt (pos? c)
                   :is-lt (neg? c)
                   :is-gt-eq (>= c 0)
                   :is-lt-eq (<= c 0)))))

(defn comparison-operator?
  [token-type]
  (contains? #{:is-eq :is-not-eq :is-gt :is-lt :is-gt-eq :is-lt-eq} token-type))

(defn comparison
  [token-type a b]
  (case token-type
    (:is-eq :is-not-eq) (equality token-type a b)
    (ordering token-type a b)))

;; Logic ----------------------------------------------------------------------

(defn logical
  "`&&` / `||` on boolean symbols."
  [op a b]
  (when-not (and (sym/boolean? a) (sym/boolean? b))
    (err/raise :operations/logical-requires-boolean {:data {:operator op}}))
  (sym/boolean (case op
                 "&&" (and (:value a) (:value b))
                 "||" (or (:value a) (:value b)))))

(defn logical-operator?
  [op]
  (contains? #{"&&" "||"} op))
