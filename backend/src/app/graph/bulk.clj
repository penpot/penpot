;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.bulk
  "Bulk Ladybug ingest via COPY FROM CSV.

  Node and relationship rows are written to a temporary staging directory
  and loaded with one COPY statement per table (or per rel FROM/TO pair)."
  (:require
   [app.common.json :as json]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]
   [app.graph.schema.values :as values]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datoteka.fs :as fs])
  (:import
   java.io.File))

(set! *warn-on-reflection* true)

(def ^:private copy-csv-options
  "Ladybug COPY CSV options. QUOTE must be set explicitly or commas in
  string fields are treated as column separators."
  "HEADER=true, DELIM=',', QUOTE='\"'")

(defn- csv-escape-string
  [s]
  (str "\"" (str/replace (str s) "\"" "\"\"") "\""))

(defn- multiline?
  "Does this value contain a newline?

  Ladybug's parallel CSV reader rejects quoted newlines outright, and a shape
  name or text body may well contain one. Rather than flatten them — beadpot
  keeps them, and a graph is not a place to lose characters — such values are
  written through Cypher afterwards (`fixup-statements`)."
  [v]
  (and (string? v)
       (or (str/includes? v "\n") (str/includes? v "\r"))))

(defn- csv-cell
  [v]
  (cond
    (nil? v)      ""
    (uuid? v)     (str v)
    (string? v)   (csv-escape-string v)
    (number? v)   (if (== v (long v)) (str (long v)) (str (double v)))
    (boolean? v)  (str v)
    (keyword? v)  (csv-escape-string (name v))
    (map? v)      (csv-escape-string (json/encode v))
    (coll? v)     (csv-escape-string (json/encode v))
    :else         (csv-escape-string (str v))))

