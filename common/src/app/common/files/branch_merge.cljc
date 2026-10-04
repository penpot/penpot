;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.files.branch-merge
  "Three-way, entity-level merge/diff engine for file branching.

  Phase 2 scope: read-only diff. `compute-merge` takes the file `:data`
  of the merge base, main and branch and returns a serializable summary
  of what the branch changes relative to main, plus the set of
  conflicting entities. It never mutates the blob; translating the
  result into `changes` (for an actual merge) and token diffing arrive
  in later phases.

  Direction `:branch->main` (merge/compare) treats main as `theirs` and
  branch as `ours`; `:main->branch` (update from main) swaps them."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.string :as str]))

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
  For `res = :branch` this equals `o`; for `:main`/nil it equals `t`."
  [t o res]
  (reduce (fn [acc [k {:keys [branch]}]]
            (if (= :branch (attr-side res k))
              (assoc acc k branch)
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

  Returns `{:changes [..] :conflicts [..]}` where changes are the
  branch's net additions/modifications/deletions that apply cleanly to
  main, and conflicts are entities both sides diverged on.

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
       (let [b (get base id)
             t (get theirs id)
             o (get ours id)
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

(defn- diff-pages
  [base theirs ours]
  (let [bpi (or (:pages-index base) {}) tpi (or (:pages-index theirs) {}) opi (or (:pages-index ours) {})
        bids (set (keys bpi)) tids (set (keys tpi)) oids (set (keys opi))
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
        ;; objects (shapes) on common pages (mergeable: :shape). The page
        ;; root frame (uuid/zero) is skipped and the shapes are diffed
        ;; STRIPPED of derived attrs (`shape-ignored-attrs`) so that pure
        ;; `:shapes`/`:touched`/geometry-cache churn neither shows up as a
        ;; change nor manufactures false conflicts (their merge is driven
        ;; by add/del/move ops, not by these values).
        obj-diffs (map (fn [pid]
                         (let [bo  (get-in base [:pages-index pid :objects] {})
                               to  (get-in theirs [:pages-index pid :objects] {})
                               oo  (get-in ours [:pages-index pid :objects] {})
                               res (three-way-entities (strip-shapes bo) (strip-shapes to) (strip-shapes oo)
                                                       {:kind :shape :page-id pid
                                                        :ignore-ids #{uuid/zero}})
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
                                                  :branch (get oo (:id e))))]
                           {:changes   (mapv enrich (:changes res))
                            :conflicts (mapv (comp rehydrate enrich) (:conflicts res))}))
                       common)]
    (merge-results (concat [presence meta-diff extra-diff order]
                           guides-diffs flows-diffs grids-diffs plugins-diffs obj-diffs))))

;; --- Tokens ---
;;
;; Token *values* (tokens within an existing set) are mergeable (kind
;; :token -> :set-token). Structural token changes (adding/renaming sets,
;; themes, active-theme/active-set toggles) are surfaced with
;; non-mergeable kinds (:token-set, :token-theme, :token-active-themes)
;; so the merge refuses them rather than dropping them silently — a full
;; structural token merge is a later step.

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
        ;; set rename/description on common sets (NOT yet mergeable)
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
        ;; active-set toggles (hidden theme) — NOT yet mergeable
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
  ({old-id -> new-id}): the file refs relinked by `duplicate-file`
  (`:component-file`, `:fill-color-ref-file`, `:stroke-color-ref-file`,
  `:typography-ref-file`, shadow/grid `:file-id`) plus the media refs
  (shape `:metadata`/`:fill-image`/`:stroke-image` ids, the `:media`
  collection keys and library-color `:image` ids) — the exact same
  surface `binfile.common/process-file` remaps when the branch copy is
  created.

  A branch is a file copy whose LOCAL references were re-pointed to its
  own ids by the duplication pipeline. Before diffing or merging against
  another file those references must be normalized to the target's ids;
  otherwise (a) every affected entity looks modified, inflating the
  diff/conflicts, and (b) merged references cannot be resolved in the
  target file — repair would detach components and images would break.
  Ids not present in `id-map` (external libraries) are left untouched."
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

(defn compute-merge
  "Compute the three-way diff between the merge `base`, `main` and
  `branch` file `:data`. Returns:

    {:changes   [<change descriptor> ...]   ; clean, branch -> main
     :conflicts [<conflict descriptor> ...] ; need resolution
     :stats     {:added n :modified n :deleted n :conflicts n}}

  NOTE: token-lib diffing is not yet implemented (handled in a later
  phase); `:tokens-lib` changes are not reported here."
  [base main branch dir]
  (let [[theirs ours] (if (= dir :main->branch) [branch main] [main branch])
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
                  (diff-pages base theirs ours)
                  (diff-tokens base theirs ours)]
        {:keys [changes conflicts]} (merge-results results)]
    {:changes   changes
     :conflicts conflicts
     :stats     {:added     (count (filterv #(= :added (:status %)) changes))
                 :modified  (count (filterv #(= :modified (:status %)) changes))
                 :deleted   (count (filterv #(= :deleted (:status %)) changes))
                 :conflicts (count conflicts)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MERGE -> CHANGES (Phase 3, no-conflict path)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Kinds `compute-changes` can translate into change ops. Components,
;; pages (add/remove/rename) and tokens are not yet supported and cause
;; the merge to refuse rather than silently drop changes.
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

(defn- index-of
  [coll x]
  (first (keep-indexed (fn [i v] (when (= v x) i)) coll)))

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

(defn- page-shape-changes
  [base theirs ours resolutions page-id]
  (let [bo (get-in base [:pages-index page-id :objects] {})
        to (get-in theirs [:pages-index page-id :objects] {})
        oo (get-in ours [:pages-index page-id :objects] {})

        ;; classification runs on STRIPPED shapes (no derived attrs) so
        ;; `:shapes`/`:touched`/cache churn cannot fabricate changes or
        ;; conflicts; emission reads the FULL maps.
        sbo (strip-shapes bo)
        sto (strip-shapes to)
        soo (strip-shapes oo)

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

        ;; additions: present in branch, absent from base and main
        added-set (into #{} (filter (fn [id]
                                      (and (contains? oo id)
                                           (not (contains? bo id))
                                           (not (contains? to id)))))
                        (keys oo))

        ;; modify-delete conflicts resolved to `:branch`: the branch modified
        ;; a shape main deleted and the user chose to keep it — re-add its
        ;; whole surviving subtree (main's delete was recursive), except the
        ;; descendants main still has (it moved them out before deleting)
        restore-set
        (into #{}
              (comp (filter (fn [id]
                              (and (contains? bo id)
                                   (not (contains? to id))
                                   (not= (get sbo id) (get soo id))
                                   (= :branch (get resolutions id)))))
                    (mapcat #(subtree-ids oo %))
                    (remove #(contains? to %)))
              (keys oo))

        all-adds (set/union added-set restore-set)

        ;; topological order so a newly-added parent is created before its
        ;; newly-added children
        ordered
        (loop [pending (vec all-adds) done #{} out []]
          (if (empty? pending)
            out
            (let [ready (filterv (fn [id]
                                   (let [p (:parent-id (get oo id))]
                                     (or (not (contains? all-adds p))
                                         (contains? done p))))
                                 pending)
                  ready (if (seq ready) ready (subvec pending 0 1))]
              (recur (filterv (complement (set ready)) pending)
                     (into done ready)
                     (into out ready)))))

        add-changes
        (mapv (fn [id]
                (let [o      (get oo id)
                      parent (:parent-id o)
                      index  (index-of (get-in oo [parent :shapes]) id)]
                  {:type :add-obj
                   :page-id page-id
                   :id id
                   ;; container shapes (frame/group/…) require `:shapes`; reset
                   ;; it to empty so the schema is valid and children get
                   ;; appended by their own add-obj (add-shape inserts at index)
                   :obj (cond-> o (contains? o :shapes) (assoc :shapes []))
                   :parent-id parent
                   :frame-id (:frame-id o)
                   :index index
                   :ignore-touched true}))
              ordered)

        ;; reparenting of EXISTING shapes — applied last, after new
        ;; containers exist and attr/add changes settled
        move-changes (page-move-changes to oo sbo sto resolutions page-id)]
    (-> mod-del
        (into add-changes)
        (into move-changes))))

(defn compute-changes
  "Translate the branch→main merge into a vector of raw change maps
  applicable to main via `app.common.files.changes/process-changes`.

  Clean changes are always included. Conflicting entities are included
  only for those `resolutions` selects `:branch` (the caller must ensure
  no conflict remains unresolved before applying).

  Returns `{:changes [..] :unsupported #{kinds..}}`. When `:unsupported`
  is non-empty the caller must refuse the merge (translation for those
  kinds — components, pages, tokens — is not implemented yet).

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
         page-meta-changes (->> (flat-changes (pmeta bpi) (pmeta mpi) (pmeta opi) resolutions
                                              (fn [_ _] nil)
                                              (fn [pid m] (assoc m :type :mod-page :id pid))
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

         ;; page order: reorder common pages to branch's order via mov-page
         page-order-changes
         (let [order-of (fn [data] (filterv tri-common-pages (or (:pages data) [])))
               bo (order-of base) mo (order-of main) oo (order-of branch)]
           (if (and (not= oo bo)
                    (or (= mo bo) (= (get resolutions :page-order) :branch)))
             (vec (map-indexed (fn [i pid] {:type :mov-page :id pid :index i}) oo))
             []))

         shapes (into [] (mapcat #(page-shape-changes base main branch resolutions %)) common-pages)

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
               (comp (filter (fn [sid]
                               (let [b (set-meta-of bl sid)
                                     m (set-meta-of ml sid)
                                     o (set-meta-of ol sid)]
                                 (and (not= o b)
                                      (or (= m b) (= (get resolutions sid) :branch))))))
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
             (let [names (mapv #(ctob/get-name (ctob/get-set ol %)) oo)]
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

     {:unsupported unsupported
      ;; components before shapes so del-component can store the main-instance
      ;; objects (still on the page) before del-obj removes them
      :changes     (vec (concat page-presence components shapes page-meta-changes
                                page-guides page-flows page-grids page-plugins page-order-changes
                                colors typos media
                                set-presence set-rename-changes set-order-changes
                                token-vals themes
                                active-changes active-sets-changes))})))
