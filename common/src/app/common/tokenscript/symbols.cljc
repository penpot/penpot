;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.symbols
  "Runtime values of the tokenscript language.

  Every value the interpreter manipulates is a symbol record implementing
  `ISymbol` (type, content, string and data conversion, copying,
  equality) and `IScriptable` (methods and attributes reachable from
  scripts):

    NullSymbol, NumberSymbol, StringSymbol, BooleanSymbol,
    NumberWithUnitSymbol, ColorSymbol            (immutable)
    ListSymbol, DictionarySymbol, TokenSymbol    (mutable containers)

  The language mutates containers in place (`list.append(x)` changes the
  list bound to the variable), so containers keep their content in a
  volatile; `value` returns the current content of any symbol.

  Lists implement `IListSymbol` and dictionaries `IDictionarySymbol`.
  Tokens implement both and accept the operations matching their content
  (a list or a record), raising for the others.

  Script methods map a method name to one of these functions in a method
  table per type, see `call-method`. The attributes and methods scripts
  see are listed in
  docs/technical-guide/developer/subsystems/tokenscript/language.md."
  (:refer-clojure :exclude [list boolean number symbol? number? string? boolean? list?])
  (:require
   [app.common.math :as mth]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.util :as u]
   [clojure.string :as str]
   [linked.map :as lkm]))


;; Protocols ------------------------------------------------------------------

