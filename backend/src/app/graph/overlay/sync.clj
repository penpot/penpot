;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay.sync
  "Incremental overlay updates from Penpot change vectors.

  The invariant this namespace defends: an overlay maintained from change
  vectors equals an overlay rebuilt from the changed document. The change
  semantics are `app.common.files.changes/process-change`'s, mirrored
  clause by clause and cited per function; where a change touches state
  the overlay does not index (geometry, style payloads), the mirror is a
  no-op by design.

  Two derived layers ride every structural change:

  - **Euler intervals.** A structural change renumbers its container from
    beyond the current global maximum, from overlay topology alone. The
    numbers differ from a rebuild's (different DFS order, different
    range); interval containment is the invariant, and the suite pins
    containment, never the numbers.
  - **Resolved references.** `:shape/refers-to` is the builder's
    `ctf/find-ref-shape` answer, so the sync path re-resolves it for
    every shape whose resolution inputs changed: the shape itself on
    `:shape-ref`, the subtree on head-attribute changes and reparents,
    and every instance subtree of a component whose record or own copy
    changed. The resolution walks the same head chain the helper walks,
    over the index instead of the document.

  One authored attribute rides every change that can move a child:
  `:shape/order`, the sibling ordinal the builder derives from each
  container's `:shapes` vector. The fold keeps it **equal to a rebuild's**,
  because a consumer holding an index and no document reads it to learn a
  shape's position among its siblings
  (`pp:graph:sibling-order-needs-an-authored-attribute`,
  `pp:graph:mirror-the-reorder-children-change`); the two derived layers
  above are pinned by their invariants instead, because their values are
  not comparable between the two paths.

  Everything here is pure: db value in, db value out. The session layer
  owns the atom and the revision bookkeeping, and `staleness` tells it
  when a batch has left the index behind for good."
  (:require
   [app.common.data :as ctd]
   [app.common.files.changes :as cp]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.page :as ctp]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.queries :as queries]
   [datascript.core :as d]))

(def supported-change-types
  #{:add-obj :mod-obj :del-obj :mov-objects :reorder-children
    :add-page :del-page :mod-page
    :add-component :mod-component :del-component
    :restore-component :purge-component
    :add-color :mod-color :del-color
    :add-typography :mod-typography :del-typography
    :set-tokens-lib :set-token :set-token-set
    :rename-token-set-group :move-token-set :move-token-set-group})

