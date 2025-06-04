;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema
  (:refer-clojure :exclude [deref merge parse-uuid parse-long parse-double parse-boolean type keys])
  #?(:cljs (:require-macros [app.common.schema :refer [ignoring]]))
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
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

(def default-options
  {:registry sr/default-registry})

(defn schema?
  [o]
  (m/schema? o))

(defn type
  [s]
  (m/-type s))

(defn properties
  [s]
  (m/properties s))

(defn type-properties
  [s]
  (m/type-properties s))

(defn schema
  [s]
  (if (schema? s)
    s
    (m/schema s default-options)))

(defn validate
  [s value]
  (m/validate s value default-options))

(defn valid?
  [s value]
  (try
    (m/validate s value default-options)
    (catch #?(:clj Throwable :cljs :default) _cause
      false)))

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
  ([schema]
   (mu/optional-keys schema nil default-options))
  ([schema keys]
   (mu/optional-keys schema keys default-options)))

(defn required-keys
  ([schema]
   (mu/required-keys schema nil default-options))
  ([schema keys]
   (mu/required-keys schema keys default-options)))

(defn transformer
  [& transformers]
  (apply mt/transformer transformers))

(defn entries
  "Get map entires of a map schema"
  [schema]
  (m/entries schema default-options))

(def ^:private xf:map-key
  (map key))

