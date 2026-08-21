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

  Everything here is pure: db value in, db value out. The session layer
  owns the atom and the revision bookkeeping."
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.queries :as queries]
   [datascript.core :as d]))

(def supported-change-types
  #{:add-obj :mod-obj :del-obj :mov-objects
    :add-page :del-page :mod-page
    :add-component :mod-component :del-component
    :restore-component :purge-component})

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
  "The shape entity plus every descendant, via the reverse parent index."
  [db root-eid]
  (loop [acc      []
         frontier [root-eid]]
    (if-let [eid (peek frontier)]
      (recur (conj acc eid)
             (into (pop frontier) (child-eids db eid)))
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
  when absent from the container, then to the container itself."
  [db {:keys [id obj frame-id parent-id] :as change}]
  (let [container-id (change-container-id change)
        ceid         (queries/container-eid db container-id)]
    (if-not ceid
      (skip db :missing-container)
      (let [pid  (or parent-id frame-id)
            peid (or (when (and pid (not= pid id))
                       (queries/shape-eid db ceid pid))
                     (queries/shape-eid db ceid uuid/zero)
                     ceid)
            ctx      (sync-asset-ctx db)
            shape    (assoc obj :id id)
            tempid   (overlay/shape-tempid container-id id)
            existing (queries/shape-eid db ceid id)
            tx       (-> (if existing (retract-shape-tx db existing) [])
                         (conj (merge {:db/id           tempid
                                       :shape/id        id
                                       :shape/container ceid
                                       :shape/parent    peid}
                                      (overlay/shape-attrs shape)
                                      (overlay/shape-asset-attrs shape ctx))))
            db'      (d/db-with db tx)
            new-eid  (queries/shape-eid db' ceid id)]
        (-> db'
            (with-reresolved (if new-eid [new-eid] []))
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

(defn- set-op-tx
  "Transaction data for one `:set` / `:set-touched` operation on `eid`.
  Attributes the overlay does not index yield nil (a designed no-op),
  mirroring `ctn/set-shape-attr` only for the indexed slice."
  [db eid ctx {:keys [type attr val touched]}]
  (cond
    (= :set-touched type)
    (swap-slot-tx db eid touched)

    (not= :set type)
    nil

    (contains? plain-attr->overlay attr)
    (replace-attr-tx eid (plain-attr->overlay attr) val)

    (= :touched attr)
    (swap-slot-tx db eid val)

    (= :fills attr)
    (replace-many-tx eid :shape/fill-color
                     (keep (:colors ctx) (overlay/fill-color-ref-ids val)))

    (= :strokes attr)
    (replace-many-tx eid :shape/stroke-color
                     (keep (:colors ctx) (overlay/stroke-color-ref-ids val)))

    (= :content attr)
    (concat (replace-many-tx eid :shape/text-color
                             (keep (:colors ctx) (overlay/content-color-ref-ids val)))
            (replace-many-tx eid :shape/uses-typography
                             (keep (:typographies ctx)
                                   (overlay/content-typography-ref-ids val))))

    (= :applied-tokens attr)
    (applied-tokens-tx eid val)

    :else nil))

(defn- apply-mod-obj
  [db {:keys [id operations] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      (skip db :missing-shape)
      (let [ctx (sync-asset-ctx db)
            tx  (into [] (mapcat #(set-op-tx db eid ctx %)) operations)
            db' (cond-> db (seq tx) (d/db-with tx))
            set-attrs (into #{} (keep :attr) operations)
            affected  (cond
                        (some head-attrs set-attrs) (subtree-eids db' eid)
                        (contains? set-attrs :shape-ref) [eid]
                        :else nil)]
        (ok (cond-> db' (seq affected) (with-reresolved affected)))))))

(defn- apply-del-obj
  [db {:keys [id] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      ;; Penpot emits one :del-obj per selected shape, so a parent's
      ;; deletion may already have removed this node; not a defect.
      (ok db)
      (ok (d/db-with db (into []
                              (mapcat #(retract-shape-tx db %))
                              (subtree-eids db eid)))))))

(defn- apply-mov-objects
  "Reparenting only: `:mov-objects` carries shape ids and a parent id and
  nothing else (`common/src/app/common/files/changes.cljc`), and the
  overlay stores no sibling order, so the move is a `:shape/parent`
  re-point per shape, a container renumber, and a reference re-resolution
  of the moved subtrees (their head chains changed)."
  [db {:keys [parent-id shapes] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        peid (when ceid (queries/shape-eid db ceid parent-id))]
    (if-not peid
      (skip db :missing-parent)
      (let [moved (into []
                        (keep (fn [sid] (queries/shape-eid db ceid sid)))
                        shapes)
            tx    (mapv (fn [eid] [:db/add eid :shape/parent peid]) moved)
            db'   (cond-> db (seq tx) (d/db-with tx))
            db'   (with-renumber db' ceid)]
        (ok (with-reresolved db' (into [] (mapcat #(subtree-eids db' %)) moved)))))))

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
      (let [ctx (assoc (sync-asset-ctx db) :numbering {} :resolve-ref nil)
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
  (let [ctx (assoc (sync-asset-ctx db) :numbering {} :resolve-ref nil)]
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
      (seq (:shape/uses-typography ent))  (assoc :shape/uses-typography (many-ids (:shape/uses-typography ent))))))

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
                           parent (:db/id (:shape/parent src))]
                       (merge {:db/id           (copy-tempid eid)
                               :shape/id        (:shape/id src)
                               :shape/container comp-eid
                               :shape/parent    (if (contains? eidset parent)
                                                  (copy-tempid parent)
                                                  comp-eid)}
                              (entity-shape-attrs src)))))
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

(defn- apply-change
  [db change]
  (case (:type change)
    :add-obj           (apply-add-obj db change)
    :mod-obj           (apply-mod-obj db change)
    :del-obj           (apply-del-obj db change)
    :mov-objects       (apply-mov-objects db change)
    :add-page          (apply-add-page db change)
    :del-page          (apply-del-page db change)
    :mod-page          (apply-mod-page db change)
    :add-component     (apply-add-component db change)
    :mod-component     (apply-mod-component db change)
    :del-component     (apply-del-component db change)
    :restore-component (apply-restore-component db change)
    :purge-component   (apply-purge-component db change)
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
