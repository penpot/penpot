;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.describe
  (:require
   [app.common.exceptions :as ex]
   [cuerdas.core :as str]
   [malli.core :as m]))

(declare describe)
(declare describe*)

(defn -diamond [s] (str "<" s ">"))
(defn -titled [schema] (if-let [t (-> schema m/properties :title)] (str " :: " t "") ""))

(defn -min-max-suffix [schema]
  (let [{:keys [min max]} (-> schema m/properties)]
    (cond
      (and min max) (str " between " min " and " max " inclusive")
      min (str " greater than " min)
      max (str " less than " max)
      :else "")))

(defn -length-suffix [schema]
  (let [{:keys [min max]} (-> schema m/properties)]
    (cond
      (and min max) (str " with length between " min " and " max " inclusive")
      min (str " with length <= " min)
      max (str " with length >= " max)
      :else "")))

(defn -pluralize-times [n]
  (when n
    (if (= 1 n) "time" "times")))

(defn -repeat-suffix [schema]
  (let [{:keys [min max]} (-> schema m/properties)
        min-timez (-pluralize-times min)
        max-timez (-pluralize-times max)]
    (cond
      (and min max) (str " at least " min " " min-timez ", up to " max " " max-timez)
      min (str " at least " min " " min-timez)
      max (str " at most " max " " max-timez)
      :else "")))

(defn -min-max-suffix-number [schema]
  (let [{:keys [min max]} (merge (-> schema m/properties) (-> schema m/type-properties))]
    (cond
      (and min max) (str " between " min " and " max " inclusive")
      min (str " greater than or equal to " min)
      max (str " less than or equal to " max)
      :else "")))

(defmulti visit (fn [name _schema _children _options] name) :default ::default)

(defmethod visit ::default [name schema children {:keys [missing-fn]}]
  (if missing-fn (missing-fn name schema children) nil))

(defmethod visit :ref [_ _schema children _] (pr-str (first children)))

(defmethod visit :> [_ _ [value] _] (str "> " value))
(defmethod visit :>= [_ _ [value] _] (str ">= " value))
(defmethod visit :< [_ _ [value] _] (str "< " value))
(defmethod visit :<= [_ _ [value] _] (str "<= " value))
(defmethod visit := [_ _ [value] _] (str "must equal " value))
(defmethod visit :not= [_ _ [value] _] (str "not equal " value))
(defmethod visit :not [_ _ children _] {:not (last children)})

(defmethod visit :multi [_ s children _]
  (let [dispatcher (or (-> s m/properties :dispatch-description)
                       (-> s m/properties :dispatch))]
    (str "one of "
         (-diamond
          (str/join " | " (map (fn [[title _ shape]] (str title " = " shape)) children)))
         " dispatched by " dispatcher)))

(defn -of-clause [children] (when children (str " of " (first children))))