(defprotocol ISymbol
  "Behaviour shared by every runtime value."
  (type-name [_] "Base type name: `Number`, `List`, ...")
  (full-type-name [_]
    "Type name including the sub type: `List.Implicit`, `Color.Hex`,
    `NumberWithUnit.Px`, `Token.Shadow`.")
  (value [_] "Primitive of a scalar symbol, current vector or map of a container.")
  (to-string- [_ config] "See `to-string`.")
  (data- [_ opts] "See `->data`.")
  (deep-copy [_] "Copy of the symbol and of every nested symbol.")
  (clone-if-mutable [_]
    "The symbol itself when immutable, a deep copy for containers.
    Containers store copies of inserted values to avoid shared
    references.")
  (equals? [_ other]
    "Value equality of the language (`==`). Containers compare by
    identity, like the other implementations.")
  (valid-value? [_ v] "Whether `v` is an acceptable value for a variable holding the symbol."))

(defprotocol IScriptable
  "Members of a symbol reachable from scripts: `x.attribute`. Script
  methods (`x.method()`) are looked up by type name, see `call-method`."
  (has-attribute? [_ attribute-name] "Whether `x.attribute-name` is defined.")
  (get-attribute [_ attribute-name]
    "Value of `x.attribute-name` or nil. Raises for unknown attributes on
    symbols with fixed attributes."))

(defprotocol ISized
  (length [_] "Number symbol with the length of a string, list, dictionary or token."))

(defprotocol IListSymbol
  "Operations of lists, and of tokens holding a list. Mutating operations
  change the content in place and return the symbol."
  (append! [_ item])
  (extend! [_ items] "Appends `items`; the items of lists among them are spliced.")
  (insert! [_ index item])
  (delete-at! [_ index])
  (update-at! [_ index item])
  (nth-item [_ index] "Item at an index; negative indexes count from the end.")
  (item-index [_ item] "Number symbol with the index of `item`, -1 when absent.")
  (join [_ config] [_ config separator] "String symbol with the items joined by `separator`."))

(defprotocol IDictionarySymbol
  "Operations of dictionaries, and of tokens holding a record. Mutating
  operations change the content in place and return the symbol."
  (get-key [_ key] "Value under `key`, the null symbol when absent.")
  (set-key! [_ key value])
  (delete-key! [_ key])
  (key-list [_] "List symbol with the keys.")
  (value-list [_] "List symbol with the values.")
  (key-exists? [_ key] "Boolean symbol.")
  (clear! [_]))

;; Names ----------------------------------------------------------------------

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

;; String conversion ----------------------------------------------------------

(defn to-string
  "Renders a symbol the way scripts and token outputs see it. Dynamic
  colors need `config` to know their channel order."
  ([s] (to-string- s nil))
  ([s config] (to-string- s config)))

(defn color-spec
  "Spec registered for the color sub type in `config`, if any."
  [config c]
  (when-let [sub (:sub-type c)]
    (reg/spec-by-name (:colors config) sub)))

(defn- format-number
  [n places remove-trailing-zeros?]
  (let [rounded (u/to-fixed (u/parse-float (u/to-fixed n places)) places)]
    (if remove-trailing-zeros?
      (str/replace rounded #"\.?0+$" "")
      rounded)))

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

(defn- items->string
  [items implicit? config]
  (str/join (if implicit? " " ", ")
            (map #(if (nil? (value %)) "null" (to-string % config)) items)))

;; Data conversion ------------------------------------------------------------

(defn ->data
  "Converts a symbol tree to plain data. Options:

    :recursive?  convert nested symbols too (default true)
    :stringify?  render structured values (colors, units) as strings

  Dictionary and token keys stay strings; fixed keys are keywords."
  ([s] (data- s nil))
  ([s opts] (data- s opts)))

(defn- child->data
  [opts]
  (if (get opts :recursive? true)
    #(data- % opts)
    identity))

(defn- items->data
  [items opts]
  (mapv (child->data opts) items))

(defn- entries->data
  [entries opts]
  (let [child (child->data opts)]
    (into {} (map (fn [[k v]] [k (child v)])) entries)))

(defn- copy-entries
  [entries]
  (into lkm/empty-linked-map (map (fn [[k v]] [k (deep-copy v)])) entries))

;; Checks ---------------------------------------------------------------------

(defn- attribute-not-found
  [s attribute-name]
  (err/raise :symbols/attribute-not-found {:data {:attribute-name attribute-name :type (type-name s)}}))

(defn- expect-string-value
  [s]
  (when (nil? (:value s))
    (err/raise :symbols/value-is-null {:data {:expected-type "string"}})))

(defn- expect-list-content
  [t operation]
  (when (map? (value t))
    (err/raise :symbols/cannot-operation-on-token-type {:data {:operation operation :value-type "Dictionary"}})))

(defn- expect-map-content
  [t operation]
  (when-not (map? (value t))
    (err/raise :symbols/cannot-operation-on-token-type {:data {:operation operation :value-type "List"}})))

;; Equality -------------------------------------------------------------------

(defn- color-sub-types-match?
  "Colors without a sub type are compatible with hex colors."
  [a b]
  (or (and (nil? a) (u/name= b "hex"))
      (and (u/name= a "hex") (nil? b))
      (u/name= a b)))

(defn- scalar-equals?
  [a b]
  (and (= (type-name a) (type-name b)) (= (:value a) (:value b))))

(defn- container-equals?
  [a b]
  (and (= (type-name a) (type-name b)) (identical? (:value a) (:value b))))

(defn- color-equals?
  "Equality of two color symbols."
  [a b]
  (and (color-sub-types-match? (:sub-type a) (:sub-type b))
       (= (:alpha a) (:alpha b))
       (let [va (:value a) vb (:value b)]
         (if (and (map? va) (map? vb))
           (and (= (count va) (count vb))
                (every? (fn [[k v]] (and (contains? vb k) (equals? v (get vb k)))) va))
           (= va vb)))))

;; Records --------------------------------------------------------------------
;;
;; Each record is followed by its predicate and constructor. A record body
;; only uses what is defined above it, which is why the containers come
;; last, and refers to its own type through the class. Inside a body the
;; `value` field shadows the `value` function.

(defrecord NullSymbol []
  ISymbol
  (type-name [_] "Null")
  (full-type-name [_] "Null")
  (value [_] nil)
  (to-string- [_ _] "null")
  (data- [_ _] nil)
  (deep-copy [this] this)
  (clone-if-mutable [this] this)
  (equals? [_ other] (instance? NullSymbol other))
  (valid-value? [_ v] (nil? v))

  IScriptable
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name)))

(defn null? [v] (instance? NullSymbol v))

(defn null
  []
  (->NullSymbol))

(defrecord NumberSymbol [value]
  ISymbol
  (type-name [_] "Number")
  (full-type-name [_] "Number")
  (value [_] value)
  (to-string- [_ _] (u/num->str value))
  (data- [_ _] value)
  (deep-copy [this] this)
  (clone-if-mutable [this] this)
  (equals? [this other] (scalar-equals? this other))
  (valid-value? [_ v] (or (clojure.core/number? v) (instance? NumberSymbol v)))

  IScriptable
  (has-attribute? [_ attribute-name] (= attribute-name "value"))
  (get-attribute [this attribute-name]
    (if (= attribute-name "value")
      this
      (attribute-not-found this attribute-name))))

(defn number? [v] (instance? NumberSymbol v))

(defrecord NumberWithUnitSymbol [value unit]
  ISymbol
  (type-name [_] "NumberWithUnit")
  (full-type-name [_] (str "NumberWithUnit." (u/capitalize unit)))
  (value [_] value)
  (to-string- [_ _] (str (u/num->str value) unit))
  (data- [this opts]
    (if (:stringify? opts)
      (to-string- this nil)
      {:value value :unit unit}))
  (deep-copy [this] this)
  (clone-if-mutable [this] this)
  (equals? [_ other]
    (and (instance? NumberWithUnitSymbol other)
         (= value (:value other))
         (u/name= unit (:unit other))))
  (valid-value? [_ v] (instance? NumberWithUnitSymbol v))

  IScriptable
  (has-attribute? [_ attribute-name] (= attribute-name "value"))
  (get-attribute [this attribute-name]
    (if (= attribute-name "value")
      (->NumberSymbol value)
      (attribute-not-found this attribute-name))))

(defn number-with-unit? [v] (instance? NumberWithUnitSymbol v))

(defn numeric?
  "Number or NumberWithUnit symbol."
  [v]
  (or (number? v) (number-with-unit? v)))

(defn number
  "Number symbol from a number, another numeric symbol or nil."
  [v]
  (cond
    (clojure.core/number? v) (->NumberSymbol (double v))
    (numeric? v) (->NumberSymbol (value v))
    (nil? v) (->NumberSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "int or float" :actual-type (u/typeof v)}})))

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

(defrecord StringSymbol [value]
  ISymbol
  (type-name [_] "String")
  (full-type-name [_] "String")
  (value [_] value)
  (to-string- [_ _] (if (nil? value) "null" value))
  (data- [_ _] value)
  (deep-copy [this] this)
  (clone-if-mutable [this] this)
  (equals? [this other] (scalar-equals? this other))
  (valid-value? [_ v] (or (clojure.core/string? v) (instance? StringSymbol v)))

  IScriptable
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name))

  ISized
  (length [this]
    (expect-string-value this)
    (number (count value))))

(defn string? [v] (instance? StringSymbol v))

(defn string
  [v]
  (cond
    (clojure.core/string? v) (->StringSymbol v)
    (string? v) (->StringSymbol (:value v))
    (nil? v) (->StringSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "string" :actual-type (u/typeof v)}})))

(defrecord BooleanSymbol [value]
  ISymbol
  (type-name [_] "Boolean")
  (full-type-name [_] "Boolean")
  (value [_] value)
  (to-string- [_ _] (if (nil? value) "null" (str value)))
  (data- [_ _] value)
  (deep-copy [this] this)
  (clone-if-mutable [this] this)
  (equals? [this other] (scalar-equals? this other))
  (valid-value? [_ v] (or (clojure.core/boolean? v) (instance? BooleanSymbol v)))

  IScriptable
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name)))

