;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay.queries
  "The standing question set over the overlay, plus the rule set the
  console shares.

  Division of labour, stated once and applied throughout: **when the
  caller holds the document, Penpot's helper is the query.** The upward
  walk (`ctn/get-parent-heads` over `cfh/get-parents-with-self`) runs
  about 1 500 times faster than the datalog formulation because the
  document already encodes it as a pointer chain
  (docs.local/graph/20260819-report-graph-overlay-datascript.md). The
  functions here exist for the reverse and cross-container questions the
  document cannot answer without a full scan, and for callers that hold
  only the index. Where a function mirrors a helper, its docstring names
  that helper, and the semantic suite pins the equality; the helper stays
  the oracle and is never reimplemented here."
  (:require
   [app.common.uuid :as uuid]
   [datascript.core :as d]))

(def rules
  "The shared rule set. Edge kinds that the Ladybug projection
  materialized as relationship tables are rules here: they derive from
  indexed attributes at query time, so they can never drift from the
  attributes they restate.

  The predicates the rules restate live in `app.common.types.component`
  and stay authoritative: `instance-head?` is `(some? (:component-id
  shape))`, which is exactly the `[?s :shape/component-id _]` clause;
  `is-instance-of` requires `component-file` as the head marker just as
  `ctk/instance-of?` does; `fills-swap-slot` reads the swap slot that
  `ctk/get-swap-slot` extracted at build time."
  '[;; ancestors, self included: cfh/get-parents-with-self as a rule
    [(anc-or-self ?s ?a)
     [(identity ?s) ?a]]
    [(anc-or-self ?s ?a)
     [?s :shape/parent ?p]
     (anc-or-self ?p ?a)]

    ;; strict descendants: cfh/get-children-ids as a rule
    [(desc ?a ?d)
     [?d :shape/parent ?a]]
    [(desc ?a ?d)
     [?x :shape/parent ?a]
     (desc ?x ?d)]

    ;; IsInstanceOf: ctk/instance-of? against a live component record
    [(is-instance-of ?s ?c)
     [?s :shape/type :frame]
     [?s :shape/component-id ?cid]
     [?s :shape/component-file _]
     [?c :component/id ?cid]
     (not [?c :component/deleted true])]

    ;; RefersTo: is-main-of? read backwards — the instance shape points
    ;; at its homologue by :shape-ref, joined on the target's id
    [(refers-to ?s ?t)
     [?s :shape/shape-ref ?r]
     [?t :shape/id ?r]]

    ;; FillsSwapSlot: the swapped-in shape names the replaced slot
    [(fills-swap-slot ?s ?t)
     [?s :shape/swap-slot ?slot]
     [?t :shape/id ?slot]
     [?s :shape/id ?sid]
     [(not= ?sid ?slot)]]

    ;; UsesColor: the per-source attributes reunited
    [(uses-color ?s ?col)
     [?s :shape/fill-color ?col]]
    [(uses-color ?s ?col)
     [?s :shape/stroke-color ?col]]
    [(uses-color ?s ?col)
     [?s :shape/text-color ?col]]

    ;; UsesToken: the relation entity flattened to an edge
    [(uses-token ?s ?t)
     [?u :token-use/shape ?s]
     [?u :token-use/token ?t]]

    ;; scope helper: shapes living on a page (component containers hold
    ;; bookkeeping copies, ctf/load-component-objects)
    [(on-page ?s)
     [?s :shape/container ?c]
     [?c :container/kind :page]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; entity resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn container-eid
  [db container-id]
  (d/entid db [:container/id container-id]))

(defn shape-eid
  "Resolve the (container, shape) pair to its entity id.

  An AVET lookup on `:shape/id` narrowed by container: the id alone is
  not identity (`uuid/zero` keys every page's root frame)."
  [db container-eid shape-id]
  (some (fn [datom]
          (let [e (:e datom)]
            (when (= container-eid (:db/id (:shape/container (d/entity db e))))
              e)))
        (d/datoms db :avet :shape/id shape-id)))

(defn shape-eids
  "Every entity carrying `shape-id`, across containers."
  [db shape-id]
  (mapv :e (d/datoms db :avet :shape/id shape-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the standing question set
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity-depth
  [db eid]
  (loop [e (d/entity db eid) n 0]
    (if-let [p (:shape/parent e)]
      (recur p (inc n))
      n)))

(defn parent-heads
  "Component heads that are ancestors of (container, shape), self
  included, top-down: the overlay formulation of `ctn/get-parent-heads`.

  Adoption note: a caller holding the page objects should call the helper
  directly; the pointer chain beats the rule walk by three orders of
  magnitude. This exists for index-only callers and as the semantic pin
  that the overlay's topology equals the document's."
  [db container-id shape-id]
  (let [heads (d/q '[:find [?a ...]
                     :in $ % ?cid ?sid
                     :where
                     [?c :container/id ?cid]
                     [?s :shape/container ?c]
                     [?s :shape/id ?sid]
                     (anc-or-self ?s ?a)
                     [?a :shape/component-id _]]
                   db rules container-id shape-id)]
    (->> heads
         (sort-by #(entity-depth db %))
         (mapv #(:shape/id (d/entity db %))))))

(defn instances-of
  "Every instance head of (component, file) on any page, as
  [page-id shape-id] pairs.

  The document-side equivalent is a full scan of every page's `:objects`
  filtered by `ctk/instance-of?`; the overlay answers it from two AVET
  lookups. Scoped to page containers because component records hold their
  own copies of shapes."
  [db component-id file-id]
  (d/q '[:find ?pid ?id
         :in $ ?cid ?fid
         :where
         [?e :shape/component-id ?cid]
         [?e :shape/component-file ?fid]
         [?e :shape/container ?c]
         [?c :container/kind :page]
         [?c :container/id ?pid]
         [?e :shape/id ?id]]
       db component-id file-id))

(defn descendant-ids
  "Descendant shape ids of (container, shape), self excluded: the overlay
  formulation of `cfh/get-children-ids`, as a set (z-order is the
  document's business; the index answers membership)."
  [db container-id shape-id]
  (set (d/q '[:find [?id ...]
              :in $ % ?cid ?sid
              :where
              [?c :container/id ?cid]
              [?s :shape/container ?c]
              [?s :shape/id ?sid]
              (desc ?s ?d)
              [?d :shape/id ?id]]
            db rules container-id shape-id)))

(defn descendant-ids-of-type
  "Descendants of (container, shape) whose `:shape/type` is `type`: the
  composition the report names as the overlay's advantage — a predicate
  is one more clause, not one more function."
  [db container-id shape-id type]
  (set (d/q '[:find [?id ...]
              :in $ % ?cid ?sid ?type
              :where
              [?c :container/id ?cid]
              [?s :shape/container ?c]
              [?s :shape/id ?sid]
              (desc ?s ?d)
              [?d :shape/type ?type]
              [?d :shape/id ?id]]
            db rules container-id shape-id type)))

(defn shapes-by-name
  "Shapes named exactly `name`, as [container-id shape-id] pairs, from
  one AVET lookup."
  [db name]
  (d/q '[:find ?cid ?id
         :in $ ?name
         :where
         [?s :shape/name ?name]
         [?s :shape/container ?c]
         [?c :container/id ?cid]
         [?s :shape/id ?id]]
       db name))

(defn instance-heads
  "Every instance head on any page, as [page-id shape-id] pairs
  (`ctk/instance-head?` over the whole document)."
  [db]
  (d/q '[:find ?pid ?id
         :where
         [?s :shape/component-id _]
         [?s :shape/container ?c]
         [?c :container/kind :page]
         [?c :container/id ?pid]
         [?s :shape/id ?id]]
       db))

(defn shapes-using-color
  "Shapes referencing library color `color-id`, as [container-id shape-id]
  pairs (the LinkUsedColors question)."
  [db color-id]
  (d/q '[:find ?cid ?id
         :in $ % ?colid
         :where
         [?col :color/id ?colid]
         (uses-color ?s ?col)
         [?s :shape/container ?c]
         [?c :container/id ?cid]
         [?s :shape/id ?id]]
       db rules color-id))

(defn shapes-using-typography
  "Shapes referencing `typography-id`, as [container-id shape-id] pairs
  (the LinkUsedTypographies question)."
  [db typography-id]
  (d/q '[:find ?cid ?id
         :in $ ?tid
         :where
         [?t :typography/id ?tid]
         [?s :shape/uses-typography ?t]
         [?s :shape/container ?c]
         [?c :container/id ?cid]
         [?s :shape/id ?id]]
       db typography-id))

(defn shapes-using-token
  "Applied-token uses of the token named `token-name`, as
  [container-id shape-id property] triples (the LinkAppliedTokens
  question)."
  [db token-name]
  (d/q '[:find ?cid ?id ?prop
         :in $ ?name
         :where
         [?t :token/name ?name]
         [?u :token-use/token ?t]
         [?u :token-use/prop ?prop]
         [?u :token-use/shape ?s]
         [?s :shape/container ?c]
         [?c :container/id ?cid]
         [?s :shape/id ?id]]
       db token-name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; inventory and parity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- attr-count
  [db attr]
  (count (d/datoms db :avet attr)))

(defn stats
  "Entity and edge inventory of the overlay."
  [db]
  {:datoms       (count db)
   :documents    (attr-count db :document/id)
   :containers   (attr-count db :container/id)
   :shapes       (attr-count db :shape/id)
   :components   (attr-count db :component/id)
   :colors       (attr-count db :color/id)
   :typographies (attr-count db :typography/id)
   :token-sets   (attr-count db :token-set/id)
   :tokens       (attr-count db :token/id)
   :token-uses   (attr-count db :token-use/shape)})

(defn edge-counts
  "Count of every edge kind under the overlay's own conventions (all
  containers, deleted components included where a rule does not exclude
  them)."
  [db]
  {:is-child-of     (+ (attr-count db :shape/parent)
                       (attr-count db :container/document)
                       (attr-count db :component/document))
   :is-instance-of  (count (d/q '[:find ?s ?c :in $ % :where (is-instance-of ?s ?c)]
                                db rules))
   :refers-to       (count (d/q '[:find ?s ?t :in $ % :where (refers-to ?s ?t)]
                                db rules))
   :fills-swap-slot (count (d/q '[:find ?s ?t :in $ % :where (fills-swap-slot ?s ?t)]
                                db rules))
   :uses-color      (+ (attr-count db :shape/fill-color)
                       (attr-count db :shape/stroke-color)
                       (attr-count db :shape/text-color))
   :uses-typography (attr-count db :shape/uses-typography)
   :uses-token      (attr-count db :token-use/shape)})

(defn ladybug-parity-counts
  "Edge counts under the Ladybug projection's own conventions, for the
  roadmap's structure check: page shapes only (component containers are
  an overlay extension), root frames excluded (the projection skips
  them), live components only.

  A count that disagrees with the projection's relation table is a
  finding to explain in writing, never to average away: the two likely
  causes are a porting bug here and a projection limitation there, and
  they demand opposite responses."
  [db]
  (let [zero      uuid/zero
        page-shapes (d/q '[:find [?s ...]
                           :in $ ?zero
                           :where
                           [?s :shape/container ?c]
                           [?c :container/kind :page]
                           (not [?s :shape/id ?zero])]
                         db zero)
        pages     (count (d/q '[:find [?c ...]
                                :where [?c :container/kind :page]]
                              db))
        live-comps (count (d/q '[:find [?c ...]
                                 :where
                                 [?c :component/id _]
                                 (not [?c :component/deleted true])]
                               db))]
    {:is-child-of    (+ (count page-shapes) pages live-comps)
     :is-instance-of (count (d/q '[:find ?s ?c
                                   :in $ %
                                   :where
                                   (on-page ?s)
                                   (is-instance-of ?s ?c)]
                                 db rules))
     :refers-to      (count (d/q '[:find ?s ?t
                                   :in $ %
                                   :where
                                   (on-page ?s)
                                   (refers-to ?s ?t)
                                   (on-page ?t)]
                                 db rules))
     :fills-swap-slot (count (d/q '[:find ?s ?t
                                    :in $ %
                                    :where
                                    (on-page ?s)
                                    (fills-swap-slot ?s ?t)
                                    (on-page ?t)]
                                  db rules))}))

(defn component-main-instances
  "[component-id main-instance-page main-instance-id] for every live
  component that declares a main instance (the beadpot main-instance
  linkage claim)."
  [db]
  (d/q '[:find ?id ?pid ?mid
         :where
         [?c :component/id ?id]
         [?c :component/main-instance-page ?pid]
         [?c :component/main-instance-id ?mid]
         (not [?c :component/deleted true])]
       db))
