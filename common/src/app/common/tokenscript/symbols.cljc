;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.symbols
  "Runtime values of the tokenscript language.

  Every value the interpreter manipulates is a symbol record:

    NullSymbol, NumberSymbol, StringSymbol, BooleanSymbol,
    NumberWithUnitSymbol, ColorSymbol            (immutable)
    ListSymbol, DictionarySymbol, TokenSymbol    (mutable containers)

  The language has in-place mutation for containers (`list.append(x)`
  mutates the list bound to the variable), so container records keep
  their content in a volatile. Use `value` to read the content of any
  symbol regardless of kind, and `->data` to convert a symbol tree to
  plain Clojure data at the API boundary.

  Methods callable from scripts are declared in method tables
  (`method-table`) and invoked through `call-method`. Functions taking a
  `config` need it only for color formatting/conversion lookups."
  (:refer-clojure :exclude [list boolean number symbol? number? string? boolean? list?])
  (:require
   [app.common.math :as mth]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.util :as u]
   [clojure.string :as str]
   [linked.map :as lkm]))

;; Records --------------------------------------------------------------------

(defrecord NullSymbol [])
(defrecord NumberSymbol [value])
(defrecord StringSymbol [value])
(defrecord BooleanSymbol [value])
(defrecord NumberWithUnitSymbol [value unit])
(defrecord ColorSymbol [value sub-type alpha])
(defrecord ListSymbol [value implicit?])
(defrecord DictionarySymbol [value])
(defrecord TokenSymbol [sub-type value metadata])

(defn symbol?
  [v]
  (or (instance? NullSymbol v)
      (instance? NumberSymbol v)
      (instance? StringSymbol v)
      (instance? BooleanSymbol v)
      (instance? NumberWithUnitSymbol v)
      (instance? ColorSymbol v)
      (instance? ListSymbol v)
      (instance? DictionarySymbol v)
      (instance? TokenSymbol v)))

(defn null? [v] (instance? NullSymbol v))
(defn number? [v] (instance? NumberSymbol v))
(defn string? [v] (instance? StringSymbol v))
(defn boolean? [v] (instance? BooleanSymbol v))
(defn number-with-unit? [v] (instance? NumberWithUnitSymbol v))
(defn color? [v] (instance? ColorSymbol v))
(defn list? [v] (instance? ListSymbol v))
(defn dictionary? [v] (instance? DictionarySymbol v))
(defn token? [v] (instance? TokenSymbol v))

(defn numeric?
  "Number or NumberWithUnit symbol."
  [v]
  (or (number? v) (number-with-unit? v)))

(defn value
  "Content of a symbol: the primitive for scalar symbols, the current
  vector/map for containers."
  [s]
  (let [v (:value s)]
    (if (volatile? v) @v v)))

(defn type-name
  "Base type name (`Number`, `List`, ...)."
  [s]
  (cond
    (null? s) "Null"
    (number? s) "Number"
    (string? s) "String"
    (boolean? s) "Boolean"
    (number-with-unit? s) "NumberWithUnit"
    (color? s) "Color"
    (list? s) "List"
    (dictionary? s) "Dictionary"
    (token? s) "Token"
    :else "Unknown"))

(defn type-equals?
  "Case insensitive type name comparison."
  [a b]
  (= (some-> a str/lower-case) (some-> b str/lower-case)))

(defn qualified-type-name
  "`Color.Hex` for base `color` and sub type `hex`."
  ([base] (u/capitalize base))
  ([base sub]
   (if (and sub (not= sub ""))
     (str (u/capitalize base) "." (u/capitalize sub))
     (u/capitalize base))))

;; Hex colors -----------------------------------------------------------------

