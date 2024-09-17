;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema
  (:refer-clojure :exclude [deref merge parse-uuid parse-long parse-double parse-boolean])
  #?(:cljs (:require-macros [app.common.schema :refer [ignoring]]))
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pprint :as pp]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.schema.registry :as sr]
   [app.common.time :as tm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [clojure.core :as c]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.dev.pretty :as mdp]
   [malli.dev.virhe :as v]
   [malli.error :as me]
   [malli.generator :as mg]
   [malli.registry :as mr]
   [malli.transform :as mt]
   [malli.util :as mu]))

(defprotocol ILazySchema
  (-validate [_ o])
  (-explain [_ o])
  (-decode [_ o]))

(def default-options
  {:registry sr/default-registry})

(defn schema?
  [o]
  (m/schema? o))

(defn properties
  [s]
  (m/properties s))

(defn type-properties
  [s]
  (m/type-properties s))

(defn- lazy-schema?
  [s]
  (satisfies? ILazySchema s))

(defn schema
  [s]
  (m/schema s default-options))

(defn validate
  [s value]
  (m/validate s value default-options))

(defn explain
  [s value]
  (m/explain s value default-options))

(defn simplify
  "Given an explain data structure, return a simplified version of it"
  [exp]
  (me/humanize exp))

(defn generate
  ([s]
   (mg/generate (schema s)))
  ([s o]
   (mg/generate (schema s) o)))

(defn form
  "Returns a readable form of the schema"
  [s]
  (m/form s default-options))

(defn merge
  "Merge two schemas"
  [& items]
  (apply mu/merge (map schema items)))

(defn ref?
  [s]
  (m/-ref-schema? s))

(defn deref
  [s]
  (m/deref s))

