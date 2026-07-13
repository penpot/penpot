;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ladybug
  "Thin Ladybug access layer for graph-backed Penpot.

  Uses the `lbug` CLI for now. A JNI-backed implementation can replace
  `exec!` later without changing callers."
  (:require
   [app.common.exceptions :as ex]
   [app.util.shell :as shell]
   [clojure.string :as str]
   [datoteka.fs :as fs])
  (:import
   java.io.File
   java.nio.file.Files))

(set! *warn-on-reflection* true)

(defn- lbug-bin
  []
  (or (System/getenv "PENPOT_LBUG_BIN") "lbug"))

(defn default-graph-dir
  []
  (or (System/getenv "PENPOT_GRAPH_DIR") "/tmp/penpot-graph"))

(defn db-path-for-file
  [file-id]
  (str (fs/path (default-graph-dir) (str file-id ".lbug"))))

(defn- write-temp-script!
  [statements]
  (let [^File file (File/createTempFile "penpot-graph-" ".cypher")
        content    (str/join "\n" (concat statements [":quit"]))]
    (spit file content)
    (.getAbsolutePath file)))

(defn exec!
  "Execute one or more Cypher statements against a Ladybug database.

  `db-path` is either `:memory:` or a filesystem path to a `.lbug` database."
  [system db-path statements & {:keys [timeout] :or {timeout 120}}]
  (assert (sequential? statements) "statements should be a sequential collection")
  (when-not (= db-path ":memory:")
    (fs/create-dir (fs/parent db-path)))
  (let [script-path (write-temp-script! statements)
        result      (shell/exec!
                     system
                     {:cmd [(lbug-bin) db-path "-i" script-path "-m" "csv" "-s" "-b"]
                      :timeout timeout})]
    (Files/deleteIfExists (fs/path script-path))
    (when (not= 0 (:exit result))
      (ex/raise :type :internal
                :code :ladybug-exec-failed
                :hint "Ladybug query execution failed"
                :db-path db-path
                :exit (:exit result)
                :err (:err result)
                :out (:out result)))
    result))

(defn smoke-test-statements
  []
  ["CREATE NODE TABLE Person(name STRING, age INT64, PRIMARY KEY(name));"
   "CREATE (:Person {name: 'Alice', age: 25});"
   "CREATE (:Person {name: 'Bob', age: 30});"
   "MATCH (a:Person) RETURN a.name AS NAME, a.age AS AGE ORDER BY NAME;"])

(defn smoke-test!
  "Run a minimal CREATE + MATCH against Ladybug."
  [system & {:keys [db-path] :or {db-path ":memory:"}}]
  (let [result (exec! system db-path (smoke-test-statements))]
    {:db-path db-path
     :out (:out result)}))