;; Longest alternative first: JavaScript takes the first alternative that
;; matches and `re-matches` then rejects the partial match.
(def ^:private hex-color-re
  #"#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})")

(defn valid-hex?
  "`#` followed by 3, 4, 6 or 8 hex digits."
  [v]
  (and (clojure.core/string? v)
       (some? (re-matches hex-color-re v))))

(defn parse-hex-with-alpha
  "Splits a hex color into `{:color \"#rrggbb\" :alpha 0.5}`. Three and six
  digit colors are kept as is with a nil alpha."
  [v]
  (when-not (valid-hex? v)
    (err/raise :symbols/invalid-hex-color {:data {:value v}}))
  (let [hex (subs v 1)
        hex->alpha (fn [s] (/ (double (u/parse-int s 16)) 255.0))]
    (case (count hex)
      3 {:color v :alpha nil}
      6 {:color v :alpha nil}
      4 (let [[r g b a] (map str hex)]
          {:color (str "#" r r g g b b) :alpha (hex->alpha (str a a))})
      8 {:color (str "#" (subs hex 0 6)) :alpha (hex->alpha (subs hex 6 8))})))

(defn valid-alpha?
  [alpha]
  (or (nil? alpha) (<= 0 alpha 1)))

(defn ensure-valid-alpha
  [alpha]
  (when-not (valid-alpha? alpha)
    (err/raise :color/invalid-alpha-value {:data {:alpha alpha}})))

(defn transparent?
  [alpha]
  (and (some? alpha) (< alpha 1)))

(defn format-hex8
  "`#rrggbbaa` for a hex color and an alpha, expanding `#rgb` first."
  [hex alpha]
  (let [alpha-hex (let [s (u/radix-str (u/js-round (* alpha 255)) 16)]
                    (if (< (count s) 2) (str "0" s) s))
        hex6 (if (= 4 (count hex))
               (let [[_ r g b] (map str hex)] (str "#" r r g g b b))
               hex)]
    (str hex6 alpha-hex)))

;; Constructors ---------------------------------------------------------------

(defn null
  []
  (->NullSymbol))

(defn number
  "Number symbol from a number, another numeric symbol or nil."
  [v]
  (cond
    (clojure.core/number? v) (->NumberSymbol (double v))
    (numeric? v) (->NumberSymbol (value v))
    (nil? v) (->NumberSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "int or float" :actual-type (u/typeof v)}})))

(defn string
  [v]
  (cond
    (clojure.core/string? v) (->StringSymbol v)
    (string? v) (->StringSymbol (:value v))
    (nil? v) (->StringSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "string" :actual-type (u/typeof v)}})))

(defn boolean
  [v]
  (cond
    (clojure.core/boolean? v) (->BooleanSymbol v)
    (boolean? v) (->BooleanSymbol (:value v))
    (nil? v) (->BooleanSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "boolean" :actual-type (u/typeof v)}})))

(defn number-with-unit
  [v unit]
  (let [safe (cond
               (clojure.core/number? v) (double v)
               (number? v) (:value v)
               (nil? v) nil
               :else (err/raise :symbols/value-must-be-type
                                {:data {:expected-type "number or NumberSymbol" :actual-type (u/typeof v)}}))]
    (when-not (contains? lex/supported-format-set unit)
      (err/raise :symbols/attribute-not-found {:data {:attribute-name unit :type "Unit"}}))
    (->NumberWithUnitSymbol safe unit)))

(defn color
  "Color symbol. `v` is a hex string (sub type `Hex`), a map of channel
  name to symbol (dynamic color with a sub type), or nil."
  ([v] (color v nil nil))
  ([v sub-type] (color v sub-type nil))
  ([v sub-type alpha]
   (let [hex? (and (or (nil? sub-type) (= "hex" (str/lower-case sub-type)))
                   (clojure.core/string? v))
         dynamic? (and (clojure.core/string? sub-type) (map? v))]
     (when-not (or (nil? v) hex? dynamic?)
       (err/raise :symbols/invalid-color-value {:data {:value (str v) :type (u/typeof v)}}))
     (when (and hex? (not (valid-hex? v)))
       (err/raise :symbols/invalid-hex-color {:data {:value (str v)}}))
     (ensure-valid-alpha alpha)
     (->ColorSymbol v (if hex? "Hex" sub-type) alpha))))

(defn list
  "List symbol from a sequence of symbols."
  ([elements] (list elements false))
  ([elements implicit?]
   (->ListSymbol (volatile! (vec elements)) (clojure.core/boolean implicit?))))

(defn- ordered-map
  [m]
  (if (nil? m)
    lkm/empty-linked-map
    (into lkm/empty-linked-map m)))

(defn dictionary
  "Dictionary symbol from a map of string keys to symbols. Entries keep
  insertion order."
  [m]
  (cond
    (dictionary? m) (->DictionarySymbol (volatile! (value m)))
    (or (nil? m) (map? m)) (->DictionarySymbol (volatile! (ordered-map m)))
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "dict" :actual-type (u/typeof m)}})))

