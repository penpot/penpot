;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.compliance
  "Runner for the external tokenscript compliance suite.

  The suite (github.com/tokens-studio/tokenscript-compliance-suite) is a
  directory of JSON files, each holding one or more cases:

    {\"name\": .., \"input\": .., \"inline\": true,
     \"expectedOutput\": \"7\", \"expectedOutputType\": \"Number\",
     \"context\": {\"x\": 3}, \"schemas\": [uri, ...]}

  It is not vendored (no license), so tests point at a local checkout
  through the TOKENSCRIPT_COMPLIANCE_DIR environment variable."
  (:require
   #?(:cljs ["fs" :as fs])
   #?(:cljs ["path" :as path])
   [app.common.json :as json]
   [app.common.tokenscript.colors :as colors]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.schema-uri :as suri]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.util :as u]
   [clojure.string :as str]
   [clojure.walk :as walk]))

;; Filesystem -----------------------------------------------------------------

(defn env
  [name]
  #?(:clj (System/getenv name)
     :cljs (aget (.-env js/process) name)))

(defn- read-file
  [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync fs path "utf-8")))

(defn- file-exists?
  [path]
  #?(:clj (.exists (java.io.File. ^String path))
     :cljs (.existsSync fs path)))

(defn- join-path
  [& parts]
  #?(:clj (.getPath (reduce (fn [^java.io.File f p] (java.io.File. f ^String p)) (java.io.File. ^String (first parts)) (rest parts)))
     :cljs (apply path/join parts)))

(defn- json-files
  "All `.json` files below `dir`, recursively."
  [dir]
  #?(:clj (->> (file-seq (java.io.File. ^String dir))
               (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".json")))
               (map #(.getPath ^java.io.File %))
               (sort))
     :cljs (letfn [(walk-dir [d]
                     (mapcat (fn [entry]
                               (let [p (path/join d entry)]
                                 (if (.isDirectory (.statSync fs p))
                                   (walk-dir p)
                                   (when (str/ends-with? entry ".json") [p]))))
                             (.readdirSync fs d)))]
             (sort (walk-dir dir)))))

;; Schemas --------------------------------------------------------------------

(defn- schema-file
  "Local spec file for a schema URI, following the layout of the
  interpreter repositories (`data/specifications/colors/<name>.json`)."
  [specs-dir uri]
  (when-let [name (suri/schema-name uri)]
    (join-path specs-dir "colors" (str (str/replace name #"-color$" "") ".json"))))

(defn- config-for
  [specs-dir schemas]
  (let [registry (reduce (fn [registry uri]
                           (let [file (schema-file specs-dir uri)]
                             (if (and file (file-exists? file))
                               (colors/register registry uri (json/decode (read-file file) :key-fn keyword))
                               registry)))
                         (colors/default-registry)
                         schemas)]
    (config/make-config {:colors registry})))

;; Cases ----------------------------------------------------------------------

(defn- context->references
  "Converts a case context into reference values. Objects tagged
  `{type: \"NumberWithUnit\"}` become unit symbols."
  [context]
  (walk/postwalk (fn [v]
                   (if (and (map? v) (= "NumberWithUnit" (get v "type")))
                     (sym/number-with-unit (get v "value") (get v "unit"))
                     v))
                 (or context {})))

(defn- expected->string
  "The suite writes some expectations as JSON scalars; outputs are always
  strings."
  [expected]
  (cond
    (nil? expected) "null"
    (string? expected) expected
    (number? expected) (u/num->str expected)
    (boolean? expected) (str expected)
    :else (json/encode expected)))

(defn- type-matches?
  "Type names in the suite are either qualified (`Color.Hex`) or the bare
  base type (`NumberWithUnit`); `ImplicitList` is the legacy name of
  `List.Implicit`."
  [expected actual]
  (or (= expected actual)
      (= expected (first (str/split actual #"\.")))
      (and (= expected "ImplicitList") (= actual "List.Implicit"))))

(defn run-case
  "Runs one case, returning it with `:status`, `:actualOutput`,
  `:actualOutputType` and optionally `:actualErrorCode`."
  [specs-dir {:strs [input inline context schemas expectedOutput expectedOutputType expectedErrorCode] :as case}]
  (let [cfg (config-for specs-dir schemas)
        actual (try
                 (let [{:keys [ast]} (parser/parse input {:mode (if inline :inline :script)})
                       result (interp/interpret ast {:references (context->references context) :config cfg})]
                   (if (sym/symbol? result)
                     {:actualOutput (if (sym/color? result)
                                      (sym/format-color cfg result)
                                      (sym/to-string result cfg))
                      :actualOutputType (sym/full-type-name result)}
                     {:actualOutput (str result) :actualOutputType "Error"
                      :error (str "result (" result ") is not an interpreter symbol")}))
                 (catch #?(:clj Throwable :cljs :default) e
                   (if (err/language-error? e)
                     {:actualOutput (err/hint e)
                      :actualOutputType "Error"
                      :actualErrorCode (err/code->compliance-code (err/code e))}
                     {:actualOutput (ex-message e) :actualOutputType "Error" :error (str e)})))
        type-matches? (type-matches? expectedOutputType (:actualOutputType actual))
        passed? (if expectedErrorCode
                  (and type-matches? (= (:actualErrorCode actual) expectedErrorCode))
                  (and type-matches? (= (:actualOutput actual) (expected->string expectedOutput))))]
    (merge case actual {:status (if passed? "passed" "failed")})))

(defn- parse-cases
  [content path]
  ;; String keys on both platforms (the cljs decoder keywordizes by default)
  (let [data (json/decode content :key-fn identity)]
    (map #(assoc % :path path) (if (sequential? data) data [data]))))

(defn run-suite
  "Runs every case below `tests-dir`. `specs-dir` holds the color
  specification files referenced by `schemas`. Returns
  `{:passed n :failed n :results [...]}`."
  [tests-dir specs-dir]
  (let [results (vec (for [file (json-files tests-dir)
                           case (parse-cases (read-file file) file)]
                       (run-case specs-dir case)))]
    {:passed (count (filter #(= "passed" (:status %)) results))
     :failed (count (filter #(= "failed" (:status %)) results))
     :results results}))

(defn failures
  [report]
  (->> (:results report)
       (filter #(= "failed" (:status %)))
       (map #(select-keys % [:path "name" "input" :actualOutput :actualOutputType :actualErrorCode
                             "expectedOutput" "expectedOutputType" "expectedErrorCode" :error]))))

(defn suite-dirs
  "`[tests-dir specs-dir]` from the TOKENSCRIPT_COMPLIANCE_DIR variable
  (the suite checkout, containing `tests/`) and the optional
  TOKENSCRIPT_SPECIFICATIONS_DIR (defaults to `../specifications` next
  to the suite)."
  []
  (when-let [dir (env "TOKENSCRIPT_COMPLIANCE_DIR")]
    [(join-path dir "tests")
     (or (env "TOKENSCRIPT_SPECIFICATIONS_DIR") (join-path dir ".." "specifications"))]))
