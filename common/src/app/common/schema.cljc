;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema
  (:refer-clojure :exclude [deref merge parse-uuid])
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
  (-get-schema [_])
  (-get-validator [_])
  (-get-explainer [_])
  (-get-decoder [_])
  (-get-encoder [_])
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

(defn lazy-schema?
  [s]
  (satisfies? ILazySchema s))

(defn schema
  [s]
  (if (lazy-schema? s)
    (-get-schema s)
    (m/schema s default-options)))

(defn validate
  [s value]
  (if (lazy-schema? s)
    (-validate s value)
    (m/validate s value default-options)))

(defn explain
  [s value]
  (if (lazy-schema? s)
    (-explain s value)
    (m/explain s value default-options)))

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

(def default-transformer
  (let [default-decoder
        {:compile (fn [s _registry]
                    (let [props (m/type-properties s)]
                      (or (::oapi/decode props)
                          (::decode props))))}

        default-encoder
        {:compile (fn [s _]
                    (let [props (m/type-properties s)]
                      (or (::oapi/encode props)
                          (::encode props))))}

        coders {:vector mt/-sequential-or-set->vector
                :sequential mt/-sequential-or-set->seq
                :set mt/-sequential->set
                :tuple mt/-sequential->vector}]

    (mt/transformer
     {:name :penpot
      :default-decoder default-decoder
      :default-encoder default-encoder}
     {:name :string
      :decoders (mt/-string-decoders)
      :encoders (mt/-string-encoders)}
     {:name :collections
      :decoders coders
      :encoders coders})))

(defn encode
  ([s val transformer]
   (m/encode s val default-options transformer))
  ([s val options transformer]
   (m/encode s val options transformer)))

(defn decode
  ([s val]
   (m/decode s val default-options default-transformer))
  ([s val transformer]
   (m/decode s val default-options transformer))
  ([s val options transformer]
   (m/decode s val options transformer)))

(defn validator
  [s]
  (if (lazy-schema? s)
    (-get-validator s)
    (-> s schema m/validator)))

(defn explainer
  [s]
  (if (lazy-schema? s)
    (-get-explainer s)
    (-> s schema m/explainer)))

(defn encoder
  ([s]
   (if (lazy-schema? s)
     (-get-decoder s)
     (encoder s default-options default-transformer)))
  ([s transformer]
   (m/encoder s default-options transformer))
  ([s options transformer]
   (m/encoder s options transformer)))

(defn decoder
  ([s]
   (if (lazy-schema? s)
     (-get-decoder s)
     (decoder s default-options default-transformer)))
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
  ([s] (lazy-decoder s default-transformer))
  ([s transformer]
   (let [vfn (delay (decoder (if (delay? s) (deref s) s) transformer))]
     (fn [v] (@vfn v)))))

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

(defn simple-schema
  [& {:keys [pred] :as options}]
  (cond-> options
    (contains? options :type-properties)
    (update :type-properties (fn [props]
                               (cond-> props
                                 (contains? props :decode/string)
                                 (update :decode/string (fn [decode-fn]
                                                          (fn [s]
                                                            (if (pred s)
                                                              s
                                                              (or (ignoring (decode-fn s)) s)))))
                                 (contains? props ::decode)
                                 (update ::decode (fn [decode-fn]
                                                    (fn [s]
                                                      (if (pred s)
                                                        s
                                                        (or (ignoring (decode-fn s)) s))))))))
    :always
    (m/-simple-schema)))

(defn lookup
  "Lookups schema from registry."
  ([s] (lookup sr/default-registry s))
  ([registry s] (schema (mr/schema registry s))))