(defn supported-change?
  [change]
  (contains? supported-change-types (:type change)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolution against the live db
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- doc-eid
  [db]
  (some-> (first (d/datoms db :avet :document/id)) :e))

(defn- sync-asset-ctx
  "The asset half of `app.graph.overlay`'s build-ctx, resolved against the
  live db: values are eids rather than tempids."
  [db]
  {:colors         (into {} (map (fn [dtm] [(:v dtm) (:e dtm)]))
                         (d/datoms db :avet :color/id))
   :typographies   (into {} (map (fn [dtm] [(:v dtm) (:e dtm)]))
                         (d/datoms db :avet :typography/id))})

(defn- change-container-id
  [{:keys [page-id component-id]}]
  (or page-id component-id))

(defn- retract-shape-tx
  [_db eid]
  [[:db.fn/retractEntity eid]])

(defn- child-eids
  [db eid]
  (mapv :e (d/datoms db :avet :shape/parent eid)))

(defn- subtree-eids
  "The shape entity plus every descendant, via the reverse parent index.

  `seen` is what makes the walk return on a cyclic index: the children
  closure is the same thing `app.common.files.helpers/get-children-ids`
  walks with its own `processed` set, and an index that carries a cycle
  (a change that escaped `app.common.files.changes/valid-move?`, or a
  rebuild of a document that already had one) would otherwise accumulate
  every revisit and never reach the end of the loop."
  [db root-eid]
  (loop [acc      []
         seen     #{root-eid}
         frontier [root-eid]]
    (if-let [eid (peek frontier)]
      (let [children (remove seen (child-eids db eid))]
        (recur (conj acc eid)
               (into seen children)
               (into (pop frontier) children)))
      acc)))

(defn- container-shape-eids
  [db container-eid]
  (mapv :e (d/datoms db :avet :shape/container container-eid)))

(defn- retract-container-shapes-tx
  [db container-eid]
  (into []
        (mapcat #(retract-shape-tx db %))
        (container-shape-eids db container-eid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Euler renumbering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- next-euler-counter
  [db]
  (transduce (map (comp long :v)) (completing (fn [acc v] (max acc (inc v)))) 0
             (d/datoms db :avet :shape/exit)))

(defn- renumber-container-tx
  "Fresh Euler intervals for every shape of `ceid`, drawn from beyond the
  current global maximum so ranges stay disjoint file-wide. Rebuilt from
  overlay topology alone: the DFS order differs from the builder's,
  interval containment is the invariant."
  [db ceid]
  (let [eids     (container-shape-eids db ceid)
        by-parent (group-by (fn [e] (:db/id (:shape/parent (d/entity db e)))) eids)
        roots    (vec (sort (get by-parent ceid [])))
        base     (next-euler-counter db)]
    (loop [events  (into [] (map (fn [e] [:enter e])) (rseq roots))
           counter (long base)
           seen    (transient #{})
           tx      (transient [])]
      (if-let [[kind eid] (peek events)]
        (let [events (pop events)]
          (case kind
            :enter
            (recur (-> events
                       (conj [:exit eid])
                       (into (map (fn [c] [:enter c]))
                             (sort (get by-parent eid []))))
                   (inc counter)
                   (conj! seen eid)
                   (conj! tx [:db/add eid :shape/enter counter]))
            :exit
            (recur events
                   (inc counter)
                   seen
                   (conj! tx [:db/add eid :shape/exit counter]))))
        (let [seen (persistent! seen)
              tx   (persistent! tx)]
          ;; degenerate intervals for shapes outside the reachable tree
          (first
           (reduce (fn [[tx c] eid]
                     (if (contains? seen eid)
                       [tx c]
                       [(conj tx
                              [:db/add eid :shape/enter c]
                              [:db/add eid :shape/exit (inc c)])
                        (+ c 2)]))
                   [tx (+ base (* 2 (count seen)))]
                   eids)))))))

(defn- with-renumber
  [db ceid]
  (d/db-with db (renumber-container-tx db ceid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sibling order
;;
;; `:shape/order` is the position a shape holds in its parent's `:shapes`
;; vector, and the fold draws it the way the builder does: dense within
;; one parent, from the parent's child list. Every change that can insert,
;; move or remove a child recomputes the affected parent's list here, and
;; the ordinals it does not move keep the datom they already carry, so an
;; appended child costs one datom and a deletion costs the siblings after
;; it. The list is read back from the ordinals themselves, which is how
;; the fold holds an order the document owns.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- children-in-order
  "The children of `peid` in the order the index holds: their sibling
  ordinals ascending. A child that carries no ordinal sorts first, which
  only a defect produces: `sibling-order` gives one to every shape a
  vector names, and `with-order` gives one to every shape it lists."
  [db peid]
  (->> (d/datoms db :avet :shape/parent peid)
       (map :e)
       (sort-by #(:shape/order (d/entity db %)))
       vec))

(defn- order-tx
  "Transaction data for the child list of one parent, `children` in order:
  the dense ordinal each child holds, and an `:db/add` only for the child
  whose ordinal moves. A child the change left where it was keeps its
  datom and its transaction."
  [db children]
  (into []
        (keep-indexed (fn [i eid]
                        (when (not= i (:shape/order (d/entity db eid)))
                          [:db/add eid :shape/order i])))
        children))

(defn- with-order
  [db children]
  (let [tx (order-tx db children)]
    (cond-> db (seq tx) (d/db-with tx))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reference re-resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parent-heads-of
  "The instance heads on the parent chain of `eid`, self included,
  top-down: `ctn/get-parent-heads` over the index."
  [db eid]
  (loop [cur (d/entity db eid) acc ()]
    (if (and cur (:shape/id cur))
      (recur (:shape/parent cur)
             (cond-> acc (:shape/component-id cur) (conj cur)))
      (vec acc))))

(defn- resolve-ref-eid
  "The sync-path mirror of `ctf/find-ref-shape`: try each parent head
  top-down; through the head's component record (deleted included) find
  the container that holds the component's shapes — the component's own
  copy when it carries one, the main-instance page otherwise — and look
  the `:shape/shape-ref` id up there."
  [db eid]
  (let [ref-id (:shape/shape-ref (d/entity db eid))]
    (when ref-id
      (some (fn [head]
              (when-let [comp (d/entity db [:component/id (:shape/component-id head)])]
                (let [ceid (if (:container/id comp)
                             (:db/id comp)
                             (some->> (:component/main-instance-page comp)
                                      (queries/container-eid db)))]
                  (when ceid
                    (queries/shape-eid db ceid ref-id)))))
            (parent-heads-of db eid)))))

(defn- reresolve-refs-tx
  [db eids]
  (into []
        (keep (fn [eid]
                (let [current (:db/id (:shape/refers-to (d/entity db eid)))
                      fresh   (resolve-ref-eid db eid)]
                  (cond
                    (= current fresh) nil
                    (nil? fresh)      [:db.fn/retractAttribute eid :shape/refers-to]
                    :else             [:db/add eid :shape/refers-to fresh]))))
        eids))

(defn- with-reresolved
  [db eids]
  (let [tx (reresolve-refs-tx db eids)]
    (cond-> db (seq tx) (d/db-with tx))))

(defn- component-affected-eids
  "Every shape whose reference resolution reads component `cid`: the
  subtrees of its instance heads, plus its own container copies."
  [db cid]
  (let [heads (mapv :e (d/datoms db :avet :shape/component-id cid))
        own   (when-let [comp (d/entity db [:component/id cid])]
                (when (:container/id comp)
                  (container-shape-eids db (:db/id comp))))]
    (-> []
        (into (mapcat #(subtree-eids db %)) heads)
        (into own)
        distinct
        vec)))

(defn- with-component-reresolved
  [db cid]
  (with-reresolved db (component-affected-eids db cid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; per-change application
;;
;; Each function returns {:db db :applied? bool :reason kw?}.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ok
  [db]
  {:db db :applied? true})

(defn- skip
  [db reason]
  {:db db :applied? false :reason reason})

(defn- apply-add-obj
  "Mirror of `process-change :add-obj` -> `ctst/add-shape`: the effective
  parent is `(or parent-id frame-id)`, falling back to the root frame
  when absent from the container, then to the container itself. The child
  lands at `:index` when the change carries one and at the end of its
  parent's children otherwise, which is `add-shape`'s own placement."
  [db {:keys [id obj frame-id parent-id index] :as change}]
  (let [container-id (change-container-id change)
        ceid         (queries/container-eid db container-id)]
    (if-not ceid
      (skip db :missing-container)
      (let [pid      (or parent-id frame-id)
            peid     (or (when (and pid (not= pid id))
                           (queries/shape-eid db ceid pid))
                         (queries/shape-eid db ceid uuid/zero)
                         ceid)
            ctx      (sync-asset-ctx db)
            shape    (assoc obj :id id)
            tempid   (overlay/shape-tempid container-id id)
            existing (queries/shape-eid db ceid id)
            siblings (children-in-order db peid)
            tx       (-> (if existing (retract-shape-tx db existing) [])
                         (conj (merge {:db/id           tempid
                                       :shape/id        id
                                       :shape/container ceid
                                       :shape/parent    peid}
                                      (overlay/shape-attrs shape)
                                      (overlay/shape-asset-attrs shape ctx))))
            db'      (d/db-with db tx)
            new-eid  (queries/shape-eid db' ceid id)
            placed   (when (some? new-eid)
                       (cond
                         ;; an id the parent already held keeps its slot
                         (some? existing) (mapv #(if (= % existing) new-eid %) siblings)
                         (nil? index)     (conj siblings new-eid)
                         :else            (ctd/insert-at-index siblings index [new-eid])))]
        (-> db'
            (with-reresolved (if new-eid [new-eid] []))
            (cond-> (some? placed) (with-order placed))
            (with-renumber ceid)
            (ok))))))

(def ^:private plain-attr->overlay
  {:name           :shape/name
   :component-id   :shape/component-id
   :component-file :shape/component-file
   :shape-ref      :shape/shape-ref})

(def ^:private head-attrs
  "Shape attributes whose change moves reference resolution for the whole
  subtree below the shape."
  #{:component-id :component-file})

(defn- replace-attr-tx
  [eid attr val]
  (if (some? val)
    [[:db/add eid attr val]]
    [[:db.fn/retractAttribute eid attr]]))

(defn- replace-many-tx
  [eid attr refs]
  (into [[:db.fn/retractAttribute eid attr]]
        (map (fn [r] [:db/add eid attr r]))
        refs))

(defn- swap-slot-tx
  "Mirror of `process-operation :set-touched`: `touched` only sticks on a
  shape inside a component copy, and the overlay only indexes the swap
  slot `ctk/get-swap-slot` extracts from it."
  [db eid touched]
  (let [in-copy? (some? (:shape/shape-ref (d/entity db eid)))
        slot     (when (and in-copy? (seq touched))
                   (ctk/get-swap-slot {:touched touched}))]
    (replace-attr-tx eid :shape/swap-slot slot)))

(defn- applied-tokens-tx
  "Replace the folded token attributes from a fresh `:applied-tokens`
  value: retract the closed vocabulary, assert the new applications."
  [eid applied]
  (into (mapv (fn [a] [:db.fn/retractAttribute eid a]) overlay/token-attrs)
        (keep (fn [[k v]]
                (when-let [attr (overlay/token-attr k)]
                  [:db/add eid attr v])))
        (or applied {})))

(defn- set-attr-tx
  "Transaction data for one attribute a `:set` operation writes on `eid`.
  Attributes the overlay does not index yield nil (a designed no-op),
  mirroring `ctn/set-shape-attr` only for the indexed slice.

  The asset attributes come in pairs: the raw ids the payload names and
  the references the ctx resolves from them. A raw id is written even
  when the ctx has no asset for it, which is the state
  `apply-add-asset` repairs later."
  [db eid ctx attr val]
  (cond
    (contains? plain-attr->overlay attr)
    (replace-attr-tx eid (plain-attr->overlay attr) val)

    (= :touched attr)
    (swap-slot-tx db eid val)

    (= :fills attr)
    (let [ids (overlay/fill-color-ref-ids val)]
      (concat (replace-many-tx eid :shape/fill-color-ref-id ids)
              (replace-many-tx eid :shape/fill-color (keep (:colors ctx) ids))))

    (= :strokes attr)
    (let [ids (overlay/stroke-color-ref-ids val)]
      (concat (replace-many-tx eid :shape/stroke-color-ref-id ids)
              (replace-many-tx eid :shape/stroke-color (keep (:colors ctx) ids))))

    (= :content attr)
    (let [col-ids (overlay/content-color-ref-ids val)
          typ-ids (overlay/content-typography-ref-ids val)]
      (concat (replace-many-tx eid :shape/text-color-ref-id col-ids)
              (replace-many-tx eid :shape/text-color (keep (:colors ctx) col-ids))
              (replace-many-tx eid :shape/typography-ref-id typ-ids)
              (replace-many-tx eid :shape/uses-typography
                               (keep (:typographies ctx) typ-ids))))

    (= :applied-tokens attr)
    (applied-tokens-tx eid val)

    :else nil))

(defn- assign-op-tx
  "Transaction data for one `:assign` operation, mirroring
  `common/src/app/common/files/changes.cljc::process-operation`: the
  operation carries an encoded attribute map, the handler decodes it
  against the shape's own `:type` and replays it as one `:set` per
  changed attribute. The decode runs here against the stored
  `:shape/type`, and each attribute goes through the same `:set` mirror.

  Two differences from the handler, both invisible in the indexed slice.
  The handler drops an attribute whose value already equals the shape's,
  a test the overlay cannot make because it stores no values, and a
  `:set` writing the value already there is idempotent. And the
  handler's `:ignore-touched` and `:ignore-geometry` flags steer
  `:touched` bookkeeping and geometry, neither of which reaches an
  overlay attribute."
  [db eid ctx value]
  (let [modifications (-> (assoc value :type (:shape/type (d/entity db eid)))
                          (cp/decode-shape-attrs)
                          (dissoc :type))]
    (into []
          (mapcat (fn [[attr val]] (set-attr-tx db eid ctx attr val)))
          modifications)))

(defn- set-op-tx
  "Transaction data for one operation on `eid`, over the four members of
  `app.common.files.changes/schema:operation`.

  `:set` and `:assign` write attributes and `:set-touched` writes the
  swap slot the overlay indexes. `:set-remote-synced` is a no-op here:
  it writes `:remote-synced` on a shape inside a component copy, and no
  overlay attribute carries that name. The Ladybug shape tables do carry
  the column, because `app.graph.schema.nodes` derives one per key of
  the shape schema, so the same operation is a real gap on that tier and
  none on this one."
  [db eid ctx {:keys [type attr val touched value]}]
  (case type
    :set               (set-attr-tx db eid ctx attr val)
    :assign            (assign-op-tx db eid ctx value)
    :set-touched       (swap-slot-tx db eid touched)
    :set-remote-synced nil
    nil))

(defn- op-attrs
  "The shape attributes one operation writes: a `:set`'s own `:attr`, and
  an `:assign`'s attribute keys. The reference re-resolution below keys
  off which attributes moved, so an `:assign` carrying a head attribute
  has to answer here too."
  [{:keys [type attr value]}]
  (case type
    :set    (when attr #{attr})
    :assign (disj (set (keys value)) :type)
    nil))

(defn- apply-mod-obj
  [db {:keys [id operations] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      (skip db :missing-shape)
      (let [ctx (sync-asset-ctx db)
            tx  (into [] (mapcat #(set-op-tx db eid ctx %)) operations)
            db' (cond-> db (seq tx) (d/db-with tx))
            set-attrs (into #{} (mapcat op-attrs) operations)
            affected  (cond
                        (some head-attrs set-attrs) (subtree-eids db' eid)
                        (contains? set-attrs :shape-ref) [eid]
                        :else nil)]
        (ok (cond-> db' (seq affected) (with-reresolved affected)))))))

(defn- apply-del-obj
  "Mirror of `process-change :del-obj` -> `ctst/delete-shape`: the shape
  and its subtree leave, and the child list of the parent it left closes
  the gap it opened, which is where a deletion moves the ordinals of its
  later siblings."
  [db {:keys [id] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      ;; Penpot emits one :del-obj per selected shape, so a parent's
      ;; deletion may already have removed this node; not a defect.
      (ok db)
      (let [peid (:db/id (:shape/parent (d/entity db eid)))
            db'  (d/db-with db (into []
                                     (mapcat #(retract-shape-tx db %))
                                     (subtree-eids db eid)))]
        (ok (with-order db' (children-in-order db' peid)))))))

(defn- container-objects
  "The destination container's objects map, reconstructed from the index
  for `app.common.files.changes/valid-move?`: shape id → `:parent-id`,
  `:shapes`, `:component-id` and `:shape-ref`, which are the keys the move
  decision reads and the only ones it reads.

  The predicate takes the objects-shaped view rather than an index, so
  this is the view: the index is not a second way to answer which moves
  are legal (`pp:graph:database-is-the-boundary`). Parent and child ids
  are shape ids, as they are in the document, so a shape the index parents
  on its container — the page root frame, the root copy of a component
  container — arrives with no parent, and the walk up stops where the
  document's stops.

  Built for the whole container, the order `renumber-container-tx` beside
  it already reads: the decision walks the moved shape's subtree and the
  destination's chain, and neither stops where it started. The child lists
  are unsorted, because the walk reads membership and never a position."
  [db ceid]
  (let [eids     (container-shape-eids db ceid)
        children (group-by (fn [e] (:db/id (:shape/parent (d/entity db e)))) eids)]
    (into {}
          (map (fn [eid]
                 (let [ent (d/entity db eid)]
                   [(:shape/id ent)
                    {:parent-id    (:shape/id (:shape/parent ent))
                     :shapes       (into [] (map #(:shape/id (d/entity db %)))
                                        (get children eid))
                     :component-id (:shape/component-id ent)
                     :shape-ref    (:shape/shape-ref ent)}])))
          eids)))

(defn- apply-mov-objects
  "Reparenting, and the order that comes with it: `:mov-objects` carries
  shape ids, a parent id, and either an `:index` or an `:after-shape`
  (`common/src/app/common/files/changes.cljc`), so the moved shapes land
  in the destination's child list where `insert-at-index` puts them and
  the parents they left close their gaps. The index is read against the
  destination's list as it stands, moved shapes included, because that is
  the list the document splits.

  The move itself is the document's decision and not a second one:
  `process-change :mov-objects` asks `valid-move?` for every shape the
  change names and moves none of them unless every answer is yes, so one
  invalid shape in a batch of ten leaves all ten where they were. The
  mirror asks that same predicate, in the same order, of the container as
  the index holds it at this point in the change list, and where the
  document leaves its objects untouched the index rewrites no datom. That
  is an applied change rather than a skip, because the fold still equals a
  rebuild of the changed document (`pp:graph:stale-index-gate`)."
  [db {:keys [parent-id shapes index after-shape allow-altering-copies syncing] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        peid (when ceid (queries/shape-eid db ceid parent-id))]
    (if-not peid
      (skip db :missing-parent)
      (let [objects (container-objects db ceid)
            flags   {:allow-altering-copies allow-altering-copies
                     :syncing syncing}]
        (if-not (and (seq shapes)
                     (every? #(cp/valid-move? objects % parent-id flags) shapes))
          (ok db)
          (let [moved    (into []
                               (keep (fn [sid] (queries/shape-eid db ceid sid)))
                               shapes)
                siblings (children-in-order db peid)
                index    (or (some->> after-shape
                                      (queries/shape-eid db ceid)
                                      (ctd/index-of siblings)
                                      inc)
                             index)
                order    (if (some? index)
                           (ctd/insert-at-index siblings index moved)
                           (cfh/append-at-the-end siblings moved))
                left     (into []
                               (comp (keep #(:db/id (:shape/parent (d/entity db %))))
                                     (remove #{peid})
                                     (distinct))
                               moved)
                tx    (mapv (fn [eid] [:db/add eid :shape/parent peid]) moved)
                db'   (cond-> db (seq tx) (d/db-with tx))
                db'   (with-order db' order)
                db'   (reduce (fn [db* pe] (with-order db* (children-in-order db* pe)))
                              db' left)
                db'   (with-renumber db' ceid)]
            (ok (with-reresolved db' (into [] (mapcat #(subtree-eids db' %)) moved)))))))))

(defn- apply-reorder-children
  "Mirror of `process-change :reorder-children` ->
  `process-children-reordering`: `:shapes` is a partial order over the
  parent's children, and the document sorts the child list it already
  holds by the index each id has in that vector, leaving a child the
  change does not name where it stands (its key is -1 and the sort is
  stable). So the mirror is a function of the change and the order the
  index carries, and it needs no document.

  A parent inside a component copy is left alone unless the change allows
  altering copies, which is the document's own gate: component sync owns
  copy child ordering.

  Nothing else rides the change. A reorder moves no parent edge, so no
  reference re-resolves, and every interval still answers containment:
  siblings stay disjoint and a subtree stays nested inside its parent.
  The interval numbers differ from a rebuild's either way, which is what
  `renumber-container-tx` is for and why the comparison excludes them;
  the order is what this change stores."
  [db {:keys [parent-id shapes allow-altering-copies] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        peid (when ceid (queries/shape-eid db ceid parent-id))]
    (cond
      (nil? peid)
      (skip db :missing-parent)

      (and (not allow-altering-copies)
           (some? (:shape/shape-ref (d/entity db peid))))
      (ok db)

      :else
      (let [children  (children-in-order db peid)
            ids       (mapv #(:shape/id (d/entity db %)) children)
            eid-of    (zipmap ids children)
            id->idx   (update-vals (->> (map-indexed vector shapes)
                                        (group-by second))
                                   (comp first first))
            reordered (mapv eid-of (sort-by #(get id->idx % -1) < ids))]
        (ok (with-order db reordered))))))

(defn- apply-add-page
  "Mirror of `process-change :add-page`: id+name means a fresh empty page
  (`ctp/make-empty-page`), otherwise the provided page map."
  [db {:keys [id name page]}]
  (let [page (if (and (string? name) (uuid? id))
               (ctp/make-empty-page {:id id :name name})
               page)
        page-id (:id page)]
    (if (queries/container-eid db page-id)
      (ok db)
      (let [ctx (assoc (sync-asset-ctx db)
                       :numbering {}
                       :order (overlay/sibling-order (:objects page))
                       :resolve-ref nil)
            tid (overlay/container-tempid page-id)
            tx  (into [(cond-> {:db/id              tid
                                :container/id       page-id
                                :container/kind     :page
                                :container/document (doc-eid db)}
                         (some? (:name page))
                         (assoc :container/name (:name page)))]
                      (overlay/container-entities tid page-id (:objects page) ctx))
            db' (d/db-with db tx)
            ceid (queries/container-eid db' page-id)
            db' (with-renumber db' ceid)]
        (ok (with-reresolved db' (container-shape-eids db' ceid)))))))

(defn- apply-del-page
  [db {:keys [id]}]
  (if-let [ceid (queries/container-eid db id)]
    (ok (d/db-with db (conj (retract-container-shapes-tx db ceid)
                            [:db.fn/retractEntity ceid])))
    (skip db :missing-page)))

(defn- apply-mod-page
  [db {:keys [id name]}]
  (let [ceid (queries/container-eid db id)]
    (cond
      (nil? ceid)          (skip db :missing-page)
      (not (string? name)) (ok db)
      :else                (ok (d/db-with db [[:db/add ceid :container/name name]])))))

(defn- component-attrs-tx
  [eid {:keys [name main-instance-id main-instance-page]}]
  (cond-> []
    (some? name)               (conj [:db/add eid :component/name name])
    (some? main-instance-id)   (conj [:db/add eid :component/main-instance-id main-instance-id])
    (some? main-instance-page) (conj [:db/add eid :component/main-instance-page main-instance-page])))

(defn- apply-add-component
  "Mirror of `ctkl/add-component`; objects never ride an add."
  [db {:keys [id] :as change}]
  (if (d/entid db [:component/id id])
    (ok db)
    (let [tx (into [{:db/id -1 :component/id id :component/document (doc-eid db)}]
              (component-attrs-tx -1 change))]
      (ok (with-component-reresolved (d/db-with db tx) id)))))

(defn- clear-component-container-tx
  "Drop a component's own shape copies and its container role, keeping the
  component record (mirror of the `dissoc :objects` in
  `ctkl/mod-component` and `ctf/restore-component`)."
  [db eid]
  (into (retract-container-shapes-tx db eid)
        [[:db.fn/retractAttribute eid :container/id]
         [:db.fn/retractAttribute eid :container/kind]
         [:db.fn/retractAttribute eid :container/document]]))

(defn- component-container-tx
  "Give the component entity a container role over `objects`."
  [db eid component-id objects]
  (let [ctx (assoc (sync-asset-ctx db)
                   :numbering {}
                   :order (overlay/sibling-order objects)
                   :resolve-ref nil)]
    (into [[:db/add eid :container/id component-id]
           [:db/add eid :container/kind :component]
           [:db/add eid :container/document (doc-eid db)]]
          (overlay/container-entities eid component-id objects ctx))))

(defn- apply-mod-component
  "Mirror of `ctkl/mod-component`, including its sharpest edge: a
  `:mod-component` without `:objects` dissocs the component's own copy,
  so the overlay drops the component container either way and rebuilds it
  only when the change carries objects."
  [db {:keys [id objects] :as change}]
  (if-let [eid (d/entid db [:component/id id])]
    (let [tx  (-> (component-attrs-tx eid change)
                  (into (clear-component-container-tx db eid))
                  (cond-> (some? objects)
                    (into (component-container-tx db eid id objects))))
          db' (cond-> db (seq tx) (d/db-with tx))
          db' (cond-> db' (some? objects) (with-renumber eid))]
      (ok (with-component-reresolved db' id)))
    (skip db :missing-component)))

(defn- entity-shape-attrs
  "The indexed attrs of a live shape entity, as assertable values (asset
  references flattened to eids; Euler intervals excluded, the copy is
  renumbered; the resolved reference excluded, the copy is re-resolved)."
  [ent]
  (let [many-ids #(into [] (map :db/id) %)]
    (cond-> (reduce (fn [acc attr]
                      (if-some [v (get ent attr)]
                        (assoc acc attr v)
                        acc))
                    {:shape/type (:shape/type ent)}
                    overlay/token-attrs)
      (some? (:shape/name ent))           (assoc :shape/name (:shape/name ent))
      (some? (:shape/component-id ent))   (assoc :shape/component-id (:shape/component-id ent))
      (some? (:shape/component-file ent)) (assoc :shape/component-file (:shape/component-file ent))
      (some? (:shape/shape-ref ent))      (assoc :shape/shape-ref (:shape/shape-ref ent))
      (some? (:shape/swap-slot ent))      (assoc :shape/swap-slot (:shape/swap-slot ent))
      (seq (:shape/fill-color ent))       (assoc :shape/fill-color (many-ids (:shape/fill-color ent)))
      (seq (:shape/stroke-color ent))     (assoc :shape/stroke-color (many-ids (:shape/stroke-color ent)))
      (seq (:shape/text-color ent))       (assoc :shape/text-color (many-ids (:shape/text-color ent)))
      (seq (:shape/uses-typography ent))  (assoc :shape/uses-typography (many-ids (:shape/uses-typography ent)))
      ;; the raw ids ride along: the builder projects them for the copy
      ;; too, and a reference copied without its id could not be repaired
      (seq (:shape/fill-color-ref-id ent))
      (assoc :shape/fill-color-ref-id (vec (:shape/fill-color-ref-id ent)))
      (seq (:shape/stroke-color-ref-id ent))
      (assoc :shape/stroke-color-ref-id (vec (:shape/stroke-color-ref-id ent)))
      (seq (:shape/text-color-ref-id ent))
      (assoc :shape/text-color-ref-id (vec (:shape/text-color-ref-id ent)))
      (seq (:shape/typography-ref-id ent))
      (assoc :shape/typography-ref-id (vec (:shape/typography-ref-id ent))))))

(defn- snapshot-component-tx
  "Mirror of `ctf/load-component-objects`: copy the main-instance subtree
  from its page into the component's own container, same ids, new
  (container, shape) entities. The copy is taken from the overlay itself,
  which is exactly the indexed slice the rebuild would project from the
  document's copy. Geometry (`delta`) is not indexed, so the move that
  accompanies an undo of cut-paste is a designed no-op."
  [db comp-eid]
  (let [ent     (d/entity db comp-eid)
        comp-id (:component/id ent)
        mid     (:component/main-instance-id ent)
        mpg     (:component/main-instance-page ent)
        pceid   (when mpg (queries/container-eid db mpg))
        root    (when (and pceid mid) (queries/shape-eid db pceid mid))]
    (when (and root (empty? (container-shape-eids db comp-eid)))
      (let [eids   (subtree-eids db root)
            eidset (set eids)
            copy-tempid (fn [eid]
                          (overlay/shape-tempid comp-id (:shape/id (d/entity db eid))))]
        (into [[:db/add comp-eid :container/id comp-id]
               [:db/add comp-eid :container/kind :component]
               [:db/add comp-eid :container/document (doc-eid db)]]
              (map (fn [eid]
                     (let [src    (d/entity db eid)
                           parent (:db/id (:shape/parent src))
                           inner? (contains? eidset parent)]
                       (cond->
                        (merge {:db/id           (copy-tempid eid)
                                :shape/id        (:shape/id src)
                                :shape/container comp-eid
                                :shape/parent    (if inner?
                                                   (copy-tempid parent)
                                                   comp-eid)}
                               (entity-shape-attrs src))
                         ;; a child of a copied shape keeps the position it
                         ;; held: the component's objects carry the same
                         ;; `:shapes` vectors. The copy of the subtree's
                         ;; root is a container root that no vector names,
                         ;; so it carries no ordinal, which is what a
                         ;; rebuild of the component's objects gives it
                         (and inner? (some? (:shape/order src)))
                         (assoc :shape/order (:shape/order src))))))
              eids)))))

(defn- apply-del-component
  "Mirror of `ctf/delete-component`: with `skip-undelete?` the component
  goes entirely; otherwise it is marked deleted and keeps its own copy of
  the main-instance subtree. Either way the resolution targets of every
  instance of this component move, so the affected references re-resolve."
  [db {:keys [id skip-undelete?]}]
  (if-let [eid (d/entid db [:component/id id])]
    (if skip-undelete?
      (let [db' (d/db-with db (conj (retract-container-shapes-tx db eid)
                                    [:db.fn/retractEntity eid]))]
        (ok (with-component-reresolved db' id)))
      (let [snapshot (snapshot-component-tx db eid)
            db'      (d/db-with db (into [[:db/add eid :component/deleted true]]
                                         snapshot))
            db'      (cond-> db' (seq snapshot) (with-renumber eid))]
        (ok (with-component-reresolved db' id))))
    (ok db)))

(defn- apply-restore-component
  "Mirror of `ctf/restore-component`: undelete, drop the component's own
  copy, optionally repoint the main-instance page; the resolution targets
  move back to the page, so the affected references re-resolve."
  [db {:keys [id page-id]}]
  (if-let [eid (d/entid db [:component/id id])]
    (let [tx  (cond-> (into [[:db.fn/retractAttribute eid :component/deleted]]
                            (clear-component-container-tx db eid))
                (some? page-id)
                (conj [:db/add eid :component/main-instance-page page-id]))]
      (ok (with-component-reresolved (d/db-with db tx) id)))
    (skip db :missing-component)))

(defn- apply-purge-component
  [db {:keys [id]}]
  (if-let [eid (d/entid db [:component/id id])]
    (let [db' (d/db-with db (conj (retract-container-shapes-tx db eid)
                                  [:db.fn/retractEntity eid]))]
      (ok (with-component-reresolved db' id)))
    (ok db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; library assets: colours and typographies
;;
;; The entity is the one `app.graph.overlay/build-tx` writes for a member
;; of `:colors` or `:typographies`: identity, the document ref, and the
;; name a standing query asks for. The value lives in the document,
;; which the holder of the overlay already has.
;;
;; A shape stores each raw `:fill-color-ref-id` (and its siblings) beside
;; the reference the id resolved to, so an asset the library did not hold
;; when the shape named it leaves the id in the index and no reference.
;; `apply-add-asset` then reads the shapes carrying that id, one index
;; lookup per source, and asserts the reference for each, which is the
;; datom a rebuild writes. The add is the only repair: nothing else
;; revisits a shape that named an asset before the library held it.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private color-asset
  {:id-attr :color/id :doc-attr :color/document :name-attr :color/name
   :tempid  overlay/color-tempid
   :sources [{:raw-attr :shape/fill-color-ref-id   :ref-attr :shape/fill-color}
             {:raw-attr :shape/stroke-color-ref-id :ref-attr :shape/stroke-color}
             {:raw-attr :shape/text-color-ref-id   :ref-attr :shape/text-color}]})

(def ^:private typography-asset
  {:id-attr :typography/id :doc-attr :typography/document
   :name-attr :typography/name :tempid overlay/typography-tempid
   :sources [{:raw-attr :shape/typography-ref-id :ref-attr :shape/uses-typography}]})

(defn- waiting-shape-ref-tx
  "Reference assertions for every shape that named asset `id` through one of
  the asset's sources. For a shape that already holds the reference this
  re-asserts a datom it has; for a shape that named the asset before the
  library held it, this is the datom a rebuild writes and the fold was
  missing."
  [db id sources asset-ref]
  (into []
        (mapcat (fn [{:keys [raw-attr ref-attr]}]
                  (map (fn [dtm] [:db/add (:e dtm) ref-attr asset-ref])
                       (d/datoms db :avet raw-attr id))))
        sources))

(defn- apply-add-asset
  "Mirror of `process-change :add-color` -> `ctl/add-color` and
  `:add-typography` -> `ctyl/add-typography`, both of which `assoc` the
  asset into the library map whether or not one is already there. A
  payload without a name drops the name a rebuild would drop too, which
  is why the name is replaced rather than merged.

  The shapes that named the asset before the library held it carry its raw
  id and no reference; this is where those references appear. The read
  runs against `db`, before the write, and the reference is the asset's
  eid or its tempid, which the same transaction resolves."
  [db {:keys [id-attr doc-attr name-attr tempid sources]} asset]
  (let [id (:id asset)]
    (if-not (uuid? id)
      (skip db :missing-asset-id)
      (let [existing  (d/entid db [id-attr id])
            asset-ref (or existing (tempid id))
            tx        (-> [(cond-> {:db/id   asset-ref
                                    id-attr  id
                                    doc-attr (doc-eid db)}
                             (some? (:name asset))
                             (assoc name-attr (:name asset)))]
                          (into (waiting-shape-ref-tx db id sources asset-ref))
                          (cond-> (and existing (nil? (:name asset)))
                            (conj [:db.fn/retractAttribute existing name-attr])))]
        (ok (d/db-with db tx))))))

(defn- apply-mod-color
  "Mirror of `process-change :mod-color` -> `ctl/set-color`, which is
  `d/assoc-in-when`: a colour the library does not hold is left alone,
  and the one it holds is replaced whole, so an absent name is an
  absence rather than a value kept."
  [db {:keys [color]}]
  (if-let [eid (d/entid db [:color/id (:id color)])]
    (ok (d/db-with db (replace-attr-tx eid :color/name (:name color))))
    (ok db)))

(defn- apply-mod-typography
  "Mirror of `process-change :mod-typography` ->
  `ctyl/update-typography` with `merge`, which is the one place the two
  asset kinds differ: the payload merges over the stored typography, so
  an absent name keeps the stored one instead of dropping it."
  [db {:keys [typography]}]
  (let [eid (d/entid db [:typography/id (:id typography)])]
    (if (and eid (some? (:name typography)))
      (ok (d/db-with db [[:db/add eid :typography/name (:name typography)]]))
      (ok db))))

(defn- apply-del-asset
  "Mirror of `common/src/app/common/types/library.cljc::delete-color` and
  of `ctyl/delete-typography`: both dissoc the asset from the library map
  and leave every referring shape alone. A rebuild therefore loses the
  asset entity *and* every resolved reference to it, because
  `app.graph.overlay/build-asset-maps` only maps the ids the library
  still holds.

  One retraction produces exactly that. Datascript's
  `:db.fn/retractEntity` retracts the entity's own datoms and every
  reference datom whose value is the entity, which here is every
  `:shape/fill-color`, `:shape/stroke-color`, `:shape/text-color` and
  `:shape/uses-typography` pointing at the asset. On the design-system
  file measured by
  `pp:vcs:the-real-change-stream-is-attribute-shaped` one skipped
  `:del-color` left up to 10,304 of those ref datoms behind."
  [db id-attr id]
  (if-let [eid (d/entid db [id-attr id])]
    (ok (d/db-with db [[:db.fn/retractEntity eid]]))
    (ok db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; design tokens
;;
;; The entity shape is `app.graph.overlay/tokens-tx`'s: one entity per
;; token set (identity, name, document ref) and one per token (identity,
;; name, type, set ref). Values, themes and resolution stay in the lib,
;; which `app.common.types.tokens-lib` already answers.
;;
;; None of the six changes reads the document or needs the lib to be
;; loaded: each one carries what the mirror needs. Two facts about the
;; lib drive the rest. A set's name is its path, so a move or a group
;; rename is a name rewrite over the sets under a prefix. And both the
;; set tree and a set's token map are keyed by name, so writing a name
;; that is already taken displaces the entity holding it and a rebuild
;; shows that entity simply gone.
;;
;; A displacement of one entity is mirrored, as one retraction beside the
;; write (`apply-set-token-set` and `apply-set-token`). The three
;; group-level writers keep their refusal, because a group move onto an
;; occupied destination drops every set under that destination and a
;; group rename can displace a set outside the group: the shape of that
;; retraction has not been observed in a real change stream, and refusing
;; is worth more than reproducing it from a reading of `d/oassoc-in`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-names
  "Every token set by name. `:token-set/name` carries no `:db/index`, so
  this reads the attribute index rather than `:avet`; a file holds token
  sets in the tens."
  [db]
  (into {} (map (fn [dtm] [(:v dtm) (:e dtm)]))
        (d/datoms db :aevt :token-set/name)))

(defn- set-token-eids
  [db set-eid]
  (mapv :e (d/datoms db :avet :token/set set-eid)))

(defn- retract-token-set-tx
  "The set entity and its tokens. Retracting the set alone would strand
  the tokens: `:token/set` is the only reference between them, and a
  token carries its own identity."
  [db set-eid]
  (into [[:db.fn/retractEntity set-eid]]
        (map (fn [eid] [:db.fn/retractEntity eid]))
        (set-token-eids db set-eid)))

(defn- token-entities
  "Token entities of one set from a lib-shaped token map, in
  `app.graph.overlay/tokens-tx`'s shape."
  [set-ref tokens]
  (into []
        (map (fn [token]
               (cond-> {:db/id    (overlay/token-tempid (:id token))
                        :token/id (:id token)
                        :token/set set-ref}
                 (some? (:name token)) (assoc :token/name (:name token))
                 (some? (:type token)) (assoc :token/type (:type token)))))
        (vals tokens)))

(defn- name-holder
  "The entity that holds `name*`, when it is not `eid`. A set tree and a
  set's token map are both keyed by name, so the writer that lands on a
  taken name writes over this entity and it leaves the library."
  [names name* eid]
  (when-let [holder (get names name*)]
    (when (not= holder eid)
      holder)))

(defn- set-under-path?
  "Whether a set's path lies strictly under a group path, which is what
  `ctob/get-sets-at-path` answers by walking into the group's node: a set
  *named* `Brand` is not under the group `Brand`."
  [set-path group-path]
  (and (> (count set-path) (count group-path))
       (= (vec group-path) (vec (take (count group-path) set-path)))))

(defn- sets-under-path
  [db path]
  (into []
        (keep (fn [[name* eid]]
                (when (set-under-path? (ctob/split-set-name name*) path) eid)))
        (set-names db)))

(defn- reprefixed-name
  "`set-path` with its `from` prefix replaced by `to`, joined back into a
  name: `ctob/rename-set-group`'s and `ctob/move-set-group`'s formula,
  expressed over paths so the separator stays the lib's."
  [set-path from to]
  (ctob/join-set-path (into (vec to) (drop (count from)) set-path)))

(defn- reprefix-sets-tx
  "Rename every set strictly under the group path `from` so its prefix
  reads `to`. Nil when one of the new names is held by a set that is not
  moving, which is the displacement the caller refuses."
  [db from to]
  (let [names  (set-names db)
        moving (into {}
                     (keep (fn [[name* eid]]
                             (let [path (ctob/split-set-name name*)]
                               (when (set-under-path? path from)
                                 [eid (reprefixed-name path from to)]))))
                     names)]
    (when-not (some (fn [[_ name*]]
                      (when-let [holder (get names name*)]
                        (not (contains? moving holder))))
                    moving)
      (mapv (fn [[eid name*]] [:db/add eid :token-set/name name*]) moving))))

(defn- apply-set-tokens-lib
  "Mirror of `process-change :set-tokens-lib`, which replaces
  `:tokens-lib` whole. The index mirrors the replacement: every token-set
  and token entity goes, and the incoming library's sets and tokens are
  written fresh. Nothing outside the pair points at them, because an
  applied token is folded onto the shape as a plain name
  (`app.graph.overlay/token-attrs`), so no shape datom needs repair."
  [db {:keys [tokens-lib]}]
  (let [old (-> (mapv (fn [dtm] [:db.fn/retractEntity (:e dtm)])
                      (d/datoms db :avet :token-set/id))
                (into (map (fn [dtm] [:db.fn/retractEntity (:e dtm)]))
                      (d/datoms db :avet :token/id)))
        new (into []
                  (mapcat (fn [set*]
                            (let [sid (ctob/get-id set*)
                                  ref (overlay/token-set-tempid sid)]
                              (cons {:db/id              ref
                                     :token-set/id       sid
                                     :token-set/name     (ctob/get-name set*)
                                     :token-set/document (doc-eid db)}
                                    (token-entities ref (ctob/get-tokens- set*))))))
                  (some-> tokens-lib ctob/get-sets))]
    ;; two transactions: the retraction frees the identities the second
    ;; one asserts, so no upsert has to resolve against a retracted row
    (ok (-> db
            (cond-> (seq old) (d/db-with old))
            (cond-> (seq new) (d/db-with new))))))

(defn- apply-set-token-set
  "Mirror of `process-change :set-token-set`: `ctob/delete-set` when
  `attrs` is absent, `ctob/add-set` when the lib holds no set with that
  id, and `ctob/update-set` replacing it with `(ctob/make-token-set
  attrs)` otherwise.

  The replacement is total and `:tokens` is optional in
  `ctob/schema:token-set-attrs`, so a payload without tokens leaves the
  set empty and a rebuild shows the previous tokens gone. This writes
  exactly the tokens the payload carries, and the name through
  `ctob/normalize-set-name`, which is the name `make-token-set` stores.

  A name another set holds is a displacement rather than a refusal: the
  writer that lands on a taken path writes over the set standing there,
  and that set leaves the library with every token under it, which a
  rebuild shows and `retract-token-set-tx` reproduces."
  [db {:keys [id attrs]}]
  (let [existing (d/entid db [:token-set/id id])]
    (cond
      (not attrs)
      (ok (cond-> db existing (d/db-with (retract-token-set-tx db existing))))

      (not (uuid? id))
      (skip db :missing-token-set-id)

      :else
      (let [name*  (ctob/normalize-set-name (:name attrs))
            holder (name-holder (set-names db) name* existing)]
        (let [set-ref (or existing (overlay/token-set-tempid id))
              old     (into (if holder (retract-token-set-tx db holder) [])
                            (map (fn [eid] [:db.fn/retractEntity eid]))
                            (when existing (set-token-eids db existing)))
              new     (into [{:db/id              set-ref
                              :token-set/id       id
                              :token-set/name     name*
                              :token-set/document (doc-eid db)}]
                            (token-entities set-ref (:tokens attrs)))]
          ;; two transactions: the retraction frees the identities the
          ;; second one asserts, so no upsert has to resolve against a
          ;; retracted row
          (ok (-> db
                  (cond-> (seq old) (d/db-with old))
                  (d/db-with new))))))))

(defn- apply-set-token
  "Mirror of `process-change :set-token`: `ctob/delete-token` when
  `attrs` is absent, `ctob/add-token` when the set holds no token with
  that id, and `ctob/update-token` with `(merge prev attrs)` otherwise,
  which is why an absent name or type keeps the stored one. All three
  route through `ctob/update-set`, a no-op when the set is absent, so an
  unknown set changes nothing here either.

  `process-change` adds the token `ctob/make-token` builds from `attrs`,
  so the identity to mirror is the one in the payload. The frontend
  builds both the id and `:token-id` from one token
  (`common/src/app/common/files/changes_builder.cljc::set-token`), and
  `ctob/make-token` keeps an id it is given, so the id is there; the
  assertion states that rather than refusing a payload the frontend
  cannot produce.

  A name another token holds is a displacement rather than a refusal: the
  set's token map is keyed by name, so the new token takes the name and
  the entity that held it leaves the library, which a rebuild shows. The
  document decides that rename by map position (`ctob/update-token-`
  moves the renamed token with `d/oassoc-before` and the later entry of
  the destination name survives), and the index keeps no token order, so
  this mirror takes the entry's reading: the holder goes, the renamed
  token stays."
  [db {:keys [set-id token-id attrs]}]
  (let [set-eid (d/entid db [:token-set/id set-id])
        eid     (let [e (d/entid db [:token/id token-id])]
                  (when (and set-eid e
                             (= set-eid (:db/id (:token/set (d/entity db e)))))
                    e))]
    (cond
      (nil? set-eid)
      (ok db)

      (not attrs)
      (ok (cond-> db eid (d/db-with [[:db.fn/retractEntity eid]])))

      :else
      (let [ent    (when eid (d/entity db eid))
            name*  (or (:name attrs) (:token/name ent))
            type*  (or (:type attrs) (:token/type ent))
            holder (some (fn [e]
                           (when (and (not= e eid)
                                      (= name* (:token/name (d/entity db e))))
                             e))
                         (set-token-eids db set-eid))
            attrs-tx (fn [e]
                       (cond-> []
                         (some? name*) (conj [:db/add e :token/name name*])
                         (some? type*) (conj [:db/add e :token/type type*])))]
        (if eid
          (ok (d/db-with db (into (cond-> [] holder (conj [:db.fn/retractEntity holder]))
                                  (attrs-tx eid))))
          (let [new-id (:id attrs)]
            (assert (uuid? new-id)
                    (str "a :set-token the frontend builds carries the token id in "
                         "attrs (`changes_builder.cljc::set-token`), and the mirror "
                         "reads the identity from there; attrs: " (pr-str attrs)))
            (let [new (cond-> {:db/id     (overlay/token-tempid new-id)
                               :token/id  new-id
                               :token/set set-eid}
                        (some? name*) (assoc :token/name name*)
                        (some? type*) (assoc :token/type type*))]
              ;; two transactions: the retraction frees the identity the
              ;; assertion below writes, so no upsert has to resolve
              ;; against a retracted row
              (ok (-> db
                      (cond-> holder (d/db-with [[:db.fn/retractEntity holder]]))
                      (d/db-with [new]))))))))))

(defn- apply-rename-token-set-group
  "Mirror of `process-change :rename-token-set-group` ->
  `ctob/rename-set-group`, which renames every set under the group path
  by replacing the path's last segment. The change carries the path and
  the new final name, so the mirror needs no lib. The rename merges into
  a group that already exists rather than replacing it, which is why
  only a set-name collision is refused here."
  [db {:keys [set-group-path set-group-fname]}]
  (let [from (vec set-group-path)
        to   (vec (ctob/replace-last-path-name from set-group-fname))]
    (if-let [tx (reprefix-sets-tx db from to)]
      (ok (cond-> db (seq tx) (d/db-with tx)))
      (skip db :token-set-name-taken))))

(defn- apply-move-token-set
  "Mirror of `process-change :move-token-set` -> `ctob/move-set`, which
  renames the set at `from-path` to `to-path`, or reorders it when the
  two paths are equal. The overlay stores no sibling order, so the
  reorder is a designed no-op and the move is one rename. A path the lib
  holds no set at is a no-op there too."
  [db {:keys [from-path to-path]}]
  (let [names (set-names db)
        from  (ctob/join-set-path from-path)
        to    (ctob/join-set-path to-path)
        eid   (get names from)]
    (cond
      (nil? eid)                          (ok db)
      (= from to)                         (ok db)
      (name-holder names to eid)          (skip db :token-set-name-taken)
      :else (ok (d/db-with db [[:db/add eid :token-set/name to]])))))

(defn- apply-move-token-set-group
  "Mirror of `process-change :move-token-set-group` ->
  `ctob/move-set-group`, which moves a whole group and rewrites the name
  of every set under it, or reorders the group when the two paths are
  equal. The reorder is a designed no-op, for the same reason a set's is.

  A destination that already holds sets is refused rather than mirrored:
  the move writes the group's node over whatever is at the destination
  path, so those sets leave the lib, and refusing is the honest answer to
  a state the editor's unique names prevent."
  [db {:keys [from-path to-path]}]
  (let [from (vec from-path)
        to   (vec to-path)]
    (cond
      (= from to)
      (ok db)

      (seq (remove (set (sets-under-path db from)) (sets-under-path db to)))
      (skip db :token-set-group-destination-occupied)

      :else
      (if-let [tx (reprefix-sets-tx db from to)]
        (ok (cond-> db (seq tx) (d/db-with tx)))
        (skip db :token-set-name-taken)))))

(defn- apply-change
  [db change]
  (case (:type change)
    :add-obj           (apply-add-obj db change)
    :mod-obj           (apply-mod-obj db change)
    :del-obj           (apply-del-obj db change)
    :mov-objects       (apply-mov-objects db change)
    :reorder-children  (apply-reorder-children db change)
    :add-page          (apply-add-page db change)
    :del-page          (apply-del-page db change)
    :mod-page          (apply-mod-page db change)
    :add-component     (apply-add-component db change)
    :mod-component     (apply-mod-component db change)
    :del-component     (apply-del-component db change)
    :restore-component (apply-restore-component db change)
    :purge-component   (apply-purge-component db change)
    :add-color         (apply-add-asset db color-asset (:color change))
    :mod-color         (apply-mod-color db change)
    :del-color         (apply-del-asset db :color/id (:id change))
    :add-typography    (apply-add-asset db typography-asset (:typography change))
    :mod-typography    (apply-mod-typography db change)
    :del-typography    (apply-del-asset db :typography/id (:id change))
    :set-tokens-lib    (apply-set-tokens-lib db change)
    :set-token-set     (apply-set-token-set db change)
    :set-token         (apply-set-token db change)
    :rename-token-set-group (apply-rename-token-set-group db change)
    :move-token-set         (apply-move-token-set db change)
    :move-token-set-group   (apply-move-token-set-group db change)
    (skip db :unsupported-type)))

(defn apply-changes
  "Apply Penpot `changes` to overlay `db`. Pure.

  Returns `{:db db' :applied [types...] :skipped [{:type .. :reason ..}]}`."
  [db changes]
  (reduce (fn [acc change]
            (let [{:keys [db applied? reason]} (apply-change (:db acc) change)]
              (-> acc
                  (assoc :db db)
                  (cond->
                   applied?       (update :applied conj (:type change))
                   (not applied?) (update :skipped conj {:type   (:type change)
                                                         :reason reason})))))
          {:db db :applied [] :skipped []}
          changes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; staleness
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def stale-reasons
  "The three ways one batch leaves a maintained index behind."
  #{:apply-failed :revision-gap :skipped})

(defn staleness
  "The verdict on one batch: nil while the index still equals a rebuild of
  the file, otherwise why it no longer does. Pure, so the session layer
  owns what to do with it.

  - `:apply-failed` when applying threw: the index holds whatever prefix
    of the batch had been folded, and nothing says which.
  - `:revision-gap` when `revn` is not `graph-revn` plus one.
    `app.rpc.commands.files-update/process-changes-and-validate`
    increments the file's revision once per accepted save and
    `send-notifications!` publishes exactly one message per save, so
    consecutive messages carry consecutive revisions. Anything else is a
    message this index never saw, which is what the dropping buffer of
    `app.graph.debug/start-sync-loop!` produces, or a redelivery, which
    would fold a batch twice.
  - `:skipped` when the applier had no mirror for a change. The types are
    named, because which type diverged is where a repair starts.

  Every case is permanent: a later batch folds onto a state no rebuild
  produces, so only a rebuild clears the verdict. `result` is
  `apply-changes`'s report, or a map carrying `:error` when it threw."
  [graph-revn revn {:keys [skipped error] :as _result}]
  (cond
    (some? error)
    {:reason :apply-failed :error (str error)}

    (and (number? graph-revn) (number? revn) (not= revn (inc graph-revn)))
    {:reason :revision-gap :graph-revn graph-revn :revn revn}

    (seq skipped)
    {:reason :skipped :types (into [] (distinct) (map :type skipped))}))
