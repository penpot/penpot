;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-overlay-console-test
  "Pure tests over the console's parse-gate-run pipeline
  (`app.graph.overlay.console/run-query`) and the default query it ships
  (`app.graph.debug/default-query`).

  The gate exists so the console cannot run arbitrary Clojure; these
  tests hold it shut against namespace-qualified functions and unknown
  bare symbols, and hold it open for the shared rules, datascript
  built-ins, and aggregates. No session, no msgbus: one overlay built
  from a test-helper file and one function call per case."
  (:require
   [app.common.features :as ffeat]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.graph.debug :as debug]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.console :as console]
   [clojure.string :as str]
   [clojure.test :as t]
   [datascript.core :as d]))

(t/use-fixtures :each thi/test-fixture)

(defn- fixture-db
  "One overlay of a file with one component and one instance of it, plus
  enough named shapes that the row-cap test has rows to cap."
  []
  (binding [ffeat/*current* #{"components/v2"}]
    (let [file (-> (thf/sample-file "console fixture")
                   (ths/add-sample-shape "main" :type :frame :name "Main")
                   (ths/add-sample-shape "child-a" :type :rect :name "Child A"
                                         :parent-label "main")
                   (ths/add-sample-shape "child-b" :type :circle :name "Child B"
                                         :parent-label "main")
                   (thc/make-component "comp" "main")
                   (thc/instantiate-component "comp" "copy"))]
      (overlay/build (:data file) file))))

(defn- thrown-data
  "The ex-data of the ExceptionInfo `body` throws, or nil when it does
  not throw."
  [body]
  (try
    (body)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(t/deftest the-gate-rejects-namespace-qualified-functions
  (let [data (thrown-data
              #(console/run-query
                (fixture-db)
                "[:find ?v :where [(clojure.core/eval (quote (+ 1 1))) ?v]]"))]
    (t/is (some? data) "the gate must throw")
    (t/is (= :graph-query-not-read-only (:code data)))
    (t/is (str/includes? (:hint data) "clojure.core/eval")
          "the error must name the offending symbol")))

(t/deftest the-gate-rejects-unknown-bare-symbols
  (let [data (thrown-data
              #(console/run-query
                (fixture-db)
                "[:find ?v :where [?e :shape/id ?v] [(slurpish ?v)]]"))]
    (t/is (= :graph-query-not-read-only (:code data)))
    (t/is (str/includes? (:hint data) "slurpish")
          "the error must name the offending symbol")))

(t/deftest unreadable-text-is-a-parse-error
  (let [data (thrown-data
              #(console/run-query (fixture-db) "[:find ?v :where"))]
    (t/is (= :graph-query-invalid (:code data)))))

(t/deftest shared-rule-invocations-pass-the-gate
  ;; The gate's allowlist derives from `queries/rules`
  ;; (`app.graph.overlay.console/check-query!`), so rule renames pass
  ;; automatically: the default query proves `instance-of`, and these
  ;; hold the door open for `descendant-of` (with rows) and `uses-token`
  ;; (the fixture defines no tokens, so the gate acceptance is the
  ;; point).
  (let [db (fixture-db)]
    (let [{:keys [rows]} (console/run-query
                          db
                          "[:find ?n :in $ % :where [?p :shape/name \"Main\"] (descendant-of ?d ?p) [?d :shape/name ?n]]")]
      (t/is (= #{"Child A" "Child B"} (set (map first rows)))))
    (let [{:keys [rows]} (console/run-query
                          db
                          "[:find ?name :in $ % :where (uses-token ?s ?tok ?a) [?tok :token/name ?name]]")]
      (t/is (empty? rows)))))

(t/deftest the-old-rule-name-is-rejected
  ;; The vocabulary is closed: `is-instance-of` is gone, renamed
  ;; `instance-of`, and the old name must not pass the gate — the
  ;; allowlist derives from the rule set, not from a hardcoded list.
  (let [data (thrown-data
              #(console/run-query
                (fixture-db)
                "[:find ?s :in $ % :where (is-instance-of ?s ?c)]"))]
    (t/is (= :graph-query-not-read-only (:code data)))
    (t/is (str/includes? (:hint data) "is-instance-of")
          "the error must name the old rule")))

(t/deftest the-default-query-runs-through-the-console
  ;; The shipped default invokes the shared `instance-of` rule (renamed
  ;; from `is-instance-of` by the retrofit) with `%`; a rule invocation
  ;; must pass the gate and return rows.
  (let [{:keys [columns rows truncated? row-count]}
        (console/run-query (fixture-db) debug/default-query)]
    (t/is (= ["component" "instance" "page" "filter_src_id" "filter_tgt_id"]
             columns))
    (t/is (pos? row-count))
    (t/is (false? truncated?))
    (t/is (every? (fn [row]
                    (every? #(re-matches #"\d+" (str %)) (subvec row 3)))
                  rows)
          "the filter columns must carry node eids, rendered as decimal strings")))

(t/deftest scalar-and-collection-finds-normalize-to-rows
  (let [scalar (console/run-query (fixture-db)
                                  "[:find ?n . :where [?e :shape/name ?n]]")
        coll   (console/run-query (fixture-db)
                                  "[:find [?n ...] :where [?e :shape/name ?n]]")]
    (t/is (= ["n"] (:columns scalar)))
    (t/is (= 1 (count (:rows scalar))))
    (t/is (= 1 (count (first (:rows scalar)))))
    (t/is (= 1 (count (:columns coll))))
    (t/is (pos? (count (:rows coll))))
    (t/is (every? #(= 1 (count %)) (:rows coll)))))

(t/deftest the-row-cap-truncates-and-says-so
  (let [{:keys [rows truncated? row-count]}
        (with-redefs [console/max-rows 3]
          (console/run-query (fixture-db) "[:find ?n :where [?e :shape/name ?n]]"))]
    (t/is (true? truncated?))
    (t/is (= 3 row-count))
    (t/is (= 3 (count rows)))))

(t/deftest aggregates-are-allowed
  (let [db       (fixture-db)
        expected (count (d/datoms db :avet :shape/id))
        {:keys [rows]} (console/run-query db
                                          "[:find (count ?s) :where [?s :shape/id _]]")]
    (t/is (= [[expected]] rows))))