(defn boolean? [v] (instance? BooleanSymbol v))

(defn boolean
  [v]
  (cond
    (clojure.core/boolean? v) (->BooleanSymbol v)
    (boolean? v) (->BooleanSymbol (:value v))
    (nil? v) (->BooleanSymbol nil)
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "boolean" :actual-type (u/typeof v)}})))

(defrecord ColorSymbol [value sub-type alpha]
  ISymbol
  (type-name [_] "Color")
  (full-type-name [_] (qualified-type-name "Color" sub-type))
  (value [_] value)
  (to-string- [this config]
    (cond
      (some? config) (format-color config this)
      (map? value) (format-entries value config)
      (clojure.core/string? value) (if (some? alpha) (format-hex8 value alpha) value)
      :else ""))
  (data- [this opts]
    (cond
      (:stringify? opts) (to-string- this nil)
      (clojure.core/string? value) {:type (or sub-type "hex") :value value :alpha alpha}
      (map? value) (into {:type (or sub-type "unknown") :alpha alpha} (entries->data value opts))
      :else {:type (or sub-type "unknown") :value nil :alpha alpha}))
  (deep-copy [this]
    (if (map? value)
      (ColorSymbol. (into {} (map (fn [[k v]] [k (deep-copy v)])) value) sub-type alpha)
      this))
  (clone-if-mutable [this] this)
  (equals? [this other] (and (instance? ColorSymbol other) (color-equals? this other)))
  (valid-value? [_ v] (or (instance? ColorSymbol v) (nil? v) (map? v) (valid-hex? v)))

  IScriptable
  (has-attribute? [_ attribute-name]
    (or (contains? #{"to" "alpha"} attribute-name)
        (and (map? value) (contains? value attribute-name))))
  (get-attribute [this attribute-name]
    (case attribute-name
      "to" this
      "alpha" (if (some? alpha) (number alpha) (null))
      (if (and (map? value) (contains? value attribute-name))
        (get value attribute-name)
        (err/raise :symbols/attribute-not-found {:data {:attribute-name attribute-name :type "Color"}})))))

(defn color? [v] (instance? ColorSymbol v))

(defn color
  "Color symbol. `v` is a hex string (sub type `Hex`), a map of channel
  name to symbol (dynamic color with a sub type), or nil."
  ([v] (color v nil nil))
  ([v sub-type] (color v sub-type nil))
  ([v sub-type alpha]
   (let [hex? (and (or (nil? sub-type) (u/name= "hex" sub-type))
                   (clojure.core/string? v))
         dynamic? (and (clojure.core/string? sub-type) (map? v))]
     (when-not (or (nil? v) hex? dynamic?)
       (err/raise :symbols/invalid-color-value {:data {:value (str v) :type (u/typeof v)}}))
     (when (and hex? (not (valid-hex? v)))
       (err/raise :symbols/invalid-hex-color {:data {:value (str v)}}))
     (ensure-valid-alpha alpha)
     (->ColorSymbol v (if hex? "Hex" sub-type) alpha))))

;; List and dictionary content ------------------------------------------------
;;
;; Shared by lists, dictionaries and tokens. Mutating helpers take the
;; volatile holding the content, the others the content itself.

(defn- index-of-symbol
  [s]
  (long (value s)))

(defn- out-of-bounds?
  [coll idx]
  (or (< idx 0) (>= idx (count coll))))

(defn- vec-insert
  [v idx item]
  (into (conj (subvec v 0 idx) item) (subvec v idx)))

(defn- vec-remove
  [v idx]
  (into (subvec v 0 idx) (subvec v (inc idx))))

(defn- list-append*
  [items item]
  (vswap! items conj (clone-if-mutable item)))

(defn- list-extend*
  "Appends `more`, splicing the items of lists. Lists are recognized by
  type name: the list record is defined after this helper."
  [items more]
  (doseq [item more]
    (if (= "List" (type-name item))
      (vswap! items into (map clone-if-mutable (value item)))
      (vswap! items conj (clone-if-mutable item)))))

(defn- list-insert*
  [items index-sym item]
  (let [idx (index-of-symbol index-sym)]
    (when (or (< idx 0) (> idx (count @items)))
      (err/raise :symbols/index-out-of-range {:data {:operation "insert"}}))
    (vswap! items vec-insert idx (clone-if-mutable item))))

(defn- list-delete*
  [items index-sym]
  (let [idx (index-of-symbol index-sym)]
    (when (out-of-bounds? @items idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "deletion"}}))
    (vswap! items vec-remove idx)))

