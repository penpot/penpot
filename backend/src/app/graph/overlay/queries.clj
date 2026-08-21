;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay.queries
  "The relation vocabulary and the standing question set over the overlay.

  **The relation vocabulary is a rule set, not raw clauses**
  (docs.local/graph/20260820-report-graph-overlay-datascript.md).
  `child-of`, `descendant-of`, `instance-of`, `refers-to`,
  `fills-swap-slot` and `uses-token` are named once here, implemented per
  engine and per encoding, and every consumer query and every backported
  beadpot assertion is written against the rules only. The encodings can
  change under a rule without a consumer noticing: `refers-to` reads a
  builder-resolved reference today and read an id join before,
  `uses-token` reads folded token attributes today and read relation
  entities before, and the consumer texts did not move.

  Rule signatures are subject first: `(child-of ?c ?p)` reads \"?c is a
  child of ?p\", `(descendant-of ?d ?a)` reads \"?d is a descendant of
  ?a\".

  Division of labour, stated once and applied throughout: **when the
  caller holds the document, Penpot's helper is the query.** The upward
  walk (`ctn/get-parent-heads` over `cfh/get-parents-with-self`) runs
  about three orders of magnitude faster than any datalog formulation
  because the document already encodes it as a pointer chain, and even
  the interval form of containment loses to `cfh/get-children-ids` by an
  order of magnitude (14.89 ms against 1.09 ms on an 808-child frame).
  The functions here exist for the reverse and cross-container questions
  the document cannot answer without a full scan, and for callers that
  hold only the index. Where a function mirrors a helper, its docstring
  names that helper, and the semantic suite pins the equality; the helper
  stays the oracle and is never reimplemented here."
  (:require
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [datascript.core :as d]))

(def rules
  "The relation vocabulary, plus the helper fragments the standing set
  composes (`anc-or-self`, `uses-color`, `on-page`).

  `descendant-of` is implemented over the global Euler-tour intervals the
  builder assigns: two comparisons instead of a recursive walk (14.89 ms
  against 93.98 ms on an 808-child frame). `descendant-of-walk` keeps the
  recursive form: it is the invariant check for the numbering and the
  slow side of that measurement, never a consumer surface.

  `uses-token` hides the folded token encoding: an attribute-variable
  clause over the closed vocabulary of `app.common.types.token/all-keys`,
  joined to the token entity by name, so the encoding stays reversible
  behind the rule."
  (into
   '[;; containment, one edge
     [(child-of ?c ?p)
      [?c :shape/parent ?p]]

     ;; containment, transitive: the Euler-tour interval form
     [(descendant-of ?d ?a)
      [?a :shape/enter ?e0]
      [?a :shape/exit ?e1]
      [?d :shape/enter ?de]
      [(< ?e0 ?de)]
      [(< ?de ?e1)]]

     ;; containment, transitive: the recursive form, kept as the
     ;; numbering's invariant check and the measured slow side
     [(descendant-of-walk ?d ?a)
      (child-of ?d ?a)]
     [(descendant-of-walk ?d ?a)
      (child-of ?d ?x)
      (descendant-of-walk ?x ?a)]

     ;; ancestors, self included: cfh/get-parents-with-self as a rule
     [(anc-or-self ?s ?a)
      [(identity ?s) ?a]]
     [(anc-or-self ?s ?a)
      [?s :shape/parent ?p]
      (anc-or-self ?p ?a)]

     ;; IsInstanceOf: ctk/instance-of? against a live component record
     [(instance-of ?s ?c)
      [?s :shape/type :frame]
      [?s :shape/component-id ?cid]
      [?s :shape/component-file _]
      [?c :component/id ?cid]
      (not [?c :component/deleted true])]

     ;; RefersTo: the reference ctf/find-ref-shape resolved at build time
     [(refers-to ?s ?t)
      [?s :shape/refers-to ?t]]

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

     ;; scope helper: shapes living on a page (component containers hold
     ;; bookkeeping copies, ctf/load-component-objects)
     [(on-page ?s)
      [?s :shape/container ?c]
      [?c :container/kind :page]]]

   ;; UsesToken over the folded encoding; the closed vocabulary is
   ;; embedded as data so the rule stays engine-portable
   [(vector '(uses-token ?s ?tok)
            '[?s ?a ?n]
            [(list 'contains? overlay/token-attrs '?a)]
            '[?tok :token/name ?n])]))

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
;; the standing question set — every relation traversal via the rules
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
  filtered by `ctk/instance-of?`; the overlay answers it from the
  `instance-of` rule plus one attribute constraint. Scoped to page
  containers because component records hold their own copies of shapes."
  [db component-id file-id]
  (d/q '[:find ?pid ?id
         :in $ % ?cid ?fid
         :where
         [?comp :component/id ?cid]
         (instance-of ?e ?comp)
         [?e :shape/component-file ?fid]
         (on-page ?e)
         [?e :shape/container ?c]
         [?c :container/id ?pid]
         [?e :shape/id ?id]]
       db rules component-id file-id))

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
              (descendant-of ?d ?s)
              [?d :shape/id ?id]]
            db rules container-id shape-id)))

