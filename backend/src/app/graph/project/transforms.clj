;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.transforms
  "Derived graph links, ported from beadpot's post-projection transforms.

  Each entry in `registry` carries the shared transform id
  (`app.graph.meta`), so a build records exactly what it produced and beadpot
  runs only the complement in Python. Adding a transform here is therefore the
  whole port step: nothing else has to be told about it.

  The Cypher mirrors beadpot's, which is written through its query builder but
  reduces to the same statements — the parity harness diffs the resulting
  graphs, so a semantic drift shows up as a differing edge set rather than as
  a differing query."
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

  beadpot `graph/transform/assets.py::LinkComponentInstances`. An instance
  head carries `:component-id` pointing at its component record (see
  `app.common.types.component/instance-of?`); every such head is linked, the
  main instance and any copy root alike."
  [^Connection conn]
  (run-scalar! conn
               (str "MATCH (f:Frame), (c:Component) "
                    "WHERE f.component_id = c.id "
                    "AND NOT COALESCE(c.deleted, false) "
                    "MERGE (f)-[:IsInstanceOf]->(c) "
                    "RETURN count(*);")))

(defn- shape-pair-statements
  "One statement per (from, to) shape-table pair.

  Ladybug cannot create a relationship bound by multiple node labels in a
  single `MERGE` (kuzudb/kuzu#5841), which is why beadpot loops over label
  pairs too; the loop is a dialect constraint, not a modelling choice."
  [f]
  (for [from nodes/shape-tables
        to   nodes/shape-tables]
    (f from to)))

(defn- link-shape-refs!
  "`RefersTo` from an instance shape to its homologue in the main instance.

  beadpot `graph/transform/assets.py::LinkShapeRefs`, driven by `shape-ref`."
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

  beadpot `graph/transform/swap_slots.py::LinkSwapSlots`. Penpot records a
  component sub-shape swap as a `swap-slot-<uuid>` entry in the *replacing*
  shape's `touched` set, where `<uuid>` is the replaced slot shape from the
  master. The entries are then stripped from `touched`, mirroring
  `app.common.types.component/normal-touched-groups`."
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
  "Every transform this backend applies, in application order.

  `:id` is the shared vocabulary with beadpot (`app.graph.meta`); `:rel` names
  what the transform produces, for the ingest report."
  [{:id "link-component-instances" :rel :IsInstanceOf  :run link-component-instances!}
   {:id "link-shape-refs"          :rel :RefersTo      :run link-shape-refs!}
   {:id "link-swap-slots"          :rel :FillsSwapSlot :run link-swap-slots!}])

(defn apply-transforms!
  "Apply every registered transform to an already loaded graph.

  Returns `{:ids [...] :counts {...} :transforms n}`; `:ids` is what the build
  records in `GraphMeta`, so beadpot subtracts exactly this set from its own
  pipeline."
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
