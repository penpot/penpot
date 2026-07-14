;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ladybug
  "Ladybug access layer for graph-backed Penpot.

  Uses the embedded Java API (`com.ladybugdb/lbug`)."
  (:require
   [app.common.exceptions :as ex]
   [clojure.string :as str]
   [datoteka.fs :as fs])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.Database
   com.ladybugdb.FlatTuple
   com.ladybugdb.QueryResult
   com.ladybugdb.Value))

(set! *warn-on-reflection* true)

(defn default-graph-dir
  []
  (or (System/getenv "PENPOT_GRAPH_DIR") "/tmp/penpot-graph"))

(defn db-path-for-file
  [file-id]
  (str (fs/path (default-graph-dir) (str file-id ".lbug"))))

(defn- memory-db-path?
  [db-path]
  (= db-path ":memory:"))

(defn reset-db-path!
  [db-path]
  (when-not (memory-db-path? db-path)
    (when (fs/exists? db-path)
      (fs/delete db-path))))

(defn escape-cypher-string
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")))

(defn format-uuid
  [id]
  (str "uuid('" (str id) "')"))

(defn format-string
  [s]
  (str "'" (escape-cypher-string s) "'"))

(defn format-int
  [n]
  (str (long n)))

(defn format-value
  [v]
  (cond
    (nil? v)     "NULL"
    (uuid? v)    (format-uuid v)
    (string? v)  (format-string v)
    (number? v)  (format-int v)
    (boolean? v) (if v "true" "false")
    :else        (format-string (str v))))

(defn- ensure-semicolon
  [statement]
  (let [s (str/trim (str statement))]
    (if (str/ends-with? s ";") s (str s ";"))))

(defn- value->clj
  [^Value value]
  (when-not (.isNull value)
    (let [v (.getValue value)]
      (cond
        (instance? Long v)    v
        (instance? Integer v) (long v)
        (instance? Double v)  v
        :else                 v))))

(defn- check-success!
  [^QueryResult result statement]
  (when-not (.isSuccess result)
    (let [err (.getErrorMessage result)]
      (ex/raise :type :internal
                :code :ladybug-query-failed
                :hint (str "Ladybug query failed: " err)
                :statement statement
                :err err))))

(def ^:private default-query-timeout-ms
  "0 disables query timeout (recommended for bulk COPY ingest)."
  0)

(defn- scalar-value
  [^Connection conn statement]
  (let [cypher (ensure-semicolon statement)]
    (with-open [^QueryResult result (.query conn cypher)]
      (check-success! result cypher)
      (when (.hasNext result)
        (with-open [^FlatTuple tuple (.getNext result)]
          (with-open [^Value value (.getValue tuple 0)]
            (value->clj value)))))))

(defn- run-statements!
  [^Connection conn statements]
  (doseq [statement statements]
    (let [cypher (ensure-semicolon statement)]
      (with-open [^QueryResult result (.query conn cypher)]
        (check-success! result cypher)))))

(defn- ensure-db-path!
  [db-path]
  (when-not (memory-db-path? db-path)
    (fs/create-dir (fs/parent db-path))))

(defn with-connection!
  "Open a Ladybug connection for `db-path` and invoke `(f conn)`.

  Options:
  - `:query-timeout-ms` query timeout in milliseconds (default 0, disabled)

  For `:memory:`, the database only lives for the duration of this call;
  all reads and writes must happen inside `f`."
  [db-path f & {:keys [query-timeout-ms]
                :or   {query-timeout-ms default-query-timeout-ms}}]
  (ensure-db-path! db-path)
  (let [^Database db (if (memory-db-path? db-path)
                       (Database.)
                       (Database. (str db-path)))]
    (try
      (let [^Connection conn (Connection. db)]
        (try
          (.setQueryTimeout conn (long query-timeout-ms))
          (f conn)
          (finally
            (.close conn))))
      (finally
        (.close db)))))

(defn exec-on-connection!
  "Execute Cypher statements on an open Ladybug connection."
  [^Connection conn statements]
  (assert (sequential? statements) "statements should be a sequential collection")
  (run-statements! conn statements))

(defn query-scalar-on-connection!
  "Execute a query expected to return a single scalar value on `conn`."
  [^Connection conn statement]
  (scalar-value conn statement))

(defn exec!
  "Execute Cypher statements against a Ladybug database.

  `db-path` is either `:memory:` or a filesystem path to a `.lbug` database."
  [db-path statements]
  (with-connection! db-path
    (fn [conn]
      (exec-on-connection! conn statements))))

(defn query-scalar!
  "Execute a query expected to return a single scalar value."
  [db-path statement]
  (with-connection! db-path
    (fn [conn]
      (query-scalar-on-connection! conn statement))))

(defn smoke-test!
  "Run a minimal CREATE + count against Ladybug."
  [& {:keys [db-path] :or {db-path ":memory:"}}]
  (when-not (memory-db-path? db-path)
    (reset-db-path! db-path))
  (with-connection! db-path
    (fn [^Connection conn]
      (run-statements! conn
                       ["CREATE NODE TABLE Person(name STRING, age INT64, PRIMARY KEY(name));"
                        "CREATE (:Person {name: 'Alice', age: 25});"
                        "CREATE (:Person {name: 'Bob', age: 30});"])
      {:db-path      db-path
       :person-count (scalar-value conn
                                   "MATCH (a:Person) RETURN count(a) AS c;")})))