(defn- ladybug-list-element
  "Format one element of a Ladybug LIST column for CSV COPY. Ladybug parses
  the (CSV-unquoted) field as a list literal: bare values for UUID/number
  elements, single-quoted strings (backslash-escaped) for STRING/JSON."
  [elem-type v]
  (cond
    (nil? v)
    "NULL"

    (contains? #{"STRING" "JSON"} elem-type)
    ;; Unreachable in practice: string-bearing lists go through Cypher
    ;; (`csv-representable?`), because Ladybug's CSV list-literal parser has no
    ;; escaping at all. Kept so the function stays total.
    (let [s (cond
              (coll? v)    (json/encode v)
              (keyword? v) (name v)
              :else        (str v))]
      (str "'" (-> s
                   (str/replace "\\" "\\\\")
                   (str/replace "'" "\\'"))
           "'"))

    :else
    (str v)))

(defn- ladybug-literal
  "A coerced value as the list literal Ladybug's CSV reader expects.

  Recursive, because the types are: `points` is a `DOUBLE[2][]`, and rendering
  its elements with `str` would emit Clojure's space-separated `[1 2]`, which
  Ladybug reads as a one-element array."
  [ladybug-type v]
  (if-let [[_ element] (and (string? ladybug-type)
                            (re-matches #"(.+?)\[\d*\]$" ladybug-type))]
    (str "[" (str/join "," (map #(ladybug-literal element %)
                                (if (coll? v) (seq v) [v])))
         "]")
    (ladybug-list-element ladybug-type v)))

(defn- ladybug-list-cell
  "CSV cell for a LIST-typed column (`UUID[]`, `DOUBLE[4]`, `DOUBLE[2][]`, …).
  JSON-encoding the collection (as `csv-cell` does) is wrong here: Ladybug
  expects its own list literal, e.g. `[id1,id2]` with bare elements."
  [ladybug-type v]
  (csv-escape-string (ladybug-literal ladybug-type v)))

(defn- compound-type?
  "A MAP or STRUCT, but not a *list* of them — a list is handled elementwise."
  [ladybug-type]
  (and (string? ladybug-type)
       (not (ladybug/list-type? ladybug-type))
       (or (str/starts-with? ladybug-type "MAP(")
           (str/starts-with? ladybug-type "STRUCT("))))

(defn- csv-representable?
  "Can a value of `ladybug-type` survive a CSV round-trip?

  Ladybug parses the *contents* of a CSV field as a Cypher-ish literal for
  compound types, and that parser has no escape mechanism whatsoever: a comma
  inside a string element ends the element, and quotes are kept as part of the
  value rather than delimiting it (verified against 0.18). So only compound
  types whose elements cannot contain a delimiter — UUID, numbers, booleans —
  are safe; anything carrying a string or JSON is not, and neither is a MAP or
  a STRUCT. Those go through Cypher instead (`fixup-statements`), where
  `app.graph.ladybug` escapes properly.

  Parquet would remove the distinction entirely (masterplan P0 T1 chose it,
  with CSV as the fallback of last resort); until the JVM side grows a Parquet
  writer, this is where the line falls."
  [ladybug-type]
  (cond
    (not (string? ladybug-type))     true
    (compound-type? ladybug-type)    false
    :else
    (if-let [[_ element] (re-matches #"(.+?)\[\d*\]$" ladybug-type)]
      ;; A list is safe only if its elements are: no delimiter can occur inside
      ;; a UUID or a number, and a nested list or struct reintroduces the
      ;; problem one level down.
      (and (not (contains? #{"STRING" "JSON"} element))
           (csv-representable? element))
      true)))

(defn- defer-to-cypher?
  "Must this value be written after the COPY rather than in the CSV?

  Two reasons, both limitations of Ladybug's CSV reader rather than choices:
  a type its literal parser cannot escape, or a string containing a newline."
  [ladybug-type v]
  (or (not (csv-representable? ladybug-type))
      (multiline? v)))

(defn- csv-typed-cell
  [ladybug-type v]
  ;; Shape the value for its column first (a matrix into six doubles, a hex
  ;; colour into a packed integer); the CSV cell then only has to escape plain
  ;; data. Same first step as the Cypher path, so the two agree.
  (let [v (values/coerce ladybug-type v)]
    (cond
      ;; Written after the COPY, through Cypher — see `fixup-statements`.
      (defer-to-cypher? ladybug-type v) ""

      (and (some? v)
           (string? ladybug-type)
           (re-matches #".+\[\d*\]$" ladybug-type))
      (ladybug-list-cell ladybug-type v)

      :else (csv-cell v))))

(defn- fixup-statements
  "Cypher to set the values the CSV had to leave empty.

  One statement per row that has any — not per column — so the cost is one
  round-trip per shape rather than per attribute, and a row with none costs
  nothing at all."
  [table rows]
  (let [columns (nodes/column-keys table)]
    (for [row rows
          ;; An empty collection is a value, not an absence: a shape with no
          ;; fills has `[]`, and writing NULL instead would tell a consumer the
          ;; attribute is unknown rather than empty.
          :let [sets (for [k columns
                           :let [v (get row k)
                                 t (nodes/column-ladybug-type table k)]
                           :when (some? v)
                           :when (defer-to-cypher? t v)]
                       (str "n." (nodes/cypher-property-key table k) " = "
                            (nodes/format-column-value table k v)))]
          :when (seq sets)]
      (str "MATCH (n:" (nodes/match-label table) " {id: "
           (ladybug/format-uuid (:id row)) "}) "
           "SET " (str/join ", " sets) ";"))))

(defn- cypher-file-path
  [^File file]
  (-> (.getAbsolutePath file)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")))

(defn- write-node-csv!
  [^File file table rows]
  (let [columns (nodes/column-keys table)
        types   (mapv #(nodes/column-ladybug-type table %) columns)]
    (with-open [w (io/writer file :encoding "UTF-8")]
      ;; COPY binds columns positionally (HEADER=true only skips the row), so
      ;; this header is documentation — but it carries the beadpot column
      ;; names, so a staged CSV reads the same as the table it loads into.
      (.write w (str (str/join "," (map #(nodes/column-name table %) columns)) "\n"))
      (doseq [row rows]
        (.write w (str (str/join "," (map (fn [k t] (csv-typed-cell t (get row k)))
                                          columns types))
                       "\n"))))))

(defn- write-edge-csv!
  [^File file edges]
  (with-open [w (io/writer file :encoding "UTF-8")]
    (.write w "from,to,position\n")
    (doseq [{:keys [from-id to-id position]} edges]
      (.write w (str (csv-cell from-id) ","
                     (csv-cell to-id) ","
                     (csv-cell position) "\n")))))

(defn- delete-tree!
  [path]
  (when (fs/exists? path)
    (doseq [f (reverse (file-seq (io/file path)))]
      (.delete ^File f))))

(defn staging-dir
  "Directory for temporary COPY CSV files."
  [db-path file-id]
  (if (= db-path ":memory:")
    (str (fs/path (System/getProperty "java.io.tmpdir")
                  "penpot-graph-bulk"
                  (str file-id)))
    (str (fs/path (str db-path ".bulk") (str file-id)))))

(defn- copy-node-table!
  [conn table ^File csv-file]
  (let [statement (str "COPY `" table "` FROM '" (cypher-file-path csv-file)
                       "' (" copy-csv-options ");")]
    (try
      (ladybug/exec-on-connection! conn [statement])
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (str "COPY node table failed: " table)
                        (merge (ex-data e)
                               {:table table
                                :csv-file (.getAbsolutePath csv-file)})
                        e))))))

(defn- copy-edge-group!
  [conn from-table to-table ^File csv-file]
  (let [statement (str "COPY `IsChildOf` FROM '" (cypher-file-path csv-file) "' "
                       "(from='" from-table "', to='" to-table "', "
                       copy-csv-options ");")]
    (try
      (ladybug/exec-on-connection! conn [statement])
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (str "COPY edge group failed: " from-table " -> " to-table)
                        (merge (ex-data e)
                               {:from-table from-table
                                :to-table to-table
                                :csv-file (.getAbsolutePath csv-file)})
                        e))))))

(defn load-projection!
  "Load projected nodes and edges into an open Ladybug connection."
  [conn {:keys [nodes edges]} staging-path]
  (fs/create-dir staging-path)
  (try
    (doseq [[table rows] (sort-by key nodes)
            :when (seq rows)]
      (let [csv-file (io/file staging-path (str table ".csv"))]
        (write-node-csv! csv-file table rows)
        (copy-node-table! conn table csv-file)
        (when-let [stmts (seq (fixup-statements table rows))]
          (ladybug/exec-on-connection! conn stmts))))
    (doseq [[[from-table to-table] group]
            (sort-by identity (group-by (juxt :from-table :to-table) edges))
            :when (seq group)]
      (let [csv-file (io/file staging-path
                              (str "IsChildOf_" from-table "_" to-table ".csv"))]
        (write-edge-csv! csv-file group)
        (copy-edge-group! conn from-table to-table csv-file)))
    (finally
      (delete-tree! staging-path))))
