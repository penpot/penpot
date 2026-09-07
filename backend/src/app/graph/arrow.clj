;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.arrow
  "Bulk Ladybug ingest through in-memory Arrow.

  Rows are built as Arrow `VectorSchemaRoot`s in the JVM's off-heap memory,
  handed to Ladybug as a virtual table, and `COPY`d into the real one. No file
  is written and no value is rendered as text for the engine to re-parse, so
  nothing in this path needs escaping. Arrow carries MAP, STRUCT, fixed-size
  arrays and multi-line strings natively.

  The type language is Ladybug's, read recursively by `app.graph.schema.values`;
  this namespace adds the matching Arrow `Field` and a writer for each shape.
  `values/coerce` shapes a value first — a matrix into six doubles, a colour
  into a packed integer — exactly as it does for the Cypher path, so the two
  writers cannot disagree.

  Engine facts this file depends on, each verified against lbug 0.19.1:

  - An Arrow table is **not** a `COPY` source identifier, but it *is* a
    MATCH-able node label: `COPY T FROM (MATCH (n:stg) RETURN n.a AS a, …)`.
  - A MAP vector's `entries` child struct must be non-nullable, and
    `MapVector/getWriter` silently promotes it to a sparse union — so map
    vectors are built from an explicit `Field` and filled child-first.
  - Ladybug quotes the column and table names it interpolates into the staged
    table's DDL, and does not quote a STRUCT member name. So a top-level field
    arrives plain and a struct member whose name is a reserved word (`column`)
    arrives backticked.
  - `createArrowRelTable` resolves a UUID-keyed endpoint only from a
    `FixedSizeBinary(16)` column carrying the `arrow.uuid` extension, so edges
    are staged as a node table and joined by the `COPY` subquery instead."
  (:require
   [app.common.json :as json]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]
   [app.graph.schema.values :as values]
   [clojure.string :as str])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.QueryResult
   java.nio.charset.StandardCharsets
   java.util.ArrayList
   java.util.List
   org.apache.arrow.memory.BufferAllocator
   org.apache.arrow.memory.RootAllocator
   org.apache.arrow.vector.BigIntVector
   org.apache.arrow.vector.BitVector
   org.apache.arrow.vector.complex.ListVector
   org.apache.arrow.vector.complex.MapVector
   org.apache.arrow.vector.complex.StructVector
   org.apache.arrow.vector.FieldVector
   org.apache.arrow.vector.Float8Vector
   org.apache.arrow.vector.TimeStampMicroVector
   org.apache.arrow.vector.types.FloatingPointPrecision
   org.apache.arrow.vector.types.pojo.ArrowType$Bool
   org.apache.arrow.vector.types.pojo.ArrowType$FloatingPoint
   org.apache.arrow.vector.types.pojo.ArrowType$Int
   org.apache.arrow.vector.types.pojo.ArrowType$List
   org.apache.arrow.vector.types.pojo.ArrowType$Map
   org.apache.arrow.vector.types.pojo.ArrowType$Struct
   org.apache.arrow.vector.types.pojo.ArrowType$Timestamp
   org.apache.arrow.vector.types.pojo.ArrowType$Utf8
   org.apache.arrow.vector.types.pojo.Field
   org.apache.arrow.vector.types.pojo.FieldType
   org.apache.arrow.vector.types.pojo.Schema
   org.apache.arrow.vector.types.TimeUnit
   org.apache.arrow.vector.UInt4Vector
   org.apache.arrow.vector.VarCharVector
   org.apache.arrow.vector.VectorSchemaRoot))

(set! *warn-on-reflection* true)

;; --------------------------------------------------------------- allocator

(defn with-allocator!
  "Invoke `(f allocator)` with a fresh Arrow `RootAllocator`.

  The allocator must outlive the Ladybug connection, because Ladybug releases
  its references to the staged buffers only when the Arrow tables are dropped —
  which happens on connection close at the latest. Closing it first surfaces as
  `IllegalStateException: Memory was leaked`, *thrown while unwinding*, which
  hides whatever actually failed. Any diagnostic here must catch inside this
  scope."
  [f]
  (with-open [allocator (RootAllocator.)]
    (f allocator)))

;; ------------------------------------------------------ Ladybug type → Field

