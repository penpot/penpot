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
   [app.common.json :as json]
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

(defn format-number
  [n]
  (if (== n (long n))
    (format-int n)
    (str (double n))))

(defn format-json
  [v]
  (str "json('" (escape-cypher-string (json/encode v)) "')"))

(defn format-timestamp
  "Ladybug TIMESTAMP literal (beadpot: `timestamp('…')`)."
  [v]
  (let [s (cond
            (instance? java.time.Instant v)
            (.toString ^java.time.Instant v)

            (instance? java.util.Date v)
            (.toString (.toInstant ^java.util.Date v))

            (string? v)
            v

            :else
            (str v))]
    (str "timestamp('" (escape-cypher-string s) "')")))

(defn format-value
  [v]
  (cond
    (nil? v)     "NULL"
    (uuid? v)    (format-uuid v)
    (instance? java.time.Instant v) (format-timestamp v)
    (instance? java.util.Date v)    (format-timestamp v)
    (string? v)  (format-string v)
    (number? v)  (format-number v)
    (boolean? v) (if v "true" "false")
    (keyword? v) (format-string (name v))
    (map? v)     (format-json v)
    (coll? v)    (format-json v)
    :else        (format-string (str v))))

(defn- format-list-element
  "Format one element of a Cypher LIST literal for typed `elem-type`."
  [elem-type v]
  (case elem-type
    "UUID"      (format-uuid v)
    "STRING"    (format-string (str v))
    "JSON"      (format-json v)
    "INT64"     (format-int v)
    "DOUBLE"    (format-number v)
    "BOOLEAN"   (if v "true" "false")
    "TIMESTAMP" (format-timestamp v)
    (format-value v)))

(defn- format-list
  "Cypher LIST literal for Ladybug LIST columns (`UUID[]`, `STRING[]`, …).

  Must not use `json(...)`: assigning a JSON value to `UUID[]` yields
  `Conversion exception: Invalid UUID` (e.g. Frame.`shapes` on component
  instantiate via sync)."
  [ladybug-type v]
  (let [elem-type (subs ladybug-type 0 (- (count ladybug-type) 2))
        elems     (if (coll? v) (seq v) [v])]
    (str "["
         (str/join ", " (map #(format-list-element elem-type %) elems))
         "]")))

(defn format-typed-value
  [ladybug-type v]
  (cond
    (nil? v)
    "NULL"

    (= ladybug-type "JSON")
    (format-json v)

    ;; Coerce string ids from transit edge-cases into UUID literals.
    (= ladybug-type "UUID")
    (format-uuid v)

    (= ladybug-type "TIMESTAMP")
    (format-timestamp v)

    (and (string? ladybug-type)
         (str/ends-with? ladybug-type "[]"))
    (format-list ladybug-type v)

    :else
    (format-value v)))

(defn- ensure-semicolon
  [statement]
  (let [s (str/trim (str statement))]
    (if (str/ends-with? s ";") s (str s ";"))))

(defn- value->clj
  [^Value value]
  (when-not (.isNull value)
    (let [v (try
              (.getValue value)
              (catch Exception _
                ;; LIST/STRUCT values are not supported by the binding's
                ;; getValue (\"value_get_value\"); fall back to the textual
                ;; representation so console queries do not crash.
                (.toString value)))]
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

(defn- query-columns
  [^QueryResult result]
  (let [ncols (.getNumColumns result)]
    (vec (for [i (range ncols)]
           (.getColumnName result (long i))))))

(defn- query-row
  [^FlatTuple tuple ncols]
  (vec (for [i (range ncols)]
         (with-open [^Value value (.getValue tuple (long i))]
           (value->clj value)))))

(def ^:private default-query-max-rows 200)

(defn- read-query-rows
  [^QueryResult result ncols max-rows]
  (loop [rows [] n 0]
    (if (and (< n max-rows) (.hasNext result))
      (let [row (with-open [^FlatTuple tuple (.getNext result)]
                  (query-row tuple ncols))]
        (recur (conj rows row) (inc n)))
      rows)))

(defn query-on-connection!
  "Execute a Cypher query on `conn` and return tabular results.

  Returns `{:columns [...] :rows [[...] ...] :truncated? bool}`."
  [^Connection conn statement & {:keys [max-rows]
                                 :or   {max-rows default-query-max-rows}}]
  (let [cypher (ensure-semicolon statement)]
    (with-open [^QueryResult result (.query conn cypher)]
      (check-success! result cypher)
      (let [ncols   (long (.getNumColumns result))
            columns (query-columns result)
            rows    (read-query-rows result ncols max-rows)
            total   (long (.getNumTuples result))]
        {:columns    columns
         :rows       rows
         :truncated? (and (pos? total) (> total (count rows)))}))))

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

(defn- extension-statement-ok?
  [err-msg]
  (let [err (str/lower-case (or err-msg ""))]
    (or (str/includes? err "already loaded")
        (str/includes? err "already installed"))))

(defn- run-extension-statement!
  [^Connection conn statement]
  (let [cypher (ensure-semicolon statement)]
    (with-open [^QueryResult result (.query conn cypher)]
      (when-not (.isSuccess result)
        (let [err (.getErrorMessage result)]
          (when-not (extension-statement-ok? err)
            (check-success! result cypher)))))))

(defn ensure-extensions!
  "Install and load Ladybug extensions required by graph ingest and sync."
  [^Connection conn]
  (run-extension-statement! conn "INSTALL json;")
  (run-extension-statement! conn "LOAD json;"))

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
          (ensure-extensions! conn)
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
