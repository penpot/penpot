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
   [app.graph.schema.values :as values]
   [clojure.string :as str]
   [datoteka.fs :as fs])
  (:import
   com.ladybugdb.Connection
   com.ladybugdb.Database
   com.ladybugdb.FlatTuple
   com.ladybugdb.PreparedStatement
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
  "Ladybug TIMESTAMP literal of the form `timestamp('<ISO-8601 instant>')`."
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

(defn map-type?
  "Is `ladybug-type` a MAP column?"
  [ladybug-type]
  (and (string? ladybug-type)
       (str/starts-with? ladybug-type "MAP(")
       (not (str/ends-with? ladybug-type "]"))))

(defn list-type?
  "Is this a list or fixed-size array type? Checked before MAP and STRUCT,
  since `STRUCT(…)[]` starts with `STRUCT(` but is a list of them."
  [ladybug-type]
  (and (string? ladybug-type)
       (some? (re-matches #".+\[\d*\]$" ladybug-type))))

(defn struct-type?
  [ladybug-type]
  (and (string? ladybug-type)
       (str/starts-with? ladybug-type "STRUCT(")
       (not (list-type? ladybug-type))))

(declare format-typed-value)

(defn- format-typed-list
  "Cypher LIST literal, elements formatted by the element type.

  Handles `T[]` and the fixed-size `T[n]` alike: the size constrains the column,
  not the literal."
  [ladybug-type v]
  (let [element (second (re-matches #"(.+?)\[\d*\]$" ladybug-type))
        elems   (if (or (sequential? v) (set? v)) (seq v) [v])]
    (str "[" (str/join ", " (map #(format-typed-value element %) elems)) "]")))

(defn- format-struct
  "Cypher STRUCT literal, `{field: value, …}`.

  *Every* declared field is emitted, NULL where the value has none: a struct
  literal's type is its field list, so omitting a field yields a different type
  and Ladybug refuses the implicit cast (`STRUCT(m2 DOUBLE, m4 DOUBLE)` cannot
  be assigned to `STRUCT(m1 …, m2 …, m3 …, m4 …)`). Penpot's layout margins are
  exactly that case — a shape sets only the sides it overrides."
  [ladybug-type v]
  (let [fields (values/struct-fields ladybug-type)]
    (str "{"
         (str/join ", "
                   (for [[field field-type] fields
                         :let [fv (get v field)]]
                     ;; Backticked for the same reason as in the DDL: a field
                     ;; named `column` is a keyword and will not parse bare.
                     ;; A bare NULL is typed STRING, which changes the struct's
                     ;; type as surely as omitting the field would, so absent
                     ;; fields get a NULL cast to their declared type.
                     (str "`" field "`: "
                          (if (nil? fv)
                            (str "cast(NULL, '" field-type "')")
                            (format-typed-value field-type fv)))))
         "}")))

(defn format-typed-value
  "Cypher literal for `v` in a column of `ladybug-type`.

  Recursive over the type language, because the types are: a
  `MAP(UUID, STRUCT(…))` needs its keys, its fields and each field's own type
  honoured. `app.graph.schema.values/coerce` shapes the value first — turning a
  matrix record into six doubles, a hex colour into a packed integer — so this
  function only has to escape plain data.

  `map-key-fn` renders the keys of a `MAP(STRING, …)`; the caller supplies it
  because the right form is a property of the column, not of this function
  (`app.graph.schema.contract/map-key-fn`)."
  ([ladybug-type v] (format-typed-value ladybug-type v nil))
  ([ladybug-type v map-key-fn]
   (let [v (values/coerce ladybug-type v)]
     (cond
       (nil? v)
       "NULL"

       (list-type? ladybug-type)
       (format-typed-list ladybug-type v)

       (map-type? ladybug-type)
       (let [[key-type value-type] (values/map-types ladybug-type)
             entries (seq v)
             format-key (if (and map-key-fn (= "STRING" key-type))
                          #(format-string (map-key-fn (key %)))
                          #(format-typed-value key-type (key %)))]
         (str "map([" (str/join ", " (map format-key entries))
              "], ["
              (str/join ", " (map #(format-typed-value value-type (val %)) entries))
              "])"))

       (struct-type? ladybug-type)
       (format-struct ladybug-type v)

       (= ladybug-type "JSON")
       (format-json v)

       ;; Coerce string ids from transit edge-cases into UUID literals.
       (= ladybug-type "UUID")
       (format-uuid v)

       (= ladybug-type "TIMESTAMP")
       (format-timestamp v)

       :else
       (format-value v)))))

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

;; --- prepared statements

(defn- ->param-value
  "Clojure scalar → `Value` for prepared-statement binding.

  This is the only `Value` constructor on the write path, so every parameter
  is wrapped here. Parameters are scalars: the `Value` constructor takes no
  list or map, so `MAP`, `STRUCT` and `T[]` columns stay literal-rendered
  (`format-typed-value`) and the `:else` raise below means a caller tried to
  bind one."
  ^Value [v]
  (cond
    (nil? v)     (Value/createNull)                ; no explicit type needed
    (uuid? v)    (Value. ^Object v)                ; native UUID
    (string? v)  (Value. ^Object v)
    (boolean? v) (Value. ^Object v)
    (integer? v) (Value. ^Object (long v))
    (number? v)  (Value. ^Object (double v))
    (keyword? v) (Value. ^Object (name v))

    (instance? java.time.Instant v)                ; native TIMESTAMP
    (Value. ^Object v)

    (instance? java.util.Date v)
    (Value. ^Object (.toInstant ^java.util.Date v))

    :else
    (ex/raise :type :internal
              :code :ladybug-unsupported-param
              :hint (str "cannot bind a " (type v) " as a Ladybug parameter; "
                         "compound columns must be literal-rendered")
              :value v)))

(defn- as-statement
  "Normalize a statement to `{:cypher … :params …}`.

  A bare string binds nothing, so the sync builders can convert to bound
  parameters one family at a time."
  [stmt]
  (if (map? stmt)
    (update stmt :params #(or % {}))
    {:cypher stmt :params {}}))

(defn prepare-on-connection!
  "Parse and bind `statement` on `conn` without executing it.

  The returned `PreparedStatement` is a JNI resource: the caller closes it."
  ^PreparedStatement [^Connection conn statement]
  (let [cypher (ensure-semicolon statement)
        ps     (.prepare conn cypher)]
    (when-not (.isSuccess ps)
      (let [err (.getErrorMessage ps)]
        (.close ps)
        (ex/raise :type :internal
                  :code :ladybug-prepare-failed
                  :hint (str "Ladybug prepare failed: " err)
                  :statement cypher
                  :err err)))
    ps))

(defn execute-prepared!
  "Bind `params` into `ps` and execute it on `conn`.

  `params` keys are parameter names without the `$` (keyword or string);
  values are scalars. Every bound `Value` is closed, including the ones built
  before a later parameter is rejected."
  [^Connection conn ^PreparedStatement ps params]
  (let [vmap (java.util.HashMap.)]
    (try
      (doseq [[k v] params]
        (.put vmap (name k) (->param-value v)))
      (with-open [^QueryResult result (.execute conn ps vmap)]
        (check-success! result "<prepared>"))
      (finally
        (run! #(.close ^Value %) (.values vmap))))))

(defn exec-prepared-on-connection!
  "Prepare all statements, then execute all of them.

  A parse or bind failure in *any* statement aborts the batch before the first
  mutation runs — the bind-level batch gate. Statements are
  `{:cypher … :params {…}}` maps or bare strings."
  [^Connection conn stmts]
  (assert (sequential? stmts) "statements should be a sequential collection")
  (let [prepared (volatile! [])]
    (try
      (doseq [stmt stmts]
        (let [{:keys [cypher params]} (as-statement stmt)]
          (vswap! prepared conj {:ps     (prepare-on-connection! conn cypher)
                                 :params params})))
      (doseq [{:keys [ps params]} @prepared]
        (execute-prepared! conn ps params))
      (finally
        (run! #(.close ^PreparedStatement (:ps %)) @prepared)))))

(defn validate-on-connection!
  "Binder gate: parse and semantic-check `statement` against the live schema,
  without executing it.

  Returns `{:ok? …  :error …  :read-only? …}`. Unlike `prepare-on-connection!`
  a failure is a return value rather than a raise: the callers are gates (the
  CI binder gate, the console read-only gate) that report it. `:read-only?` is
  the engine's own read/write analysis."
  [^Connection conn statement]
  (with-open [^PreparedStatement ps (.prepare conn (ensure-semicolon statement))]
    (let [ok? (.isSuccess ps)]
      {:ok?        ok?
       :error      (when-not ok? (.getErrorMessage ps))
       :read-only? (when ok? (.isReadOnly ps))})))

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