(defn error-values
  "Get error values form explain data structure"
  [exp]
  (malli.error/error-value exp {:malli.error/mask-valid-values '...}))

(defn optional-keys
  [schema]
  (mu/optional-keys schema default-options))

(defn transformer
  [& transformers]
  (apply mt/transformer transformers))

;; (defn key-transformer
;;   [& {:as opts}]
;;   (mt/key-transformer opts))

;; (defn- transform-map-keys
;;   [f o]
;;   (cond
;;     (record? o)
;;     (reduce-kv (fn [res k v]
;;                  (let [k' (f k)]
;;                    (if (= k k')
;;                      res
;;                      (-> res
;;                          (assoc k' v)
;;                          (dissoc k)))))
;;                o
;;                o)

;;     (map? o)
;;     (persistent!
;;      (reduce-kv (fn [res k v]
;;                   (assoc! res (f k) v))
;;                 (transient {})
;;                 o))

;;     :else
;;     o))

(defn json-transformer
  []
  (mt/transformer
   (mt/json-transformer)
   (mt/collection-transformer)))

(defn string-transformer
  []
  (mt/transformer
   (mt/string-transformer)
   (mt/collection-transformer)))

(defn encode
  ([s val transformer]
   (m/encode s val default-options transformer))
  ([s val options transformer]
   (m/encode s val options transformer)))

(defn decode
  ([s val transformer]
   (m/decode s val default-options transformer))
  ([s val options transformer]
   (m/decode s val options transformer)))

(defn validator
  [s]
  (-> s schema m/validator))

(defn explainer
  [s]
  (-> s schema m/explainer))

(defn encoder
  ([s transformer]
   (m/encoder s default-options transformer))
  ([s options transformer]
   (m/encoder s options transformer)))

(defn decoder
  ([s transformer]
   (m/decoder s default-options transformer))
  ([s options transformer]
   (m/decoder s options transformer)))

(defn lazy-validator
  [s]
  (let [vfn (delay (validator (if (delay? s) (deref s) s)))]
    (fn [v] (@vfn v))))

(defn lazy-explainer
  [s]
  (let [vfn (delay (explainer (if (delay? s) (deref s) s)))]
    (fn [v] (@vfn v))))

(defn lazy-decoder
  [s transformer]
  (let [vfn (delay (decoder (if (delay? s) (deref s) s) transformer))]
    (fn [v] (@vfn v))))

(defn humanize-explain
  "Returns a string representation of the explain data structure"
  [{:keys [schema errors value]} & {:keys [length level]}]
  (let [errors (mapv #(update % :schema form) errors)]
    (with-out-str
      (println "Schema: ")
      (println (pp/pprint-str (form schema) {:width 100 :level 15 :length 20}))
      (println "Errors:")
      (println (pp/pprint-str errors {:width 100 :level 15 :length 20}))
      (println "Value:")
      (println (pp/pprint-str value {:width 160
                                     :level (d/nilv level 8)
                                     :length (d/nilv length 12)})))))

(defmethod v/-format ::schemaless-explain
  [_ explanation printer]
  {:body [:group
          (v/-block "Value" (v/-visit (me/error-value explanation printer) printer) printer) :break :break
          (v/-block "Errors" (v/-visit (me/humanize (me/with-spell-checking explanation)) printer) printer)]})

(defmethod v/-format ::explain
  [_ {:keys [schema] :as explanation} printer]
  {:body [:group
          (v/-block "Value" (v/-visit (me/error-value explanation printer) printer) printer) :break :break
          (v/-block "Errors" (v/-visit (me/humanize (me/with-spell-checking explanation)) printer) printer) :break :break
          (v/-block "Schema" (v/-visit schema printer) printer)]})

(defn pretty-explain
  "A helper that allows print a console-friendly output for the
  explain; should not be used for other purposes"
  [explain & {:keys [variant message]
              :or {variant ::explain
                   message "Validation Error"}}]
  (let [explain (fn [] (me/with-error-messages explain))]
    ((mdp/prettifier variant message explain default-options))))

(defmacro ignoring
  [expr]
  (if (:ns &env)
    `(try ~expr (catch :default e# nil))
    `(try ~expr (catch Throwable e# nil))))

(defn lookup
  "Lookups schema from registry."
  ([s] (lookup sr/default-registry s))
  ([registry s] (schema (mr/schema registry s))))

(defn- fast-check!
  "A fast path for checking process, assumes the ILazySchema protocol
  implemented on the provided `s` schema. Sould not be used directly."
  [s value]
  (when-not ^boolean (-validate s value)
    (let [hint    (d/nilv dm/*assert-context* "check error")
          explain (-explain s value)]
      (throw (ex-info hint {:type :assertion
                            :code :data-validation
                            :hint hint
                            ::explain explain}))))
  true)

(declare ^:private lazy-schema)

(defn check-fn
  "Create a predefined check function"
  [s]
  (let [schema (if (lazy-schema? s) s (lazy-schema s))]
    (partial fast-check! schema)))

(defn check!
  "A helper intended to be used on assertions for validate/check the
  schema over provided data. Raises an assertion exception, should be
  used together with `dm/assert!` or `dm/verify!`."
  [s value]
  (let [s (if (lazy-schema? s) s (lazy-schema s))]
    (fast-check! s value)))

(defn- fast-validate!
  "A fast path for validation process, assumes the ILazySchema protocol
  implemented on the provided `s` schema. Sould not be used directly."
  ([s value] (fast-validate! s value nil))
  ([s value options]
   (when-not ^boolean (-validate s value)
     (let [explain (-explain s value)
           options (into {:type :validation
                          :code :data-validation
                          ::explain explain}
                         options)
           hint    (get options :hint "schema validation error")]
       (throw (ex-info hint options))))
   value))

(defn validate-fn
  "Create a predefined validate function that raises an expception"
  [s]
  (let [schema (if (lazy-schema? s) s (lazy-schema s))]
    (partial fast-validate! schema)))

(defn validate!
  "A generic validation function for predefined schemas."
  ([s value] (validate! s value nil))
  ([s value options]
   (let [s (if (lazy-schema? s) s (lazy-schema s))]
     (fast-validate! s value options))))

(defn register! [type s]
  (let [s (if (map? s) (m/-simple-schema s) s)]
    (swap! sr/registry assoc type s)
    nil))

(defn- lazy-schema
  "Create ans instance of ILazySchema"
  [s]
  (let [schema      (delay (schema s))
        validator   (delay (m/validator @schema))
        explainer   (delay (m/explainer @schema))]

    (reify
      m/AST
      (-to-ast [_ options] (m/-to-ast @schema options))

      m/EntrySchema
      (-entries [_] (m/-entries @schema))
      (-entry-parser [_] (m/-entry-parser @schema))

      m/Cached
      (-cache [_] (m/-cache @schema))

      m/LensSchema
      (-keep [_] (m/-keep @schema))
      (-get [_ key default] (m/-get @schema key default))
      (-set [_ key value] (m/-set @schema key value))

      m/Schema
      (-validator [_]
        (m/-validator @schema))
      (-explainer [_ path]
        (m/-explainer @schema path))
      (-parser [_]
        (m/-parser @schema))
      (-unparser [_]
        (m/-unparser @schema))
      (-transformer [_ transformer method options]
        (m/-transformer @schema transformer method options))
      (-walk [_ walker path options]
        (m/-walk @schema walker path options))
      (-properties [_]
        (m/-properties @schema))
      (-options [_]
        (m/-options @schema))
      (-children [_]
        (m/-children @schema))
      (-parent [_]
        (m/-parent @schema))
      (-form [_]
        (m/-form @schema))

      ILazySchema
      (-validate [_ o]
        (@validator o))
      (-explain [_ o]
        (@explainer o))
      (-decode [_ o]
        (@decoder o)))))

;; --- BUILTIN SCHEMAS

(register! :merge (mu/-merge))
(register! :union (mu/-union))

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn parse-uuid
  [s]
  (if (string? s)
    (some->> (re-matches uuid-rx s) uuid/uuid)
    s))

(register! ::uuid
  {:type ::uuid
   :pred uuid?
   :type-properties
   {:title "uuid"
    :description "UUID formatted string"
    :error/message "should be an uuid"
    :gen/gen (sg/uuid)
    :decode/string parse-uuid
    :decode/json parse-uuid
    :encode/string str
    :encode/json str
    ::oapi/type "string"
    ::oapi/format "uuid"}})

(def email-re #"[a-zA-Z0-9_.+-\\\\]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (if (string? s)
    (re-matches email-re s)
    nil))

(defn email-string?
  [s]
  (and (string? s)
       (re-seq email-re s)))

(register! ::email
  {:type :string
   :pred email-string?
   :property-pred
   (fn [{:keys [max] :as props}]
     (if (some? max)
       (fn [value]
         (<= (count value) max))
       (constantly true)))

   :type-properties
   {:title "email"
    :description "string with valid email address"
    :error/code "errors.invalid-email"
    :gen/gen (sg/email)
    :decode/string (fn [v] (or (parse-email v) v))
    :decode/json (fn [v] (or (parse-email v) v))
    ::oapi/type "string"
    ::oapi/format "email"}})

(def xf:filter-word-strings
  (comp
   (filter string?)
   (remove str/empty?)
   (remove str/blank?)))

;; NOTE: this is general purpose set spec and should be used over the other

(register! ::set
  {:type :set
   :min 0
   :max 1
   :compile
   (fn [{:keys [kind max min] :as props} children _]
     (let [kind  (or (last children) kind)

           pred
           (cond
             (fn? kind)  kind
             (nil? kind) any?
             :else       (validator kind))

           pred
           (cond
             (and max min)
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= min size max)
                      (every? pred value))))

             min
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= min size)
                      (every? pred value))))

             max
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= size max)
                      (every? pred value))))

             :else
             (fn [value]
               (every? pred value)))


           decode-string-child
           (decoder kind string-transformer)

           decode-string
           (fn [v]
             (let [v (if (string? v) (str/split v #"[\s,]+") v)
                   x (comp xf:filter-word-strings (map decode-string-child))]
               (into #{} x v)))

           decode-json-child
           (decoder kind json-transformer)

           decode-json
           (fn [v]
             (let [v (if (string? v) (str/split v #"[\s,]+") v)
                   x (comp xf:filter-word-strings (map decode-json-child))]
               (into #{} x v)))

           encode-string-child
           (encoder kind string-transformer)

           encode-string
           (fn [o]
             (if (set? o)
               (str/join ", " (map encode-string-child o))
               o))

           encode-json
           (fn [o]
             (if (set? o)
               (vec o)
               o))]


       {:pred pred
        :type-properties
        {:title "set"
         :description "Set of Strings"
         :error/message "should be a set of strings"
         :gen/gen (-> kind sg/generator sg/set)
         :decode/string decode-string
         :decode/json decode-json
         :encode/string encode-string
         :encode/json encode-json
         ::oapi/type "array"
         ::oapi/format "set"
         ::oapi/items {:type "string"}
         ::oapi/unique-items true}}))})


(register! ::vec
  {:type :vector
   :min 0
   :max 1
   :compile
   (fn [{:keys [kind max min] :as props} children _]
     (let [kind  (or (last children) kind)
           pred
           (cond
             (fn? kind)  kind
             (nil? kind) any?
             :else       (validator kind))

           pred
           (cond
             (and max min)
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= min size max)
                      (every? pred value))))

             min
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= min size)
                      (every? pred value))))

             max
             (fn [value]
               (let [size (count value)]
                 (and (set? value)
                      (<= size max)
                      (every? pred value))))

             :else
             (fn [value]
               (every? pred value)))

           decode-string-child
           (decoder kind string-transformer)

           decode-json-child
           (decoder kind json-transformer)

           decode-string
           (fn [v]
             (let [v (if (string? v) (str/split v #"[\s,]+") v)
                   x (comp xf:filter-word-strings (map decode-string-child))]
               (into #{} x v)))

           decode-json
           (fn [v]
             (let [v (if (string? v) (str/split v #"[\s,]+") v)
                   x (comp xf:filter-word-strings (map decode-json-child))]
               (into #{} x v)))

           encode-string-child
           (encoder kind string-transformer)

           encode-string
           (fn [o]
             (if (vector? o)
               (str/join ", " (map encode-string-child o))
               o))]

       {:pred pred
        :type-properties
        {:title "set"
         :description "Set of Strings"
         :error/message "should be a set of strings"
         :gen/gen (-> kind sg/generator sg/set)
         :decode/string decode-string
         :decode/json decode-json
         :encode/string encode-string
         ::oapi/type "array"
         ::oapi/format "set"
         ::oapi/items {:type "string"}
         ::oapi/unique-items true}}))})

(register! ::set-of-strings
  {:type ::set-of-strings
   :pred #(and (set? %) (every? string? %))
   :type-properties
   {:title "set[string]"
    :description "Set of Strings"
    :error/message "should be a set of strings"
    :gen/gen (-> :string sg/generator sg/set)
    :decode/string (fn [v]
                     (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                       (into #{} xf:filter-word-strings v)))
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string"}
    ::oapi/unique-items true}})

(register! ::set-of-keywords
  {:type ::set-of-keywords
   :pred #(and (set? %) (every? keyword? %))
   :type-properties
   {:title "set[string]"
    :description "Set of Strings"
    :error/message "should be a set of strings"
    :gen/gen (-> :keyword sg/generator sg/set)
    :decode/string (fn [v]
                     (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                       (into #{} (comp xf:filter-word-strings (map keyword)) v)))
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "keyword"}
    ::oapi/unique-items true}})

(register! ::set-of-uuid
  {:type ::set-of-uuid
   :pred #(and (set? %) (every? uuid? %))
   :type-properties
   {:title "set[uuid]"
    :description "Set of UUID"
    :error/message "should be a set of UUID instances"
    :gen/gen (-> ::uuid sg/generator sg/set)
    :decode/string (fn [v]
                     (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                       (into #{} (keep parse-uuid) v)))
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "uuid"}
    ::oapi/unique-items true}})

(register! ::coll-of-uuid
  {:type ::set-of-uuid
   :pred (partial every? uuid?)
   :type-properties
   {:title "[uuid]"
    :description "Coll of UUID"
    :error/message "should be a coll of UUID instances"
    :gen/gen (-> ::uuid sg/generator sg/set)
    :decode/string (fn [v]
                     (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                       (into [] (keep parse-uuid) v)))
    ::oapi/type "array"
    ::oapi/format "array"
    ::oapi/items {:type "string" :format "uuid"}
    ::oapi/unique-items false}})

(register! ::one-of
  {:type ::one-of
   :min 1
   :max 1
   :compile (fn [props children _]
              (let [options (into #{} (last children))
                    format  (:format props "keyword")
                    decode  (if (= format "keyword")
                              keyword
                              identity)]
                {:pred #(contains? options %)
                 :type-properties
                 {:title "one-of"
                  :description "One of the Set"
                  :gen/gen (sg/elements options)
                  :decode/string decode
                  :decode/json decode
                  ::oapi/type "string"
                  ::oapi/format (:format props "keyword")}}))})

;; Integer/MAX_VALUE
(def max-safe-int 2147483647)
;; Integer/MIN_VALUE
(def min-safe-int -2147483648)

(defn parse-long
  [v]
  (or (ignoring
       (if (string? v)
         (c/parse-long v)
         v))
      v))

(def type:int
  {:type :int
   :min 0
   :max 0
   :compile
   (fn [{:keys [max min] :as props} _ _]
     (let [pred int?
           pred (if (some? min)
                  (fn [v]
                    (and (>= v min)
                         (pred v)))
                  pred)
           pred (if (some? max)
                  (fn [v]
                    (and (>= max v)
                         (pred v)))
                  pred)]

       {:pred pred
        :type-properties
        {:title "int"
         :description "int"
         :error/message "expected to be int/long"
         :error/code "errors.invalid-integer"
         :gen/gen (sg/small-int :max max :min min)
         :decode/string parse-long
         :decode/json parse-long
         ::oapi/type "integer"
         ::oapi/format "int64"}}))})

(defn parse-double
  [v]
  (or (ignoring
       (if (string? v)
         (c/parse-double v)
         v))
      v))

(def type:double
  {:type :double
   :min 0
   :max 0
   :compile
   (fn [{:keys [max min] :as props} _ _]
     (let [pred double?
           pred (if (some? min)
                  (fn [v]
                    (and (>= v min)
                         (pred v)))
                  pred)
           pred (if (some? max)
                  (fn [v]
                    (and (>= max v)
                         (pred v)))
                  pred)]

       {:pred pred
        :type-properties
        {:title "doble"
         :description "double number"
         :error/message "expected to be double"
         :error/code "errors.invalid-double"
         :gen/gen (sg/small-double :max max :min min)
         :decode/string parse-double
         :decode/json parse-double
         ::oapi/type "number"
         ::oapi/format "double"}}))})

(def type:number
  {:type :number
   :min 0
   :max 0
   :compile
   (fn [{:keys [max min] :as props} _ _]
     (let [pred number?
           pred (if (some? min)
                  (fn [v]
                    (and (>= v min)
                         (pred v)))
                  pred)
           pred (if (some? max)
                  (fn [v]
                    (and (>= max v)
                         (pred v)))
                  pred)

           gen  (sg/one-of
                 (sg/small-int :max max :min min)
                 (sg/small-double :max max :min min))]

       {:pred pred
        :type-properties
        {:title "int"
         :description "int"
         :error/message "expected to be number"
         :error/code "errors.invalid-number"
         :gen/gen gen
         :decode/string parse-double
         :decode/json parse-double
         ::oapi/type "number"}}))})

(register! ::int type:int)
(register! ::double type:double)
(register! ::number type:number)

(register! ::safe-int [::int {:max max-safe-int :min min-safe-int}])
(register! ::safe-double [::double {:max max-safe-int :min min-safe-int}])
(register! ::safe-number [::number {:max max-safe-int :min min-safe-int}])

(defn parse-boolean
  [v]
  (if (string? v)
    (case v
      ("true" "t" "1") true
      ("false" "f" "0") false
      v)
    v))

(def type:boolean
  {:type :boolean
   :pred boolean?
   :type-properties
   {:title "boolean"
    :description "boolean"
    :error/message "expected boolean"
    :error/code "errors.invalid-boolean"
    :gen/gen sg/boolean
    :decode/string parse-boolean
    :decode/json parse-boolean
    :encode/string str
    ::oapi/type "boolean"}})

(register! ::boolean type:boolean)

(def type:contains-any
  {:type ::contains-any
   :min 1
   :max 1
   :compile (fn [props children _]
              (let [choices (last children)
                    pred    (if (:strict props)
                              #(some (fn [prop]
                                       (some? (get % prop)))
                                     choices)
                              #(some (fn [prop]
                                       (contains? % prop))
                                     choices))]
                {:pred pred
                 :type-properties
                 {:title "contains"
                  :description "contains predicate"}}))})

(register! ::contains-any type:contains-any)

(def type:inst
  {:type ::inst
   :pred inst?
   :type-properties
   {:title "inst"
    :description "Satisfies Inst protocol"
    :error/message "should be an instant"
    :gen/gen (->> (sg/small-int)
                  (sg/fmap (fn [v] (tm/parse-instant v))))

    :decode/string tm/parse-instant
    :encode/string tm/format-instant
    :decode/json tm/parse-instant
    :encode/json tm/format-instant
    ::oapi/type "string"
    ::oapi/format "iso"}})

(register! ::inst type:inst)

(register! ::fn [:schema fn?])

;; FIXME: deprecated, replace with ::text

(register! ::word-string
  {:type ::word-string
   :pred #(and (string? %) (not (str/blank? %)))
   :property-pred (m/-min-max-pred count)
   :type-properties
   {:title "string"
    :description "string"
    :error/message "expected a non empty string"
    :gen/gen (sg/word-string)
    ::oapi/type "string"
    ::oapi/format "string"}})


(defn decode-uri
  [val]
  (if (u/uri? val)
    val
    (-> val str/trim u/uri)))

(register! ::uri
  {:type ::uri
   :pred u/uri?
   :property-pred
   (fn [{:keys [min max prefix] :as props}]
     (if (seq props)
       (fn [value]
         (let [value  (str value)
               size   (count value)]

           (and
            (cond
              (and min max)
              (<= min size max)

              min
              (<= min size)

              max
              (<= size max))

            (cond
              (d/regexp? prefix)
              (some? (re-seq prefix value))

              :else
              true))))

       (constantly true)))

   :type-properties
   {:title "uri"
    :description "URI formatted string"
    :error/code "errors.invalid-uri"
    :gen/gen (sg/uri)
    :decode/string decode-uri
    :decode/json decode-uri
    ::oapi/type "string"
    ::oapi/format "uri"}})

(register! ::text
  {:type :string
   :pred #(and (string? %) (not (str/blank? %)))
   :property-pred
   (fn [{:keys [min max] :as props}]
     (if (seq props)
       (fn [value]
         (let [size (count value)]
           (cond
             (and min max)
             (<= min size max)

             min
             (<= min size)

             max
             (<= size max))))
       (constantly true)))

   :type-properties
   {:title "string"
    :description "not whitespace string"
    :gen/gen (sg/word-string)
    :error/code "errors.invalid-text"
    :error/fn
    (fn [{:keys [value schema]}]
      (let [{:keys [max min] :as props} (properties schema)]
        (cond
          (and (string? value)
               (number? max)
               (> (count value) max))
          ["errors.field-max-length" max]

          (and (string? value)
               (number? min)
               (< (count value) min))
          ["errors.field-min-length" min]

          (and (string? value)
               (str/blank? value))
          "errors.field-not-all-whitespace")))}})

(register! ::password
  {:type :string
   :pred
   (fn [value]
     (and (string? value)
          (>= (count value) 8)
          (not (str/blank? value))))
   :type-properties
   {:title "password"
    :gen/gen (->> (sg/word-string)
                  (sg/filter #(>= (count %) 8)))
    :error/code "errors.password-too-short"
    ::oapi/type "string"
    ::oapi/format "password"}})


;; ---- PREDICATES

(def valid-safe-number?
  (lazy-validator ::safe-number))

(def check-safe-int!
  (check-fn ::safe-int))

(def check-set-of-strings!
  (check-fn ::set-of-strings))

(def check-email!
  (check-fn ::email))

(def check-coll-of-uuid!
  (check-fn ::coll-of-uuid))

(def check-set-of-uuid!
  (check-fn ::set-of-uuid))

(def check-set-of-emails!
  (check-fn [::set ::email]))
