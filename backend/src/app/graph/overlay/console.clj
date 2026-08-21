;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay.console
  "Free-form Datalog for the debug graph console: parse, gate, run,
  format.

  The gate replaces the Cypher binder gate
  (`app.graph.ladybug/validate-on-connection!`) with the equivalent
  guarantee for datascript: a console query can read anything and execute
  nothing. `d/q` cannot transact, so mutation is off the table by
  construction; what remains is function clauses. Datascript resolves a
  namespace-qualified symbol in call position through `resolve`, which
  would hand the console arbitrary code execution
  (`[(clojure.core/eval ...)]`), so every symbol in call position must be
  a datascript built-in, a built-in aggregate, or a rule from the shared
  rule set. Unknown symbols are rejected before the query runs, with the
  binder's own courtesy: the error names the symbol."
  (:require
   [app.common.exceptions :as ex]
   [app.graph.overlay.queries :as queries]
   [clojure.edn :as edn]
   [datascript.built-ins :as bi]
   [datascript.core :as d]))

(def max-rows
  "Row cap for console query results; far above expected per-file counts."
  10000)

(def ^:private control-symbols
  "Clause heads that structure a query rather than call a function."
  '#{not not-join or or-join and pull})

(def ^:private rule-names
  (into #{} (map (comp first first)) queries/rules))

(def ^:private allowed-call-symbols
  (-> #{}
      (into (keys bi/query-fns))
      (into (keys bi/aggregates))
      (into control-symbols)
      (into rule-names)))

(defn- collect-call-symbols
  "Every symbol in call position anywhere in `form`: heads of seqs (rule
  invocations, aggregates, control forms) and of function/predicate
  clauses."
  [form]
  (cond
    (seq? form)
    (let [head (first form)]
      (into (if (symbol? head) [head] [])
            (mapcat collect-call-symbols)
            (rest form)))

    (coll? form)
    (into [] (mapcat collect-call-symbols) form)

    :else []))

(defn parse-query
  "Parse the console text as EDN. Only data readers for uuid are honoured;
  everything else is inert data."
  [text]
  (try
    (let [q (edn/read-string {:readers {'uuid parse-uuid}} text)]
      (when-not (or (vector? q) (map? q))
        (ex/raise :type :validation
                  :code :graph-query-invalid
                  :hint "a datalog query is a vector or a map"))
      q)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (ex/raise :type :validation
                :code :graph-query-invalid
                :hint (str "unreadable query: " (ex-message e))))))

(defn check-query!
  "The gate: reject any call-position symbol that is not a datascript
  built-in, aggregate, control form, or shared rule."
  [query]
  (let [unknown (into (sorted-set)
                      (remove allowed-call-symbols)
                      (collect-call-symbols query))]
    (when (seq unknown)
      (ex/raise :type :validation
                :code :graph-query-not-read-only
                :hint (str "unknown function(s) in query: "
                           (pr-str (vec unknown))
                           " — the console runs datascript built-ins and the shared rules only")))
    query))

(defn- query-sections
  "Normalize the vector query form into its keyword sections."
  [query]
  (if (map? query)
    query
    (loop [acc {} section nil forms (seq query)]
      (if-let [form (first forms)]
        (if (keyword? form)
          (recur acc form (rest forms))
          (recur (update acc section (fnil conj []) form) section (rest forms)))
        acc))))

(defn- find-columns
  "Column names from the :find clause; `?foo` renders as `foo`, so the
  console's `filter_*` graph-view convention carries over verbatim."
  [query]
  (mapv (fn [el]
          (cond
            (symbol? el) (let [n (name el)]
                           (cond-> n (.startsWith ^String n "?") (subs 1)))
            :else        (pr-str el)))
        (remove '#{. ...} (:find (query-sections query)))))

(defn- wants-rules?
  [query]
  (some #{'%} (:in (query-sections query))))

(defn- normalize-rows
  "d/q returns a relation (set of tuples), a scalar, a collection, or a
  single tuple depending on the find spec; the console table always wants
  rows of cells."
  [result]
  (cond
    (set? result)        (mapv vec result)
    (sequential? result) (if (and (seq result) (sequential? (first result)))
                           (mapv vec result)
                           (mapv vector result))
    (nil? result)        []
    :else                [[result]]))

(defn run-query
  "Parse, gate, and run `text` against overlay `db`.

  `$` is always supplied; `%` (the shared rule set of
  `app.graph.overlay.queries`) is supplied when the query's :in asks for
  it. Returns {:columns [...] :rows [...] :truncated? bool :row-count n}
  in the shape the console template renders."
  [db text]
  (let [query   (-> text parse-query check-query!)
        rows    (normalize-rows
                 (if (wants-rules? query)
                   (d/q query db queries/rules)
                   (d/q query db)))
        over?   (> (count rows) max-rows)
        rows    (if over? (into [] (take max-rows) rows) rows)
        columns (find-columns query)]
    {:columns    columns
     :rows       rows
     :truncated? over?
     :row-count  (count rows)}))
