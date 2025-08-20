;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.desc-js-like
  (:require
   [app.common.data :as d]
   [app.common.schema :as-alias sm]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.util :as mu]))

(def ^:dynamic *definitions* nil)

(declare describe)
(declare describe*)

(defn -diamond [s] (str "<" s ">"))
(defn -titled [schema] (if-let [t (-> schema m/properties :title)] (str " :: " t "") ""))

(defn minmax-suffix [schema]
  (let [{:keys [min max]} (-> schema m/properties)]
    (cond
      (and min max) (str "[min=" min ",max=" max "]")
      min (str "[min=" min "]")
      max (str "[max=" max "]"))))

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

(defmethod visit :ref [_ _schema children _] (pr-str (first children)))

(defmethod visit :> [_ _ [value] _] (str "> " value))
(defmethod visit :>= [_ _ [value] _] (str ">= " value))
(defmethod visit :< [_ _ [value] _] (str "< " value))
(defmethod visit :<= [_ _ [value] _] (str "<= " value))
(defmethod visit := [_ _ [value] _] (str "== '" (name value) "'"))
(defmethod visit :not= [_ _ [value] _] (str "not equal " value))
(defmethod visit :not [_ _ children _] {:not (last children)})

(defn -of-clause [children] (when children (str " of " (first children))))

(defmethod visit :sequential [_ schema children _] (str "sequence" (-titled schema) (-length-suffix schema) (-of-clause children)))
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
(defmethod visit :select-keys [_ schema _ options] (describe* (m/deref schema) options))
(defmethod visit :and [_ s children _]
  (str (str/join " && " (filter some? children)) (-titled s)))
(defmethod visit :enum [_ s children _options] (str "enum" (-titled s) " of " (str/join ", " children)))
(defmethod visit :maybe [_ _ children _] (str (first children) " nullable"))
(defmethod visit :tuple [_ _ children _] (str "(" (str/join ", " children) ")"))
(defmethod visit :re [_ _ children _]
  (let [pattern (first children)]
    (str "string & regex pattern /" (str pattern) "/")))

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
(defmethod visit :keyword [_ _ _ _] "string")
(defmethod visit :fn [_ _ _ _]
  nil)

(defmethod visit :vector [_ _ children _]
  (str "[" (str/trim (last children)) "]"))

(defn -tagged [children] (map (fn [[tag _ c]] (str c " (tag: " tag ")")) children))

(defmethod visit :or [_ _ children _] (str/join ", or " children))
(defmethod visit :orn [_ _ children _] (str/join ", or " (-tagged children)))
(defmethod visit :cat [_ _ children _] (str/join ", " children))
(defmethod visit :catn [_ _ children _] (str/join ", and " (-tagged children)))
(defmethod visit :alt [_ _ children _] (str/join ", or " children))
(defmethod visit :altn [_ _ children _] (str/join ", or " (-tagged children)))

(defmethod visit :repeat [_ schema children _]
  (str "repeat " (-diamond (first children)) (-repeat-suffix schema)))

(defmethod visit :set [_ schema children _]
  (str "set[" (first children) "]" (minmax-suffix schema)))

(defmethod visit ::sm/set [_ schema children _]
  (str "set[" (first children) "]" (minmax-suffix schema)))

(defmethod visit ::m/val [_ schema children _]
  (let [suffix (minmax-suffix schema)]
    (cond-> (first children)
      (some? suffix)
      (str suffix))))

(defmethod visit :map-of
  [_ schema children _]
  (let [props   (m/properties schema)
        title   (some->> (:title props) str/camel str/capital)]

    (str (if title
           (str "type " title ": ")
           "")
         "map[" (first children) "," (second children) "]")))

(defmethod visit :union [_ _ children _]
  (str/join " | " children))

(defn pad
  [data n]
  (let [prefix (apply str (take n (repeat "  ")))]
    (->> (str/lines data)
         (map (fn [s] (str prefix s)))
         (str/join "\n"))))


(defmethod visit ::default [_ schema _ _]
  (let [props (m/type-properties schema)]
    (or (:title props)
        "*")))


(defn- format-map
  [schema children]
  (let [props      (m/properties schema)
        closed?    (get props :closed)
        title      (some->> (:title props) str/camel str/capital)
        optional (into #{} (comp (filter (m/-comp :optional second))
                                 (map first))
                       children)
        entries  (->> children
                      (map (fn [[k _ s]]
                             ;; NOTE: maybe we can detect multiple lines
                             ;; and then just insert a break line
                             (str "  " (str/camel k)
                                  (when (contains? optional k) "?")
                                  ": " (str/trim s))))
                      (str/join ",\n"))

        header   (cond-> (str "type " title)
                   closed?       (str "!")
                   (some? title) (str " "))]

    (str header "{\n" entries "\n}")))

