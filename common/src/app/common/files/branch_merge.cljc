;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.files.branch-merge
  "Three-way, entity-level merge/diff engine for file branching.

  `compute-merge` takes the file `:data` of the merge base, main and
  branch and returns a serializable summary of what the branch changes
  relative to main, plus the set of conflicting entities. It never
  mutates the blob. `compute-changes` translates the same comparison
  into `changes` for an actual merge or update, resolved conflicts
  included. It covers every kind `compute-merge` reports, token kinds
  included, except the residual page attrs (`:page-attrs`), which
  `unsupported-kinds` reports so the caller refuses them.

  Direction `:branch->main` (merge/compare) treats main as `theirs` and
  branch as `ours`; `:main->branch` (update from main) swaps them. The
  reported payload names the DOCUMENTS either way (see `compute-merge`)."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.string :as str]))

;; --- Tree-shape policies ---
;;
;; Three tree-shape cases have more than one defensible merge. The engine
;; reads the choice from `*policies*`; the backend binds it from
;; `backend/resources/app/branch-merge-policies.edn` around every compare,
;; merge and update.

(def default-policies
  "Policy chosen for each tree-shape case when none is configured."
  {:same-parent-reorder              :merge
   :addition-under-deleted-parent    :conflict
   :container-delete-over-main-edits :conflict})

