;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.util
  "Platform helpers for the tokenscript interpreter.

  Tokenscript semantics are specified against JavaScript numbers and
  strings (see the compliance suite), so this namespace provides
  JS-compatible number formatting, parsing and string splitting that
  behave the same on the JVM and in ClojureScript."
  (:require
   [clojure.string :as str])
  #?(:clj (:import
           java.math.BigDecimal
           java.math.RoundingMode)))

;; Characters -----------------------------------------------------------------

(defn code-point
  "Returns the UTF-16 code unit of the first char of `s` (a one char
  string) or nil."
  [s]
  (when (and (string? s) (pos? (count s)))
    #?(:clj (int (.charAt ^String s 0))
       :cljs (.charCodeAt s 0))))

(defn alpha?
  [c]
  (when-let [cp (code-point c)]
    (or (<= 65 cp 90) (<= 97 cp 122))))

(defn digit?
  [c]
  (when-let [cp (code-point c)]
    (<= 48 cp 57)))

(defn alphanumeric?
  [c]
  (or (alpha? c) (digit? c)))

(defn space?
  "Whether `c` is matched by the JavaScript `\\s` regex class: 9-13, 32,
  160, 5760, 8192-8202, 8232, 8233, 8239, 8287, 12288 and 65279."
  [c]
  (when-let [cp (code-point c)]
    (or (== cp 32)
        (<= 9 cp 13)
        (and (> cp 127)
             (or (== cp 160) (== cp 5760) (<= 8192 cp 8202) (== cp 8232) (== cp 8233)
                 (== cp 8239) (== cp 8287) (== cp 12288) (== cp 65279))))))

(defn capitalize
  "Upper-cases the first char and lower-cases the rest, like the TS
  `capitalize` helper (differs from `str/capitalize` for empty input)."
  [s]
  (if (str/blank? s)
    s
    (str (str/upper-case (subs s 0 1)) (str/lower-case (subs s 1)))))

(defn code-points
  "Splits a string into a vector of code point strings (what
  `Array.from(str)` does in JS)."
  [s]
  #?(:cljs (vec (js/Array.from s))
     :clj (let [^String s s
                n (.length s)]
            (loop [i 0 acc (transient [])]
              (if (>= i n)
                (persistent! acc)
                (let [cp (.codePointAt s i)
                      len (Character/charCount cp)]
                  (recur (+ i len) (conj! acc (.substring s i (+ i len))))))))))

(defn split
  "JS compatible `String.prototype.split` with a literal delimiter:
  keeps empty trailing parts and splits by UTF-16 units for the empty
  delimiter."
  [s delimiter]
  #?(:cljs (vec (.split s delimiter))
     :clj (if (= "" delimiter)
            (mapv str (seq s))
            (vec (str/split s (re-pattern (java.util.regex.Pattern/quote delimiter)) -1)))))

;; Numbers --------------------------------------------------------------------

(defn nan?
  [n]
  (and (number? n)
       #?(:clj (and (double? n) (Double/isNaN n))
          :cljs (js/isNaN n))))

(defn finite?
  [n]
  (and (number? n)
       #?(:clj (not (or (nan? n) (and (double? n) (Double/isInfinite n))))
          :cljs (js/isFinite n))))

(defn integer-value?
  "`Number.isInteger`"
  [n]
  (and (number? n) (finite? n) (== n (Math/floor n))))

#?(:clj
   (defn- digits->js-string
     "Formats the shortest decimal digit string `digits` with decimal
     point position `n` (value = 0.digits * 10^n) using the algorithm
     of `Number.prototype.toString`."
     [^String digits n]
     (let [k (count digits)]
       (cond
         (and (<= k n) (<= n 21))
         (str digits (apply str (repeat (- n k) "0")))

         (and (< 0 n) (<= n 21))
         (str (subs digits 0 n) "." (subs digits n))

         (and (< -6 n) (<= n 0))
         (str "0." (apply str (repeat (- n) "0")) digits)

         :else
         (let [e (dec n)
               sign (if (neg? e) "-" "+")]
           (if (= k 1)
             (str digits "e" sign (Math/abs e))
             (str (subs digits 0 1) "." (subs digits 1) "e" sign (Math/abs e))))))))

