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
   [app.graph.ladybug :as ladybug]
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

(defn- csv-cell
  [v]
  (cond
    (uuid? v)     (str v)
    (string? v)   (csv-escape-string v)
    (number? v)   (str (long v))
    :else         (csv-escape-string (str v))))

(defn- cypher-file-path
  [^File file]
  (-> (.getAbsolutePath file)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")))

(defn- node-columns
  [rows]
  (let [cols (into #{} (mapcat keys rows))
        preferred [:id :name :version :revision :index]]
    (into (vec (filter cols preferred))
          (sort (remove (set preferred) cols)))))

(defn- write-node-csv!
  [^File file rows]
  (let [columns (node-columns rows)]
    (with-open [w (io/writer file :encoding "UTF-8")]
      (.write w (str (str/join "," (map name columns)) "\n"))
      (doseq [row rows]
        (.write w (str (str/join "," (map #(csv-cell (get row %)) columns))
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
        (write-node-csv! csv-file rows)
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