(def policy-values
  "Allowed alternatives per policy key.

  `:same-parent-reorder`: one side reorders the children of a parent both
  sides keep.
    `:merge`  detected, reported as a `:shapes` attr on the parent and
              applied as `:mov-objects`; both sides reordering it
              differently is a conflict on the parent.
    `:refuse` reported as an `:unsupported` `:shape-order` entry naming
              the parent, so the merge or update refuses.
    `:ignore` invisible and lost (the behaviour before the policy).

  `:addition-under-deleted-parent`: the target deleted container P and the
  source added a shape inside it.
    `:conflict` a `:modify-delete` conflict on P (see `three-way-entities`).
    `:refuse`   reported as an `:unsupported` `:shape-orphan` entry naming
                P.
    `:reparent-to-ancestor` the addition lands under the nearest ancestor
                the target still has.
    `:page-root` the addition lands at the page root (the behaviour before
                the policy).

  `:container-delete-over-main-edits`: the source deleted container F while
  the target modified or added something in F's subtree.
    `:conflict` a `:delete-modify` conflict on F.
    `:expand`   only what the source deleted goes; the target's additions
                move to F's parent.
    `:cascade`  F goes with its whole subtree (the behaviour before the
                policy)."
  {:same-parent-reorder              #{:merge :refuse :ignore}
   :addition-under-deleted-parent    #{:conflict :refuse :reparent-to-ancestor :page-root}
   :container-delete-over-main-edits #{:conflict :expand :cascade}})

(def ^:dynamic *policies*
  "Effective policies; the engine reads only `(get *policies* k)`."
  default-policies)

(defn- policy
  [k]
  (get *policies* k (get default-policies k)))

(defn- entity-label
  [kind v]
  (or (:name v)
      (some-> (:id v) str)
      (name kind)))

(defn- shallow-attr-diff
  "Map of attr -> {:main v :branch v} for the keys whose values differ
  between the `theirs` and `ours` entity maps. Powers the
  \"property changes\" detail in the compare view. Returns `{}` for
  non-map entities (e.g. order/name tuples)."
  [theirs ours]
  (if (and (map? theirs) (map? ours))
    (let [ks (set/union (set (keys theirs)) (set (keys ours)))]
      (reduce (fn [acc k]
                (let [tv (get theirs k)
                      ov (get ours k)]
                  (if (= tv ov)
                    acc
                    (assoc acc k {:main tv :branch ov}))))
              {}
              ks))
    {}))

;; --- Per-property conflict resolution ---
;;
;; A resolution value for a conflicting entity is one of:
;;   :main            keep main entirely
;;   :branch          take branch entirely
;;   {attr -> side}   per-property: choose :main or :branch for each
;;                    changed attribute independently
;; The per-attr map only applies to conflicts that carry `:changed-attrs`
;; (modify-modify / add-add). Structural conflicts (delete/modify, order,
;; presence, …) stay keyword-only.

(defn- attr-side
  "Resolved side (`:main`/`:branch`) for attribute `k` under resolution
  `res`. `:branch` keyword -> branch for every attr; a map -> its per-attr
  choice (defaulting to `:main`); anything else (`:main`, nil) -> main."
  [res k]
  (cond
    (= res :branch) :branch
    (map? res)      (if (= :branch (get res k)) :branch :main)
    :else           :main))

(defn- merge-attrs
  "Entity to apply for a per-attr resolution: start from main (`t`) and
  overlay branch's value for each differing attr resolved to `:branch`.
  For `res = :branch` this equals `o`; for `:main`/nil it equals `t`.
  An attr the winning side does not carry is REMOVED (absent key — how the
  closed change schemas express removal) rather than assoc-ed nil."
  [t o res]
  (reduce (fn [acc [k {:keys [branch]}]]
            (if (= :branch (attr-side res k))
              (if (nil? branch)
                (dissoc acc k)
                (assoc acc k branch))
              acc))
          t
          (shallow-attr-diff t o)))

(defn conflict-resolved?
  "True when `res` fully resolves `conflict`. A keyword `:main`/`:branch`
  always resolves it; a per-attr map resolves it only when it carries a
  choice for every key in the conflict's `:changed-attrs` (and the
  conflict actually has changed-attrs)."
  [conflict res]
  (cond
    (contains? #{:main :branch} res) true
    (map? res) (let [attrs (keys (:changed-attrs conflict))]
                 (boolean (and (seq attrs)
                               (every? #(contains? res %) attrs))))
    :else false))

(def ^:private shape-ignored-attrs
  "Purely derived/structural shape attrs excluded from the shape diff
  CLASSIFICATION (and thus from the compare summary): children
  membership/order (`:shapes`, redundant with the child's own add/move),
  the sync flag (`:touched`) and geometry caches (`:selrect`, `:points`,
  recomputed from position/size). Containment (`:parent-id`/`:frame-id`)
  is deliberately NOT here: reparenting a layer into a board is a real,
  user-meaningful change worth surfacing.

  Classifying on stripped shapes is what prevents FALSE conflicts: both
  sides adding children to the same frame only differ on `:shapes`, and a
  library sync on main only flips `:touched` — neither is a user change,
  so neither may turn a clean branch edit into a modify-modify/delete
  conflict. The merge still applies the real values: containment through
  add/del/move ops, geometry caches piggybacked on the geometry attrs
  (see `page-shape-changes`)."
  #{:shapes :touched :selrect :points})

(defn- strip-shapes
  "Remove `shape-ignored-attrs` from every shape of an `:objects` map, for
  diff classification purposes."
  [objects]
  (persistent!
   (reduce-kv (fn [acc id shape]
                (assoc! acc id (apply dissoc shape shape-ignored-attrs)))
              (transient {})
              (or objects {}))))

(defn- strip-nil-attrs
  "Drop keys whose value is `nil`, deeply, so that a key present with a
  `nil` value compares equal to an absent key.

  The three sides reach the comparison by different routes: the base is a
  stored snapshot, main is its own saved data, and the branch is that base
  with its op log replayed over it. They do not always spell an unset
  attribute the same way: one side can carry a key like
  `:fill-color-ref-file` or `:typography-ref-file` with `nil` where another
  omits it, and the same asymmetry appears inside a text `:content`.
  Comparing the two forms as different makes an untouched entity look
  edited on one side, and when the other side really moved it the engine
  reports a false `:modify-modify` conflict — 368 of them on the
  design-system file's screenshots of the shape trees, before this
  existed. Absent and nil mean the same thing for a shape attribute, so
  the comparison should not see the difference."
  [v]
  (cond
    (map? v)
    (persistent!
     (reduce-kv (fn [acc k x]
                  (let [x' (strip-nil-attrs x)]
                    (if (nil? x')
                      acc
                      (assoc! acc k x'))))
                (transient {})
                v))

    (vector? v) (mapv strip-nil-attrs v)
    (set? v)    (into #{} (map strip-nil-attrs) v)
    :else       v))

(defn- shape-display-meta
  "Display-only metadata for a shape diff entry — its type and component
  nature — so the compare view can pick a type-accurate icon and label
  instead of a generic one."
  [shape]
  (when (map? shape)
    (cond-> {:shape-type (:type shape)}
      (ctk/main-instance? shape)            (assoc :component? true)
      (and (ctk/instance-head? shape)
           (not (ctk/main-instance? shape))) (assoc :component-copy? true)
      (ctk/is-variant? shape)               (assoc :variant? true)
      (= :bool (:type shape))               (assoc :bool-type (:bool-type shape))
      (:masked-group shape)                 (assoc :masked? true))))

(defn three-way-entities
  "Diff one indexed entity collection (id->value) across base/theirs/ours.

  Returns `{:changes [..] :conflicts [..]}` where changes are `ours`' net
  additions/modifications/deletions that apply cleanly to `theirs`, and
  conflicts are entities both sides diverged on.

  Entries label the two sides for the `:branch->main` roles: `:main` holds
  the `theirs` value and `:branch` the `ours` one. `compute-merge` renames
  them to the two documents before reporting them (see `doc-sides`), so a
  summary names main's value `:main` and the branch's `:branch` in both
  directions.

  `ctx`: `{:kind <keyword> :page-id <optional uuid>}`. Optional display
  knobs (do NOT affect the actual merge, only this summary):
    `:ignore-ids`   ids skipped entirely (e.g. the page root frame);
    `:ignore-attrs` attr keys stripped from `:changed-attrs` (structural
                    noise like `:shapes` whose merge is driven by other ops);
    `:drop-empty-modified?` when a `:modified` entry's `:changed-attrs`
                    becomes empty after stripping, omit it altogether."
  [base theirs ours {:keys [kind ignore-ids ignore-attrs drop-empty-modified?] :as ctx}]
  (let [extras (dissoc ctx :kind :ignore-ids :ignore-attrs :drop-empty-modified?)
        attr-diff (fn [t o]
                    (let [d (shallow-attr-diff t o)]
                      (if (seq ignore-attrs) (apply dissoc d ignore-attrs) d)))
        ids (cond-> (set/union (set (keys base)) (set (keys theirs)) (set (keys ours)))
              (seq ignore-ids) (set/difference (set ignore-ids)))
        mk  (fn [status extra]
              (merge extra extras {:kind kind :status status}))]
    (reduce
     (fn [acc id]
       (let [b (strip-nil-attrs (get base id))
             t (strip-nil-attrs (get theirs id))
             o (strip-nil-attrs (get ours id))
             in-b? (contains? base id)
             in-t? (contains? theirs id)
             in-o? (contains? ours id)]
         (cond
           ;; --- deletions ---
           (and in-b? (not in-o?) (not in-t?))            ; deleted in both
           acc

           (and in-b? (not in-o?) (= b t))                ; deleted in branch, main intact
           (update acc :changes conj (mk :deleted {:id id :label (entity-label kind t)}))

           (and in-b? (not in-t?) (= b o))                ; deleted in main, branch intact
           acc

           (and in-b? (not in-o?) (not= b t))             ; delete (branch) / modify (main)
           (update acc :conflicts conj (mk :conflict {:id id :reason :delete-modify
                                                      :label (entity-label kind t)
                                                      :base b :main t :branch nil}))

           (and in-b? (not in-t?) (not= b o))             ; modify (branch) / delete (main)
           (update acc :conflicts conj (mk :conflict {:id id :reason :modify-delete
                                                      :label (entity-label kind o)
                                                      :base b :main nil :branch o}))

           ;; --- additions ---
           (and (not in-b?) in-o? (not in-t?))            ; new in branch
           (update acc :changes conj (mk :added {:id id :label (entity-label kind o)}))

           (and (not in-b?) in-t? (not in-o?))            ; new in main only
           acc

           (and (not in-b?) in-o? in-t? (= o t))          ; both added the same
           acc

           (and (not in-b?) in-o? in-t? (not= o t))       ; both added, different
           (update acc :conflicts conj (mk :conflict {:id id :reason :add-add
                                                      :label (entity-label kind o)
                                                      :changed-attrs (attr-diff t o)
                                                      :base nil :main t :branch o}))

           ;; --- present in all three ---
           (= o b)                                        ; branch didn't touch -> main wins
           acc

           (= t b)                                        ; main didn't touch, branch did
           (let [ca (attr-diff t o)]
             (if (and drop-empty-modified? (map? o) (empty? ca))
               acc
               (update acc :changes conj (mk :modified {:id id :label (entity-label kind o)
                                                        :changed-attrs ca}))))

           (= o t)                                        ; both reached the same value
           acc

           :else                                          ; both diverged differently
           (update acc :conflicts conj (mk :conflict {:id id :reason :modify-modify
                                                      :label (entity-label kind o)
                                                      :changed-attrs (attr-diff t o)
                                                      :base b :main t :branch o})))))
     {:changes [] :conflicts []}
     ids)))

(defn- merge-results
  [results]
  {:changes   (into [] (mapcat :changes) results)
   :conflicts (into [] (mapcat :conflicts) results)})

(defn- presence-only
  "Keep only the existence-level results of a CONTENT-valued presence diff:
  added/deleted changes and delete conflicts. Content modifications of
  entities present on both sides are covered by the granular passes, so
  they are dropped here. Diffing presence over content (instead of `true`)
  is what turns \"one side deleted it, the other edited it\" into a proper
  delete conflict instead of a silent clean delete."
  [{:keys [changes conflicts]}]
  {:changes   (filterv #(contains? #{:added :deleted} (:status %)) changes)
   :conflicts (filterv #(contains? #{:delete-modify :modify-delete} (:reason %)) conflicts)})

(defn- page-meta
  "Page attrs mergeable via `:mod-page` (name/background/pixel-grid)."
  [page]
  (select-keys page [:name :background :pixel-grid-color :pixel-grid-opacity]))

(def ^:private clearable-page-meta
  "Page meta attrs the `:mod-page` schema expresses removal for with an
  explicit nil (`[:maybe ...]` slots: nil is what `mod-page` removes on)."
  [:background :pixel-grid-color :pixel-grid-opacity])

(defn- page-meta-clears
  "The `:mod-page` attrs for the winning meta map `m` of a page whose
  target meta is `t`: a clearable attr `m` lacks but `t` still carries is
  emitted as explicit nil — the value `mod-page` removes on (an absent key
  would leave main's value in place)."
  [t m]
  (reduce (fn [acc k]
            (cond-> acc
              (and (not (contains? m k))
                   (contains? t k))
              (assoc k nil)))
          m
          clearable-page-meta))

(defn- page-extra
  "Residual page attrs that no pass handles — kept as `:page-attrs` to be
  refused (never silently dropped). Objects, name/background/grid, guides,
  flows, default-grids and plugin-data have their own passes; `:index` is
  derived from page order; comment-thread-positions deliberately stay on
  the branch (comments are not migrated, like Figma)."
  [page]
  (dissoc page :objects :id :name :background :pixel-grid-color :pixel-grid-opacity
          :guides :flows :default-grids :plugin-data
          :index :comment-thread-positions))

(defn- page-content
  "Normalized page value for the presence pass: the page without derived
  attrs (`:index`, comment positions — comments are not migrated) and with
  its shapes stripped of derived attrs, so only user-meaningful content
  can turn a page deletion into a delete conflict."
  [page]
  (some-> page
          (dissoc :index :comment-thread-positions)
          (update :objects strip-shapes)))

(defn- page-content-map
  [pages-index ids]
  (into {} (map (fn [id] [id (page-content (get pages-index id))])) ids))

(defn- slim-page
  "Compact page summary for conflict payloads (a full page embeds every
  shape three times over the wire)."
  [page]
  (when page
    {:id (:id page) :name (:name page) :shapes (count (:objects page))}))

(defn- slim-conflict-sides
  [slim-fn conflicts]
  (mapv (fn [c] (-> c (update :base slim-fn) (update :main slim-fn) (update :branch slim-fn)))
        conflicts))

(defn- flatten-plugin-data
  "{namespace {key value}} -> {[namespace key] value} for diffing."
  [pd]
  (persistent!
   (reduce-kv (fn [acc ns kvs]
                (reduce-kv (fn [acc k v] (assoc! acc [ns k] v)) acc kvs))
              (transient {})
              (or pd {}))))

;; --- Shape tree ---
;;
;; `three-way-entities` classifies shapes one by one, while containment
;; and child order are relations between them. The helpers below put the
;; tree back: they find same-parent reorders for the classification and
;; apply the tree-shape policies (`*policies*`) to a page's result. They
;; work in the roles of `three-way-entities`: `to` is the target of the
;; change (theirs) and `oo` its source (ours).

(defn- index-of
  [coll x]
  (first (keep-indexed (fn [i v] (when (= v x) i)) coll)))

(defn- subtree-ids
  "`id` plus all its descendants' ids, walking `:shapes` in `objects`."
  [objects id]
  (loop [pending [id] out []]
    (if (empty? pending)
      out
      (let [cur (peek pending)]
        (recur (into (pop pending) (get-in objects [cur :shapes]))
               (conj out cur))))))

(defn- shape-depth
  "Depth of a shape in its objects tree (root = 0). Used to order moves so a
  container is relocated before the shapes the branch moved into it."
  [objects id]
  (loop [id id d 0]
    (let [p (:parent-id (get objects id))]
      (if (and p (not= p id) (contains? objects p))
        (recur p (inc d))
        d))))

(defn- root-id?
  "True for the page root and for a missing parent."
  [id]
  (or (nil? id) (= id uuid/zero)))

(defn- preorder-ids
  "`id` and its descendants in `objects`, parents first and children in
  `:shapes` order."
  [objects id]
  (loop [stack (list id) out []]
    (if (empty? stack)
      out
      (let [cur (first stack)]
        (recur (into (rest stack) (reverse (get-in objects [cur :shapes])))
               (conj out cur))))))

(defn- relative-order
  [shapes keep]
  (filterv #(contains? keep %) shapes))

(defn- child-reorder
  "Relative order, on each side, of the children `os` shares with `bs`
  (and with `ts` when `theirs?`), when `os` changes it; nil otherwise.
  Equal vectors short-circuit, so an untouched parent costs one `=`."
  [bs ts os theirs?]
  (when-not (or (identical? bs os) (= bs os))
    (let [keep (cond-> (set/intersection (set bs) (set os))
                 theirs? (set/intersection (set ts)))]
      (when (> (count keep) 1)
        (let [order-b (relative-order bs keep)
              order-o (relative-order os keep)]
          (when (not= order-b order-o)
            {:base   order-b
             :theirs (when theirs? (relative-order ts keep))
             :ours   order-o}))))))

(defn- shape-reorders
  "Parent id -> `child-reorder` for every parent whose children `oo`
  reorders against `bo`."
  [bo to oo]
  (persistent!
   (reduce-kv (fn [acc id o]
                (let [os (:shapes o)
                      b  (when (some? os) (get bo id))]
                  (if (nil? b)
                    acc
                    (let [t (get to id)]
                      (if-let [r (child-reorder (:shapes b) (:shapes t) os (some? t))]
                        (assoc! acc id r)
                        acc)))))
              (transient {})
              (or oo {}))))

(defn- classification-objects
  "The stripped objects maps (see `strip-shapes`) a page's shapes are
  classified on, plus the same-parent reorders `oo` makes.

  Under `:same-parent-reorder :merge` a reordered parent carries the
  relative order of its shared children as `:shapes` on every side that
  has it, so the reorder classifies as a change of the parent and both
  sides reordering it differently as a conflict on it. `:refuse` finds
  the reorders without classifying them; `:ignore` skips the scan."
  [bo to oo]
  (let [mode     (policy :same-parent-reorder)
        reorders (if (= :ignore mode) {} (shape-reorders bo to oo))
        inject   (fn [objects side]
                   (if (and (= :merge mode) (seq reorders))
                     (reduce-kv (fn [acc id r]
                                  (if (contains? acc id)
                                    (assoc-in acc [id :shapes] (get r side))
                                    acc))
                                objects
                                reorders)
                     objects))]
    {:sbo      (inject (strip-shapes bo) :base)
     :sto      (inject (strip-shapes to) :theirs)
     :soo      (inject (strip-shapes oo) :ours)
     :reorders reorders}))

(defn- tree-modes
  "The tree-shape policies in the roles of `three-way-entities` for `dir`.

  The policies name the documents and the engine handles two role
  shapes: `:lost`, the target deleted a container the source edits
  inside (main deleted P and the branch added into it, when merging),
  and `:dropped`, the source deleted a container the target edits into
  (the branch deleted F while main edited inside it, when merging). A
  merge maps each policy to its own shape; an update from main swaps the
  roles, so each policy drives the other shape through the alternative
  that means the same thing for the documents.

  A mechanism's alternatives:
    `:lost`    `:conflict` (a `:modify-delete` conflict on the container:
               `:branch` restores it as the source has it, additions
               included, `:main` drops the source's shapes), `:refuse`,
               `:reparent-to-ancestor`, `:page-root`, `:cascade` (the
               container goes with its whole subtree).
    `:dropped` `:conflict` (a `:delete-modify` conflict on the container:
               `:branch` deletes the whole subtree knowingly, `:main`
               keeps it as the target has it), `:expand` (the target's
               shapes relocate to the container's slot in its parent),
               `:page-root`, `:refuse`, `:cascade`."
  [dir]
  (let [update? (= dir :main->branch)
        add     (policy :addition-under-deleted-parent)
        cont    (policy :container-delete-over-main-edits)]
    {:order   (policy :same-parent-reorder)
     :lost    (if update?
                (get {:conflict :conflict :expand :reparent-to-ancestor :cascade :cascade}
                     cont :conflict)
                (get {:conflict :conflict :refuse :refuse
                      :reparent-to-ancestor :reparent-to-ancestor :page-root :page-root}
                     add :conflict))
     :dropped (if update?
                (get {:conflict :conflict :refuse :refuse
                      :reparent-to-ancestor :expand :page-root :page-root}
                     add :conflict)
                (get {:conflict :conflict :expand :expand :cascade :cascade}
                     cont :conflict))}))

(defn- lost-root
  "Highest shape on `id`'s ancestry in the source tree `oo`, `id`
  included, that the target deleted. The walk climbs while a shape is
  absent from the target `to`, either deleted there (it is in the base
  `bo`) or added by the source; nil when it meets no deleted shape."
  [bo to oo id]
  (loop [cur id top nil n (count oo)]
    (if (or (root-id? cur) (contains? to cur) (not (contains? oo cur)) (neg? n))
      top
      (recur (:parent-id (get oo cur))
             (if (contains? bo cur) cur top)
             (dec n)))))

(defn- target-subtree-edits
  "Ids in the target's subtree of `id`, `id` excluded, that the target
  added, changed or (unless `order-mode` is `:ignore`) reordered the
  children of, against the base."
  [bo to sbo sto order-mode id]
  (filterv (fn [d]
             (let [b (get bo d)]
               (or (nil? b)
                   (not= (strip-nil-attrs (get sbo d)) (strip-nil-attrs (get sto d)))
                   (and (not= :ignore order-mode)
                        (some? (child-reorder (:shapes b) nil (:shapes (get to d)) false))))))
           (rest (preorder-ids to id))))

(defn- source-deleted-roots
  "The ids of `deleted` whose parent in `to` is not in `deleted`: the
  topmost shapes of each deleted subtree."
  [to deleted]
  (filterv #(not (contains? deleted (:parent-id (get to %)))) deleted))

(defn- tree-policy-pass
  "Apply the tree-shape policies (`tree-modes`) to one page's shape
  result `res` from `three-way-entities`.

  `:same-parent-reorder :refuse` adds an `:unsupported` `:shape-order`
  entry per reordered parent the merge would have to reorder.

  `:lost` gathers the source's additions, and its changes to shapes the
  target deleted, under the highest deleted shape on their path
  (`lost-root`). `:conflict` replaces them with one `:modify-delete`
  conflict on that shape: resolved to `:branch` it restores the shape
  and its subtree as the source has them, additions included; resolved
  to `:main` it leaves them out. `:refuse` adds an `:unsupported`
  `:shape-orphan` entry per deleted shape holding an addition. The other
  alternatives act in `page-shape-changes`.

  `:dropped` looks at the topmost shapes the source deleted whose
  subtree the target edited (added, changed or reordered something in).
  `:conflict` turns each into one `:delete-modify` conflict that replaces
  its `:deleted` entry and every entry inside its subtree: resolved to
  `:branch` the whole subtree goes, resolved to `:main` it stays as the
  target has it. `:refuse` adds an `:unsupported` `:shape-orphan` entry
  per such shape the target added inside. The other alternatives act in
  `page-shape-changes`.

  A region conflict lists the shapes it decides for in `:subtree-edits`
  (`[{:id :label}]`). Without any of these cases the pass returns `res`
  as it is."
  [{:keys [changes conflicts] :as res} bo to oo sbo sto reorders page-id modes]
  (let [{lost-mode :lost dropped-mode :dropped order-mode :order} modes
        label   (fn [id] (entity-label :shape (or (get oo id) (get to id) (get bo id))))
        edits   (fn [ids] (mapv (fn [id] {:id id :label (label id)}) ids))
        entry   (fn [m] (assoc m :kind (:kind m :shape) :page-id page-id :label (label (:id m))))
        added?  #(= :added (:status %))

        refused-orders
        (when (= :refuse order-mode)
          (into [] (keep (fn [[id {:keys [theirs ours]}]]
                           (when (not= theirs ours)
                             (entry {:kind :shape-order :status :unsupported :id id
                                     :policy :same-parent-reorder}))))
                reorders))

        ;; [id root] for the source's edits inside a container the target
        ;; deleted, in result order
        lost
        (case lost-mode
          :conflict (into [] (keep (fn [{:keys [id]}]
                                     (when-let [r (lost-root bo to oo id)] [id r])))
                          (concat (filter added? changes)
                                  (filter #(= :modify-delete (:reason %)) conflicts)))
          :refuse   (into [] (keep (fn [{:keys [id]}]
                                     (when-let [r (lost-root bo to oo id)] [id r])))
                          (filter added? changes))
          [])
        lost-ids (into #{} (map first) lost)
        lost-by  (group-by second lost)

        ;; topmost shapes the source deleted whose subtree the target edited
        dropped
        (if (contains? #{:conflict :refuse} dropped-mode)
          (let [deleted (into #{} (comp (filter #(or (= :deleted (:status %))
                                                     (= :delete-modify (:reason %))))
                                        (map :id))
                              (concat changes conflicts))]
            (into [] (keep (fn [f]
                             (let [e (cond->> (target-subtree-edits bo to sbo sto order-mode f)
                                       (= :refuse dropped-mode) (filterv #(not (contains? bo %))))]
                               (when (seq e) [f e]))))
                  (source-deleted-roots to deleted)))
          [])
        dropped-in (into #{} (mapcat (fn [[f _]] (preorder-ids to f))) dropped)]

    (if (and (empty? refused-orders) (empty? lost) (empty? dropped))
      res
      (let [lost-conflict?    (= :conflict lost-mode)
            dropped-conflict? (= :conflict dropped-mode)
            region-edits      (merge (into {} (map (fn [[r pairs]]
                                                     [r (edits (into [] (comp (map first) (remove #{r})) pairs))]))
                                           (when lost-conflict? lost-by))
                                     (when dropped-conflict? (into {} (map (fn [[f e]] [f (edits e)])) dropped)))
            own-reason        (fn [id] (if (contains? lost-by id) :modify-delete :delete-modify))
            absorbed?         (fn [{:keys [id status reason]}]
                                (or (and lost-conflict? (contains? lost-ids id)
                                         (or (= :added status) (= :modify-delete reason))
                                         (not (contains? region-edits id)))
                                    (and dropped-conflict? (contains? dropped-in id)
                                         (or (= :deleted status) (= :delete-modify reason)))))
            kept-conflicts    (into [] (remove absorbed?) conflicts)
            own               (into #{} (keep (fn [{:keys [id reason]}]
                                                (when (and (contains? region-edits id)
                                                           (= reason (own-reason id)))
                                                  id)))
                                    kept-conflicts)
            conflicts'        (-> (mapv (fn [c]
                                          (if (and (contains? own (:id c))
                                                   (= (:reason c) (own-reason (:id c))))
                                            (assoc c :subtree-edits (get region-edits (:id c)))
                                            c))
                                        kept-conflicts)
                                  (into (comp (remove own)
                                              (map (fn [r]
                                                     (entry {:status :conflict :id r
                                                             :reason (own-reason r)
                                                             :subtree-edits (get region-edits r)}))))
                                        (keys region-edits)))
            refused-orphans   (concat
                               (when (= :refuse lost-mode)
                                 (map (fn [[r pairs]]
                                        (entry {:kind :shape-orphan :status :unsupported :id r
                                                :policy :addition-under-deleted-parent
                                                :shapes (mapv first pairs)}))
                                      lost-by))
                               (when (= :refuse dropped-mode)
                                 (map (fn [[f e]]
                                        (entry {:kind :shape-orphan :status :unsupported :id f
                                                :policy :addition-under-deleted-parent
                                                :shapes e}))
                                      dropped)))]
        {:changes   (-> (into [] (remove absorbed?) changes)
                        (into refused-orders)
                        (into refused-orphans))
         :conflicts conflicts'}))))

(defn- diff-pages
  ([base theirs ours] (diff-pages base theirs ours nil :branch->main))
  ([base theirs ours only-pages dir]
   ;; `only-pages` bounds every page pass, the presence one included,
   ;; because `page-content` strips the shapes of every page it looks at
   ;; and that is most of what a whole-file comparison costs. It is the
   ;; caller's job to establish that `ours` cannot differ from `base`
   ;; outside that set (see `compute-merge`).
   (let [keep?    (if (some? only-pages) #(contains? only-pages %) (constantly true))
         bpi (or (:pages-index base) {}) tpi (or (:pages-index theirs) {}) opi (or (:pages-index ours) {})
         bids (into #{} (filter keep?) (keys bpi))
         tids (into #{} (filter keep?) (keys tpi))
         oids (into #{} (filter keep?) (keys opi))
         common (set/intersection bids tids oids)

         ;; page add/delete (mergeable: :page). Presence is diffed over the
         ;; page CONTENT so that deleting a page the other side edited raises
         ;; a delete conflict instead of silently dropping those edits;
         ;; content-only modifications are filtered out (granular passes own
         ;; them) and conflict payloads are slimmed (a full page is huge).
         presence (-> (three-way-entities (page-content-map bpi bids)
                                          (page-content-map tpi tids)
                                          (page-content-map opi oids)
                                          {:kind :page})
                      (presence-only)
                      (update :conflicts #(slim-conflict-sides slim-page %)))
         ;; page name/background/grid on common pages (mergeable: :page via mod-page)
         meta-map (fn [pi] (into {} (map (fn [id] [id (page-meta (get pi id))])) common))
         meta-diff (three-way-entities (meta-map bpi) (meta-map tpi) (meta-map opi) {:kind :page})
         ;; residual page attrs on common pages (NOT mergeable -> refused, never dropped)
         extra-map (fn [pi] (into {} (map (fn [id] [id (page-extra (get pi id))])) common))
         extra-diff (three-way-entities (extra-map bpi) (extra-map tpi) (extra-map opi) {:kind :page-attrs})
         ;; page order on common pages. The entity id is `:page-order` (NOT a
         ;; bare `:order`) so its conflict resolution cannot collide with the
         ;; token-set-order one and matches what `compute-changes` reads.
         order-of (fn [data] (filterv common (or (:pages data) [])))
         order (three-way-entities {:page-order (order-of base)} {:page-order (order-of theirs)} {:page-order (order-of ours)}
                                   {:kind :page-order})
         ;; guides / flows per common page (mergeable: :page-guide / :page-flow)
         sub-of (fn [data pid k] (get-in data [:pages-index pid k] {}))
         guides-diffs (map (fn [pid]
                             (three-way-entities (sub-of base pid :guides)
                                                 (sub-of theirs pid :guides)
                                                 (sub-of ours pid :guides)
                                                 {:kind :page-guide :page-id pid}))
                           common)
         flows-diffs (map (fn [pid]
                            (three-way-entities (sub-of base pid :flows)
                                                (sub-of theirs pid :flows)
                                                (sub-of ours pid :flows)
                                                {:kind :page-flow :page-id pid}))
                          common)
         ;; default-grids per common page (mergeable: :page-grid -> :set-default-grid)
         grids-diffs (map (fn [pid]
                            (three-way-entities (sub-of base pid :default-grids)
                                                (sub-of theirs pid :default-grids)
                                                (sub-of ours pid :default-grids)
                                                {:kind :page-grid :page-id pid}))
                          common)
         ;; page-level plugin-data per common page (mergeable: :page-plugin -> :set-plugin-data)
         plugin-of (fn [data pid] (flatten-plugin-data (get-in data [:pages-index pid :plugin-data] {})))
         plugins-diffs (map (fn [pid]
                              (three-way-entities (plugin-of base pid)
                                                  (plugin-of theirs pid)
                                                  (plugin-of ours pid)
                                                  {:kind :page-plugin :page-id pid}))
                            common)
         ;; objects (shapes) on common pages (mergeable: :shape). The shapes
         ;; are diffed STRIPPED of derived attrs (`shape-ignored-attrs`) and
         ;; with the same-parent reorders folded in as `:shapes` of their
         ;; parent (`classification-objects`), so derived churn neither shows
         ;; up as a change nor manufactures false conflicts, while a reorder
         ;; is a change of the parent. The page root frame (uuid/zero) is
         ;; skipped unless the reordering touches it: top-level layer order
         ;; is a real, user-meaningful change.
         modes     (tree-modes dir)
         obj-diffs (map (fn [pid]
                          (let [bo  (get-in base [:pages-index pid :objects] {})
                                to  (get-in theirs [:pages-index pid :objects] {})
                                oo  (get-in ours [:pages-index pid :objects] {})
                                {:keys [sbo sto soo reorders]} (classification-objects bo to oo)
                                res (three-way-entities sbo sto soo
                                                        {:kind :shape :page-id pid
                                                         :ignore-ids (if (contains? reorders uuid/zero)
                                                                       #{}
                                                                       #{uuid/zero})})
                                ;; enrich each entry with the shape's type/component
                                ;; nature, read from whichever side still has it
                                enrich (fn [e]
                                         (merge e (shape-display-meta
                                                   (or (get oo (:id e))
                                                       (get to (:id e))
                                                       (get bo (:id e))))))
                                ;; conflict payloads must carry the FULL shapes
                                ;; (the diff classified stripped copies, but the
                                ;; conflict UI renders real previews that need
                                ;; :selrect/:points/:transform)
                                rehydrate (fn [e]
                                            (assoc e
                                                   :base (get bo (:id e))
                                                   :main (get to (:id e))
                                                   :branch (get oo (:id e))))
                                passed (tree-policy-pass res bo to oo sbo sto reorders pid modes)]
                            {:changes   (mapv (fn [e]
                                                ;; a `:refuse` entry names the
                                                ;; container it could not place
                                                ;; under; it is not a shape to
                                                ;; display
                                                (if (= :unsupported (:status e))
                                                  e
                                                  (enrich e)))
                                              (:changes passed))
                             :conflicts (mapv (comp rehydrate enrich) (:conflicts passed))}))
                        common)]
     (merge-results (concat [presence meta-diff extra-diff order]
                            guides-diffs flows-diffs grids-diffs plugins-diffs obj-diffs)))))

;; --- Tokens ---
;;
;; Every token change is mergeable. Token *values* (tokens within an
;; existing set) carry kind :token -> :set-token. Structural changes
;; carry their own kinds, each translated by `compute-changes`: set
;; add/delete :token-set, rename/description :token-set-rename, set order
;; :token-set-order, themes :token-theme, active themes
;; :token-active-themes and active-set toggles :token-active-sets.

(defn- lib-set-ids
  [lib]
  (if lib (into #{} (map ctob/get-id) (ctob/get-sets lib)) #{}))

(defn- lib-set-order
  [lib]
  (if lib (mapv ctob/get-id (ctob/get-sets lib)) []))

(defn- lib-set-meta
  "set-id -> [name description] for each set."
  [lib]
  (if lib
    (into {} (map (fn [s] [(ctob/get-id s) [(ctob/get-name s) (ctob/get-description s)]]))
          (ctob/get-sets lib))
    {}))

(declare lib-tokens-by-id)

(defn- set-content
  "Normalized token-set value (metadata + tokens) for the presence pass, so
  deleting a set the other side edited raises a delete conflict."
  [lib set-id]
  (when (and lib (ctob/get-set lib set-id))
    (let [s (ctob/get-set lib set-id)]
      {:id set-id
       :name (ctob/get-name s)
       :description (ctob/get-description s)
       :tokens (lib-tokens-by-id lib set-id)})))

(defn- set-content-map
  [lib]
  (into {} (map (fn [sid] [sid (set-content lib sid)]))
        (if lib (map ctob/get-id (ctob/get-sets lib)) [])))

(defn- slim-set
  "Compact token-set summary for conflict payloads."
  [s]
  (when s
    {:id (:id s) :name (:name s) :tokens (count (:tokens s))}))

(defn- set-restore-attrs
  "Attrs to (re)create a set in the target. A brand-new set (absent from
  the base) carries metadata only — its tokens are added by the per-token
  pass. A RESTORED set (present in the base, deleted on the other side)
  must carry its tokens too: the per-token pass skips sets deleted on
  either side."
  [lib base-lib set-id]
  (let [s     (ctob/get-set lib set-id)
        attrs {:id set-id :name (ctob/get-name s) :description (ctob/get-description s)}]
    (if (and base-lib (ctob/get-set base-lib set-id))
      (assoc attrs :tokens (ctob/get-tokens lib set-id))
      attrs)))

(defn- set-rename-attrs
  "Attrs to rename a set: take branch's name/description but keep MAIN's
  tokens — emitted before the per-token pass, which then layers branch's
  token edits on top. `:set-token-set`/`update-set` relocates the set and
  updates theme references for the new name."
  [main-lib branch-lib set-id]
  (let [bs (ctob/get-set branch-lib set-id)]
    {:id set-id
     :name (ctob/get-name bs)
     :description (ctob/get-description bs)
     :tokens (ctob/get-tokens main-lib set-id)}))

(defn- set-meta-of
  [lib set-id]
  (let [s (ctob/get-set lib set-id)]
    [(ctob/get-name s) (ctob/get-description s)]))

(defn- set-rename-wins?
  "True when the rename/description change for `sid` is emitted: branch
  changed the set metadata and either main left it at the base or the
  resolution takes branch. Those renames run before the set-order moves
  in the change list, so they settle the names the moves address."
  [bl ml ol resolutions sid]
  (let [b (set-meta-of bl sid)
        m (set-meta-of ml sid)
        o (set-meta-of ol sid)]
    (and (not= o b)
         (or (= m b)
             (= (get resolutions sid) :branch)))))

(defn- lib-tokens-by-id
  "token-id -> token (plain map) for a single set."
  [lib set-id]
  (if (and lib (ctob/get-set lib set-id))
    (into {} (map (fn [t] [(:id t) (into {} t)])) (vals (ctob/get-tokens lib set-id)))
    {}))

(defn- lib-themes
  "theme-id -> theme (plain map), excluding the internal hidden theme."
  [lib]
  (if lib
    (into {} (comp (remove #(= (:id %) ctob/hidden-theme-id))
                   (map (fn [t] [(:id t) (into {} t)])))
          (ctob/get-themes lib))
    {}))

(defn- lib-active-paths
  "Active theme paths (mergeable via :set-active-token-themes)."
  [lib]
  (if lib (set (ctob/get-active-theme-paths lib)) #{}))

(defn- lib-hidden-sets
  "The hidden theme's active sets (active-set toggles)."
  [lib]
  (some-> lib (ctob/get-theme ctob/hidden-theme-id) :sets set))

(defn- hidden-theme-map
  "The hidden theme as a plain map (for :set-token-theme)."
  [lib]
  (some->> (when lib (ctob/get-theme lib ctob/hidden-theme-id)) (into {})))

(defn- set-order-by-id
  "Set ids in their stored order, filtered to `ids`."
  [lib ids]
  (filterv ids (lib-set-order lib)))

(defn- set-name->path
  "Split a token-set name into its path vector (sets are referenced by
  path, separator \"/\")."
  [name]
  (str/split name #"/"))

(defn- diff-tokens
  [base theirs ours]
  (let [bl (:tokens-lib base) tl (:tokens-lib theirs) ol (:tokens-lib ours)
        bids (lib-set-ids bl) tids (lib-set-ids tl) oids (lib-set-ids ol)
        common (set/intersection bids tids oids)

        ;; set add/delete (mergeable: :token-set). Diffed over the set
        ;; CONTENT so deleting a set the other side edited raises a delete
        ;; conflict (see `presence-only`); payloads slimmed for the wire.
        presence (-> (three-way-entities (set-content-map bl) (set-content-map tl) (set-content-map ol)
                                         {:kind :token-set})
                     (presence-only)
                     (update :conflicts #(slim-conflict-sides slim-set %)))
        ;; set rename/description on common sets (mergeable: :token-set-rename)
        rename   (three-way-entities (select-keys (lib-set-meta bl) common)
                                     (select-keys (lib-set-meta tl) common)
                                     (select-keys (lib-set-meta ol) common)
                                     {:kind :token-set-rename})
        ;; set order on common sets. Entity id `:token-set-order` (NOT a bare
        ;; `:order`) so its resolution cannot collide with the page-order one.
        order-of (fn [lib] (filterv common (lib-set-order lib)))
        order    (three-way-entities {:token-set-order (order-of bl)} {:token-set-order (order-of tl)} {:token-set-order (order-of ol)}
                                     {:kind :token-set-order})
        ;; themes, excluding hidden (mergeable: :token-theme)
        themes   (three-way-entities (lib-themes bl) (lib-themes tl) (lib-themes ol)
                                     {:kind :token-theme})
        ;; active theme paths (mergeable: :token-active-themes)
        active-paths (three-way-entities {:active-themes (lib-active-paths bl)}
                                         {:active-themes (lib-active-paths tl)}
                                         {:active-themes (lib-active-paths ol)}
                                         {:kind :token-active-themes})
        ;; active-set toggles (hidden theme) (mergeable: :token-active-sets)
        active-sets (three-way-entities {:active-sets (lib-hidden-sets bl)}
                                        {:active-sets (lib-hidden-sets tl)}
                                        {:active-sets (lib-hidden-sets ol)}
                                        {:kind :token-active-sets})
        ;; per-token values (mergeable: :token). Sets deleted on either side
        ;; relative to the base are decided wholesale at set level (delete or
        ;; delete-conflict), so their per-token diff would only add redundant
        ;; token-level noise — skip them.
        set-ids  (set/union bids tids oids)
        deleted-on-a-side? (fn [sid]
                             (and (contains? bids sid)
                                  (or (not (contains? tids sid))
                                      (not (contains? oids sid)))))
        tokens   (map (fn [sid]
                        (three-way-entities (lib-tokens-by-id bl sid)
                                            (lib-tokens-by-id tl sid)
                                            (lib-tokens-by-id ol sid)
                                            {:kind :token :set-id sid}))
                      (remove deleted-on-a-side? set-ids))]
    (merge-results (concat [presence rename order themes active-paths active-sets] tokens))))

(defn remap-refs
  "Rewrite cross-file references in `data` through `id-map`
  ({old-id -> new-id}): the file refs `cfh/relink-refs` rewrites
  (`:component-file`, `:fill-color-ref-file`, `:stroke-color-ref-file`,
  `:typography-ref-file`, shadow/grid `:file-id`) plus the media refs
  (shape `:metadata`/`:fill-image`/`:stroke-image` ids, the `:media`
  collection keys and library-color `:image` ids). It is the same surface
  `binfile.common/process-file` remaps when it copies a file.

  A branch stores no data of its own: its state is its source's base
  snapshot with the branch's op log replayed over it
  (`binfile.common/branch-file-data`). What it inherited from the base
  keeps the source's ids, but the references the branch writes itself
  name the branch file and the media rows it owns. Before diffing or
  merging against another file those references must be normalized to
  the target's ids; otherwise (a) every affected entity looks modified,
  inflating the diff/conflicts, and (b) merged references cannot be
  resolved in the target file — repair would detach components and
  images would break. Ids not present in `id-map` (external libraries)
  are left untouched."
  [data id-map]
  (if (empty? id-map)
    data
    (let [lookup #(get id-map % %)]
      (-> data
          (d/update-when :pages-index #(cfh/relink-refs % lookup))
          (d/update-when :components #(cfh/relink-refs % lookup))
          (d/update-when :media
                         (fn [media]
                           (reduce-kv (fn [res k v]
                                        (let [id (lookup k)]
                                          (if (= id k)
                                            res
                                            (-> res (dissoc k) (assoc id (assoc v :id id))))))
                                      media
                                      media)))
          (d/update-when :colors
                         (fn [colors]
                           (reduce-kv (fn [res k v]
                                        (let [image-id (get-in v [:image :id])
                                              new-id   (some-> image-id lookup)]
                                          (if (or (nil? image-id) (= new-id image-id))
                                            res
                                            (assoc-in res [k :image :id] new-id))))
                                      colors
                                      colors)))))))

(def ^:private reference-attrs
  "Shape attrs whose value IS a reference to the file that owns the entity.
  `relink-refs` finds them inside a shape map, where the attr and the value
  travel together. An operation that sets one of them carries the id alone."
  #{:component-file :fill-color-ref-file :stroke-color-ref-file :typography-ref-file})

(defn- remap-op
  "Rewrite the reference of one `:mod-obj` operation through `id-map`.
  `shape-set-ops` emits one `:set` per differing attr, so a reference can
  arrive either as the attr's own value or, for an image shape, as the media
  map under `:metadata`."
  [op id-map]
  (let [attr (:attr op)
        val  (:val op)]
    (cond
      (and (contains? reference-attrs attr) (uuid? val))
      (assoc op :val (get id-map val val))

      (and (= :metadata attr) (uuid? (:id val)))
      (assoc op :val (update val :id #(get id-map % %)))

      :else op)))

(defn remap-changes
  "Rewrite the cross-file references of a change vector through `id-map`,
  the counterpart of `remap-refs` for the changes `compute-changes` emits.
  A change computed in the source file's frame has to be applied in the
  target's, because a reference is only a reference in the file it names: a
  fill whose `:fill-color-ref-file` names another file loses its link, and so
  does an instance whose `:component-file` does.

  Every payload `compute-changes` can emit is covered. `relink-refs` walks a
  whole map, so `:obj`, `:page`, `:params`, and a component's `:objects` are
  complete on their own. The payloads it cannot see are the values that ARE
  the reference: a media object's `:id` (its `:media-id` is a storage key and
  must not be touched), a library color's `:image` id, a `:del-media`'s
  top-level `:id`, and an operation value that is the reference itself. The
  top-level `:id` of the other delete changes (`:del-color`,
  `:del-typography`, `:del-component`, `:del-obj`, `:del-page`) is NOT such a
  value: those name entities the two files share by id, never a media row,
  so they are left alone — only a media id or the file id is ever a key of
  `id-map`. Token changes are left alone: no id in `id-map` is a token id."
  [changes id-map]
  (if (empty? id-map)
    changes
    (let [lookup #(get id-map % %)
          relink #(cfh/relink-refs % lookup)]
      (mapv (fn [change]
              (cond-> (relink change)
                (= :del-media (:type change))
                (update :id lookup)

                (uuid? (:id (:object change)))
                (update-in [:object :id] lookup)

                (uuid? (get-in change [:color :image :id]))
                (update-in [:color :image :id] lookup)

                (seq (:operations change))
                (update :operations #(mapv (fn [op] (remap-op op id-map)) %))))
            changes))))

(defn- strip-modified-at
  "Remove the `:modified-at` bookkeeping timestamp from every entry of an
  indexed collection (components, colors, typographies).

  `:modified-at` is a \"last touched\" stamp refreshed on EVERY apply
  (`components-list/touch`, `types.library/touch`): diffing it flags
  spurious `:modified` entries — e.g. an update-from-main restamps the
  copied entities, making them differ from main forever after — and, when
  both sides edited the same entity, spurious `:modify-modify` conflicts
  whose only difference is the timestamp. Dropping it is safe because the
  change pipeline regenerates it on apply anyway."
  [index]
  (persistent!
   (reduce-kv (fn [acc id v]
                (assoc! acc id (dissoc v :modified-at)))
              (transient {})
              (or index {}))))

(defn- doc-sides
  "Rewrite one summary entry from the comparison roles to the documents.

  `three-way-entities` reports entries in the `:branch->main` roles — the
  `theirs` value under `:main`, the `ours` value under `:branch`. For
  `:main->branch` the engine runs with those roles fed the other way round
  (so the reported changes are main's), and this renames the payload back
  before `compute-merge` reports it: the two entity sides, the two values
  inside every `:changed-attrs` pair, and the two delete reasons.
  `:delete-modify` names the branch's deletion against main's modification
  and `:modify-delete` the branch's modification against main's deletion —
  the same meaning in both directions."
  [{:keys [status changed-attrs] :as entry}]
  (let [entry (cond-> entry
                (map? changed-attrs)
                (assoc :changed-attrs
                       (into {} (map (fn [[k v]] [k {:main (:branch v) :branch (:main v)}]))
                             changed-attrs)))]
    (if (= :conflict status)
      (-> entry
          (assoc :main (:branch entry) :branch (:main entry))
          (update :reason #(get {:delete-modify :modify-delete
                                 :modify-delete :delete-modify} % %)))
      entry)))

(defn- compute-merge*
  "The comparison itself. Both arities of `compute-merge` land here rather
  than one delegating to the other through the var, so that a test which
  counts engine calls counts comparisons and not dispatches."
  [base main branch dir only-pages]
  (let [update?  (= dir :main->branch)
        [theirs ours] (if update? [branch main] [main branch])
        results  [(three-way-entities (strip-modified-at (:colors base))
                                      (strip-modified-at (:colors theirs))
                                      (strip-modified-at (:colors ours))
                                      {:kind :color})
                  (three-way-entities (strip-modified-at (:typographies base))
                                      (strip-modified-at (:typographies theirs))
                                      (strip-modified-at (:typographies ours))
                                      {:kind :typography})
                  (three-way-entities (strip-modified-at (:components base))
                                      (strip-modified-at (:components theirs))
                                      (strip-modified-at (:components ours))
                                      {:kind :component})
                  (three-way-entities (:media base) (:media theirs) (:media ours)
                                      {:kind :media})
                  (diff-pages base theirs ours only-pages dir)
                  (diff-tokens base theirs ours)]
        {:keys [changes conflicts]} (merge-results results)
        ;; the engine reports `ours`' changes against `theirs` and labels
        ;; those roles `:main`/`:branch`; the summary names the DOCUMENTS,
        ;; so `:main->branch` (theirs=branch, ours=main) is renamed here
        ;; (see `doc-sides`)
        changes   (if update? (mapv doc-sides changes) changes)
        conflicts (if update? (mapv doc-sides conflicts) conflicts)]
    {:changes   changes
     :conflicts conflicts
     ;; the tree-shape policies act per direction (`tree-modes`), so
     ;; `compute-changes` needs to know which one this summary is
     :dir       dir
     :stats     {:added     (count (filterv #(= :added (:status %)) changes))
                 :modified  (count (filterv #(= :modified (:status %)) changes))
                 :deleted   (count (filterv #(= :deleted (:status %)) changes))
                 :conflicts (count conflicts)}}))

(defn compute-merge
  "Compute the three-way diff between the merge `base`, `main` and
  `branch` file `:data`. Returns:

    {:changes   [<change descriptor> ...]   ; clean, branch -> main
     :conflicts [<conflict descriptor> ...] ; need resolution
     :stats     {:added n :modified n :deleted n :conflicts n}}

  Every entry names the two DOCUMENTS, whatever `dir`: `:main` carries
  main's value, `:branch` the branch's, and each `:changed-attrs` pair
  (`attribute -> {:main v :branch v}`) says the same. The two delete
  reasons do not flip with `dir` either: `:delete-modify` names the
  branch's deletion against main's modification, `:modify-delete` the
  branch's modification against main's deletion. The `:changes` do follow
  `dir` — `:branch->main` reports the branch's outgoing changes,
  `:main->branch` main's incoming ones.

  `opts` may carry `:only-pages`, a set of page ids the page passes are
  bounded to. It is sound exactly when `ours` cannot differ from `base`
  outside that set, because then no page outside it can carry an
  `ours`-side change and no page outside it can hold a conflict, which
  needs one. A branch whose op log touches only those pages satisfies
  that by construction, since its state IS the base plus that log. It is
  NOT sound for the direction that reports main's changes, and passing it
  there would hide them.

  The `:tokens-lib` is diffed as well (`diff-tokens`), so token changes
  are reported here with their own kinds."
  ([base main branch dir] (compute-merge* base main branch dir nil))
  ([base main branch dir {:keys [only-pages]}]
   (compute-merge* base main branch dir only-pages)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MERGE -> CHANGES (clean changes plus resolved conflicts)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Kinds `compute-changes` can translate into change ops: every kind
;; `compute-merge` reports except the residual page attrs (`:page-attrs`),
;; which make the merge refuse rather than silently drop changes.
(def ^:private mergeable-kinds
  #{:color :typography :media :shape :token :token-set :token-set-rename :token-set-order
    :token-theme :token-active-themes :token-active-sets
    :page :page-order :page-guide :page-flow :page-grid :page-plugin :component})

(defn unsupported-kinds
  "Set of change kinds present in `changes` that `compute-changes`
  cannot translate yet."
  [changes]
  (into #{} (comp (map :kind) (remove mergeable-kinds)) changes))

(defn- flat-changes
  "Emit add/mod/del change maps for one flat id->value collection.

  Clean (non-conflicting) changes are always emitted. Conflicting
  entities are emitted only when `resolutions` selects `:branch` for that
  id (taking the branch side); `:main` (or absent) leaves main untouched.
  The caller is responsible for refusing the merge while conflicts remain
  unresolved."
  [base theirs ours resolutions add-fn mod-fn del-fn]
  (reduce
   (fn [acc id]
     (let [b (get base id) t (get theirs id) o (get ours id)
           in-b? (contains? base id)
           in-t? (contains? theirs id)
           in-o? (contains? ours id)
           res   (get resolutions id)]
       (cond
         ;; present in all three
         (and in-b? in-o? in-t?)
         (cond (= o b) acc                          ; branch didn't touch
               (= t b) (conj acc (mod-fn id o))     ; main didn't touch, branch did
               (= o t) acc                          ; both reached same value
               (= res :branch) (conj acc (mod-fn id o)) ; modify/modify -> branch
               (map? res) (let [eff (merge-attrs t o res)] ; modify/modify -> per-attr
                            (if (= eff t) acc (conj acc (mod-fn id eff))))
               :else acc)

         ;; deleted in branch, still in main
         (and in-b? (not in-o?) in-t?)
         (cond (= b t) (conj acc (del-fn id))       ; clean delete
               (= res :branch) (conj acc (del-fn id)) ; delete/modify -> branch (delete)
               :else acc)

         ;; deleted in main, still in branch
         (and in-b? in-o? (not in-t?))
         (cond (= b o) acc                          ; clean (already gone in main)
               (= res :branch) (conj acc (add-fn id o)) ; modify/delete -> keep branch
               :else acc)

         ;; new in branch only
         (and (not in-b?) in-o? (not in-t?))
         (conj acc (add-fn id o))

         ;; added on both sides with different values (add/add)
         (and (not in-b?) in-o? in-t? (not= o t))
         (cond (= res :branch) (conj acc (mod-fn id o))
               (map? res) (let [eff (merge-attrs t o res)]
                            (if (= eff t) acc (conj acc (mod-fn id eff))))
               :else acc)

         :else acc)))
   []
   (set/union (set (keys base)) (set (keys theirs)) (set (keys ours)))))

(def ^:private structural-set-attrs
  "Attrs NOT expressible as a plain `:set` op: containment/order are applied
  via `:mov-objects` and add/del object changes, so setting their raw values
  would corrupt the tree (leave shapes loose / duplicated)."
  #{:shapes :parent-id :frame-id})

(def ^:private shape-geometry-attrs
  "Attrs whose change invalidates the geometry caches (`:selrect`/`:points`),
  which must then follow the winning side (see `shape-resolved-ops`)."
  #{:x :y :width :height :rotation :transform :transform-inverse :flip-x :flip-y})

(defn- shape-set-ops
  "`:set` operations for the attrs that differ between the main and branch
  shape (FULL maps, so geometry caches ride along), excluding the
  structural ones (see `structural-set-attrs`)."
  [t o]
  (->> (shallow-attr-diff t o)
       (into [] (comp (remove (fn [[k _]] (contains? structural-set-attrs k)))
                      (map (fn [[k {:keys [branch]}]] {:type :set :attr k :val branch}))))))

(defn- shape-resolved-ops
  "`:set` ops for a per-attr conflict resolution: the classification attrs
  (stripped maps) the user resolved to `:branch`, valued from the FULL
  branch shape; when a geometry attr is taken, the geometry caches
  (`:selrect`/`:points`) are appended from the branch shape so position and
  caches stay consistent."
  [t-stripped o-stripped t-full o-full res]
  (let [chosen (into []
                     (keep (fn [[k _]]
                             (when (and (= :branch (attr-side res k))
                                        (not (contains? structural-set-attrs k)))
                               k)))
                     (shallow-attr-diff t-stripped o-stripped))
        ops    (mapv (fn [k] {:type :set :attr k :val (get o-full k)}) chosen)]
    (if (some shape-geometry-attrs chosen)
      (into ops
            (keep (fn [k]
                    (when (not= (get t-full k) (get o-full k))
                      {:type :set :attr k :val (get o-full k)})))
            [:selrect :points])
      ops)))

(defn- page-move-changes
  "Emit `:mov-objects` for EXISTING shapes the branch reparented to a
  different container, when the branch wins (main left the shape untouched,
  or the conflict was resolved to `:branch`). Reparenting cannot be applied
  as a `:set :parent-id` op — that only rewrites the attribute and leaves
  the shape loose in its old parent, duplicated by file repair.
  `sbo`/`sto` are the STRIPPED base/main objects (see `strip-shapes`), used
  for the \"main untouched\" check so derived-attr churn on main does not
  block a branch reparent."
  [to oo sbo sto resolutions page-id]
  (->> (keys oo)
       (keep (fn [id]
               (let [t (get to id)
                     o (get oo id)]
                 (when (and (some? t)                          ; exists on both sides
                            (not= id uuid/zero)
                            (or (= (get sbo id) (get sto id))   ; main untouched -> branch wins
                                (= :branch (attr-side (get resolutions id) :parent-id))))
                   (let [new-parent (:parent-id o)]
                     (when (not= new-parent (:parent-id t))     ; reparented
                       {:id id
                        :parent-id new-parent
                        :index (index-of (get-in oo [new-parent :shapes]) id)
                        :depth (shape-depth oo id)}))))))
       (sort-by :depth)
       (mapv (fn [{:keys [id parent-id index]}]
               {:type :mov-objects
                :page-id page-id
                :parent-id parent-id
                :index index
                :shapes [id]
                :ignore-touched true}))))

(defn- reorder-ops
  "`:mov-objects` operations that make the parent's children follow
  `order-o` (the source's order) among the shapes the target keeps. The
  target's other children keep their slots; each shape out of place is
  moved, one at a time, to the slot its relative order demands."
  [to order-o page-id id]
  (let [keep   (set order-o)
        cur0   (vec (get-in to [id :shapes]))
        slots  (into [] (keep-indexed (fn [i x] (when (contains? keep x) i))) cur0)
        ;; the target's children with the shared ones in the source's
        ;; order: the slots the shared shapes sit in, filled left to right
        wanted (loop [src slots [x & xs] order-o out (vec cur0)]
                 (if (or (nil? x) (empty? src))
                   out
                   (recur (rest src) xs (assoc out (first src) x))))]
    (loop [i 0 cur cur0 out []]
      (if (>= i (count wanted))
        out
        (let [x (nth wanted i)
              j (index-of cur x)]
          (if (or (nil? j) (= i j))
            (recur (inc i) cur out)
            (recur (inc i)
                   (d/insert-at-index cur i [x])
                   (conj out {:type :mov-objects :page-id page-id
                              :parent-id id :index i :shapes [x]
                              :ignore-touched true}))))))))

(defn- page-reorder-changes
  "`:mov-objects` operations for the same-parent reorders (`reorders`)
  the source wins: its order is the entry's `:ours`, or it resolved the
  parent to `:branch`/`{:shapes :branch}`. Applied before everything
  else, on the tree as the target has it."
  [to _oo sbo sto reorders resolutions page-id]
  (into []
        (mapcat (fn [[id {:keys [ours]}]]
                  (let [res (get resolutions id)]
                    (when (and (contains? to id)
                               (or (= (get sbo id) (get sto id))
                                   (= :branch (attr-side res :shapes))))
                      (reorder-ops to ours page-id id)))))
        reorders))

(defn- placement
  "Where the merge creates the added shape `id`: its parent, its frame
  and the index it takes among that parent's children. `lost-mode` is
  the `:lost` alternative (`tree-modes`).
  An orphan — a shape whose parent the result lacks, its `lost-root`
  deleted — is created at the slot its lost root has under the nearest
  ancestor the result keeps (`:reparent-to-ancestor`), at that slot but
  on the page root (`:page-root`, `:refuse`), or not at all (`:cascade`,
  which never calls this). Children of a placed shape keep their place
  under it."
  [oo to all-adds lost-mode root-id id]
  (let [o       (get oo id)
        host    (:parent-id o)
        anchor  (loop [cur host n (count oo)]
                  (cond
                    (root-id? cur)                          root-id
                    (or (contains? to cur) (contains? all-adds cur)) cur
                    (neg? n)                                root-id
                    :else                                   (recur (:parent-id (get oo cur)) (dec n))))
        orphan? (not= anchor host)
        chain   (when orphan?
                  ;; the direct child of `anchor` on `id`'s ancestry
                  (loop [cur host n (count oo)]
                    (if (or (root-id? cur) (= anchor (:parent-id (get oo cur))))
                      cur
                      (recur (:parent-id (get oo cur)) (dec n)))))
        to-root? (and orphan? (contains? #{:page-root :refuse} lost-mode))
        parent   (if to-root? root-id anchor)
        index    (if orphan?
                   (if to-root?
                     (index-of (get-in oo [host :shapes]) id)
                     (+ (or (index-of (get-in oo [anchor :shapes]) chain) 0)
                        (or (index-of (filterv #(contains? all-adds %)
                                               (get-in oo [chain :shapes]))
                                      id)
                            0)))
                   (index-of (get-in oo [host :shapes]) id))]
    {:parent parent
     :frame  (cond
               (root-id? parent)      uuid/zero
               (= :frame (:type (get oo parent))) parent
               :else                  (or (:frame-id (get oo parent)) uuid/zero))
     :index  index
     :depth  (if orphan?
               (inc (shape-depth oo anchor))
               (shape-depth oo id))}))

(defn- expand-relocations
  "Where the target's shapes move when the merge deletes a container out
  from under them (`:dropped` `:expand`/`:page-root`): out of each
  container the source deleted, to its slot in the nearest ancestor the
  target keeps (`:expand`) or to the page root (`:page-root`). Shapes
  whose target parent another relocated shape is travel with it.
  Returns {container {:parent p :index i :depth d :shapes [...]}}."
  [to deleted root-id mode]
  (let [anchor   (fn [f]
                   (loop [cur (:parent-id (get to f)) n (count to)]
                     (cond
                       (root-id? cur)             root-id
                       (not (contains? deleted cur)) cur
                       (neg? n)                   root-id
                       :else                      (recur (:parent-id (get to cur)) (dec n)))))
        lift?    (fn [id]
                   (and (contains? to id)
                        (not (contains? deleted id))
                        (contains? deleted (:parent-id (get to id)))))
        lift-all? (fn [id] (and (contains? to id) (not (contains? deleted id))))
        groups   (into {}
                       (keep (fn [f]
                               (let [ids (into [] (filter lift-all?) (rest (preorder-ids to f)))]
                                 ;; only shapes whose own parent is gone need
                                 ;; lifting; the others travel with them
                                 (when-let [ids (seq (filter lift? ids))]
                                   [f (vec ids)]))))
                       (source-deleted-roots to deleted))]
    (persistent!
     (reduce-kv (fn [acc f ids]
                  (let [p (if (= :page-root mode) root-id (anchor f))
                        i (index-of (get-in to [(:parent-id (get to f)) :shapes]) f)]
                    (assoc! acc f {:parent p
                                   :index  i
                                   :depth  (if (= p root-id) 0 (inc (shape-depth to p)))
                                   :shapes ids})))
                (transient {})
                groups))))

(defn- move-changes
  "`:mov-objects` for the shapes `moves` relocates. Groups landing in the
  same parent at the same slot (an expand out of nested containers) are
  merged, so the group keeps one contiguous slot."
  [moves page-id]
  (->> moves
       (sort-by (fn [[_ {:keys [depth]}]] depth))
       (reduce (fn [out [_ {:keys [parent index shapes]}]]
                 (let [k [parent index]
                       g [k {:parent parent :index index :shapes (vec shapes)}]]
                   (if-let [prev (peek out)]
                     (if (= k (key prev))
                       (conj (pop out) [k (update (val prev) :shapes into shapes)])
                       (conj out g))
                     (conj out g))))
               [])
       (map (fn [[_ {:keys [parent index shapes]}]]
              {:type :mov-objects :page-id page-id :parent-id parent
               :index index :shapes (vec shapes) :ignore-touched true}))
       (vec)))

(defn- page-shape-changes
  "Changes for one page's shapes under `dir`, with the tree-shape
  policies (`tree-modes`) applied on top of `flat-changes`:
  `:same-parent-reorder` reorders emit `:mov-objects`; `:lost` places or
  restores the source's shapes under a container the target deleted; and
  `:dropped` lifts the target's shapes out of a container the source
  deleted before it goes. The order is reorders, relocations,
  add/mod/del, moves: a relocation may depend on a new container and a
  move on a new parent.
  Returns `{:changes [...] :unsupported #{entries..}}`: the `:refuse`
  alternatives report `:shape-orphan` entries naming the container
  instead of a placement the merge cannot honour."
  [base theirs ours resolutions page-id dir]
  (let [bo (get-in base [:pages-index page-id :objects] {})
        to (get-in theirs [:pages-index page-id :objects] {})
        oo (get-in ours [:pages-index page-id :objects] {})
        root-id uuid/zero
        modes   (tree-modes dir)
        {lost-mode :lost dropped-mode :dropped order-mode :order} modes
        snil=   (fn [a b] (= (strip-nil-attrs a) (strip-nil-attrs b)))
        {:keys [sbo sto soo reorders]} (classification-objects bo to oo)

        ;; modifications + deletions (additions handled below, ordered)
        mod-del
        (->> (flat-changes sbo sto soo resolutions
                           (fn [_ _] nil)
                           (fn [id _]
                             (let [res (get resolutions id)
                                   ops (if (and (map? res)
                                                (not= (get sto id) (get sbo id))) ; genuine conflict -> per-attr
                                         (shape-resolved-ops (get sto id) (get soo id)
                                                             (get to id) (get oo id) res)
                                         (shape-set-ops (get to id) (get oo id)))]
                               (when (seq ops)
                                 {:type :mod-obj :page-id page-id :id id :operations ops})))
                           (fn [id]
                             {:type :del-obj :page-id page-id :id id :ignore-touched true}))
             (filterv some?))

        ;; the ids the merge deletes. `:dropped :conflict` resolved to
        ;; `:main` keeps the container's whole subtree as the target has
        ;; it, so no delete inside it is emitted
        deleted (into #{} (keep (fn [{:keys [type id]}]
                                  (when (= :del-obj type) id)))
                      mod-del)
        dropped-roots (when (contains? #{:conflict :expand :page-root :refuse} dropped-mode)
                        (source-deleted-roots to deleted))
        kept    (when (= :conflict dropped-mode)
                  (into #{} (mapcat #(preorder-ids to %))
                        (filter #(= :main (get resolutions %)) dropped-roots)))
        mod-del (if (seq kept)
                  (filterv (fn [{:keys [type id]}]
                             (not (and (= :del-obj type) (contains? kept id))))
                           mod-del)
                  mod-del)

        ;; `:lost`: the source's shapes under a container the target
        ;; deleted — its additions and its changes to deleted shapes
        lost-pairs (into []
                         (keep (fn [id]
                                 (when (and (contains? oo id)
                                            (or (not (contains? bo id))
                                                (and (not (contains? to id))
                                                     (not (snil= (get sbo id) (get soo id))))))
                                   (when-let [r (lost-root bo to oo id)]
                                     [id r]))))
                         (keys oo))
        lost    (into {} lost-pairs)

        ;; same-parent reorders, on the tree as the target has it
        reorder-changes (when (= :merge order-mode)
                          (page-reorder-changes to oo sbo sto reorders resolutions page-id))

        ;; `:dropped` `:expand`/`:page-root`: the target's shapes the
        ;; deleted container would take with it move out before the delete
        expand-moves  (if (contains? #{:expand :page-root} dropped-mode)
                        (expand-relocations to deleted root-id dropped-mode)
                        {})
        expand-changes (move-changes expand-moves page-id)

        ;; additions: present in the source, absent from the base and the
        ;; target, plus the shapes a modify-delete conflict resolved to
        ;; `:branch` restores (the target's delete was recursive: the whole
        ;; surviving subtree comes back, except the descendants the target
        ;; still has — it moved them out before deleting)
        additions (into #{}
                        (filter (fn [id]
                                  (and (contains? oo id)
                                       (not (contains? bo id))
                                       (not (contains? to id)))))
                        (keys oo))
        added-set (if (contains? #{:conflict :cascade} lost-mode)
                    ;; under `:lost :conflict` the region conflict on the
                    ;; lost root decides an addition's fate; under
                    ;; `:cascade` the container goes with its whole subtree
                    (into #{} (remove lost) additions)
                    additions)
        ;; a modify-delete resolved to `:branch` re-adds its whole
        ;; surviving subtree (the target's delete was recursive), except
        ;; the descendants the target still has — it moved them out before
        ;; deleting. Under `:lost :conflict` the region conflict on the
        ;; lost root decides instead: resolved to `:branch`, the root's
        ;; whole subtree comes back.
        restore-set
        (into #{}
              (remove #(contains? to %))
              (into #{}
                    (mapcat #(subtree-ids oo %))
                    (cond
                      (= :conflict lost-mode)
                      (into #{} (comp (filter (fn [[_ r]] (= :branch (get resolutions r))))
                                      (map second))
                            lost-pairs)

                      (= :cascade lost-mode)
                      []

                      :else
                      (keep (fn [id]
                              (when (and (contains? bo id)
                                         (not (contains? to id))
                                         (not (snil= (get sbo id) (get soo id)))
                                         (= :branch (get resolutions id)))
                                id))
                            (keys oo)))))
        all-adds (set/union added-set restore-set)

        ;; where each shape is created: its parent, its frame and the index
        ;; it takes there (see `placement` for orphans)
        placements (into {} (map (fn [id] [id (placement oo to all-adds lost-mode root-id id)])) all-adds)

        ;; topological order so a newly-added parent is created before its
        ;; newly-added children; within one parent, the source's sibling
        ;; order (`:index`), so added siblings keep it
        ordered
        (loop [pending (vec all-adds) done #{} out []]
          (if (empty? pending)
            out
            (let [ready (filterv (fn [id]
                                   (let [p (:parent (get placements id))]
                                     (or (root-id? p) (contains? to p) (contains? done p))))
                                 pending)
                  ready (if (seq ready) ready (subvec pending 0 1))]
              (recur (filterv (complement (set ready)) pending)
                     (into done ready)
                     (into out (sort-by (juxt :depth :index)
                                        (map (fn [id] (assoc (get placements id) :id id))
                                             ready)))))))

        add-changes
        (mapv (fn [{:keys [id parent frame index]}]
                (let [o (get oo id)]
                  {:type :add-obj
                   :page-id page-id
                   :id id
                   ;; container shapes (frame/group/…) require `:shapes`; reset
                   ;; it to empty so the schema is valid and children get
                   ;; appended by their own add-obj (add-shape inserts at index)
                   :obj (cond-> o (contains? o :shapes) (assoc :shapes []))
                   :parent-id parent
                   :frame-id frame
                   :index index
                   :ignore-touched true}))
              ordered)

        ;; reparenting of EXISTING shapes — applied last, after new
        ;; containers exist and attr/add changes settled
        move-changes (page-move-changes to oo sbo sto resolutions page-id)

        ;; `:refuse`: the merge cannot place these shapes where they
        ;; belong, so it refuses instead, naming the container
        refused
        (concat
         (when (= :refuse lost-mode)
           (->> (set/union added-set restore-set)
                (keep (fn [id] (when-let [r (get lost id)] [r id])))
                (group-by first)
                (map (fn [[r pairs]]
                       {:kind :shape-orphan :status :unsupported :id r
                        :page-id page-id
                        :label (entity-label :shape (get bo r))
                        :policy :addition-under-deleted-parent
                        :shapes (mapv second pairs)}))))
         (when (= :refuse dropped-mode)
           (map (fn [f]
                  {:kind :shape-orphan :status :unsupported :id f
                   :page-id page-id
                   :label (entity-label :shape (get to f))
                   :policy :addition-under-deleted-parent
                   :shapes (into [] (remove #(contains? bo %)) (rest (preorder-ids to f)))})
                dropped-roots)))]
    {:changes (into (vec (concat reorder-changes expand-changes mod-del))
                    (concat add-changes move-changes))
     :unsupported (into #{} refused)}))

(defn compute-changes
  "Translate the branch→main merge into a vector of raw change maps
  applicable to main via `app.common.files.changes/process-changes`.

  Clean changes are always included. Conflicting entities are included
  only for those `resolutions` selects `:branch` (the caller must ensure
  no conflict remains unresolved before applying).

  Returns `{:changes [..] :unsupported #{kinds..}}`. When `:unsupported`
  is non-empty the caller must refuse the merge: `:page-attrs` (the
  residual page attrs no pass handles) is untranslatable, and the shape
  pass reports its `:refuse` policy refusals there too.

  The 5-arity accepts the `compute-merge` summary the caller usually
  already computed (for the conflict gate), so the full three-way diff is
  not run a second time just to derive `:unsupported`."
  ([base main branch]
   (compute-changes base main branch {}))
  ([base main branch resolutions]
   (compute-changes base main branch resolutions nil))
  ([base main branch resolutions merge-summary]
   (let [merge-summary (or merge-summary (compute-merge base main branch :branch->main))
         unsupported   (unsupported-kinds (concat (:changes merge-summary) (:conflicts merge-summary)))

         ;; colors/typographies classified without :modified-at (the apply
         ;; regenerates it via `touch`, so the emitted values may omit it)
         colors (flat-changes (strip-modified-at (:colors base))
                              (strip-modified-at (:colors main))
                              (strip-modified-at (:colors branch))
                              resolutions
                              (fn [_ o] {:type :add-color :color o})
                              (fn [_ o] {:type :mod-color :color o})
                              (fn [id] {:type :del-color :id id}))

         typos  (flat-changes (strip-modified-at (:typographies base))
                              (strip-modified-at (:typographies main))
                              (strip-modified-at (:typographies branch))
                              resolutions
                              (fn [_ o] {:type :add-typography :typography o})
                              (fn [_ o] {:type :mod-typography :typography o})
                              (fn [id] {:type :del-typography :id id}))

         media  (flat-changes (:media base) (:media main) (:media branch) resolutions
                              (fn [_ o] {:type :add-media :object o})
                              (fn [_ o] {:type :mod-media :object o})
                              (fn [id] {:type :del-media :id id}))

         ;; pages: add (full page incl. objects) / delete + rename (mod-page)
         bpi (:pages-index base) mpi (:pages-index main) opi (:pages-index branch)
         bpids (set (keys bpi)) mpids (set (keys mpi)) opids (set (keys opi))
         common-pages (set/intersection mpids opids)
         tri-common-pages (set/intersection bpids mpids opids)

         ;; presence over page CONTENT (mirrors `diff-pages`): a clean delete
         ;; requires main untouched; a delete conflict resolved to `:branch`
         ;; either deletes (branch deleted) or RESTORES the full branch page
         ;; via the add-fn (main deleted, branch edited)
         page-presence (->> (flat-changes (page-content-map bpi bpids)
                                          (page-content-map mpi mpids)
                                          (page-content-map opi opids)
                                          resolutions
                                          (fn [pid _] {:type :add-page :page (get opi pid)})
                                          (fn [_ _] nil)
                                          (fn [pid] {:type :del-page :id pid}))
                            (filterv some?))
         pmeta (fn [pi] (into {} (map (fn [id] [id (page-meta (get pi id))])) tri-common-pages))
         bpm (pmeta bpi) tpm (pmeta mpi) opm (pmeta opi)
         page-meta-changes (->> (flat-changes bpm tpm opm resolutions
                                              (fn [_ _] nil)
                                              (fn [pid m] (-> (page-meta-clears (get tpm pid) m)
                                                              (assoc :type :mod-page :id pid)))
                                              (fn [_] nil))
                                (filterv some?))

         ;; page guides / flows per common page
         page-guides (into []
                           (mapcat (fn [pid]
                                     (flat-changes (get-in base [:pages-index pid :guides] {})
                                                   (get-in main [:pages-index pid :guides] {})
                                                   (get-in branch [:pages-index pid :guides] {})
                                                   resolutions
                                                   (fn [gid g] {:type :set-guide :page-id pid :id gid :params g})
                                                   (fn [gid g] {:type :set-guide :page-id pid :id gid :params g})
                                                   (fn [gid] {:type :set-guide :page-id pid :id gid :params nil}))))
                           common-pages)
         page-flows (into []
                          (mapcat (fn [pid]
                                    (flat-changes (get-in base [:pages-index pid :flows] {})
                                                  (get-in main [:pages-index pid :flows] {})
                                                  (get-in branch [:pages-index pid :flows] {})
                                                  resolutions
                                                  (fn [fid f] {:type :set-flow :page-id pid :id fid :params f})
                                                  (fn [fid f] {:type :set-flow :page-id pid :id fid :params f})
                                                  (fn [fid] {:type :set-flow :page-id pid :id fid :params nil}))))
                          common-pages)

         ;; page default-grids per common page
         page-grids (into []
                          (mapcat (fn [pid]
                                    (flat-changes (get-in base [:pages-index pid :default-grids] {})
                                                  (get-in main [:pages-index pid :default-grids] {})
                                                  (get-in branch [:pages-index pid :default-grids] {})
                                                  resolutions
                                                  (fn [gt p] {:type :set-default-grid :page-id pid :grid-type gt :params p})
                                                  (fn [gt p] {:type :set-default-grid :page-id pid :grid-type gt :params p})
                                                  (fn [gt] {:type :set-default-grid :page-id pid :grid-type gt :params nil}))))
                          common-pages)

         ;; page-level plugin-data per common page (flattened to [ns key] -> value)
         page-plugins (into []
                            (mapcat (fn [pid]
                                      (flat-changes (flatten-plugin-data (get-in base [:pages-index pid :plugin-data] {}))
                                                    (flatten-plugin-data (get-in main [:pages-index pid :plugin-data] {}))
                                                    (flatten-plugin-data (get-in branch [:pages-index pid :plugin-data] {}))
                                                    resolutions
                                                    (fn [[ns k] v] {:type :set-plugin-data :object-type :page :object-id pid :namespace ns :key k :value v})
                                                    (fn [[ns k] v] {:type :set-plugin-data :object-type :page :object-id pid :namespace ns :key k :value v})
                                                    (fn [[ns k]] {:type :set-plugin-data :object-type :page :object-id pid :namespace ns :key k :value nil}))))
                            common-pages)

         ;; page order: reorder the common pages to branch's order via
         ;; mov-page. Every :index counts the FULL :pages vector as it is
         ;; when the op runs: the presence changes above are already in it
         ;; and the moves before it have landed, so a page main added keeps
         ;; its slot while the common pages reorder around it.
         page-order-changes
         (let [order-of (fn [data] (filterv tri-common-pages (or (:pages data) [])))
               bo (order-of base) mo (order-of main) oo (order-of branch)]
           (if (and (not= oo bo)
                    (or (= mo bo) (= (get resolutions :page-order) :branch)))
             (let [run-pages (reduce (fn [pages change]
                                       (case (:type change)
                                         :add-page (:pages (ctpl/add-page {:pages pages} (:page change)))
                                         :del-page (:pages (ctpl/delete-page {:pages pages} (:id change)))
                                         pages))
                                     (vec (or (:pages main) []))
                                     page-presence)
                   ;; target: the common pages in branch's order, every
                   ;; other page where it already is
                   target (first (reduce (fn [[out order] pid]
                                           (if (and (contains? tri-common-pages pid) (seq order))
                                             [(conj out (first order)) (rest order)]
                                             [(conj out pid) order]))
                                         [[] oo]
                                         run-pages))]
               ;; land the pages left to right: slot i is already the right
               ;; index against the vector the op runs on (the prefix before
               ;; i is settled and the page comes from below it)
               (loop [cur run-pages i 0 out []]
                 (cond
                   (>= i (count target))
                   out

                   (= (nth cur i) (nth target i))
                   (recur cur (inc i) out)

                   :else
                   (let [pid (nth target i)]
                     (recur (d/insert-at-index cur i [pid])
                            (inc i)
                            (conj out {:type :mov-page :id pid :index i}))))))
             []))

         ;; shapes: the tree-shape policies act per page (see
         ;; `page-shape-changes`); its `:unsupported` entries name the
         ;; container a `:refuse` alternative could not place under
         dir        (or (:dir merge-summary) :branch->main)
         shapes-res (mapv (fn [pid] (page-shape-changes base main branch resolutions pid dir))
                          common-pages)
         shapes     (into [] (mapcat :changes) shapes-res)

         ;; components: row metadata (shapes handled by the shape/page passes).
         ;; A branch soft-delete keeps the row with `:deleted true`; surface it
         ;; as a proper del-component so the deletion propagates.
         components (flat-changes (strip-modified-at (:components base))
                                  (strip-modified-at (:components main))
                                  (strip-modified-at (:components branch)) resolutions
                                  (fn [_ c] (assoc c :type :add-component))
                                  (fn [id c] (if (:deleted c)
                                               {:type :del-component :id id}
                                               (assoc c :type :mod-component)))
                                  (fn [id] {:type :del-component :id id}))

         bl (:tokens-lib base) ml (:tokens-lib main) ol (:tokens-lib branch)
         set-ids (set/union (lib-set-ids bl) (lib-set-ids ml) (lib-set-ids ol))

         ;; set add/delete, presence over set CONTENT (mirrors `diff-tokens`):
         ;; a new set carries metadata only (tokens come from the per-token
         ;; pass); a RESTORED set (delete conflict resolved to `:branch`)
         ;; carries its tokens, since the per-token pass skips deleted sets
         set-presence (->> (flat-changes (set-content-map bl)
                                         (set-content-map ml)
                                         (set-content-map ol)
                                         resolutions
                                         (fn [sid _] {:type :set-token-set :id sid :attrs (set-restore-attrs ol bl sid)})
                                         (fn [_ _] nil)
                                         (fn [sid] {:type :set-token-set :id sid :attrs nil}))
                           (filterv some?))

         ;; set rename: take branch name/description, keep main's tokens
         ;; (the per-token pass then layers branch's token edits). Emitted
         ;; before token-vals.
         common-sets (set/intersection (lib-set-ids bl) (lib-set-ids ml) (lib-set-ids ol))
         set-rename-changes
         (into []
               (comp (filter (fn [sid] (set-rename-wins? bl ml ol resolutions sid)))
                     (map (fn [sid] {:type :set-token-set :id sid :attrs (set-rename-attrs ml ol sid)})))
               common-sets)

         ;; active theme paths
         active-changes
         (let [bp (lib-active-paths bl) mp (lib-active-paths ml) op (lib-active-paths ol)]
           (cond
             (= op bp) []
             (= mp bp) [{:type :set-active-token-themes :theme-paths op}]
             (= op mp) []
             (= (get resolutions :active-themes) :branch) [{:type :set-active-token-themes :theme-paths op}]
             :else []))

         ;; active sets (hidden theme): bring branch's hidden theme
         active-sets-changes
         (let [bs (lib-hidden-sets bl) ms (lib-hidden-sets ml) os (lib-hidden-sets ol)
               take! (fn [] (if-let [h (hidden-theme-map ol)]
                              [{:type :set-token-theme :id ctob/hidden-theme-id :attrs h}]
                              []))]
           (cond
             (= os bs) []
             (= ms bs) (take!)
             (= os ms) []
             (= (get resolutions :active-sets) :branch) (take!)
             :else []))

         ;; set order: reorder main's common sets to branch's order using
         ;; "move nᵢ before nᵢ₊₁" right-to-left. Emitted after renames so set
         ;; names are settled.
         set-order-changes
         (let [bo (set-order-by-id bl common-sets)
               mo (set-order-by-id ml common-sets)
               oo (set-order-by-id ol common-sets)]
           (if (and (not= oo bo)
                    (or (= mo bo) (= (get resolutions :token-set-order) :branch)))
             ;; the moves address the sets by name as the renames above
             ;; leave them: branch's name where the rename wins, main's
             ;; where main's rename stands
             (let [names (mapv (fn [sid]
                                 (ctob/get-name
                                  (ctob/get-set (if (set-rename-wins? bl ml ol resolutions sid) ol ml) sid)))
                               oo)]
               (vec (for [i (range (- (count names) 2) -1 -1)]
                      {:type :move-token-set
                       :from-path (set-name->path (nth names i))
                       :to-path (set-name->path (nth names i))
                       :before-path (set-name->path (nth names (inc i)))
                       :before-group false})))
             []))
         ;; sets deleted on either side (relative to the base) are decided
         ;; wholesale at set level (delete, or restore-with-tokens); skip
         ;; their per-token pass (mirrors `diff-tokens`)
         skip-set-ids (into #{} (filter (fn [sid]
                                          (and (contains? (lib-set-ids bl) sid)
                                               (or (not (contains? (lib-set-ids ml) sid))
                                                   (not (contains? (lib-set-ids ol) sid))))))
                            set-ids)

         token-vals (into []
                          (comp (remove skip-set-ids)
                                (mapcat (fn [sid]
                                          (flat-changes (lib-tokens-by-id bl sid)
                                                        (lib-tokens-by-id ml sid)
                                                        (lib-tokens-by-id ol sid)
                                                        resolutions
                                                        (fn [tid t] {:type :set-token :set-id sid :token-id tid :attrs t})
                                                        (fn [tid t] {:type :set-token :set-id sid :token-id tid :attrs t})
                                                        (fn [tid] {:type :set-token :set-id sid :token-id tid :attrs nil})))))
                          set-ids)

         themes (flat-changes (lib-themes bl) (lib-themes ml) (lib-themes ol) resolutions
                              (fn [tid t] {:type :set-token-theme :id tid :attrs t})
                              (fn [tid t] {:type :set-token-theme :id tid :attrs t})
                              (fn [tid] {:type :set-token-theme :id tid :attrs nil}))]

     {:unsupported (into unsupported (map :kind) (mapcat :unsupported shapes-res))
      ;; components before shapes so del-component can store the main-instance
      ;; objects (still on the page) before del-obj removes them
      :changes     (vec (concat page-presence components shapes page-meta-changes
                                page-guides page-flows page-grids page-plugins page-order-changes
                                colors typos media
                                set-presence set-rename-changes set-order-changes
                                token-vals themes
                                active-changes active-sets-changes))})))