(defn token
  "Token symbol: a typed record (map) or list of symbols."
  ([sub-type v] (token sub-type v nil))
  ([sub-type v metadata]
   (let [safe (cond
                (token? v) (value v)
                (map? v) (ordered-map v)
                (sequential? v) (vec v)
                (nil? v) lkm/empty-linked-map
                :else (err/raise :symbols/value-must-be-type
                                 {:data {:expected-type "Record or List" :actual-type (u/typeof v)}}))]
     (->TokenSymbol sub-type (volatile! safe) metadata))))

(def empty-constructors
  "Lower case type name to a constructor of the empty (null) symbol."
  {"number" #(number nil)
   "string" #(string nil)
   "boolean" #(boolean nil)
   "numberwithunit" #(number-with-unit nil "px")
   "color" #(color nil)
   "null" null
   "list" #(list nil)
   "dictionary" #(dictionary nil)
   "token" #(token "unknown" nil)})

(def nullable-type-names
  #{"number" "string" "boolean" "numberwithunit" "color" "null"})

(defn nullable?
  [s]
  (contains? nullable-type-names (str/lower-case (type-name s))))

(defn null-value?
  "True for nullable symbols holding a nil value (and the null symbol)."
  [s]
  (or (null? s) (and (nullable? s) (nil? (value s)))))

(defn empty-of-type
  [type-name]
  (when-let [ctor (get empty-constructors (str/lower-case type-name))]
    (ctor)))

;; Copying --------------------------------------------------------------------

(declare deep-copy)

(defn clone-if-mutable
  "Returns `s` for immutable symbols and a deep copy for containers.
  Containers store copies of inserted values to avoid shared references."
  [s]
  (if (or (list? s) (dictionary? s) (token? s))
    (deep-copy s)
    s))

(defn deep-copy
  [s]
  (cond
    (list? s) (->ListSymbol (volatile! (mapv deep-copy (value s))) (:implicit? s))
    (dictionary? s) (->DictionarySymbol (volatile! (into lkm/empty-linked-map (map (fn [[k v]] [k (deep-copy v)])) (value s))))
    (token? s) (let [v (value s)]
                 (->TokenSymbol (:sub-type s)
                                (volatile! (if (map? v)
                                             (into lkm/empty-linked-map (map (fn [[k v]] [k (deep-copy v)])) v)
                                             (mapv deep-copy v)))
                                (:metadata s)))
    (color? s) (if (map? (:value s))
                 (->ColorSymbol (into {} (map (fn [[k v]] [k (deep-copy v)])) (:value s)) (:sub-type s) (:alpha s))
                 s)
    :else s))

;; Type checks ----------------------------------------------------------------

(defn hex-color?
  [c]
  (and (color? c) (type-equals? (:sub-type c) "hex")))

(defn symbol-type-equals?
  "Type compatibility used for variable assignment. Colors without a sub
  type are compatible with hex colors."
  [a b]
  (cond
    (and (color? a) (color? b))
    (or (and (nil? (:sub-type a)) (hex-color? b))
        (and (hex-color? a) (nil? (:sub-type b)))
        (type-equals? (:sub-type a) (:sub-type b)))

    :else
    (type-equals? (type-name a) (type-name b))))

(defn valid-value?
  "Whether `v` is an acceptable value for a variable holding `s`."
  [s v]
  (cond
    (null? s) (nil? v)
    (number? s) (or (clojure.core/number? v) (number? v))
    (string? s) (or (clojure.core/string? v) (string? v))
    (boolean? s) (or (clojure.core/boolean? v) (boolean? v))
    (number-with-unit? s) (number-with-unit? v)
    (list? s) (or (vector? v) (list? v))
    (dictionary? s) (or (map? v) (nil? v))
    (token? s) (or (map? v) (vector? v) (nil? v))
    (color? s) (or (color? v) (nil? v) (map? v) (valid-hex? v))
    :else false))

(defn full-type-name
  "Qualified type name including the sub type: `List.Implicit`,
  `Color.Hex`, `NumberWithUnit.Px`, `Token.Shadow`."
  [s]
  (cond
    (list? s) (if (:implicit? s) (qualified-type-name "List" "Implicit") "List")
    (number-with-unit? s) (str "NumberWithUnit." (u/capitalize (:unit s)))
    (color? s) (qualified-type-name "Color" (:sub-type s))
    (token? s) (qualified-type-name "Token" (:sub-type s))
    :else (type-name s)))

;; Equality -------------------------------------------------------------------

(defn equals?
  "Value equality as defined by the language (`==`). Containers compare
  by identity, like the other implementations."
  [a b]
  (cond
    (null? a) (null? b)
    (number-with-unit? a) (and (number-with-unit? b)
                               (= (:value a) (:value b))
                               (= (str/lower-case (:unit a)) (str/lower-case (:unit b))))
    (color? a) (and (color? b)
                    (symbol-type-equals? a b)
                    (= (:alpha a) (:alpha b))
                    (if (and (map? (:value a)) (map? (:value b)))
                      (and (= (count (:value a)) (count (:value b)))
                           (every? (fn [[k v]] (and (contains? (:value b) k) (equals? v (get (:value b) k))))
                                   (:value a)))
                      (= (:value a) (:value b))))
    (or (list? a) (dictionary? a) (token? a))
    (and (symbol-type-equals? a b) (identical? (:value a) (:value b)))
    :else (and (symbol? b) (symbol-type-equals? a b) (= (:value a) (:value b)))))

;; Color formatting -----------------------------------------------------------

(defn color-spec
  "Spec registered for the color sub type in `config`, if any."
  [config c]
  (when-let [sub (:sub-type c)]
    (let [registry (:colors config)
          uri (get (:spec-types registry) (str/lower-case sub))]
      (get (:specs registry) uri))))

(defn- format-number
  [n places remove-trailing-zeros?]
  (let [rounded (u/to-fixed (u/parse-float (u/to-fixed n places)) places)]
    (if remove-trailing-zeros?
      (str/replace rounded #"\.?0+$" "")
      rounded)))

(declare to-string)

(defn- format-entries
  [entries config]
  (str "{" (str/join ", " (map (fn [[k v]] (str k ": " (to-string v config))) entries)) "}"))

(defn format-color
  "Formats a color like the color manager does: hex colors as `#rrggbb`
  (`#rrggbbaa` with alpha), dynamic colors as `hsl(h, s, l[, a])` using
  the channel order of the registered spec. Returns \"\" when no spec is
  registered for a dynamic color."
  ([config c] (format-color config c nil))
  ([config c {:keys [decimal-places remove-trailing-zeros?]
              :or {decimal-places 2 remove-trailing-zeros? true}}]
   (let [v (:value c)]
     (cond
       (clojure.core/string? v)
       (if (some? (:alpha c)) (format-hex8 v (:alpha c)) v)

       (and (map? v) (:sub-type c))
       (if-let [spec (color-spec config c)]
         (let [order (let [o (get-in spec [:schema :order])]
                       (if (seq o) o (keys v)))
               values (map (fn [k]
                             (let [attr (get v k)
                                   av (when attr (value attr))]
                               (if (clojure.core/number? av)
                                 (format-number av decimal-places remove-trailing-zeros?)
                                 (if (some? av) (to-string attr config) "0"))))
                           order)
               values (if (transparent? (:alpha c))
                        (concat values [(format-number (:alpha c) decimal-places remove-trailing-zeros?)])
                        values)]
           (str (str/lower-case (:sub-type c)) "(" (str/join ", " values) ")"))
         "")

       :else ""))))

;; String conversion ----------------------------------------------------------

(defn- list->string
  [elements implicit? config]
  (str/join (if implicit? " " ", ")
            (map #(if (nil? (value %)) "null" (to-string % config)) elements)))

(defn to-string
  "Renders a symbol the way scripts and token outputs see it. Dynamic
  colors need `config` to know their channel order."
  ([s] (to-string s nil))
  ([s config]
   (cond
     (null? s) "null"
     (number? s) (u/num->str (:value s))
     (string? s) (if (nil? (:value s)) "null" (:value s))
     (boolean? s) (if (nil? (:value s)) "null" (str (:value s)))
     (number-with-unit? s) (str (u/num->str (:value s)) (:unit s))
     (color? s)
     (let [v (:value s)]
       (cond
         (some? config) (format-color config s)
         (map? v) (format-entries v config)
         (clojure.core/string? v) (if (some? (:alpha s)) (format-hex8 v (:alpha s)) v)
         :else ""))
     (list? s) (list->string (value s) (:implicit? s) config)
     (dictionary? s) (format-entries (value s) config)
     (token? s) (let [v (value s)]
                  (if (map? v) (format-entries v config) (list->string v false config)))
     (clojure.core/string? s) s
     (nil? s) "null"
     :else (str s))))

(defn result-type-name
  "Type name of an interpreter result (symbol, string or nil)."
  [result]
  (cond
    (nil? result) "Null"
    (clojure.core/string? result) "String"
    (symbol? result) (full-type-name result)
    :else "Unknown"))

;; Data conversion ------------------------------------------------------------

(defn ->data
  "Converts a symbol tree to plain data. Options:

    :recursive?  convert nested symbols too (default true)
    :stringify?  render structured values (colors, units) as strings

  Dictionary and token keys stay strings; fixed keys are keywords."
  ([s] (->data s nil))
  ([s {:keys [recursive? stringify?] :or {recursive? true stringify? false} :as opts}]
   (let [child (fn [c] (if recursive? (->data c opts) c))]
     (cond
       (nil? s) nil
       (clojure.core/string? s) s
       (null? s) nil
       (number? s) (:value s)
       (string? s) (:value s)
       (boolean? s) (:value s)
       (number-with-unit? s) (if stringify? (to-string s) {:value (:value s) :unit (:unit s)})
       (list? s) (mapv child (value s))
       (dictionary? s) (into {} (map (fn [[k v]] [k (child v)])) (value s))
       (token? s) (let [v (value s)]
                    (if (map? v)
                      (into {} (map (fn [[k v]] [k (child v)])) v)
                      (mapv child v)))
       (color? s) (cond
                    stringify? (to-string s)
                    (clojure.core/string? (:value s))
                    {:type (or (:sub-type s) "hex") :value (:value s) :alpha (:alpha s)}
                    (map? (:value s))
                    (into {:type (or (:sub-type s) "unknown") :alpha (:alpha s)}
                          (map (fn [[k v]] [k (child v)]))
                          (:value s))
                    :else {:type (or (:sub-type s) "unknown") :value nil :alpha (:alpha s)})
       :else s))))

(defn data->symbol
  "Converts plain data (numbers, strings, booleans, nil, vectors, maps)
  into symbols. Hex strings become hex colors. Symbols pass through."
  [v]
  (cond
    (symbol? v) v
    (nil? v) (null)
    (clojure.core/number? v) (number v)
    (clojure.core/string? v) (if (valid-hex? v) (color v "Hex") (string v))
    (clojure.core/boolean? v) (boolean v)
    (sequential? v) (list (mapv data->symbol v) false)
    (map? v) (dictionary (into lkm/empty-linked-map
                               (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (data->symbol v)]))
                               v))
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "valid TokenScript type" :actual-type (u/typeof v)}})))

(defn ->string-result
  "String form of an interpreter result."
  ([result] (->string-result result nil))
  ([result config]
   (cond
     (clojure.core/string? result) result
     (symbol? result) (to-string result config)
     :else (str result))))

;; List and dictionary operations ---------------------------------------------

(defn- expect-number-value
  [s expected]
  (when (nil? (value s))
    (err/raise :symbols/value-is-null {:data {:expected-type expected}})))

(defn- index-of-symbol
  [s]
  (long (value s)))

(defn- out-of-bounds?
  [coll idx]
  (or (< idx 0) (>= idx (count coll))))

(defn- expect-string-key
  [k]
  (cond
    (clojure.core/string? k) k
    (and (string? k) (some? (:value k))) (:value k)
    :else (err/raise :symbols/key-must-be-string {:data {:type (u/typeof k)}})))

(defn list-append
  [l item]
  (vswap! (:value l) conj (clone-if-mutable item))
  l)

(defn list-extend
  [l & items]
  (doseq [item items]
    (if (list? item)
      (vswap! (:value l) into (map clone-if-mutable (value item)))
      (vswap! (:value l) conj (clone-if-mutable item))))
  l)

(defn- vec-insert
  [v idx item]
  (into (conj (subvec v 0 idx) item) (subvec v idx)))

(defn- vec-remove
  [v idx]
  (into (subvec v 0 idx) (subvec v (inc idx))))

(defn list-insert
  [l index-sym item]
  (let [idx (index-of-symbol index-sym)
        v (value l)]
    (when (or (< idx 0) (> idx (count v)))
      (err/raise :symbols/index-out-of-range {:data {:operation "insert"}}))
    (vswap! (:value l) vec-insert idx (clone-if-mutable item))
    l))

(defn list-delete
  [l index-sym]
  (let [idx (index-of-symbol index-sym)]
    (when (out-of-bounds? (value l) idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "deletion"}}))
    (vswap! (:value l) vec-remove idx)
    l))

