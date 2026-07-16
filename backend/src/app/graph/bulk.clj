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

(defn- csv-normalize-string
  "Graph node names are single-line labels; flatten Penpot text newlines."
  [s]
  (-> (str s)
      (str/replace #"\r\n" " ")
      (str/replace #"\r" " ")
      (str/replace #"\n" " ")))

(defn- csv-escape-string
  [s]
  (str "\"" (str/replace (csv-normalize-string s) "\"" "\"\"") "\""))

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

(defn- kuzu-list-element
  "Format one element of a Ladybug LIST column for CSV COPY. Kuzu parses
  the (CSV-unquoted) field as a list literal: bare values for UUID/number
  elements, single-quoted strings (backslash-escaped) for STRING/JSON."
  [elem-type v]
  (cond
    (nil? v)
    "NULL"

    (contains? #{"STRING" "JSON"} elem-type)
    (let [s (if (coll? v) (json/encode v) (csv-normalize-string (str v)))]
      (str "'" (-> s
                   (str/replace "\\" "\\\\")
                   (str/replace "'" "\\'"))
           "'"))

    :else
    (str v)))

(defn- kuzu-list-cell
  "CSV cell for a LIST-typed column (`UUID[]`, `STRING[]`, `JSON[]`, …).
  JSON-encoding the collection (as `csv-cell` does) is wrong here: Kuzu
  expects its own list literal, e.g. `[id1,id2]` with bare elements."
  [ladybug-type v]
  (let [elem-type (subs ladybug-type 0 (- (count ladybug-type) 2))
        elems     (if (coll? v) (seq v) [v])]
    (csv-escape-string
     (str "[" (str/join "," (map #(kuzu-list-element elem-type %) elems)) "]"))))

(defn- csv-typed-cell
  [ladybug-type v]
  (if (and (some? v)
           (string? ladybug-type)
           (str/ends-with? ladybug-type "[]"))
    (kuzu-list-cell ladybug-type v)
    (csv-cell v)))

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
      (.write w (str (str/join "," (map name columns)) "\n"))
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
        (copy-node-table! conn table csv-file)))
    (doseq [[[from-table to-table] group]
            (sort-by identity (group-by (juxt :from-table :to-table) edges))
            :when (seq group)]
      (let [csv-file (io/file staging-path
                              (str "IsChildOf_" from-table "_" to-table ".csv"))]
        (write-edge-csv! csv-file group)
        (copy-edge-group! conn from-table to-table csv-file)))
    (finally
      (delete-tree! staging-path))))