(defmethod visit :map
  [_ schema children {:keys [::level] :as options}]
  (let [props      (m/properties schema)
        extracted? (get props ::extracted false)]

    (cond
      (or (= level 0) extracted?)
      (format-map schema children)

      :else
      (let [schema (mu/update-properties schema assoc ::extracted true)
            title  (or (some->> (:title props) str/camel str/capital) "<untitled>")]
        (swap! *definitions* conj (format-map schema children))
        title))))

(defn format-multi
  [s children]
  (let [props      (m/properties s)
        title      (or (some-> (:title props) str/camel str/capital) "<untitled>")
        dispatcher (or (-> s m/properties :dispatch-description)
                       (-> s m/properties :dispatch))
        entries    (->> children
                        (map (fn [[_ _ entry]]
                               (pad entry 1)))
                        (str/join ",\n"))

        header     (str "type " title " [dispatch=" (d/name dispatcher) "]")]
    (str header " {\n" entries "\n}")))

(defmethod visit :multi
  [_ schema children {:keys [::level] :as options}]
  (let [props      (m/properties schema)
        title      (or (some-> (:title props) str/camel str/capital) "<untitled>")
        extracted? (get props ::extracted false)]

    (cond
      (or (zero? level) extracted?)
      (format-multi schema children)

      :else
      (let [schema (mu/update-properties schema assoc ::extracted true)]
        (swap! *definitions* conj (format-multi schema children))
        title))))

(defn- format-merge
  [schema children]

  (let [props      (m/properties schema)
        entries    (->> children
                        (map (fn [shape]
                               (pad shape 1)))
                        (str/join ",\n"))
        title      (some-> (:title props) str/camel str/capital)


        header     (str "merge type " title)]

    (str header " {\n" entries "\n}")))

(defmethod visit :merge
  [_ schema children {:keys [::level] :as options}]
  (let [props      (m/properties schema)
        title      (some-> (:title props) str/camel str/capital)
        extracted? (get props ::extracted false)]

    (cond
      (or (zero? level) extracted?)
      (format-merge schema children)

      :else
      (let [schema (mu/update-properties schema assoc ::extracted true)]
        (swap! *definitions* conj
               (format-merge schema children))
        title))))

(defmethod visit ::sm/one-of
  [_ _ children _]
  (let [elems (last children)]
    (str "string oneOf (" (->> elems
                               (map d/name)
                               (str/join "|")) ")")))

(defmethod visit :schema
  [_ schema children options]
  (let [props      (m/properties schema)
        title      (some-> (:title props) str/camel str/capital)
        extracted? (get props ::extracted false)]

    (cond
      (not title)
      (visit ::m/schema schema children options)

      extracted?
      (let [title (or title "<untitled>")]
        (str "type " title ": "
             (visit ::m/schema schema children options)))

      :else
      (let [schema (mu/update-properties schema assoc ::extracted true)
            title  (or title "<untitled>")]
        (swap! *definitions* conj
               (str "type " title ": "
                    (visit ::m/schema schema children (update options ::level inc))))
        title))))

(defmethod visit ::m/schema
  [_ schema _ {:keys [::level] :as options}]
  (let [schema' (m/deref schema)]
    (describe* schema' (assoc options ::base-level level))))

(defn describe* [s options]
  (letfn [(walk-fn [schema path children {:keys [::base-level] :or {base-level 0} :as options}]
            (let [options (assoc options ::level (+ base-level (count path)))]
              (visit (m/type schema) schema children options)))]
    (m/walk s walk-fn options)))

(defn describe
  "Given a schema, returns a string explaiaing the required shape in English"
  ([s]
   (describe s nil))
  ([s options]
   (let [type    (m/type s)
         defs    (atom (d/ordered-set))
         s       (cond-> s
                   (= type ::m/schema)
                   (m/deref)
                   :always
                   (mu/update-properties assoc ::root true))

         options (into {::m/walk-entry-vals true
                        ::level 0}
                       options)]

     (binding [*definitions* defs]
       (str (str/trim (describe* s options))
            (when-let [defs @*definitions*]
              (str "\n\n" (str/join "\n\n" defs))))))))