(defn descendant-ids-walk
  "The recursive-rule form of `descendant-ids`: the numbering invariant's
  slow side, kept for the A/B measurement, never a consumer surface."
  [db container-id shape-id]
  (set (d/q '[:find [?id ...]
              :in $ % ?cid ?sid
              :where
              [?c :container/id ?cid]
              [?s :shape/container ?c]
              [?s :shape/id ?sid]
              (descendant-of-walk ?d ?s)
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
              (descendant-of ?d ?s)
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
         :in $ %
         :where
         [?s :shape/component-id _]
         (on-page ?s)
         [?s :shape/container ?c]
         [?c :container/id ?pid]
         [?s :shape/id ?id]]
       db rules))

(defn refers-to-pairs
  "Resolved reference pairs [[src-container src-id tgt-container tgt-id]]
  from the `refers-to` rule (builder-resolved `ctf/find-ref-shape`)."
  [db]
  (d/q '[:find ?scid ?sid ?tcid ?tid
         :in $ %
         :where
         (refers-to ?s ?t)
         [?s :shape/container ?sc] [?sc :container/id ?scid]
         [?s :shape/id ?sid]
         [?t :shape/container ?tc] [?tc :container/id ?tcid]
         [?t :shape/id ?tid]]
       db rules))

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

(defn- applied-props
  "The applied-token properties under which `eid` carries `token-name`,
  recovered from the folded attributes of the entity."
  [db eid token-name]
  (let [e (d/entity db eid)]
    (into []
          (keep (fn [attr]
                  (when (= token-name (get e attr))
                    (overlay/token-attr->prop attr))))
          overlay/token-attrs)))

(defn shapes-using-token
  "Applied-token uses of the token named `token-name`, as
  [container-id shape-id property] triples (the LinkAppliedTokens
  question). The relation comes from the `uses-token` rule; the property
  is recovered from the folded attributes per matched shape."
  [db token-name]
  (let [pairs (d/q '[:find ?cid ?id ?s
                     :in $ % ?name
                     :where
                     [?tok :token/name ?name]
                     (uses-token ?s ?tok)
                     [?s :shape/container ?c]
                     [?c :container/id ?cid]
                     [?s :shape/id ?id]]
                   db rules token-name)]
    (into #{}
          (mapcat (fn [[cid id eid]]
                    (map (fn [prop] [cid id prop])
                         (applied-props db eid token-name))))
          pairs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; inventory and parity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- attr-count
  [db attr]
  (count (d/datoms db :avet attr)))

(defn token-application-count
  "Datoms across the folded token attributes."
  [db]
  (transduce (map #(count (d/datoms db :aevt %))) + 0 overlay/token-attrs))

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
   :token-uses   (token-application-count db)
   :refs-resolved (attr-count db :shape/refers-to)
   :refs-carried  (attr-count db :shape/shape-ref)})

(defn edge-counts
  "Count of every edge kind under the overlay's own conventions (all
  containers, deleted components included where a rule does not exclude
  them)."
  [db]
  {:is-child-of     (+ (attr-count db :shape/parent)
                       (attr-count db :container/document)
                       (attr-count db :component/document))
   :is-instance-of  (count (d/q '[:find ?s ?c :in $ % :where (instance-of ?s ?c)]
                                db rules))
   :refers-to       (attr-count db :shape/refers-to)
   :fills-swap-slot (count (d/q '[:find ?s ?t :in $ % :where (fills-swap-slot ?s ?t)]
                                db rules))
   :uses-color      (+ (attr-count db :shape/fill-color)
                       (attr-count db :shape/stroke-color)
                       (attr-count db :shape/text-color))
   :uses-typography (attr-count db :shape/uses-typography)
   :uses-token      (token-application-count db)})

(defn ladybug-parity-counts
  "Edge counts under the Ladybug projection's own conventions, for the
  roadmap's structure check: page shapes only (component containers are
  an overlay extension), root frames excluded (the projection skips
  them), live components only.

  `refers-to` diverges by construction since the builder-resolution
  retrofit: the projection's RefersTo is an id join over page shapes,
  the overlay's is `ctf/find-ref-shape`'s answer, and the two differ
  exactly where the id join is ambiguous or the helper's fallbacks
  apply. The difference is a semantics upgrade, recorded rather than
  reconciled."
  [db]
  (let [zero      uuid/zero
        page-shapes (d/q '[:find [?s ...]
                           :in $ % ?zero
                           :where
                           (on-page ?s)
                           (not [?s :shape/id ?zero])]
                         db rules zero)
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
                                   (instance-of ?s ?c)]
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