(defn num->str
  "Formats a number the same way `String(n)` does in JavaScript."
  [n]
  #?(:cljs (if (nil? n) "null" (str n))
     :clj (cond
            (nil? n) "null"
            (nan? n) "NaN"
            (and (double? n) (Double/isInfinite n)) (if (pos? n) "Infinity" "-Infinity")
            (zero? n) "0"
            (and (not (double? n)) (integer? n)) (str n)
            :else
            (let [d (double n)
                  neg? (neg? d)
                  a (Math/abs d)]
              (if (and (integer-value? a) (< a 1e21))
                (str (when neg? "-") (.toBigInteger (BigDecimal. a)))
                (let [s (Double/toString a)
                      [mantissa e] (str/split s #"E")
                      e (if e (Long/parseLong e) 0)
                      [ip fp] (str/split mantissa #"\." 2)
                      fp (or fp "")
                      raw (str ip fp)
                      stripped (str/replace raw #"^0+" "")
                      z (- (count raw) (count stripped))
                      digits (str/replace stripped #"0+$" "")
                      n (+ (- (count ip) z) e)]
                  (str (when neg? "-") (digits->js-string digits n))))))))

(defn to-fixed
  "JS `Number.prototype.toFixed`."
  [n places]
  #?(:cljs (.toFixed n places)
     :clj (let [bd (.setScale (BigDecimal. (double n)) (int places) RoundingMode/HALF_UP)]
            (.toPlainString bd))))

(defn parse-float
  "Lenient `parseFloat`: returns nil for non numeric strings."
  [s]
  (when (string? s)
    #?(:cljs (let [v (js/parseFloat s)] (when-not (js/isNaN v) v))
       :clj (when-let [m (re-find #"^\s*[+-]?(\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?|Infinity)" s)]
              (let [v (str/trim (first m))]
                (if (str/ends-with? v "Infinity")
                  (if (str/starts-with? v "-") Double/NEGATIVE_INFINITY Double/POSITIVE_INFINITY)
                  (Double/parseDouble v)))))))

(defn- digit-value
  [c]
  (let [cp (code-point c)]
    (cond
      (<= 48 cp 57) (- cp 48)
      (<= 97 cp 122) (+ 10 (- cp 97))
      (<= 65 cp 90) (+ 10 (- cp 65))
      :else 99)))

(defn parse-int
  "JS `parseInt(str, radix)`: trims, accepts a sign and a `0x` prefix
  for radix 16, parses the longest valid prefix. Returns nil instead of
  NaN."
  [s radix]
  (when (string? s)
    (let [s (str/trim s)
          [sign s] (cond
                     (str/starts-with? s "-") [-1 (subs s 1)]
                     (str/starts-with? s "+") [1 (subs s 1)]
                     :else [1 s])
          radix (long (or radix 10))
          [radix s] (if (and (= radix 16) (re-find #"^0[xX]" s))
                      [16 (subs s 2)]
                      [radix s])
          digits (take-while #(< (digit-value %) radix) (map str (seq s)))]
      (when (seq digits)
        (* sign (double (reduce (fn [acc c] (+ (* acc radix) (digit-value c))) 0 digits)))))))

(defn radix-str
  "`Number.prototype.toString(radix)` for integral values."
  [n radix]
  #?(:cljs (.toString n radix)
     :clj (Long/toString (long n) (int radix))))

(defn js-round
  "JS `Math.round`: rounds half towards positive infinity."
  [n]
  (double (Math/floor (+ (double n) 0.5))))

(defn js-sign
  [n]
  (cond (pos? n) 1.0 (neg? n) -1.0 :else 0.0))

(defn js-trunc
  [n]
  (if (neg? n) (Math/ceil n) (Math/floor n)))

(defn pow
  [a b]
  (Math/pow (double a) (double b)))

(defn typeof
  "Approximation of the JS `typeof` operator, used in error data."
  [v]
  (cond
    (nil? v) "undefined"
    (string? v) "string"
    (number? v) "number"
    (boolean? v) "boolean"
    (fn? v) "function"
    :else "object"))
