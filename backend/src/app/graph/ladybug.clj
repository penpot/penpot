;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ladybug
  "Thin Ladybug CLI access layer for graph-backed Penpot."
  (:require
   [app.common.exceptions :as ex]
   [app.util.shell :as shell]
   [clojure.string :as str]
   [datoteka.fs :as fs])
  (:import
   java.util.concurrent.TimeUnit
   org.apache.commons.io.IOUtils))

(defn lbug-bin
  "Resolved Ladybug CLI path (`PENPOT_LBUG_BIN`, `./lbug`, or `lbug`)."
  []
  (or (System/getenv "PENPOT_LBUG_BIN")
      (let [local (java.io.File. "lbug")]
        (when (.exists local)
          (.getAbsolutePath local)))
      "lbug"))

(defn default-graph-dir
  []
  (or (System/getenv "PENPOT_GRAPH_DIR") "/tmp/penpot-graph"))

(defn db-path-for-file
  [file-id]
  (str (fs/path (default-graph-dir) (str file-id ".lbug"))))

(defn reset-db-path!
  [db-path]
  (when-not (= db-path ":memory:")
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

(defn- script-content
  [statements]
  (str (str/join "\n" (map ensure-semicolon statements)) "\n"))

(defn- shell-quote
  [s]
  (str "\"" (str/replace s "\"" "\\\"") "\""))

(defn- shell-single-quote
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- exec-sh-sync!
  "Run `sh -c` synchronously on the calling thread.

  `shell/exec!` can return empty stdout for very fast pipelines when used from
  the REPL executor; this path reads the merged stream before `waitFor` returns."
  [shell-cmd & {:keys [timeout] :or {timeout 120}}]
  (let [^Process proc (.start (doto (ProcessBuilder. (into-array String ["sh" "-c" shell-cmd]))
                               (.redirectErrorStream true)))
        out        (IOUtils/toString (.getInputStream proc) "UTF-8")]
    (when-not (.waitFor proc (long timeout) TimeUnit/SECONDS)
      (.destroyForcibly proc)
      (ex/raise :type :internal
                :code :ladybug-timeout
                :hint "Ladybug query timed out"
                :shell-cmd shell-cmd
                :timeout timeout))
    {:exit (.exitValue proc)
     :out  out
     :err  ""}))

(defn- run-script!
  "Run `lbug` with Cypher on stdin.

  Writes use a heredoc via `shell/exec!`. Queries use `printf ... | lbug`
  via a synchronous shell invocation (matches the working manual command)."
  [system db-path flags script & {:keys [timeout] :or {timeout 120}}]
  (let [cmd       (into [(lbug-bin) db-path] flags)
        query?    (some #{"line"} flags)
        shell-cmd (if query?
                    (str "printf " (shell-single-quote script)
                         " | " (str/join " " (map shell-quote cmd)))
                    (str (str/join " " (map shell-quote cmd))
                         " <<'LBUG_EOF'\n" script "\nLBUG_EOF"))]
    (if query?
      (exec-sh-sync! shell-cmd :timeout timeout)
      (shell/exec! system {:cmd ["sh" "-c" shell-cmd] :timeout timeout}))))

(defn exec!
  "Execute Cypher statements. `:mode` is `:write` (default) or `:query`."
  [system db-path statements & {:keys [timeout mode] :or {timeout 120 mode :write}}]
  (assert (sequential? statements) "statements should be a sequential collection")
  (when-not (= db-path ":memory:")
    (fs/create-dir (fs/parent db-path)))
  (let [flags  (if (= mode :query)
                 ["-m" "line" "-s"]
                 ["-m" "trash" "-s" "-b"])
        script (if (= mode :query)
                 (str ":singleline\n" (script-content statements))
                 (script-content statements))
        result (run-script! system db-path flags script :timeout timeout)]
    (when (not= 0 (:exit result))
      (ex/raise :type :internal
                :code :ladybug-exec-failed
                :hint "Ladybug execution failed"
                :db-path db-path
                :exit (:exit result)
                :out (:out result)
                :err (:err result)))
    result))

(defn- lbug-noise-line?
  [line]
  (or (str/blank? line)
      (str/starts-with? line "--")
      (str/starts-with? line ":singleline")
      (str/includes? line "Single line mode")
      (str/includes? line "usage hints")
      (str/includes? line "Processing:")
      (str/includes? line "Pipeline")
      (str/includes? line "Progress:")))

(defn- data-lines
  [out]
  (->> (str/split-lines (str out))
       (map str/trim)
       (remove lbug-noise-line?)))

(defn- parse-scalar-line
  [line]
  (let [line (str/trim line)]
    (cond
      (re-matches #"-?\d+" line) (Long/parseLong line)
      :else (some->> (re-seq #"-?\d+" line) last Long/parseLong))))

(defn- parse-equality-value
  "Last `label = 123` tuple in output (results come after pipeline noise)."
  [out]
  (some->> (re-seq #"([A-Za-z][A-Za-z0-9_]*)\s*=\s*(-?\d+)" (str out))
           (remove (fn [[_ label _]]
                     (or (str/includes? label "Pipeline")
                         (str/includes? label "Progress"))))
           last
           (nth 2)
           Long/parseLong))

(defn query-scalar!
  [system db-path statement & {:keys [timeout] :or {timeout 120}}]
  (let [out (:out (exec! system db-path [statement] :timeout timeout :mode :query))]
    (or (some parse-scalar-line (data-lines out))
        (parse-equality-value out))))

(defn smoke-test-statements
  []
  ["CREATE NODE TABLE Person(name STRING, age INT64, PRIMARY KEY(name));"
   "CREATE (:Person {name: 'Alice', age: 25});"
   "CREATE (:Person {name: 'Bob', age: 30});"
   "MATCH (a:Person) RETURN a.name AS NAME, a.age AS AGE ORDER BY NAME;"])

(defn smoke-test!
  [system & {:keys [db-path] :or {db-path ":memory:"}}]
  {:db-path db-path
   :out     (:out (exec! system db-path (smoke-test-statements)))})
