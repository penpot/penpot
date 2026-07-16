;; Spike: Ladybug createArrowTable → native COPY (no CSV).
;; Run inside devenv from backend/:
;;   clojure -M:dev -m graph-arrow-spike
;;
;; Requires --add-opens=java.base/java.nio=ALL-UNNAMED
;; (configured in deps.edn :dev :jvm-opts).
;;
;; FINDING: Arrow tables created with createArrowTable are NOT available as direct
;; identifiers in COPY statements, but ARE available as nodes in MATCH queries.
;; SOLUTION: Use "COPY table FROM (MATCH (n:arrow_table) RETURN ...)" pattern.

(ns graph-arrow-spike
  (:gen-class)
  (:import
   (com.ladybugdb Connection Database QueryResult)
   (java.nio.charset StandardCharsets)
   (java.util ArrayList List)
   (org.apache.arrow.memory RootAllocator)
   (org.apache.arrow.vector VarCharVector VectorSchemaRoot)
   (org.apache.arrow.vector.types.pojo ArrowType$Utf8 Field FieldType Schema)))

(defn- check!
  [^QueryResult result label]
  (when-not (.isSuccess result)
    (throw (ex-info (str label ": " (.getErrorMessage result))
                    {:label label
                     :err (.getErrorMessage result)})))
  result)

(defn- page-root
  [^RootAllocator alloc]
  (let [varchar-type (org.apache.arrow.vector.types.pojo.ArrowType$Utf8.)
        field-id     (Field. "id" (FieldType/nullable varchar-type) nil)
        field-name   (Field. "name" (FieldType/nullable varchar-type) nil)
        schema       (Schema. [field-id field-name])
        root         (VectorSchemaRoot/create schema alloc)
        ^VarCharVector idv (.getVector root "id")
        ^VarCharVector nv  (.getVector root "name")]
    (.allocateNew idv 2)
    (.allocateNew nv 2)
    (.setSafe idv 0 (.getBytes "p1" StandardCharsets/UTF_8))
    (.setSafe idv 1 (.getBytes "p2" StandardCharsets/UTF_8))
    (.setSafe nv 0 (.getBytes "Home" StandardCharsets/UTF_8))
    (.setSafe nv 1 (.getBytes "About" StandardCharsets/UTF_8))
    (.setValueCount idv 2)
    (.setValueCount nv 2)
    (.setRowCount root 2)
    root))

(defn- try-query!
  [^Connection conn cypher label]
  (with-open [^QueryResult r (.query conn cypher)]
    (println label
             "success?" (.isSuccess r)
             "tuples" (when (.isSuccess r) (.getNumTuples r))
             "err" (when-not (.isSuccess r) (.getErrorMessage r)))
    (.isSuccess r)))

(defn -main
  [& _]
  (with-open [^RootAllocator alloc (RootAllocator.)
              ^Database db (Database.)
              ^Connection conn (Connection. db)]
    (.setQueryTimeout conn 0)
    (println "=== 1) native DDL ===")
    (with-open [r (.query conn "CREATE NODE TABLE Page(id STRING, name STRING, PRIMARY KEY(id));")]
      (check! r "ddl"))

    (println "=== 2) createArrowTable staging ===")
    (let [root2 (page-root alloc)
          batches (doto (ArrayList.) (.add root2))]
      (with-open [r (.createArrowTable conn "stg_Page" ^List batches alloc)]
        (check! r "createArrowTable"))

      (println "=== 3) query staging ===")
      (try-query! conn "MATCH (n:stg_Page) RETURN n.id, n.name;" "stg")

      (println "=== 4) COPY Page FROM Arrow table via MATCH subquery ===")
      (try-query! conn
                  "COPY Page FROM (MATCH (n:stg_Page) RETURN n.id AS id, n.name AS name);"
                  "copy-via-match")

      (println "=== 5) query native Page ===")
      (try-query! conn "MATCH (n:Page) RETURN n.id, n.name;" "page")

      (println "=== 6) dropArrowTable ===")
      (with-open [r (.dropArrowTable conn "stg_Page")]
        (println "drop success?" (.isSuccess r) "err" (.getErrorMessage r)))

      (.close root2))
    (println "DONE")))