(def ^:private scalar-arrow-type
  "Ladybug scalar → Arrow type. `UUID` and `JSON` ride as UTF-8: Ladybug
  accepts a string into either column and does the conversion itself, which is
  cheaper than teaching this side two more binary layouts."
  {"STRING"    #(ArrowType$Utf8.)
   "UUID"      #(ArrowType$Utf8.)
   "JSON"      #(ArrowType$Utf8.)
   "INT64"     #(ArrowType$Int. 64 true)
   "UINT32"    #(ArrowType$Int. 32 false)
   "DOUBLE"    #(ArrowType$FloatingPoint. FloatingPointPrecision/DOUBLE)
   "BOOLEAN"   #(ArrowType$Bool.)
   "TIMESTAMP" #(ArrowType$Timestamp. TimeUnit/MICROSECOND nil)})

(defn column-field
  "Arrow `Field` for a column of `ladybug-type`, recursively.

  `nullable?` is false only where Arrow's own invariants demand it — a MAP's
  `entries` struct and its key."
  (^Field [^String field-name ladybug-type]
   (column-field field-name ladybug-type true))
  (^Field [^String field-name ladybug-type nullable?]
   (cond
     ;; A list first: `STRUCT(…)[]` starts with `STRUCT(` but is a list of them.
     (ladybug/list-type? ladybug-type)
     (Field. field-name (FieldType. nullable? (ArrowType$List.) nil)
             [(column-field "item" (values/list-element ladybug-type))])

     (ladybug/map-type? ladybug-type)
     (let [[key-type value-type] (values/map-types ladybug-type)]
       (Field. field-name (FieldType. nullable? (ArrowType$Map. false) nil)
               [(Field. "entries" (FieldType. false (ArrowType$Struct.) nil)
                        [(column-field "key" key-type false)
                         (column-field "value" value-type)])]))

     (ladybug/struct-type? ladybug-type)
     (Field. field-name (FieldType. nullable? (ArrowType$Struct.) nil)
             ;; Backticks kept: Ladybug quotes none of these when it names the
             ;; staged struct's fields, so `column` has to arrive quoted.
             (mapv (fn [[field field-type]] (column-field field field-type))
                   (values/struct-fields-quoted ladybug-type)))

     :else
     (if-let [mk (get scalar-arrow-type ladybug-type)]
       (Field. field-name (FieldType. nullable? (mk) nil) nil)
       (throw (ex-info (str "no Arrow mapping for Ladybug type: " ladybug-type)
                       {:ladybug-type ladybug-type}))))))

;; ------------------------------------------------------------------- writer

(defn- utf8
  ^bytes [v]
  (.getBytes (if (keyword? v) (name v) (str v)) StandardCharsets/UTF_8))

(defn- epoch-micros
  ^long [v]
  (let [^java.time.Instant inst
        (cond
          (instance? java.time.Instant v) v
          (instance? java.util.Date v)    (.toInstant ^java.util.Date v)
          :else                           (java.time.Instant/parse (str v)))]
    (+ (* (.getEpochSecond inst) 1000000) (long (quot (.getNano inst) 1000)))))

(defn- write-scalar!
  [^FieldVector fv ladybug-type ^long idx v]
  (case ladybug-type
    ("STRING" "UUID")        (.setSafe ^VarCharVector fv idx (utf8 v))
    ;; A JSON column holds JSON, not a Clojure value's print form: `str` on a
    ;; map yields `{:fill-color "#000000"}`, which is EDN and which every
    ;; consumer of `fills`, `content` or `position_data` would fail to parse.
    ;; Same encoder the Cypher path uses (`app.graph.ladybug/format-json`).
    "JSON"                   (.setSafe ^VarCharVector fv idx
                                       (.getBytes ^String (json/encode v)
                                                  StandardCharsets/UTF_8))
    "INT64"                  (.setSafe ^BigIntVector fv idx (long v))
    "UINT32"                 (.setSafe ^UInt4Vector fv idx (unchecked-int (long v)))
    "DOUBLE"                 (.setSafe ^Float8Vector fv idx (double v))
    "BOOLEAN"                (.setSafe ^BitVector fv idx (if v 1 0))
    "TIMESTAMP"              (.setSafe ^TimeStampMicroVector fv idx (epoch-micros v))
    (throw (ex-info (str "no Arrow writer for Ladybug type: " ladybug-type)
                    {:ladybug-type ladybug-type}))))

(defn write-value!
  "Write already-coerced `v` into `fv` at `idx`, per `ladybug-type`.

  `map-key-fn` renders the keys of a `MAP(STRING, …)`, for the same reason
  `app.graph.ladybug/format-typed-value` takes one: the right spelling is a
  property of the column, not of the writer."
  ;; `idx` is deliberately unhinted: Clojure only accepts primitive args on fns
  ;; of four or fewer, and the map-key renderer has to travel with the value.
  [^FieldVector fv ladybug-type idx v map-key-fn]
  (if (nil? v)
    (.setNull fv (int idx))
    (cond
      (ladybug/list-type? ladybug-type)
      (let [^ListVector lv fv
            child        (.getDataVector lv)
            element-type (values/list-element ladybug-type)
            elements     (vec (if (or (sequential? v) (set? v)) v [v]))
            start        (.startNewValue lv (int idx))]
        (dotimes [i (count elements)]
          (write-value! child element-type (+ start i) (nth elements i) map-key-fn))
        (.endValue lv (int idx) (count elements)))

      (ladybug/map-type? ladybug-type)
      (let [^MapVector mv fv
            ^StructVector entries (.getDataVector mv)
            [key-type value-type] (values/map-types ladybug-type)
            key-vec   (.getChild entries "key")
            value-vec (.getChild entries "value")
            render-key (if (and map-key-fn (= "STRING" key-type)) map-key-fn identity)
            pairs      (vec (seq v))
            start      (.startNewValue mv (int idx))]
        (dotimes [i (count pairs)]
          (let [[k mv'] (nth pairs i)
                at      (+ start i)]
            ;; The entries struct is non-nullable: every slot must be defined.
            (.setIndexDefined entries (int at))
            (write-value! key-vec key-type at (render-key k) nil)
            (write-value! value-vec value-type at mv' map-key-fn)))
        (.endValue mv (int idx) (count pairs)))

      (ladybug/struct-type? ladybug-type)
      (let [^StructVector sv fv]
        (.setIndexDefined sv (int idx))
        (doseq [[quoted-field field-type] (values/struct-fields-quoted ladybug-type)]
          ;; The child is named with its backticks; the coerced value is keyed
          ;; without them.
          (write-value! (.getChild sv quoted-field) field-type idx
                        (get v (str/replace quoted-field "`" "")) map-key-fn)))

      :else
      (write-scalar! fv ladybug-type (long idx) v))))

;; ------------------------------------------------------------------ batches

(defn- fill-vector!
  [^VectorSchemaRoot root ^String field-name ladybug-type rows value-fn map-key-fn]
  (let [^FieldVector fv (.getVector root field-name)]
    (.allocateNew fv)
    (dotimes [i (count rows)]
      (write-value! fv ladybug-type i
                    (values/coerce ladybug-type (value-fn (nth rows i)))
                    map-key-fn))
    (.setValueCount fv (count rows))))

(defn- node-batch
  "One `VectorSchemaRoot` holding every projected row of `table`.

  Fields carry the plain column name. Ladybug quotes every identifier it
  interpolates into the staged table's DDL, so a name that is a reserved word
  (`Page.index`, `Document.options`) arrives unquoted and a name arriving
  pre-quoted comes out doubly backticked and fails to parse. The `COPY`
  projection below is Cypher, not DDL, so it quotes the same names itself."
  ^VectorSchemaRoot [^BufferAllocator allocator table rows]
  (let [columns (nodes/column-keys table)
        fields  (mapv (fn [k] (column-field (nodes/column-name table k)
                                            (nodes/column-ladybug-type table k)))
                      columns)
        root    (VectorSchemaRoot/create (Schema. ^List fields) allocator)]
    (doseq [k columns]
      (fill-vector! root (nodes/column-name table k)
                    (nodes/column-ladybug-type table k)
                    rows #(get % k) (nodes/column-map-key-fn table k)))
    (.setRowCount root (count rows))
    root))

(def ^:private edge-fields
  "Edge staging columns. `id` is the staging table's own key — Ladybug wants a
  first column to key the virtual table on — and `from`/`to` land as STRING,
  hence the cast in the join."
  [(Field. "id"       (FieldType. true (ArrowType$Utf8.) nil) nil)
   (Field. "from"     (FieldType. true (ArrowType$Utf8.) nil) nil)
   (Field. "to"       (FieldType. true (ArrowType$Utf8.) nil) nil)
   (Field. "position" (FieldType. true (ArrowType$Int. 64 true) nil) nil)])

(defn- edge-batch
  ^VectorSchemaRoot [^BufferAllocator allocator edges]
  (let [root (VectorSchemaRoot/create (Schema. ^List edge-fields) allocator)
        ^VarCharVector iv (.getVector root "id")
        ^VarCharVector fv (.getVector root "from")
        ^VarCharVector tv (.getVector root "to")
        ^BigIntVector  pv (.getVector root "position")
        n (count edges)]
    (doseq [^FieldVector v [iv fv tv pv]] (.allocateNew v))
    (dotimes [i n]
      (let [{:keys [from-id to-id position]} (nth edges i)]
        (.setSafe iv i (utf8 i))
        (.setSafe fv i (utf8 from-id))
        (.setSafe tv i (utf8 to-id))
        (if (nil? position) (.setNull pv i) (.setSafe pv i (long position)))))
    (doseq [^FieldVector v [iv fv tv pv]] (.setValueCount v n))
    (.setRowCount root n)
    root))

;; ------------------------------------------------------------------ staging

(defn- batches
  ^List [^VectorSchemaRoot root]
  (doto (ArrayList.) (.add root)))

(defn- check!
  [^QueryResult result hint data]
  (when-not (.isSuccess result)
    (throw (ex-info (str hint ": " (.getErrorMessage result))
                    (assoc data :err (.getErrorMessage result))))))

(defn- with-staged-table!
  "Create Arrow table `staging-name` from `root`, run `(f)`, always drop it."
  [^Connection conn ^BufferAllocator allocator ^String staging-name
   ^VectorSchemaRoot root data f]
  (try
    (with-open [^QueryResult r (.createArrowTable conn staging-name (batches root) allocator)]
      (check! r "createArrowTable failed" data))
    (f)
    (finally
      ;; Dropped even on failure: the staged buffers stay referenced by Ladybug
      ;; until it is, and the allocator's leak check fires on close otherwise.
      (try (.close ^QueryResult (.dropArrowTable conn staging-name))
           (catch Throwable _ nil)))))

(defn- copy-node-table!
  [^Connection conn table ^String staging-name]
  (let [projection (str/join ", " (for [k (nodes/column-keys table)
                                        :let [c (nodes/cypher-property-key table k)]]
                                    (str "n." c " AS " c)))
        statement  (str "COPY `" table "` FROM (MATCH (n:" staging-name ") "
                        "RETURN " projection ");")]
    (with-open [^QueryResult r (.query conn statement)]
      (check! r (str "COPY node table failed: " table)
              {:table table :statement statement}))))

(defn- copy-edge-group!
  "Load one FROM/TO pair of `IsChildOf`.

  `createArrowRelTable` is unusable here — it cannot resolve endpoints against a
  UUID-keyed node table — so the edge list is staged as a node table and the
  endpoints are resolved by the subquery. The `WHERE` is clause-level because
  this dialect prohibits an inline pattern `WHERE`, and both sides are pinned by
  label so the join cannot reach outside the pair."
  [^Connection conn from-table to-table ^String staging-name]
  (let [statement (str "COPY `IsChildOf` FROM ("
                       "MATCH (e:" staging-name "), "
                       "(a:" (nodes/match-label from-table) "), "
                       "(b:" (nodes/match-label to-table) ") "
                       "WHERE a.id = cast(e.from AS UUID) "
                       "AND b.id = cast(e.to AS UUID) "
                       "RETURN a.id, b.id, e.position) "
                       "(from='" from-table "', to='" to-table "');")]
    (with-open [^QueryResult r (.query conn statement)]
      (check! r (str "COPY edge group failed: " from-table " -> " to-table)
              {:from-table from-table :to-table to-table :statement statement}))))

(defn- staging-name
  [prefix & parts]
  (str/replace (str/join "_" (cons (str "stg_" prefix) parts)) #"[^A-Za-z0-9_]" "_"))

;; --------------------------------------------------------------------- load

(defn load-projection!
  "Load projected nodes and edges into an open Ladybug connection.

  `allocator` must outlive `conn` — see `with-allocator!`."
  [^Connection conn {:keys [nodes edges]} ^BufferAllocator allocator]
  (doseq [[table rows] (sort-by key nodes)
          :when (seq rows)]
    (let [name (staging-name "node" table)]
      (with-open [root (node-batch allocator table rows)]
        (with-staged-table! conn allocator name root {:table table}
          #(copy-node-table! conn table name)))))
  (doseq [[[from-table to-table] group]
          (sort-by key (group-by (juxt :from-table :to-table) edges))
          :when (seq group)]
    (let [name (staging-name "edge" from-table to-table)]
      (with-open [root (edge-batch allocator group)]
        (with-staged-table! conn allocator name root
          {:from-table from-table :to-table to-table}
          #(copy-edge-group! conn from-table to-table name))))))
