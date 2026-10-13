;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.token-data
  "Token input normalization and structured token helpers for the
  processor.

  Token data is `{:value any :type string-or-nil :metadata any}`. Values
  are strings (expressions), numbers, booleans, nil, or structured maps
  and vectors whose string leaves are expressions themselves."
  (:require
   [app.common.tokenscript.symbols :as sym]
   [clojure.string :as str]))

;; Normalization --------------------------------------------------------------

(defn- key-name
  [k]
  (if (keyword? k) (name k) (str k)))

(def ^:private field-keys
  "Keys a token data field may have in the input, in order of precedence:
  `:value`, `\"value\"`, `\"$value\"` and `:$value` (DTCG)."
  (into {}
        (map (fn [k] [k [(keyword k) k (str "$" k) (keyword (str "$" k))]]))
        ["value" "type" "metadata" "unit"]))

(defn- field
  "Value of the token data field `k` in `m`, see `field-keys`."
  [m k]
  (some-> (some #(find m %) (field-keys k)) val))

(defn token-data?
  [v]
  (and (map? v)
       (boolean (some #(contains? v %) (field-keys "value")))))

(defn- normalized?
  "Whether `v` is already token data as `normalize` returns it."
  [v]
  (and (map? v)
       (contains? v :value)
       (case (count v)
         1 true
         2 (string? (get v :type))
         false)))

(defn normalize
  "Coerces any accepted token input into token data."
  [v]
  (cond
    (normalized? v)
    v

    (token-data? v)
    (let [type (field v "type")
          metadata (field v "metadata")]
      (cond-> {:value (field v "value")}
        (some? type) (assoc :type (key-name type))
        (some? metadata) (assoc :metadata metadata)))

    :else
    {:value v}))

(defn normalize-tokens
  "Normalizes a flat map of token name to token input. Returns `tokens`
  itself when it is already normalized."
  [tokens]
  (if (every? string? (keys tokens))
    (reduce-kv (fn [acc k v]
                 (let [v' (normalize v)]
                   (if (identical? v v') acc (assoc acc k v'))))
               tokens
               tokens)
    (into {} (map (fn [[k v]] [(key-name k) (normalize v)])) tokens)))

(defn- nested?
  "A map is a nested token tree when it has a non `$` key with a map
  value."
  [m]
  (some (fn [[k v]] (and (map? v) (not (str/starts-with? (key-name k) "$")))) m))

(defn flatten-tree
  "Flattens a nested token tree (DTCG style) into token data keyed by
  dot separated paths. Leaves are maps with a `$value`/`value` key;
  `$`-prefixed keys are metadata and skipped."
  ([tree] (flatten-tree tree ""))
  ([tree prefix]
   (reduce-kv (fn [acc k v]
                (let [k (key-name k)]
                  (if (str/starts-with? k "$")
                    acc
                    (let [path (if (= prefix "") k (str prefix "." k))]
                      (cond
                        (and (map? v) (token-data? v)) (assoc acc path (normalize v))
                        (map? v) (merge acc (flatten-tree v path))
                        :else (assoc acc path {:value v}))))))
              {}
              tree)))

(defn ->flat-tokens
  "Accepts either a flat token map or a nested tree and returns normalized
  flat token data."
  [tokens]
  (if (and (map? tokens) (nested? tokens) (not (every? (fn [[_ v]] (token-data? v)) tokens)))
    (flatten-tree tokens)
    (normalize-tokens tokens)))

;; Object parsers -------------------------------------------------------------

(def number-with-unit-parser
  "Turns `{:value 8 :unit \"px\"}` maps into number with unit symbols."
  {:predicate (fn [v]
                (and (map? v)
                     (= 2 (count v))
                     (number? (field v "value"))
                     (string? (field v "unit"))))
   :->symbol (fn [v] (sym/number-with-unit (field v "value") (field v "unit")))})

(def default-object-parsers [])

(defn- matching-parser
  [parsers v]
  (some #(when ((:predicate %) v) %) parsers))

(defn value->symbol
  "Converts a structured leaf to a symbol, trying object parsers first."
  [v parsers]
  (cond
    (sym/symbol? v) v
    :else (if-let [parser (matching-parser parsers v)]
            ((:->symbol parser) v)
            (sym/data->symbol v))))

;; Structured tokens ----------------------------------------------------------

(defn string-fields
  "Expression leaves of a structured value as an ordered vector of
  `[field-path expression]`. Paths use `.key` and `[index]` segments, e.g.
  `shadow[0].blur`. Maps matched by an object parser are atomic."
  ([value parent-path] (string-fields value parent-path default-object-parsers))
  ([value parent-path parsers]
   (letfn [(walk-value [acc v path]
             (cond
               (string? v) (conj! acc [path v])
               (map? v) (if (matching-parser parsers v)
                          acc
                          (reduce (fn [acc [k child]] (walk-value acc child (str path "." (key-name k))))
                                  acc
                                  (seq v)))
               (sequential? v) (loop [acc acc i 0 items (seq v)]
                                 (if items
                                   (recur (walk-value acc (first items) (str path "[" i "]")) (inc i) (next items))
                                   acc))
               :else acc))]
     (persistent! (walk-value (transient []) value parent-path)))))

(defn parse-field-path
  "`\"[0].blur\"` -> `[0 \"blur\"]`, `\"offsetX\"` -> `[\"offsetX\"]`."
  [field-path]
  (loop [remaining field-path acc []]
    (if (str/blank? remaining)
      acc
      (if-let [[m idx] (re-find #"^\[(\d+)\]" remaining)]
        (recur (subs remaining (count m)) (conj acc (long #?(:clj (Long/parseLong idx) :cljs (js/parseInt idx 10)))))
        (if-let [[m prop] (re-find #"^\.?([^.\[\]]+)" remaining)]
          (recur (subs remaining (count m)) (conj acc prop))
          (if (str/starts-with? remaining ".")
            (recur (subs remaining 1) acc)
            acc))))))

(defn structured->symbol
  "Token symbol for a structured value with no expression leaves."
  ([value token-type] (structured->symbol value token-type nil default-object-parsers))
  ([value token-type metadata parsers]
   (cond
     (map? value) (sym/token token-type
                             (into (sym/value (sym/dictionary nil))
                                   (map (fn [[k v]] [(key-name k) (value->symbol v parsers)]))
                                   value)
                             metadata)
     (sequential? value) (sym/token token-type (mapv #(value->symbol % parsers) value) metadata)
     :else (sym/token token-type value metadata))))

(defn- build-value
  [path original resolved parsers]
  (cond
    (contains? resolved path) (get resolved path)
    (map? original) (if-let [parser (matching-parser parsers original)]
                      ((:->symbol parser) original)
                      (sym/dictionary (into (sym/value (sym/dictionary nil))
                                            (map (fn [[k v]] [(key-name k) (build-value (str path "." (key-name k)) v resolved parsers)]))
                                            original)))
    (sequential? original) (sym/list (vec (map-indexed (fn [i v] (build-value (str path "[" i "]") v resolved parsers)) original)) false)
    :else (value->symbol original parsers)))

(defn structured->symbol-with-fields
  "Token symbol for a structured value whose expression leaves were
  resolved to symbols (`resolved` maps field path to value)."
  [token-name resolved original token-type metadata parsers]
  (cond
    (map? original) (sym/token token-type
                               (into (sym/value (sym/dictionary nil))
                                     (map (fn [[k v]] [(key-name k) (build-value (str token-name "." (key-name k)) v resolved parsers)]))
                                     original)
                               metadata)
    (sequential? original) (sym/token token-type
                                      (vec (map-indexed (fn [i v] (build-value (str token-name "[" i "]") v resolved parsers)) original))
                                      metadata)
    :else (sym/token token-type original metadata)))

;; Names ----------------------------------------------------------------------

(def ^:private invalid-name-chars #"[ \t\n\r\f{}\[\]]")

(defn valid-token-name?
  "A name segment is valid when non empty and free of whitespace, braces
  and brackets."
  [name]
  (and (string? name) (pos? (count name)) (nil? (re-find invalid-name-chars name))))

;; Dot separated valid names, see `valid-token-name?`
(def ^:private valid-path-re
  #"[^ \t\n\r\f{}\[\].]+(?:\.[^ \t\n\r\f{}\[\].]+)*")

(defn valid-token-path?
  [path]
  (and (string? path) (some? (re-matches valid-path-re path))))
