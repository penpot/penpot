;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-binder-gate-test
  "Binder gate for the incremental-sync statement templates.

  Every template `app.graph.sync` emits is *prepared* — parsed and bound by
  the engine against the live DDL — and never executed. A parse or bind
  failure (a renamed column, a reserved-word label emitted unquoted, a dropped
  table) turns the gate red here, before the statement can reach a live
  session.

  One instance per template is the gate; per-column type coverage belongs to
  beadpot's schema diff, not here. The templates are `defn-`, so they are
  reached through their vars."
  (:require
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]
   [app.graph.sync]
   [clojure.test :as t]))

(def ^:private create-node-statement            #'app.graph.sync/create-node-statement)
(def ^:private delete-node-statement            #'app.graph.sync/delete-node-statement)
(def ^:private create-edge-statement            #'app.graph.sync/create-edge-statement)
(def ^:private delete-edge-statement            #'app.graph.sync/delete-edge-statement)
(def ^:private set-edge-position-statement      #'app.graph.sync/set-edge-position-statement)
(def ^:private create-instance-of-statement     #'app.graph.sync/create-instance-of-statement)
(def ^:private delete-instance-of-statement     #'app.graph.sync/delete-instance-of-statement)
(def ^:private set-node-attr-statement          #'app.graph.sync/set-node-attr-statement)
(def ^:private set-page-name-statement          #'app.graph.sync/set-page-name-statement)
(def ^:private remove-node-attr-statement       #'app.graph.sync/remove-node-attr-statement)
(def ^:private set-document-revision-statement  #'app.graph.sync/set-document-revision-statement)

;; Dummy identities. Fixed rather than generated: a gate failure should read
;; the same on every run.
(def ^:private doc-id       #uuid "00000000-0000-0000-0000-0000000000d0")
(def ^:private page-id      #uuid "00000000-0000-0000-0000-0000000000a0")
(def ^:private shape-id     #uuid "00000000-0000-0000-0000-0000000000b0")
(def ^:private frame-id     #uuid "00000000-0000-0000-0000-0000000000c0")
(def ^:private component-id #uuid "00000000-0000-0000-0000-0000000000e0")

(def ^:private child-edge
  {:from-table "Rectangle" :from-id shape-id
   :to-table   "Page"      :to-id   page-id
   :position   3})

(def ^:private ^:dynamic *conn* nil)

(defn- with-graph-connection
  "Open a `:memory:` database, create the live schema, run the tests on it.

  Nothing is executed against it — the gate only prepares — but the DDL has to
  be there for the binder to resolve tables and columns against."
  [next]
  (ladybug/with-connection! ":memory:"
    (fn [conn]
      (ladybug/exec-on-connection! conn (nodes/ddl-statements))
      (binding [*conn* conn]
        (next)))))

(t/use-fixtures :once with-graph-connection)

(defn- gate
  "Assert `statement` binds, and that the engine agrees on read/write."
  [label statement read-only?]
  (let [result (ladybug/validate-on-connection! *conn* statement)]
    (t/is (:ok? result)
          (str label " does not bind: " (:error result) "\n  " statement))
    (when (:ok? result)
      (t/is (= read-only? (:read-only? result))
            (str label " read-only? " (:read-only? result) ", expected " read-only?)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the eleven sync templates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest create-node-binds
  (gate "create-node-statement"
        (create-node-statement "Rectangle" {:id      shape-id
                                            :name    "a shape"
                                            :opacity 1.0
                                            :hidden  false})
        false))

(t/deftest delete-node-binds
  (gate "delete-node-statement"
        (delete-node-statement "Rectangle" shape-id)
        false))

(t/deftest create-edge-binds
  (gate "create-edge-statement"
        (create-edge-statement child-edge)
        false))

(t/deftest delete-edge-binds
  (gate "delete-edge-statement"
        (delete-edge-statement (dissoc child-edge :position))
        false))

(t/deftest set-edge-position-binds
  (gate "set-edge-position-statement"
        (set-edge-position-statement child-edge)
        false))

(t/deftest create-instance-of-binds
  (gate "create-instance-of-statement"
        (create-instance-of-statement frame-id component-id)
        false))

(t/deftest delete-instance-of-binds
  (gate "delete-instance-of-statement"
        (delete-instance-of-statement frame-id)
        false))

(t/deftest set-node-attr-binds
  (gate "set-node-attr-statement"
        (set-node-attr-statement "Rectangle" shape-id :name "a shape")
        false))

(t/deftest set-page-name-binds
  (gate "set-page-name-statement"
        (set-page-name-statement page-id "a page")
        false))

(t/deftest remove-node-attr-binds
  (gate "remove-node-attr-statement"
        (remove-node-attr-statement "Rectangle" shape-id :name)
        false))

(t/deftest set-document-revision-binds
  (gate "set-document-revision-statement"
        (set-document-revision-statement doc-id 42)
        false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; label quoting across the registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest every-node-label-binds
  ;; `Group` and `Boolean` are reserved words: unquoted they do not parse.
  ;; One MATCH per registered table is the cheapest way to keep `match-label`
  ;; honest as tables come and go.
  (doseq [table (map :table nodes/node-types)]
    (gate (str "delete-node-statement on " table)
          (delete-node-statement table shape-id)
          false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the gate itself
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest read-only-discriminates
  ;; Without this the `read-only? false` assertions above would hold for a
  ;; `validate-on-connection!` that always answered false.
  (gate "a read query" "MATCH (n:Rectangle) RETURN count(n);" true))

(t/deftest bad-statement-is-reported-not-thrown
  (let [result (ladybug/validate-on-connection!
                *conn* "MATCH (n:Rectangle) SET n.no_such_column = 1;")]
    (t/is (false? (:ok? result)))
    (t/is (string? (:error result)))
    (t/is (nil? (:read-only? result)))))
