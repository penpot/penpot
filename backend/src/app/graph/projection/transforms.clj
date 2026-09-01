;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.projection.transforms
  "Derived graph links: edges a reader could compute from the projected
  columns, materialized once at build time so a query does not have to.

  Each entry in `registry` names the transform, the relationship it produces,
  and the function that produces it, so adding one is a single entry and
  nothing else has to be told about it."
  (:require
   [app.common.logging :as l]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes])
  (:import
   com.ladybugdb.Connection))

(set! *warn-on-reflection* true)

(defn- run-scalar!
  [^Connection conn statement]
  (or (ladybug/query-scalar-on-connection! conn statement) 0))

(defn- link-component-instances!
  "`IsInstanceOf` from Frame instance heads to their Component.

  Every head is linked, the main instance and any copy root alike.

  `component-file` is what makes a head a head here, not `component-id` alone.
  `app.common.types.component/instance-of?` requires both, and the projection
  denormalizes `component-id` down the shape tree
  (`app.graph.projection.document`), so on its own it no longer distinguishes a
  head from a shape that merely lives inside one. `component-file` is not
  denormalized and remains the head marker Penpot itself uses."
  [^Connection conn]
  (run-scalar! conn
               (str "MATCH (f:Frame), (c:Component) "
                    "WHERE f.component_id = c.id "
                    "AND f.component_file IS NOT NULL "
                    "AND NOT COALESCE(c.deleted, false) "
                    "MERGE (f)-[:IsInstanceOf]->(c) "
                    "RETURN count(*);")))

(defn- shape-pair-statements
  "One statement per (from, to) shape-table pair.

  Ladybug cannot create a relationship bound by multiple node labels in a
  single `MERGE`, a constraint inherited from Kùzu, which it forks (upstream
  issue kuzudb/kuzu#5841). The loop over label pairs is that dialect
  constraint, not a modelling choice."
  [f]
  (for [from nodes/shape-tables
        to   nodes/shape-tables]
    (f from to)))

(defn- link-shape-refs!
  "`RefersTo` from an instance shape to its homologue in the main instance,
  driven by `shape-ref`."
  [^Connection conn]
  (reduce
   (fn [total statement] (+ total (run-scalar! conn statement)))
   0
   (shape-pair-statements
    (fn [from to]
      (str "MATCH (s:" (nodes/match-label from) "), (t:" (nodes/match-label to) ") "
           "WHERE s.shape_ref = t.id "
           "MERGE (s)-[:RefersTo]->(t) "
           "RETURN count(*);")))))

(def ^:private swap-slot-prefix "swap-slot-")

(def ^:private slot-uuid-expr
  ;; Ladybug `substring` is 1-indexed; 36 = RFC 4122 UUID text length.
  (str "substring(touched_key, " (inc (count swap-slot-prefix)) ", 36)"))

(defn- link-swap-slots!
  "`FillsSwapSlot` from a swapped-in shape to the slot it replaces.

  Penpot records a component sub-shape swap as a `swap-slot-<uuid>` entry in
  the *replacing* shape's `touched` set, where `<uuid>` names the replaced
  slot shape in the main instance. The entries are then stripped from
  `touched`, as `app.common.types.component/normal-touched-groups` does, so a
  reader of `touched` sees design edits rather than swap bookkeeping.

  Stripping makes this the one transform that writes a column another
  transform could read. Anything reading `touched` has to run before it."
  [^Connection conn]
  (let [linked
        (reduce
         (fn [total statement] (+ total (run-scalar! conn statement)))
         0
         (shape-pair-statements
          (fn [from to]
            (str "MATCH (s:" (nodes/match-label from) ") "
                 "WHERE size(s.touched) > 0 "
                 "UNWIND s.touched AS touched_key "
                 "WITH s, touched_key "
                 "WHERE STARTS_WITH(touched_key, '" swap-slot-prefix "') "
                 "WITH s, CAST(" slot-uuid-expr ", 'UUID') AS slot_id "
                 "MATCH (t:" (nodes/match-label to) ") "
                 "WHERE t.id = slot_id AND s.id <> t.id "
                 "MERGE (s)-[r:FillsSwapSlot {slot_id: slot_id}]->(t) "
                 "RETURN count(r);"))))]
    ;; Strip unconditionally: an entry may name a slot that was garbage
    ;; collected, so "no edge created" does not mean "nothing to strip".
    (doseq [table nodes/shape-tables]
      (ladybug/exec-on-connection!
       conn
       [(str "MATCH (s:" (nodes/match-label table) ") "
             "WHERE size(s.touched) > 0 "
             "SET s.touched = list_filter(s.touched, x -> "
             "NOT STARTS_WITH(x, '" swap-slot-prefix "'));")]))
    linked))

(def registry
  "Every transform this backend applies.

  `:id` names the transform in the ingest report and the log. `:rel` names
  the relationship it produces. The three registered here read disjoint
  columns, so the vector order is not load-bearing. The one ordering
  constraint that exists is stated on `link-swap-slots!`."
  [{:id "link-component-instances" :rel :IsInstanceOf  :run link-component-instances!}
   {:id "link-shape-refs"          :rel :RefersTo      :run link-shape-refs!}
   {:id "link-swap-slots"          :rel :FillsSwapSlot :run link-swap-slots!}])

(defn apply-transforms!
  "Apply every registered transform to an already loaded graph.

  Returns `{:ids [...] :counts {...} :transforms n}`, where `:ids` names what
  ran and `:counts` gives the edges each one produced."
  [_system ^Connection conn _data _file]
  (reduce
   (fn [acc {:keys [id rel run]}]
     (let [n (run conn)]
       (l/inf :hint "graph transform" :transform id :edges n)
       (-> acc
           (update :ids conj id)
           (update :counts assoc rel n)
           (assoc rel n))))
   {:ids [] :counts {} :transforms (count registry)}
   registry))

(defn transform-ids
  "Ids of every transform in the registry."
  []
  (mapv :id registry))