(defn fast-check!
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

(declare define)

(defn check-fn
  "Create a predefined check function"
  [s]
  (let [schema (if (lazy-schema? s) s (define s))]
    (partial fast-check! schema)))

(defn check!
  "A helper intended to be used on assertions for validate/check the
  schema over provided data. Raises an assertion exception, should be
  used together with `dm/assert!` or `dm/verify!`."
  [s value]
  (if (lazy-schema? s)
    (fast-check! s value)
    (do
      (when-not ^boolean (m/validate s value default-options)
        (let [hint    (d/nilv dm/*assert-context* "check error")
              explain (explain s value)]
          (throw (ex-info hint {:type :assertion
                                :code :data-validation
                                :hint hint
                                ::explain explain}))))
      true)))


(defn fast-validate!
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
       (throw (ex-info hint options))))))

(defn validate-fn
  "Create a predefined validate function that raises an expception"
  [s]
  (let [schema (if (lazy-schema? s) s (define s))]
    (partial fast-validate! schema)))

(defn validate!
  "A generic validation function for predefined schemas."
  ([s value] (validate! s value nil))
  ([s value options]
   (if (lazy-schema? s)
     (fast-validate! s value options)
     (when-not ^boolean (m/validate s value default-options)
       (let [explain (explain s value)
             options (into {:type :validation
                            :code :data-validation
                            ::explain explain}
                           options)
             hint    (get options :hint "schema validation error")]
         (throw (ex-info hint options)))))))

;; FIXME: revisit
(defn conform!
  [schema value]
  (assert (lazy-schema? schema) "expected `schema` to satisfy ILazySchema protocol")
  (let [params (-decode schema value)]
    (fast-validate! schema params nil)
    params))

(defn register! [type s]
  (let [s (if (map? s) (simple-schema s) s)]
    (swap! sr/registry assoc type s)
    nil))

(defn define
  "Create ans instance of ILazySchema"
  [s & {:keys [transformer] :as options}]
  (let [schema      (delay (schema s))
        validator   (delay (m/validator @schema))
        explainer   (delay (m/explainer @schema))

        options     (c/merge default-options (dissoc options :transformer))
        transformer (or transformer default-transformer)
        decoder     (delay (m/decoder @schema options transformer))
        encoder     (delay (m/encoder @schema options transformer))]

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
      (-get-schema [_]
        @schema)
      (-get-validator [_]
        @validator)
      (-get-explainer [_]
        @explainer)
      (-get-encoder [_]
        @encoder)
      (-get-decoder [_]
        @decoder)
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
    ::oapi/type "string"
    ::oapi/format "uuid"
    ::oapi/decode parse-uuid}})

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
    ::oapi/type "string"
    ::oapi/format "email"
    ::oapi/decode
    (fn [v]
      (or (parse-email v) v))}})

(def non-empty-strings-xf
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
   (fn [{:keys [coerce kind max min] :as props} children _]
     (let [xform (if coerce
                   (comp non-empty-strings-xf (map coerce))
                   non-empty-strings-xf)
           kind  (or (last children) kind)
           pred  (cond
                   (fn? kind)  kind
                   (nil? kind) any?
                   :else       (validator kind))

           pred (cond
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
                    (every? pred value)))]

       {:pred pred
        :type-properties
        {:title "set"
         :description "Set of Strings"
         :error/message "should be a set of strings"
         :gen/gen (-> kind sg/generator sg/set)
         ::oapi/type "array"
         ::oapi/format "set"
         ::oapi/items {:type "string"}
         ::oapi/unique-items true
         ::oapi/decode (fn [v]
                         (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                           (into #{} xform v)))}}))})


(register! ::vec
  {:type :vector
   :min 0
   :max 1
   :compile
   (fn [{:keys [coerce kind max min] :as props} children _]
     (let [xform (if coerce
                   (comp non-empty-strings-xf (map coerce))
                   non-empty-strings-xf)

           kind  (or (last children) kind)
           pred  (cond
                   (fn? kind)  kind
                   (nil? kind) any?
                   :else       (validator kind))

           pred (cond
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
                    (every? pred value)))]

       {:pred pred
        :type-properties
        {:title "set"
         :description "Set of Strings"
         :error/message "should be a set of strings"
         :gen/gen (-> kind sg/generator sg/set)
         ::oapi/type "array"
         ::oapi/format "set"
         ::oapi/items {:type "string"}
         ::oapi/unique-items true
         ::oapi/decode (fn [v]
                         (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                           (into [] xform v)))}}))})


(register! ::set-of-strings
  {:type ::set-of-strings
   :pred #(and (set? %) (every? string? %))
   :type-properties
   {:title "set[string]"
    :description "Set of Strings"
    :error/message "should be a set of strings"
    :gen/gen (-> :string sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string"}
    ::oapi/unique-items true
    ::oapi/decode (fn [v]
                    (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                      (into #{} non-empty-strings-xf v)))}})

(register! ::set-of-keywords
  {:type ::set-of-keywords
   :pred #(and (set? %) (every? keyword? %))
   :type-properties
   {:title "set[string]"
    :description "Set of Strings"
    :error/message "should be a set of strings"
    :gen/gen (-> :keyword sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "keyword"}
    ::oapi/unique-items true
    ::oapi/decode (fn [v]
                    (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                      (into #{} (comp non-empty-strings-xf (map keyword)) v)))}})

(register! ::set-of-emails
  {:type ::set-of-emails
   :pred #(and (set? %) (every? string? %))
   :type-properties
   {:title "set[email]"
    :description "Set of Emails"
    :error/message "should be a set of emails"
    :gen/gen (-> ::email sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "email"}
    ::oapi/unique-items true
    ::decode (fn [v]
               (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                 (into #{} (keep parse-email) v)))}})

(register! ::set-of-uuid
  {:type ::set-of-uuid
   :pred #(and (set? %) (every? uuid? %))
   :type-properties
   {:title "set[uuid]"
    :description "Set of UUID"
    :error/message "should be a set of UUID instances"
    :gen/gen (-> ::uuid sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "set"
    ::oapi/items {:type "string" :format "uuid"}
    ::oapi/unique-items true
    ::oapi/decode (fn [v]
                    (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                      (into #{} (keep parse-uuid) v)))}})

(register! ::coll-of-uuid
  {:type ::set-of-uuid
   :pred (partial every? uuid?)
   :type-properties
   {:title "[uuid]"
    :description "Coll of UUID"
    :error/message "should be a coll of UUID instances"
    :gen/gen (-> ::uuid sg/generator sg/set)
    ::oapi/type "array"
    ::oapi/format "array"
    ::oapi/items {:type "string" :format "uuid"}
    ::oapi/unique-items false
    ::oapi/decode (fn [v]
                    (let [v (if (string? v) (str/split v #"[\s,]+") v)]
                      (into [] (keep parse-uuid) v)))}})

(register! ::one-of
  {:type ::one-of
   :min 1
   :max 1
   :compile (fn [props children _]
              (let [options (into #{} (last children))
                    format  (:format props "keyword")]
                {:pred #(contains? options %)
                 :type-properties
                 {:title "one-of"
                  :description "One of the Set"
                  :gen/gen (sg/elements options)
                  ::oapi/type "string"
                  ::oapi/format (:format props "keyword")
                  ::oapi/decode (if (= format "keyword")
                                  keyword
                                  identity)}}))})

;; Integer/MAX_VALUE
(def max-safe-int 2147483647)
;; Integer/MIN_VALUE
(def min-safe-int -2147483648)

(register! ::safe-int
  {:type ::safe-int
   :pred #(and (int? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "int"
    :description "Safe Integer"
    :error/message "expected to be int in safe range"
    :gen/gen (sg/small-int)
    ::oapi/type "integer"
    ::oapi/format "int64"
    ::oapi/decode (fn [s]
                    (if (string? s)
                      (parse-long s)
                      s))}})

(register! ::safe-number
  {:type ::safe-number
   :pred #(and (number? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "number"
    :description "Safe Number"
    :error/message "expected to be number in safe range"
    :gen/gen (sg/one-of (sg/small-int)
                        (sg/small-double))
    ::oapi/type "number"
    ::oapi/format "double"
    ::oapi/decode (fn [s]
                    (if (string? s)
                      (parse-double s)
                      s))}})

(register! ::safe-double
  {:type ::safe-double
   :pred #(and (double? %) (>= max-safe-int %) (>= % min-safe-int))
   :type-properties
   {:title "number"
    :description "Safe Number"
    :error/message "expected to be number in safe range"
    :gen/gen (sg/small-double)
    ::oapi/type "number"
    ::oapi/format "double"
    ::oapi/decode (fn [s]
                    (if (string? s)
                      (parse-double s)
                      s))}})

(register! ::contains-any
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

(register! ::inst
  {:type ::inst
   :pred inst?
   :type-properties
   {:title "inst"
    :description "Satisfies Inst protocol"
    :error/message "expected to be number in safe range"
    :gen/gen (->> (sg/small-int)
                  (sg/fmap (fn [v] (tm/instant v))))
    ::oapi/type "number"
    ::oapi/format "int64"}})

(register! ::fn
  [:schema fn?])

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
    ::oapi/type "string"
    ::oapi/format "uri"
    ::oapi/decode
    (fn [val]
      (if (u/uri? val)
        val
        (-> val str/trim u/uri)))}})

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
  (check-fn ::set-of-emails))