(defn- list-update*
  [items index-sym item]
  (let [idx (index-of-symbol index-sym)]
    (when (out-of-bounds? @items idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "update"}}))
    (vswap! items assoc idx (clone-if-mutable item))))

(defn- list-get*
  [items index-sym]
  (let [idx (index-of-symbol index-sym)
        idx (if (neg? idx) (+ (count items) idx) idx)]
    (when (out-of-bounds? items idx)
      (err/raise :symbols/index-out-of-range {:data {:operation "get"}}))
    (nth items idx)))

(defn- list-index*
  [items item]
  (number (or (first (keep-indexed (fn [i el] (when (equals? el item) i)) items)) -1)))

(defn- list-join*
  [items separator config]
  (let [sep (let [v (some-> separator value)] (if (or (nil? v) (= v "")) "" (str v)))]
    (string (str/join sep (map #(if (nil? (value %)) "null" (to-string % config)) items)))))

(defrecord ListSymbol [value implicit?]
  ISymbol
  (type-name [_] "List")
  (full-type-name [_] (if implicit? (qualified-type-name "List" "Implicit") "List"))
  (value [_] @value)
  (to-string- [_ config] (items->string @value implicit? config))
  (data- [_ opts] (items->data @value opts))
  (deep-copy [_] (ListSymbol. (volatile! (mapv deep-copy @value)) implicit?))
  (clone-if-mutable [this] (deep-copy this))
  (equals? [this other] (container-equals? this other))
  (valid-value? [_ v] (or (vector? v) (instance? ListSymbol v)))

  IScriptable
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name))

  ISized
  (length [_] (number (count @value)))

  IListSymbol
  (append! [this item] (list-append* value item) this)
  (extend! [this items] (list-extend* value items) this)
  (insert! [this index item] (list-insert* value index item) this)
  (delete-at! [this index] (list-delete* value index) this)
  (update-at! [this index item] (list-update* value index item) this)
  (nth-item [_ index] (list-get* @value index))
  (item-index [_ item] (list-index* @value item))
  (join [this config] (join this config nil))
  (join [_ config separator] (list-join* @value separator config)))

(defn list? [v] (instance? ListSymbol v))

(defn list
  "List symbol from a sequence of symbols."
  ([elements] (list elements false))
  ([elements implicit?]
   (->ListSymbol (volatile! (vec elements)) (clojure.core/boolean implicit?))))

(defn- expect-string-key
  [k]
  (cond
    (clojure.core/string? k) k
    (and (string? k) (some? (:value k))) (:value k)
    :else (err/raise :symbols/key-must-be-string {:data {:type (u/typeof k)}})))

(defn- dict-get*
  [entries k]
  (let [v (get entries (expect-string-key k))]
    (if (some? v) v (null))))

(defn- dict-set*
  [entries k v]
  (vswap! entries assoc (expect-string-key k) (clone-if-mutable v)))

(defn- dict-delete*
  [entries k]
  (vswap! entries dissoc (expect-string-key k)))

(defn- dict-keys*
  [entries]
  (list (mapv string (keys entries)) false))

(defn- dict-values*
  [entries]
  (list (vec (vals entries)) false))

(defn- dict-key-exists*
  [entries k]
  (boolean (contains? entries (expect-string-key k))))

(defn- ordered-map
  [m]
  (if (nil? m)
    lkm/empty-linked-map
    (into lkm/empty-linked-map m)))

(defrecord DictionarySymbol [value]
  ISymbol
  (type-name [_] "Dictionary")
  (full-type-name [_] "Dictionary")
  (value [_] @value)
  (to-string- [_ config] (format-entries @value config))
  (data- [_ opts] (entries->data @value opts))
  (deep-copy [_] (DictionarySymbol. (volatile! (copy-entries @value))))
  (clone-if-mutable [this] (deep-copy this))
  (equals? [this other] (container-equals? this other))
  (valid-value? [_ v] (or (map? v) (nil? v)))

  IScriptable
  (has-attribute? [_ attribute-name] (contains? @value attribute-name))
  (get-attribute [_ attribute-name] (get @value attribute-name))

  ISized
  (length [_] (number (count @value)))

  IDictionarySymbol
  (get-key [_ key] (dict-get* @value key))
  (set-key! [this key v] (dict-set* value key v) this)
  (delete-key! [this key] (dict-delete* value key) this)
  (key-list [_] (dict-keys* @value))
  (value-list [_] (dict-values* @value))
  (key-exists? [_ key] (dict-key-exists* @value key))
  (clear! [this] (vreset! value lkm/empty-linked-map) this))

(defn dictionary? [v] (instance? DictionarySymbol v))

(defn dictionary
  "Dictionary symbol from a map of string keys to symbols. Entries keep
  insertion order."
  [m]
  (cond
    (dictionary? m) (->DictionarySymbol (volatile! (value m)))
    (or (nil? m) (map? m)) (->DictionarySymbol (volatile! (ordered-map m)))
    :else (err/raise :symbols/value-must-be-type
                     {:data {:expected-type "dict" :actual-type (u/typeof m)}})))

(defrecord TokenSymbol [sub-type value metadata]
  ISymbol
  (type-name [_] "Token")
  (full-type-name [_] (qualified-type-name "Token" sub-type))
  (value [_] @value)
  (to-string- [_ config]
    (let [v @value]
      (if (map? v) (format-entries v config) (items->string v false config))))
  (data- [_ opts]
    (let [v @value]
      (if (map? v) (entries->data v opts) (items->data v opts))))
  (deep-copy [_]
    (let [v @value]
      (TokenSymbol. sub-type (volatile! (if (map? v) (copy-entries v) (mapv deep-copy v))) metadata)))
  (clone-if-mutable [this] (deep-copy this))
  (equals? [this other] (container-equals? this other))
  (valid-value? [_ v] (or (map? v) (vector? v) (nil? v)))

  IScriptable
  (has-attribute? [_ attribute-name]
    (or (contains? #{"subType" "type"} attribute-name)
        (let [v @value] (and (map? v) (contains? v attribute-name)))))
  (get-attribute [_ attribute-name]
    (case attribute-name
      "subType" (string sub-type)
      "type" (string "Token")
      (let [v @value] (when (map? v) (get v attribute-name)))))

  ISized
  (length [_] (number (count @value)))

  IListSymbol
  (append! [this item] (expect-list-content this "append") (list-append* value item) this)
  (extend! [this items] (expect-list-content this "extend") (list-extend* value items) this)
  (insert! [this index item] (expect-list-content this "insert") (list-insert* value index item) this)
  (delete-at! [this index] (expect-list-content this "delete") (list-delete* value index) this)
  (update-at! [this index item] (expect-list-content this "update") (list-update* value index item) this)
  (nth-item [this index] (expect-list-content this "get") (list-get* @value index))
  (item-index [this item] (expect-list-content this "get index") (list-index* @value item))
  (join [this config] (join this config nil))
  (join [this config separator] (expect-list-content this "join") (list-join* @value separator config))

  IDictionarySymbol
  (get-key [this key] (expect-map-content this "get") (dict-get* @value key))
  (set-key! [this key v] (expect-map-content this "set key") (dict-set* value key v) this)
  (delete-key! [this key] (expect-map-content this "delete key") (dict-delete* value key) this)
  (key-list [this] (expect-map-content this "get keys") (dict-keys* @value))
  (value-list [_] (let [v @value] (if (map? v) (dict-values* v) (list v false))))
  (key-exists? [this key] (expect-map-content this "key exists") (dict-key-exists* @value key))
  (clear! [this] (expect-map-content this "clear") (vreset! value lkm/empty-linked-map) this))

(defn token? [v] (instance? TokenSymbol v))

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

(defn- token-get
  "`get(keyOrIndex)` of tokens: a key for records, an index for lists."
  [t key-or-index]
  (if (map? (value t))
    (let [k (if (clojure.core/string? key-or-index) (string key-or-index) key-or-index)]
      (when-not (string? k)
        (err/raise :symbols/cannot-operation-on-token-type
                   {:data {:operation "Dictionary get" :value-type "non-String key"}}))
      (get-key t k))
    (let [i (if (clojure.core/number? key-or-index) (number key-or-index) key-or-index)]
      (when-not (number? i)
        (err/raise :symbols/cannot-operation-on-token-type
                   {:data {:operation "List get" :value-type "non-Number index"}}))
      (nth-item t i))))

;; Host values ----------------------------------------------------------------

;; Values from the host (nil, strings and other data) render and convert as
;; themselves; they have no type, methods or attributes.
(extend-protocol ISymbol
  nil
  (type-name [_] "Unknown")
  (full-type-name [_] "Unknown")
  (value [_] nil)
  (to-string- [_ _] "null")
  (data- [_ _] nil)
  (deep-copy [_] nil)
  (clone-if-mutable [_] nil)
  (equals? [_ _] false)
  (valid-value? [_ _] false)

  #?(:clj String :cljs string)
  (type-name [_] "Unknown")
  (full-type-name [_] "Unknown")
  (value [_] nil)
  (to-string- [s _] s)
  (data- [s _] s)
  (deep-copy [s] s)
  (clone-if-mutable [s] s)
  (equals? [_ _] false)
  (valid-value? [_ _] false)

  #?(:clj Object :cljs default)
  (type-name [_] "Unknown")
  (full-type-name [_] "Unknown")
  (value [x] (let [v (get x :value)] (if (volatile? v) @v v)))
  (to-string- [x _] (str x))
  (data- [x _] x)
  (deep-copy [x] x)
  (clone-if-mutable [x] x)
  (equals? [_ _] false)
  (valid-value? [_ _] false))

(extend-protocol IScriptable
  nil
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name))

  #?(:clj Object :cljs default)
  (has-attribute? [_ _] false)
  (get-attribute [this attribute-name] (attribute-not-found this attribute-name)))

;; Types ----------------------------------------------------------------------

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

(defn hex-color?
  [c]
  (and (color? c) (u/name= (:sub-type c) "hex")))

(defn symbol-type-equals?
  "Type compatibility used for variable assignment. Colors without a sub
  type are compatible with hex colors."
  [a b]
  (if (and (color? a) (color? b))
    (color-sub-types-match? (:sub-type a) (:sub-type b))
    (u/name= (type-name a) (type-name b))))

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
  (some? (u/get-name nullable-type-names (type-name s))))

(defn null-value?
  "True for nullable symbols holding a nil value (and the null symbol)."
  [s]
  (or (null? s) (and (nullable? s) (nil? (value s)))))

(defn empty-of-type
  [type-name]
  (when-let [ctor (u/get-name empty-constructors type-name)]
    (ctor)))

;; Results --------------------------------------------------------------------

(defn result-type-name
  "Type name of an interpreter result (symbol, string or nil)."
  [result]
  (cond
    (nil? result) "Null"
    (clojure.core/string? result) "String"
    (symbol? result) (full-type-name result)
    :else "Unknown"))

(defn ->string-result
  "String form of an interpreter result."
  ([result] (->string-result result nil))
  ([result config]
   (cond
     (clojure.core/string? result) result
     (symbol? result) (to-string result config)
     :else (str result))))

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

;; Scalar operations ----------------------------------------------------------

(defn- expect-number-value
  [s expected]
  (when (nil? (value s))
    (err/raise :symbols/value-is-null {:data {:expected-type expected}})))

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

(defn- unit->string-symbol
  "`to_string()` of numbers with unit; a radix argument is ignored."
  ([n] (unit->string-symbol n nil))
  ([n _radix]
   (expect-number-value n "number")
   (string (str (u/num->str (:value n)) (:unit n)))))

(defn- unit->number-symbol
  [n]
  (expect-number-value n "number")
  (number (:value n)))

(defn- color->string-symbol
  [c config]
  (string (to-string c config)))

(defn string-concat
  [s other]
  (expect-string-value s)
  (if (string? other)
    (do (expect-string-value other)
        (string (str (:value s) (:value other))))
    (err/raise :symbols/cannot-concatenate {:data {:type (str "String " (u/typeof other))}})))

(defn string-split
  ([s] (string-split s nil))
  ([s delimiter]
   (expect-string-value s)
   (cond
     (string? delimiter) (list (mapv string (u/split (:value s) (str (:value delimiter)))) false)
     (clojure.core/string? delimiter) (list (mapv string (u/split (:value s) delimiter)) false)
     (nil? delimiter) (list (mapv string (u/code-points (:value s))) false)
     :else (err/raise :symbols/cannot-split {:data {:type (u/typeof delimiter)}}))))

(defn- string-upper
  [s]
  (expect-string-value s)
  (string (str/upper-case (:value s))))

(defn- string-lower
  [s]
  (expect-string-value s)
  (string (str/lower-case (:value s))))

(defn- string-trim
  [s]
  (expect-string-value s)
  (string (str/trim (:value s))))

;; Script methods -------------------------------------------------------------
;;
;; A method table maps the lower case method name to
;;
;;   :fn       called with the symbol followed by the arguments
;;   :args     accepted argument count, a number or `[min max]`. Without it
;;             any arguments are accepted and none are passed, like methods
;;             declaring no arguments in the other implementations
;;   :rest?    pass all the arguments as one sequence
;;   :config?  pass the interpreter config before the arguments

(def ^:private method-tables
  "Script methods by type name, then by lower case method name."
  {"Number"
   {"to_string" {:fn number->string-symbol :args [0 1]}}

   "NumberWithUnit"
   {"to_string" {:fn unit->string-symbol :args [0 1]}
    "to_number" {:fn unit->number-symbol}}

   "String"
   {"upper" {:fn string-upper}
    "lower" {:fn string-lower}
    "length" {:fn length}
    "concat" {:fn string-concat :args 1}
    "split" {:fn string-split :args [0 1]}
    "trim" {:fn string-trim}}

   "Color"
   {"to_string" {:fn color->string-symbol :config? true}}

   "List"
   {"append" {:fn append! :args 1}
    "extend" {:fn extend! :rest? true}
    "insert" {:fn insert! :args 2}
    "delete" {:fn delete-at! :args 1}
    "length" {:fn length}
    "index" {:fn item-index :args 1}
    "get" {:fn nth-item :args 1}
    "update" {:fn update-at! :args 2}
    "join" {:fn join :args [0 1] :config? true}}

   "Dictionary"
   {"get" {:fn get-key :args 1}
    "set" {:fn set-key! :args 2}
    "delete" {:fn delete-key! :args 1}
    "keys" {:fn key-list}
    "values" {:fn value-list}
    "key_exists" {:fn key-exists? :args 1}
    "length" {:fn length}
    "clear" {:fn clear!}}

   "Token"
   {"get" {:fn token-get :args 1}
    "set" {:fn set-key! :args 2}
    "keys" {:fn key-list}
    "values" {:fn value-list}
    "length" {:fn length}
    "append" {:fn append! :args 1}
    "extend" {:fn extend! :rest? true}
    "insert" {:fn insert! :args 2}
    "delete" {:fn delete-at! :args 1}
    "index" {:fn item-index :args 1}
    "update" {:fn update-at! :args 2}
    "join" {:fn join :args [0 1] :config? true}}})

(defn- method-definition
  [s method-name]
  (u/get-name (get method-tables (type-name s)) method-name))

(defn- accepts?
  [{:keys [args]} n]
  (cond
    (nil? args) true
    (vector? args) (<= (nth args 0) n (nth args 1))
    :else (== args n)))

(defn has-method?
  "Whether `s` has a built in method `method-name` accepting `args`."
  [s method-name args]
  (when-let [definition (method-definition s method-name)]
    (accepts? definition (count args))))

(defn call-method
  "Calls the built in method `method-name` of `s` with `args`."
  [s method-name args config]
  (let [definition (method-definition s method-name)]
    (when-not (and definition (accepts? definition (count args)))
      (err/raise :symbols/method-not-found {:data {:method-name method-name :type (type-name s)}}))
    (let [{f :fn arity :args :keys [rest? config?]} definition
          fargs (cond rest? [args] (nil? arity) [] :else args)]
      (apply f s (if config? (cons config fargs) fargs)))))