(defmethod visit :vector [_ schema children _] (str "vector" (-titled schema) (-length-suffix schema) (-of-clause children)))
(defmethod visit :sequential [_ schema children _] (str "sequence" (-titled schema) (-length-suffix schema) (-of-clause children)))
(defmethod visit :set [_ schema children _] (str "set" (-titled schema) (-length-suffix schema) (-of-clause children)))
(defmethod visit :string [_ schema _ _] (str "string" (-titled schema) (-length-suffix schema)))
(defmethod visit :number [_ schema _ _] (str "number" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :pos-int [_ schema _ _] (str "integer greater than 0" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :neg-int [_ schema _ _] (str "integer less than 0" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :nat-int [_ schema _ _] (str "natural integer" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :float [_ schema _ _] (str "float" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :pos [_ schema _ _] (str "number greater than 0" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :neg [_ schema _ _] (str "number less than 0" (-titled schema) (-min-max-suffix schema)))
(defmethod visit :int [_ schema _ _] (str "integer" (-titled schema) (-min-max-suffix-number schema)))
(defmethod visit :double [_ schema _ _] (str "double" (-titled schema) (-min-max-suffix-number schema)))
(defmethod visit :merge [_ schema _ options] (describe* (m/deref schema) options))
(defmethod visit :union [_ schema _ options] (describe* (m/deref schema) options))
(defmethod visit :select-keys [_ schema _ options] (describe* (m/deref schema) options))
(defmethod visit :and [_ s children _] (str (str/join ", and " children) (-titled s)))
(defmethod visit :enum [_ s children _options] (str "enum" (-titled s) " of " (str/join ", " children)))
(defmethod visit :maybe [_ s children _] (str "nullable " (-titled s) (first children)))
(defmethod visit :tuple [_ s children _] (str "vector " (-titled s) "with exactly " (count children) " items of type: " (str/join ", " children)))
(defmethod visit :re [_ s _ options] (str "regex pattern " (-titled s) "matching " (pr-str (first (m/children s options)))))
(defmethod visit :any [_ s _ _] (str "anything" (-titled s)))
(defmethod visit :some [_ _ _ _] "anything but null")
(defmethod visit :nil [_ _ _ _] "null")
(defmethod visit :qualified-ident [_ _ _ _] "qualified-ident")
(defmethod visit :simple-keyword [_ _ _ _] "simple-keyword")
(defmethod visit :simple-symbol [_ _ _ _] "simple-symbol")
(defmethod visit :qualified-keyword [_ _ _ _] "qualified keyword")
(defmethod visit :symbol [_ _ _ _] "symbol")
(defmethod visit :qualified-symbol [_ _ _ _] "qualified symbol")
(defmethod visit :uuid [_ _ _ _] "uuid")
(defmethod visit :boolean [_ _ _ _] "boolean")
(defmethod visit :keyword [_ _ _ _] "keyword")

(defn -tagged [children] (map (fn [[tag _ c]] (str c " (tag: " tag ")")) children))

(defmethod visit :or [_ _ children _] (str/join ", or " children))
(defmethod visit :orn [_ _ children _] (str/join ", or " (-tagged children)))
(defmethod visit :cat [_ _ children _] (str/join ", " children))
(defmethod visit :catn [_ _ children _] (str/join ", and " (-tagged children)))
(defmethod visit :alt [_ _ children _] (str/join ", or " children))
(defmethod visit :altn [_ _ children _] (str/join ", or " (-tagged children)))

(defmethod visit :repeat [_ schema children _]
  (str "repeat " (-diamond (first children)) (-repeat-suffix schema)))


(defn minmax-suffix [schema]
  (let [{:keys [min max]} (-> schema m/properties)]
    (cond
      (and min max) (str "[min=" min ",max=" max "]")
      min (str "[min=" min "]")
      max (str "[max=" max "]"))))

(defmethod visit ::m/val [_ schema children _]
  (let [suffix (minmax-suffix schema)]
    (cond-> (first children)
      (some? suffix)
      (str suffix))))

(defmethod visit :map-of [_ _ children _]
  (str "map[" (first children) "," (second children) "]"))

(defmethod visit :map [_ schema children options]
  (let [optional (into #{} (comp (filter (m/-comp :optional second))
                                 (map first))
                       children)

        title    (-> schema m/properties :title)


        closed?  (:closed (m/properties schema))
        level    (::level options 1)
        padding  (->> (repeat "  ") (take level) (str/join ""))
        entries  (->> children
                      (map (fn [[k _ s]]
                             (str padding (str/camel k)
                                  (when (contains? optional k) "?")
                                  ": " s )))
                      (str/join ",\n"))]
    (str/trim
     (cond-> "object"
       (some? title) (str " " (str/capital (str/camel title)))
       closed?       (str "!")
       (seq entries) (str " {\n" entries "\n" (ex/ignoring (subs padding 2)) "} ")))))

(defmethod visit :schema [_ schema children options]
  (visit ::m/schema schema children options))

(def inc-level?
  #{:merge :map})

(defmethod visit ::m/schema [_ schema _ options]
  (let [schema' (m/deref schema)
        result  (describe* schema' (cond-> options
                                     (inc-level? (m/type schema'))
                                     (update ::level inc)))
        props   (merge
                 (m/properties schema)
                 (m/properties schema')
                 (m/type-properties schema'))]

    (if-let [ref (m/-ref schema)]
      (cond-> (or (:title props) (str/camel ref))
        (some? result)
        (str " -> " result))
      result)))

(defn describe* [s options]
  (letfn [(walk-fn [schema _ children options]
            (visit (m/type schema) schema children options))]
    (m/walk s walk-fn options)))

(defn describe
  "Given a schema, returns a string explaiaing the required shape in English"
  ([s]
   (describe s nil))
  ([s options]
   (let [type    (m/type s)
         s       (cond-> s
                   (= type ::m/schema)
                   (m/deref))
         options (assoc options ::m/walk-entry-vals true ::level 1)]
     (str/trim (describe* s options)))))