(defn keys
  "Given a map schema, return all keys as set"
  [schema]
  (->> (entries schema)
       (into #{} xf:map-key)))


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

(defn -transform-map-keys
  ([f]
   (let [xform (map (fn [[k v]] [(f k) v]))]
     #(cond->> % (map? %) (into (empty %) xform))))
  ([ks f]
   (let [xform (map (fn [[k v]] [(cond-> k (contains? ks k) f) v]))]
     #(cond->> % (map? %) (into (empty %) xform)))))

(defn json-transformer
  []
  (let [map-of-key-decoders (mt/-string-decoders)]
    (mt/transformer
     {:name :json
      :decoders (-> (mt/-json-decoders)
                    (assoc :map-of {:compile (fn [schema _]
                                               (let [key-schema (some-> schema (m/children) (first))]
                                                 (or (some-> key-schema (m/type) map-of-key-decoders
                                                             (mt/-interceptor schema {}) (m/-intercepting)
                                                             (m/-comp m/-keyword->string)
                                                             (mt/-transform-if-valid key-schema)
                                                             (-transform-map-keys))
                                                     (-transform-map-keys m/-keyword->string))))}))
      :encoders (mt/-json-encoders)}
     (mt/collection-transformer))))

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
  (let [vfn (delay (validator s))]
    (fn [v] (@vfn v))))

(defn lazy-explainer
  [s]
  (let [vfn (delay (explainer (if (delay? s) (deref s) s)))]
    (fn [v] (@vfn v))))

(defn lazy-decoder
  [s transformer]
  (let [vfn (delay (decoder (if (delay? s) (deref s) s) transformer))]
    (fn [v] (@vfn v))))

(defn decode-fn
  [s transformer]
  (let [vfn (delay (decoder (if (delay? s) (deref s) s) transformer))]
    (fn [v] (@vfn v))))

(defn humanize-explain
  "Returns a string representation of the explain data structure"
  [{:keys [errors value]} & {:keys [length level]}]
  (let [errors (mapv #(update % :schema form) errors)]
    (with-out-str
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

(defn check-fn
  "Create a predefined check function"
  [s & {:keys [hint type code]}]
  (let [s          (schema s)
        validator* (delay (m/validator s))
        explainer* (delay (m/explainer s))
        hint       (or ^boolean hint "check error")
        type       (or ^boolean type :assertion)
        code       (or ^boolean code :data-validation)]

    (fn [value]
      (let [validate-fn @validator*]
        (when-not ^boolean (validate-fn value)
          (let [explain-fn @explainer*
                explain    (explain-fn value)]
            (throw (ex-info hint {:type type
                                  :code code
                                  :hint hint
                                  ::explain explain}))))
        value))))

(defn check
  "A helper intended to be used on assertions for validate/check the
  schema over provided data. Raises an assertion exception.

  Use only on non-performance sensitive code, because it creates the
  check-fn instance all the time it is invoked."
  [s value & {:as opts}]
  (let [check-fn (check-fn s opts)]
    (check-fn value)))

(defn type-schema
  [& {:as params}]
  (m/-simple-schema params))

(defn coll-schema
  [& {:as params}]
  (m/-collection-schema params))

(defn register!
  ([params]
   (cond
     (map? params)
     (let [mdata (meta params)
           type  (or (get mdata ::id)
                     (get mdata ::type)
                     (get params :type))]
       (assert (qualified-keyword? type) "expected qualified keyword for `type`")
       (let [s (m/-simple-schema params)]
         (swap! sr/registry assoc type s)
         s))

     (vector? params)
     (let [mdata (meta params)
           type  (or (get mdata ::id)
                     (get mdata ::type))]
       (assert (qualified-keyword? type) "expected qualified keyword to be on metadata")
       (swap! sr/registry assoc type params)
       params)

     (m/into-schema? params)
     (let [type (m/-type params)]
       (swap! sr/registry assoc type params)
       params)

     :else
     (throw (ex-info "Invalid Arguments" {}))))

  ([type params]
   (swap! sr/registry assoc type params)
   params))

;; --- BUILTIN SCHEMAS

(register! :merge (mu/-merge))
(register! :union (mu/-union))

(defn- parse-uuid
  [s]
  (if (uuid? s)
    s
    (if (str/empty? s)
      nil
      (try
        (uuid/parse s)
        (catch #?(:clj Exception :cljs :default) _cause
          s)))))

(defn- encode-uuid
  [v]
  (if (uuid? v)
    (str v)
    v))

(register!
 {:type ::uuid
  :pred uuid?
  :type-properties
  {:title "uuid"
   :description "UUID formatted string"
   :error/message "should be an uuid"
   :gen/gen (sg/uuid)
   :decode/string parse-uuid
   :decode/json parse-uuid
   :encode/string encode-uuid
   :encode/json encode-uuid
   ::oapi/type "string"
   ::oapi/format "uuid"}})

(def email-re #"[a-zA-Z0-9_.+-\\\\]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (if (string? s)
    (first (re-seq email-re s))
    nil))

(defn email-string?
  [s]
  (and (string? s)
       (re-seq email-re s)))

(register!
 {:type ::email
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

(register!
 (coll-schema
  :type ::set
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

          decode
          (fn [v]
            (cond
              (string? v)
              (let [v  (str/split v #"[\s,]+")]
                (into #{} xf:filter-word-strings v))

              (set? v)
              v

              (coll? v)
              (into #{} v)

              :else
              v))

          encode-string-child
          (encoder kind string-transformer)

          encode-string
          (fn [o]
            (if (set? o)
              (str/join ", " (map encode-string-child o))
              o))]

      {:pred pred
       :empty #{}
       :type-properties
       {:title "set"
        :description "Set of Strings"
        :error/message "should be a set of strings"
        :gen/gen (-> kind sg/generator sg/set)
        :decode/string decode
        :decode/json decode
        :encode/string encode-string
        :encode/json identity
        ::oapi/type "array"
        ::oapi/format "set"
        ::oapi/items {:type "string"}
        ::oapi/unique-items true}}))))

(register!
 (coll-schema
  :type ::vec
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

          decode
          (fn [v]
            (cond
              (string? v)
              (let [v (str/split v #"[\s,]+")]
                (into [] xf:filter-word-strings v))

              (vector? v)
              v

              (coll? v)
              (into [] v)

              :else
              v))

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
        :decode/string decode
        :decode/json decode
        :encode/string encode-string
        :encode/json identity
        ::oapi/type "array"
        ::oapi/format "set"
        ::oapi/items {:type "string"}
        ::oapi/unique-items true}}))))

(register!
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

(register!
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

(register!
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

(register!
 {:type ::coll-of-uuid
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

(register!
 {:type ::one-of
  :min 1
  :max 1
  :compile
  (fn [props children _]
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

(register!
 {:type ::int
  :min 0
  :max 0
  :compile
  (fn [{:keys [max min] :as props} _ _]
    (let [pred int?
          pred (if (some? min)
                 (fn [v]
                   (and (pred v)
                        (>= v min)))
                 pred)
          pred (if (some? max)
                 (fn [v]
                   (and (pred v)
                        (>= max v)))
                 pred)

          gen (or (get props :gen/gen)
                  (sg/small-int :max max :min min))]

      {:pred pred
       :type-properties
       {:title "int"
        :description "int"
        :error/message "expected to be int/long"
        :error/code "errors.invalid-integer"
        :gen/gen gen
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

(register!
 {:type ::double
  :compile
  (fn [{:keys [max min] :as props} _ _]
    (let [pred double?
          pred (if (some? min)
                 (fn [v]
                   (and (pred v)
                        (>= v min)))
                 pred)
          pred (if (some? max)
                 (fn [v]
                   (and (pred v)
                        (>= max v)))
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

(register!
 {:type ::number
  :compile
  (fn [{:keys [max min] :as props} _ _]
    (let [pred number?
          pred (if (some? min)
                 (fn [v]
                   (and (pred v)
                        (>= v min)))
                 pred)
          pred (if (some? max)
                 (fn [v]
                   (and (pred v)
                        (>= max v)))
                 pred)

          gen  (or (get props :gen/gen)
                   (sg/one-of
                    (sg/small-int :max max :min min)
                    (->> (sg/small-double :max max :min min)
                         (sg/fmap #(mth/precision % 2)))))]

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

(register! ::safe-int [::int {:max max-safe-int :min min-safe-int}])
(register! ::safe-double [::double {:max max-safe-int :min min-safe-int}])
(register! ::safe-number [::number {:gen/gen (sg/small-double)
                                    :max max-safe-int
                                    :min min-safe-int}])

(defn parse-boolean
  [v]
  (if (string? v)
    (case v
      ("true" "t" "1") true
      ("false" "f" "0") false
      v)
    v))

(register!
 {:type ::boolean
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

(register!
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
                {:title "contains any"
                 :description "contains predicate"}}))})

(register!
 {:type ::inst
  :pred inst?
  :type-properties
  {:title "inst"
   :description "Satisfies Inst protocol"
   :error/message "should be an instant"
   :gen/gen (->> (sg/small-int :min 0 :max 100000)
                 (sg/fmap (fn [v] (tm/parse-instant v))))

   :decode/string tm/parse-instant
   :encode/string tm/format-instant
   :decode/json tm/parse-instant
   :encode/json tm/format-instant
   ::oapi/type "string"
   ::oapi/format "iso"}})

(register!
 {:type ::timestamp
  :pred inst?
  :type-properties
  {:title "inst"
   :description "Satisfies Inst protocol"
   :error/message "should be an instant"
   :gen/gen (->> (sg/small-int)
                 (sg/fmap (fn [v] (tm/parse-instant v))))
   :decode/string tm/parse-instant
   :encode/string inst-ms
   :decode/json tm/parse-instant
   :encode/json inst-ms
   ::oapi/type "string"
   ::oapi/format "number"}})

(register!
 {:type ::fn
  :pred fn?})

;; FIXME: deprecated, replace with ::text

(register!
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

(register!
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

(register!
 {:type ::text
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
   :error/fn
   (fn [{:keys [value schema]}]
     (let [{:keys [max min] :as props} (properties schema)]
       (cond
         (and (string? value)
              (number? max)
              (> (count value) max))
         {:code ["errors.field-max-length" max]}

         (and (string? value)
              (number? min)
              (< (count value) min))
         {:code ["errors.field-min-length" min]}

         (and (string? value)
              (str/empty? value))
         {:code "errors.field-missing"}

         (and (string? value)
              (str/blank? value))
         {:code "errors.field-not-all-whitespace"}

         :else
         {:code "errors.invalid-text"})))}})

(register!
 {:type ::password
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

#?(:clj
   (register!
    {:type ::agent
     :pred #(instance? clojure.lang.Agent %)
     :type-properties
     {:title "agent"
      :description "instance of clojure agent"}}))

(register! ::any (mu/update-properties :any assoc :gen/gen sg/any))

;; ---- PREDICATES

(def valid-safe-number?
  (lazy-validator ::safe-number))

(def valid-text?
  (validator ::text))

(def check-safe-int
  (check-fn ::safe-int))

(def check-set-of-strings
  (check-fn ::set-of-strings))

(def check-email
  (check-fn ::email))

(def check-uuid
  (check-fn ::uuid :hint "expected valid uuid instance"))

(def check-string
  (check-fn :string :hint "expected string"))

(def check-coll-of-uuid
  (check-fn ::coll-of-uuid))

(def check-set-of-uuid
  (check-fn ::set-of-uuid))

(def check-set-of-emails
  (check-fn [::set ::email]))
