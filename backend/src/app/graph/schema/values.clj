;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.values
  "Shape a Penpot value into the plain data its Ladybug column type wants.

  Ladybug is strongly typed, and `app.graph.schema.types` maps Penpot's Malli
  schemas onto types as tight as it can — a matrix is `DOUBLE[6]`, a rect
  `DOUBLE[4]`, a colour `UINT32`, a closed map a `STRUCT`. A tight column is
  only worth having if the writer actually fills it in that shape, which is
  what this namespace does: it turns records and maps into the numbers, vectors
  and plain maps the type names.

  It deliberately stops there. Serialization belongs to the writer — Cypher
  literals in `app.graph.ladybug`, Arrow vectors in `app.graph.arrow` — so that
  shaping a value and writing it are separate concerns and each has one home.

  The type language is the Ladybug one, read recursively: `T[]`, `T[n]`,
  `MAP(k, v)`, `STRUCT(name t, …)`. Anything else is passed through."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.color :as clr]
   [clojure.string :as str]))

(defn- split-args
  "Split a comma-separated type argument list, respecting nesting.

  `\"UUID, STRUCT(a INT64, b INT64)\"` → `[\"UUID\" \"STRUCT(a INT64, b INT64)\"]`."
  [s]
  (loop [chars (seq s) depth 0 current (StringBuilder.) out []]
    (if-let [c (first chars)]
      (cond
        (and (= c \,) (zero? depth))
        (recur (rest chars) depth (StringBuilder.) (conj out (str/trim (str current))))

        (or (= c \() (= c \[))
        (recur (rest chars) (inc depth) (.append current c) out)

        (or (= c \)) (= c \]))
        (recur (rest chars) (dec depth) (.append current c) out)

        :else
        (recur (rest chars) depth (.append current c) out))
      (let [last-arg (str/trim (str current))]
        (cond-> out (seq last-arg) (conj last-arg))))))

