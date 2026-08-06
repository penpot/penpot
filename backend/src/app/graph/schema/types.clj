;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.types
  "Map Malli schemas to Ladybug column types.

  Ladybug is schema-first and strongly typed: every property key gets its type
  at table-creation time, and there is no widening later. That makes this
  mapping the whole of the graph's typing, and it is worth being tight — a
  column typed `DOUBLE[4]` is four numbers a consumer reads as a tensor row,
  where the same value as `JSON` is text somebody has to parse and trust. So
  JSON is the fallback of last resort, taken only where the Malli schema
  genuinely admits shapes no single column can hold.

  Three groups, in the order the mapping tries them:

  1. **Scalars** (`base-type->ladybug`) — the leaf Malli types.
  2. **Registered composites** (`custom-type->ladybug`) — Penpot's own value
     types whose *layout* is fixed even though Malli only sees a map or a
     string: a matrix is six doubles, a point two, a rect four, a hex colour
     one packed integer. These are named explicitly because the tight encoding
     is a modelling decision, not something derivable from the schema.
  3. **Structure** — collections become `T[]`, `:map-of` becomes `MAP(k, v)`,
     and a closed map of scalars becomes a `STRUCT`. Anything that could be
     more than one shape (a `:multi`, an `:or`, an optional-keyed map) becomes
     `JSON`, because a Ladybug column cannot be two types.

  Every encoding here has a matching value formatter in `app.graph.ladybug`.
  The two must move together: a column type with no case there falls back to
  guessing the literal from the runtime value."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [clojure.string :as str]
   [malli.core :as m]))

(def ^:private malli-opts sm/default-options)

(def ^:private base-type->ladybug
  {::sm/uuid        "UUID"
   ::sm/safe-number "DOUBLE"
   ::sm/safe-double "DOUBLE"
   ::sm/safe-int    "INT64"
   ::sm/number      "DOUBLE"
   ::sm/boolean     "BOOLEAN"
   ::sm/int         "INT64"
   ::ct/inst        "TIMESTAMP"
   :uuid            "UUID"
   :string          "STRING"
   :int             "INT64"
   :double          "DOUBLE"
   :float           "DOUBLE"
   :boolean         "BOOLEAN"
   :keyword         "STRING"
   :inst            "TIMESTAMP"})

(def ^:private custom-type->ladybug
  "Penpot value types with a fixed layout Malli does not express.

  Fixed-size arrays are the point of each: they are dense, they need no
  parsing, and a consumer can read a whole column as a tensor.

  - `::gmt/matrix` — the affine transform, `[a b c d e f]`.
  - `::gpt/point` — `[x y]`.
  - `::grc/rect` — `[x y width height]`. `x1`/`y1`/`x2`/`y2` are dropped: they
    are derivable from those four, and carrying them would double the column.
  - `::clr/hex-color` — `#RRGGBB` packed as `0xRRGGBBAA`, so colours compare
    and group without string handling."
  {:app.common.geom.matrix/matrix   "DOUBLE[6]"
   :app.common.geom.point/point     "DOUBLE[2]"
   :app.common.geom.rect/rect       "DOUBLE[4]"
   :app.common.types.color/hex-color "UINT32"})

(def ^:private collection-types
  #{:vector :sequential :set ::sm/vec ::sm/set ::sm/coll})

(def ^:private string-collection-types
  "Registered collection schemas whose element type is not in `children`."
  {::sm/set-of-strings  "STRING[]"
   ::sm/set-of-keywords "STRING[]"
   ::sm/set-of-uuid     "UUID[]"
   ::sm/vec-of-uuid     "UUID[]"})

(defn- normalize-schema
  "Resolve refs, but stop at a schema this namespace maps explicitly.

  Order matters: `::grc/rect` derefs to an `:and` over a map, and following
  that would lose the fixed-size-array encoding."
  [schema]
  (let [s (sm/schema schema)]
    (if (and (m/-ref-schema? s)
             (not (contains? custom-type->ladybug (m/type s)))
             (not (contains? string-collection-types (m/type s))))
      (recur (m/deref s malli-opts))
      s)))

(declare ladybug-type)

(defn- entry-child
  "The value schema of a Malli map entry (`[k s]` or `[k props s]`)."
  [entry]
  (if (> (count entry) 2) (nth entry 2) (nth entry 1)))

(defn- entry-optional?
  [entry]
  (and (> (count entry) 2)
       (:optional (nth entry 1))))

(defn- struct-type
  "`STRUCT(...)` for a closed map of scalars, or nil when JSON is the honest answer.

  A struct is a fixed layout: every field present, every field a single type.
  An optional key would make the column's shape depend on the row, and a nested
  collection or map makes it recursive — Ladybug allows nesting, but a consumer
  reading such a column gains nothing over JSON, so the line is drawn at
  scalars."
  [s]
  (let [entries (m/entries s malli-opts)]
    (when (and (seq entries)
               (not-any? entry-optional? entries))
      (let [fields (for [entry entries
                         :let [t (ladybug-type (entry-child entry))]]
                     (when (and t
                                (not= "JSON" t)
                                (not (str/includes? t "(")))
                       ;; snake_case like a column name, and always
                       ;; backtick-quoted: a grid cell has a field called
                       ;; `column`, which is a Ladybug keyword, and an unquoted
                       ;; one fails to parse in the DDL *and* in every literal.
                       ;; The catalog reports them unquoted.
                       (str "`" (str/replace (name (key entry)) "-" "_") "` " t)))]
        (when (every? some? fields)
          (str "STRUCT(" (str/join ", " fields) ")"))))))

(defn ladybug-type
  "Return the Ladybug column type for a Malli child schema."
  [schema]
  (let [s (normalize-schema schema)
        t (m/type s)]
    (or (base-type->ladybug t)
        (custom-type->ladybug t)
        (string-collection-types t)
        (when (contains? collection-types t)
          (when-let [child (first (m/children s malli-opts))]
            (str (ladybug-type child) "[]")))
        (case t
          (:maybe :and) (ladybug-type (first (m/children s malli-opts)))

          ;; `::sm/one-of` is how Penpot spells a closed set of keywords —
          ;; `:blend-mode`, `:grow-type`, every `:layout-*`. One keyword, one
          ;; string.
          (:enum ::sm/one-of) "STRING"

          :map-of
          (let [[key-schema value-schema] (m/children s malli-opts)]
            (str "MAP(" (ladybug-type key-schema) ", "
                 (ladybug-type value-schema) ")"))

          :map (or (struct-type s) "JSON")

          ;; A schema we do not recognize. If it has no children it is a leaf —
          ;; one of Penpot's registered keyword or enum schemas, say — and a
          ;; string holds it exactly. If it has children it is a composite whose
          ;; shape we cannot pin down, and JSON is the honest answer.
          (if (empty? (m/children s malli-opts))
            "STRING"
            (do
              (l/wrn :hint "unmapped composite malli type, defaulting to JSON"
                     :malli-type t)
              "JSON"))))))