(defn list-length
  [l]
  (number (count (value l))))

(defn list-index
  [l item]
  (let [idx (first (keep-indexed (fn [i el] (when (equals? el item) i)) (value l)))]
    (number (or idx -1))))

(defn list-get
  [l index-sym]
  (let [v (value l)
        idx (index-of-symbol index-sym)
        idx (if (neg? idx) (+ (count v) idx) idx)]
    (when (out-of-bounds? v idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "get"}}))
    (nth v idx)))

(defn list-update
  [l index-sym item]
  (let [idx (index-of-symbol index-sym)]
    (when (out-of-bounds? (value l) idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "update"}}))
    (vswap! (:value l) assoc idx (clone-if-mutable item))
    l))

(defn list-join
  ([l] (list-join l nil nil))
  ([l separator] (list-join l separator nil))
  ([l separator config]
   (let [sep (let [v (some-> separator value)] (if (or (nil? v) (= v "")) "" (str v)))]
     (string (str/join sep (map #(if (nil? (value %)) "null" (to-string % config)) (value l)))))))

(defn dict-get
  [d k]
  (let [k (expect-string-key k)
        v (get (value d) k)]
    (if (some? v) v (null))))

(defn dict-set
  [d k v]
  (vswap! (:value d) assoc (expect-string-key k) (clone-if-mutable v))
  d)

(defn dict-delete
  [d k]
  (vswap! (:value d) dissoc (expect-string-key k))
  d)

(defn dict-keys
  [d]
  (list (mapv string (keys (value d))) false))

(defn dict-values
  [d]
  (list (vec (vals (value d))) false))

(defn dict-key-exists
  [d k]
  (boolean (contains? (value d) (expect-string-key k))))

(defn dict-length
  [d]
  (number (count (value d))))

(defn dict-clear
  [d]
  (vreset! (:value d) lkm/empty-linked-map)
  d)

;; Token operations -----------------------------------------------------------

(defn- token-map?
  [t]
  (map? (value t)))

(defn- expect-token-list
  [t operation]
  (when (token-map? t)
    (err/raise :symbols/cannot-operation-on-token-type {:data {:operation operation :value-type "Dictionary"}})))

(defn- expect-token-map
  [t operation]
  (when-not (token-map? t)
    (err/raise :symbols/cannot-operation-on-token-type {:data {:operation operation :value-type "List"}})))

(defn token-get
  [t key-or-index]
  (if (token-map? t)
    (let [k (if (clojure.core/string? key-or-index) (string key-or-index) key-or-index)]
      (when-not (string? k)
        (err/raise :symbols/cannot-operation-on-token-type
                   {:data {:operation "Dictionary get" :value-type "non-String key"}}))
      (dict-get t k))
    (let [i (if (clojure.core/number? key-or-index) (number key-or-index) key-or-index)]
      (when-not (number? i)
        (err/raise :symbols/cannot-operation-on-token-type
                   {:data {:operation "List get" :value-type "non-Number index"}}))
      (list-get t i))))

(defn token-set
  [t k v]
  (expect-token-map t "set key")
  (dict-set t k v))

(defn token-keys
  [t]
  (expect-token-map t "get keys")
  (dict-keys t))

(defn token-values
  [t]
  (if (token-map? t) (dict-values t) (list (value t) false)))

(defn token-length
  [t]
  (number (count (value t))))

;; Method tables --------------------------------------------------------------
;;
;; A method definition is `{:args [{:name "x" :optional? true}] :fn f}`
;; where `f` receives the symbol, the config and the arguments. An empty
;; `:args` vector means any number of arguments is accepted.

(defn- method
  ([f] {:args [] :fn f})
  ([args f] {:args args :fn f}))

(defn- arg
  ([name] {:name name :optional? false})
  ([name optional?] {:name name :optional? optional?}))

(defn number->string-symbol
  "`to_string([radix])` for numbers. Hexadecimal conversion rounds
  halves down so color channel conversion stays stable."
  ([n] (number->string-symbol n nil))
  ([n radix]
   (expect-number-value n "int or float")
   (if (nil? radix)
     (string (u/num->str (:value n)))
     (do
       (expect-number-value radix "int or float")
       (let [base (value radix)
             v (:value n)]
         (when-not (and (u/integer-value? base) (<= 2 base 36))
           (err/raise :symbols/invalid-radix {:data {:radix base}}))
         (let [num-value (if (== base 16)
                           (if (== 0.5 (mth/abs (rem v 1.0)))
                             (mth/floor v)
                             (u/js-round v))
                           v)]
           (if (u/integer-value? num-value)
             (string (u/radix-str num-value (long base)))
             (string (u/num->str v)))))))))

(def ^:private number-methods
  {"to_string" (method [(arg "radix" true)] (fn [n _ & [radix]] (number->string-symbol n radix)))})

(defn- expect-string-value
  [s]
  (when (nil? (:value s))
    (err/raise :symbols/value-is-null {:data {:expected-type "string"}})))

(defn string-concat
  [s other]
  (expect-string-value s)
  (if (string? other)
    (do (expect-string-value other)
        (string (str (:value s) (:value other))))
    (err/raise :symbols/cannot-concatenate {:data {:type (str "String " (u/typeof other))}})))

(defn string-split
  [s delimiter]
  (expect-string-value s)
  (cond
    (string? delimiter) (list (mapv string (u/split (:value s) (str (:value delimiter)))) false)
    (clojure.core/string? delimiter) (list (mapv string (u/split (:value s) delimiter)) false)
    (nil? delimiter) (list (mapv string (u/code-points (:value s))) false)
    :else (err/raise :symbols/cannot-split {:data {:type (u/typeof delimiter)}})))

(def ^:private string-methods
  {"upper" (method (fn [s _] (expect-string-value s) (string (str/upper-case (:value s)))))
   "lower" (method (fn [s _] (expect-string-value s) (string (str/lower-case (:value s)))))
   "length" (method (fn [s _] (expect-string-value s) (number (count (:value s)))))
   "concat" (method [(arg "other")] (fn [s _ other] (string-concat s other)))
   "split" (method [(arg "delimiter" true)] (fn [s _ & [delimiter]] (string-split s delimiter)))
   "trim" (method (fn [s _] (expect-string-value s) (string (str/trim (:value s)))))})

(def ^:private list-methods
  {"append" (method [(arg "item")] (fn [l _ item] (list-append l item)))
   "extend" (method (fn [l _ & items] (apply list-extend l items)))
   "insert" (method [(arg "index") (arg "item")] (fn [l _ i item] (list-insert l i item)))
   "delete" (method [(arg "index")] (fn [l _ i] (list-delete l i)))
   "length" (method (fn [l _] (list-length l)))
   "index" (method [(arg "item")] (fn [l _ item] (list-index l item)))
   "get" (method [(arg "index")] (fn [l _ i] (list-get l i)))
   "update" (method [(arg "index") (arg "item")] (fn [l _ i item] (list-update l i item)))
   "join" (method [(arg "separator" true)] (fn [l config & [sep]] (list-join l sep config)))})

(def ^:private number-with-unit-methods
  {"to_string" (method [(arg "radix" true)]
                       (fn [n _ & _]
                         (expect-number-value n "number")
                         (string (str (u/num->str (:value n)) (:unit n)))))
   "to_number" (method (fn [n _]
                         (expect-number-value n "number")
                         (number (:value n))))})

(def ^:private dictionary-methods
  {"get" (method [(arg "key")] (fn [d _ k] (dict-get d k)))
   "set" (method [(arg "key") (arg "value")] (fn [d _ k v] (dict-set d k v)))
   "delete" (method [(arg "key")] (fn [d _ k] (dict-delete d k)))
   "keys" (method (fn [d _] (dict-keys d)))
   "values" (method (fn [d _] (dict-values d)))
   "key_exists" (method [(arg "key")] (fn [d _ k] (dict-key-exists d k)))
   "length" (method (fn [d _] (dict-length d)))
   "clear" (method (fn [d _] (dict-clear d)))})

(def ^:private token-methods
  {"get" (method [(arg "keyOrIndex")] (fn [t _ k] (token-get t k)))
   "set" (method [(arg "key") (arg "value")] (fn [t _ k v] (token-set t k v)))
   "keys" (method (fn [t _] (token-keys t)))
   "values" (method (fn [t _] (token-values t)))
   "length" (method (fn [t _] (token-length t)))
   "append" (method [(arg "item")] (fn [t _ item] (expect-token-list t "append") (list-append t item)))
   "extend" (method (fn [t _ & items] (expect-token-list t "extend") (apply list-extend t items)))
   "insert" (method [(arg "index") (arg "item")]
                    (fn [t _ i item] (expect-token-list t "insert") (list-insert t i item)))
   "delete" (method [(arg "index")] (fn [t _ i] (expect-token-list t "delete") (list-delete t i)))
   "index" (method [(arg "item")] (fn [t _ item] (expect-token-list t "get index") (list-index t item)))
   "update" (method [(arg "index") (arg "item")]
                    (fn [t _ i item] (expect-token-list t "update") (list-update t i item)))
   "join" (method [(arg "separator" true)]
                  (fn [t config & [sep]] (expect-token-list t "join") (list-join t sep config)))})

(def ^:private color-methods
  {"to_string" (method (fn [c config] (string (to-string c config))))})

(defn method-table
  [s]
  (cond
    (number? s) number-methods
    (string? s) string-methods
    (list? s) list-methods
    (number-with-unit? s) number-with-unit-methods
    (dictionary? s) dictionary-methods
    (token? s) token-methods
    (color? s) color-methods
    :else {}))

(defn method-definition
  [s method-name]
  (get (method-table s) (str/lower-case method-name)))

(defn has-method?
  "Whether `s` has a built in method `method-name` accepting `args`."
  [s method-name args]
  (when-let [specs (:args (method-definition s method-name))]
    (or (empty? specs)
        (let [required (count (remove :optional? specs))]
          (and (>= (count args) required)
               (<= (count args) (count specs)))))))

(defn call-method
  "Calls the built in method `method-name` of `s` with `args`."
  [s method-name args config]
  (let [definition (method-definition s method-name)]
    (when (or (nil? definition) (not (has-method? s method-name args)))
      (err/raise :symbols/method-not-found {:data {:method-name method-name :type (type-name s)}}))
    (let [specs (:args definition)
          f (:fn definition)]
      (if (empty? specs)
        (apply f s config args)
        (let [processed (reduce (fn [acc [i spec]]
                                  (if (< i (count args))
                                    (conj acc (nth args i))
                                    (if (:optional? spec)
                                      acc
                                      (err/raise :symbols/missing-required-argument
                                                 {:data {:argument-name (:name spec) :method-name method-name}}))))
                                []
                                (map-indexed vector specs))]
          (apply f s config processed))))))

;; Attributes -----------------------------------------------------------------

(defn has-attribute?
  [s attribute-name]
  (cond
    (or (number? s) (number-with-unit? s)) (= attribute-name "value")
    (dictionary? s) (contains? (value s) attribute-name)
    (token? s) (or (contains? #{"subType" "type"} attribute-name)
                   (and (map? (value s)) (contains? (value s) attribute-name)))
    (color? s) (or (contains? #{"to" "alpha"} attribute-name)
                   (and (map? (:value s)) (contains? (:value s) attribute-name)))
    :else false))

(defn get-attribute
  "Attribute value or nil. Raises for unknown attributes on symbols that
  have fixed attributes."
  [s attribute-name]
  (cond
    (or (number? s) (number-with-unit? s))
    (if (= attribute-name "value")
      (number (:value s))
      (err/raise :symbols/attribute-not-found {:data {:attribute-name attribute-name :type (type-name s)}}))

    (dictionary? s)
    (get (value s) attribute-name)

    (token? s)
    (case attribute-name
      "subType" (string (:sub-type s))
      "type" (string "Token")
      (when (map? (value s)) (get (value s) attribute-name)))

    (color? s)
    (case attribute-name
      "to" s
      "alpha" (if (some? (:alpha s)) (number (:alpha s)) (null))
      (if (and (map? (:value s)) (contains? (:value s) attribute-name))
        (get (:value s) attribute-name)
        (err/raise :symbols/attribute-not-found {:data {:attribute-name attribute-name :type "Color"}})))

    :else
    (err/raise :symbols/attribute-not-found {:data {:attribute-name attribute-name :type (type-name s)}})))