(defn- parse-list
  "`[element-type]` when `t` is a list or fixed-size array type, else nil.

  `DOUBLE[]` and `DOUBLE[4]` are both lists of doubles as far as shaping goes;
  the size only matters to the DDL."
  [t]
  (when-let [[_ element] (re-matches #"(.+?)\[\d*\]$" t)]
    [element]))

(defn- parse-map
  "`[key-type value-type]` when `t` is a MAP type, else nil."
  [t]
  (when-let [[_ args] (re-matches #"MAP\((.*)\)$" t)]
    (let [[k v] (split-args args)]
      (when (and k v) [k v]))))

(defn- parse-struct
  "`[[field-name field-type] …]` when `t` is a STRUCT type, else nil.

  Field names arrive backtick-quoted (see `app.graph.schema.types`). The
  quoting is syntax, so it is stripped by default and re-applied by the writer —
  except for the Arrow writer, which needs it kept (`keep-quotes?`)."
  [t keep-quotes?]
  (when-let [[_ args] (re-matches #"STRUCT\((.*)\)$" t)]
    (for [arg (split-args args)
          :let [idx (str/index-of arg " ")]
          :when idx]
      [(cond-> (subs arg 0 idx) (not keep-quotes?) (str/replace "`" ""))
       (str/trim (subs arg (inc idx)))])))

(def ^:private struct-field-keys
  "Field name → the Penpot keys that may hold it.

  A STRUCT field name is the snake_case of the Penpot key, but a value arrives
  with its original key, and some arrive from JSON with the string form. Both
  are tried before giving up."
  (memoize
   (fn [field]
     [(keyword (str/replace field "_" "-"))
      (keyword field)
      field
      (str/replace field "_" "-")])))

(defn- struct-field
  [value field]
  (some (fn [k] (when (contains? value k) (get value k)))
        (struct-field-keys field)))

(defn- fixed-vector
  "`v` as a plain vector of numbers, for a `DOUBLE[n]` column.

  Records come first because they are what a realized snapshot holds; the map
  forms are what a JSON round-trip leaves behind."
  [v]
  (cond
    (gmt/matrix? v) [(:a v) (:b v) (:c v) (:d v) (:e v) (:f v)]
    (gpt/point? v)  [(:x v) (:y v)]

    ;; A rect: four of the eight fields, the rest being derivable.
    (and (map? v) (contains? v :width) (contains? v :height))
    [(:x v) (:y v) (:width v) (:height v)]

    (and (map? v) (contains? v :x) (contains? v :y))
    [(:x v) (:y v)]

    (and (map? v) (contains? v :a) (contains? v :f))
    [(:a v) (:b v) (:c v) (:d v) (:e v) (:f v)]

    (sequential? v) (vec v)
    :else nil))

(defn- packed-color
  "`#RRGGBB` as the packed integer `0xRRGGBBAA`.

  Alpha defaults to opaque: the column holds a colour, and any opacity Penpot
  keeps alongside it is a separate attribute."
  [v]
  (cond
    (integer? v) v
    (and (string? v) (clr/valid-hex-color? v))
    (let [rgb (Long/parseLong (subs v 1) 16)]
      (bit-or (bit-shift-left rgb 8) 0xFF))
    :else nil))

(def struct-fields
  "`[[field-name field-type] …]` for a STRUCT type, memoized.

  Public because the writers need the same field list to emit a literal."
  (memoize (fn [ladybug-type] (vec (parse-struct ladybug-type false)))))

(def struct-fields-quoted
  "`struct-fields` with the DDL's backticks intact.

  Only the Arrow writer wants this: Ladybug names a staged struct's fields from
  the Arrow child names and quotes none of them, so a field whose name is a
  reserved word — a layout grid cell's `column` — has to arrive already quoted
  or `createArrowTable` fails outright."
  (memoize (fn [ladybug-type] (vec (parse-struct ladybug-type true)))))

(def map-types
  "`[key-type value-type]` for a MAP type, memoized."
  (memoize (fn [ladybug-type] (parse-map ladybug-type))))

(def list-element
  "Element type of a `T[]` / `T[n]` column, memoized; nil when not a list."
  (memoize (fn [ladybug-type] (first (parse-list ladybug-type)))))

(declare coerce)

(defn- coerce-struct
  [fields v]
  (when (map? v)
    (into {}
          (keep (fn [[field field-type]]
                  (when-some [fv (struct-field v field)]
                    [field (coerce field-type fv)])))
          fields)))

(defn coerce
  "`v` as the plain data a column of `ladybug-type` holds.

  Returns `nil` when the value cannot be shaped that way, which callers treat
  as \"write NULL\" — a wrong shape in a strongly typed column fails the whole
  load, so declining is better than guessing."
  [ladybug-type v]
  (cond
    (nil? v) nil
    (not (string? ladybug-type)) v

    (= "UINT32" ladybug-type) (packed-color v)

    ;; Fixed-size numeric arrays are records: matrix, point, rect.
    (re-matches #"DOUBLE\[\d+\]" ladybug-type) (fixed-vector v)

    :else
    (if-let [[element] (parse-list ladybug-type)]
      (when (or (sequential? v) (set? v))
        ;; A set has no order, so its column would otherwise vary between
        ;; builds of the same file. Sorting makes it deterministic — which is
        ;; what lets two builds be diffed at all, and what a stable golden
        ;; needs. Sequential values keep their order: for `shapes` and
        ;; `points`, the order *is* the content.
        (let [elements (mapv #(coerce element %) v)]
          (if (set? v) (vec (sort-by str elements)) elements)))
      (if-let [[key-type value-type] (parse-map ladybug-type)]
        (when (map? v)
          (into {}
                (map (fn [[k mv]] [(coerce key-type k) (coerce value-type mv)]))
                v))
        (if-let [fields (seq (parse-struct ladybug-type false))]
          (coerce-struct fields v)
          v)))))
