;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.composable-tests.comp.nodes
  "Component-specific operation nodes for the test model.

   These are the `IOperation` implementations whose subject is Penpot component
   behaviour: they wrap component/shape operations and drive the real production
   change pipeline. This namespace sits behind the `comp` boundary precisely
   because it is about components; the generic engine in
   `frontend-tests.composable-tests.core` has no domain terms. See
   `mem:frontend/composable-component-tests`.

   Nodes deliberately call the production change pipeline directly
   (`cls/generate-update-shapes` + `thf/apply-changes`,
   `cll/generate-sync-file-changes` + `thf/apply-changes`) in the same way the
   existing `app.common.test-helpers.compositions` helpers do.

   Each node, on apply, records a self-description into the situation's applied-log
   so conditions (and later undo) can read back what it did, keeping the
   operation the single source of truth for what should hold."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [frontend-tests.composable-tests.core :as tm]))

;; ---------------------------------------------------------------------------
;; Properties — an OPEN, extensible vocabulary of "what about a shape can change"
;;
;; A property is named by a keyword (`:fills`, `:opacity`, … extensible to
;; `:text`). Two pure functions know how to WRITE a property onto a shape (the
;; edit) and how to READ its comparable value back (the check). `change-property`
;; (the edit operation) uses `set-property`; `has-property-of` (the inspection)
;; uses `read-property` — so the change and its check stay duals, and adding a new
;; property kind is one clause in each `case`. (`change-attr`/`has-attr?` remain as
;; aliases for the common `:fills`/`:opacity` use.)
;; ---------------------------------------------------------------------------

(defn set-property
  "Write `property` = `value` onto `shape`, returning the updated shape. The edit
   half of a property; extend the `case` to support more properties. Public so the
   frontend interpreter applies the IDENTICAL edit the common node does."
  [shape property value]
  (case property
    :fills   (assoc shape :fills (ths/sample-fills-color :fill-color value))
    :opacity (assoc shape :opacity value)
    (throw (ex-info (str "set-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity)")
                    {:property property}))))

(defn- read-property
  "Read the comparable value of `property` from `shape`. The check half of a
   property; extend the `case` to support more properties."
  [shape property]
  (case property
    :fills   (-> shape :fills first :fill-color)
    :opacity (:opacity shape)
    (throw (ex-info (str "read-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity)")
                    {:property property}))))

(defrecord ChangeProperty [target property value]
  tm/IOperation
  (apply-to [this situation]
    ;; Goes through the production change path, exactly like tho/update-color,
    ;; then records under its own identity.
    (let [the-file (tm/file situation)
          ;; `target` may be a ROLE (resolved via the situation, so the edit
          ;; FOLLOWS the role as state-building ops like make-nested-component re-point it)
          ;; or a label; strict-presence throws if neither resolves.
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          changes  (cls/generate-update-shapes
                    (pcb/empty-changes nil (:id page))
                    #{shape-id}
                    (fn [shape] (set-property shape property value))
                    (:objects page)
                    {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:target target :property property :value value})))))

(defn change-property
  "Constructor for the property-change operation: change `property` of the shape
   named by `target` (a role or a label) to `value`. Stamps a node identity so the
   test can interrogate it (via `has-property-of`). The general form; `change-attr`
   is the same thing for the common `:fills`/`:opacity` properties."
  [target property value]
  (tm/assign-id (->ChangeProperty target property value)))

(def ^{:doc "Alias of `change-property` (the historical name for :fills/:opacity
  changes). `[target attr value]`."}
  change-attr change-property)

(defprotocol IPropertyCheck
  "Inspection capability of a property-changing operation: report on its OWN
   effect, applied to a shape the caller supplies. Makes no judgment about which
   shape — the test supplies that (e.g. `(role-shape s :copy-child-rect)`)."
  (applied-property [node] "The property this node changed.")
  (applied-value [node] "The value this node applied.")
  (has-property-of [node shape]
    "True if `shape` carries this node's changed property at this node's applied
     value. The node reports against its own change; the test chooses the shape."))

(extend-type ChangeProperty
  IPropertyCheck
  (applied-property [node] (:property node))
  (applied-value [node] (:value node))
  (has-property-of [node shape]
    (= (:value node) (read-property shape (:property node)))))

;; Aliases for the historical `has-attr?` name (used by the earlier cases).
(def ^{:doc "Alias of `has-property-of`."} has-attr? has-property-of)
(def ^{:doc "Alias of `applied-property`."} applied-attr applied-property)

;; ---------------------------------------------------------------------------
;; Geometry operations
;;
;; Unlike `change-property` (a raw attribute write), geometric changes must go
;; through the TRANSFORM pipeline: on the frontend, the interpreter dispatches
;; the real sidebar events (`dwt/increase-rotation`, `dwt/update-dimensions`),
;; whose apply-modifiers step also runs the placement-vs-override classification
;; for component copies (calculate-ignore-tree / check-delta) — which is part of
;; what these operations exist to exercise. The synchronous `apply-to` fallback
;; below performs the geometrically equivalent transform through the production
;; math, but cannot classify placement (that code is frontend-only), so cases
;; using these operations are meant to run through the interpreter.

(defrecord Rotate [target angle]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          shape    (get objects shape-id)
          ;; rotating a container rotates its whole subtree, as the real event does
          ids      (into #{shape-id} (cfh/get-children-ids objects shape-id))
          center   (gsh/shape->center shape)
          rotate1  (fn [s] (gsh/transform-shape s (ctm/rotation-modifiers s center angle)))
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               ids
                                               rotate1
                                               objects
                                               {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:target target :angle angle})))))

(defn rotate
  "Constructor for the rotation operation: rotate the shape named by `target`
   (a role, a label, or a `(situation -> id)` fn) — including its whole subtree —
   by `angle` degrees around its center. On the frontend this dispatches the real
   `dwt/increase-rotation` event."
  [target angle]
  (tm/assign-id (->Rotate target angle)))

(defrecord ChangeHeight [target value]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          shape    (get objects shape-id)
          resize1  (fn [s] (gsh/transform-shape
                            s
                            (ctm/resize-modifiers (gpt/point 1 (/ value (:height s)))
                                                  (gpt/point (:x s) (:y s)))))
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{(:id shape)}
                                               resize1
                                               objects
                                               {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:target target :value value})))))

(defn change-height
  "Constructor for the height-change operation: resize the shape named by `target`
   to height `value` (width unchanged). On the frontend this dispatches the real
   `dwt/update-dimensions` event. Implements `IPropertyCheck`, so a `one-of` over
   property and geometry edits can assert uniformly via `has-property-of`."
  [target value]
  (tm/assign-id (->ChangeHeight target value)))

(extend-type ChangeHeight
  IPropertyCheck
  (applied-property [_node] :height)
  (applied-value [node] (:value node))
  (has-property-of [node shape]
    (mth/close? (:value node) (:height shape))))

;; ===========================================================================
;; Synchronisation-scenario building blocks
;;
;; A family of operations that build up a component/copy configuration STEP BY
;; STEP, tracking a small, explicit set of named things in the situation so that
;; later edits and assertions can refer to them regardless of how much nesting was
;; applied. The tracked things per lineage (see the "Component objects" block
;; below for the exact fields):
;;
;;   - the REMOTE head/rect — the ORIGINAL instance and its rect (the fixed
;;     deepest origin; never re-pointed by plain nesting),
;;   - the MAIN head/rect — the CURRENT outer main and its rect (the shape an
;;     edit-to-main targets; advances inward with each `make-nested-component`),
;;   - the COPY head/rect — the copy produced by `instantiate-copy` and its rect
;;     (the shape an edit-to-copy targets / assertions observe),
;;   - the component id the next instantiate/nest uses, and the nesting count.
;;
;; "corresponds to" between layers is resolved by following the :shape-ref chain
;; (a copy shape refs the main shape it mirrors); within these single-file setups
;; the chain resolves in the local page objects, so a local walk suffices.
;; ===========================================================================

(defn- ref-chain-ids
  "The set of ids on `shape`'s :shape-ref chain within `objects`, INCLUDING the
   shape's own id and every shape its :shape-ref transitively points at. So a copy
   shape's chain contains the near-main it mirrors, that near-main's near-main, and
   so on — letting us recognise the descendant that is the image of a given origin
   shape regardless of how many layers sit between."
  [objects shape]
  (loop [s shape, acc #{}]
    (let [acc (conj acc (:id s))]
      (if-let [ref (:shape-ref s)]
        (recur (get objects ref) acc)
        acc))))

(defn- descendant-corresponding-to
  "The id of the shape in `head-id`'s subtree that is the propagated image of
   `target-id` — i.e. whose :shape-ref chain PASSES THROUGH `target-id`. Throws if
   not found or ambiguous — a structural invariant of these setups."
  [objects head-id target-id]
  (let [descendants (cfh/get-children-ids objects head-id)
        matches     (filter (fn [id]
                              (contains? (ref-chain-ids objects (get objects id)) target-id))
                            descendants)]
    (case (count matches)
      1 (first matches)
      0 (throw (ex-info "descendant-corresponding-to: no descendant corresponds to target"
                        {:head head-id :target target-id}))
      (throw (ex-info "descendant-corresponding-to: ambiguous correspondence"
                      {:head head-id :target target-id :matches matches})))))

(defn- self-or-descendant-corresponding-to
  "Like `descendant-corresponding-to`, but considers `head-id` ITSELF as well as
   its subtree. Needed for the nested HEAD: when the instance nested at a level is
   directly the image of the origin instance (e.g. level 0, where the inner copy
   head IS that image), the match is the head itself, not a descendant."
  [objects head-id target-id]
  (if (contains? (ref-chain-ids objects (get objects head-id)) target-id)
    head-id
    (descendant-corresponding-to objects head-id target-id)))

;; ===========================================================================
;; Component objects — the situation tracks NAMED component lineages
;;
;; The sync/swap scenario operations track one or more COMPONENT LINEAGES, each an
;; addressable OBJECT under `:vars :components`, keyed by a name (e.g. "main"). This
;; replaces the earlier flat, single-lineage roles (:main-child-rect, …): grouping
;; one lineage's fields into one object (a) lets several lineages coexist — required
;; for swap, whose target is a *different* component — and (b) makes each operation
;; "read object `name`, update its fields, write it back".
;;
;; A component object has:
;;   :main-component-id  - the component to instantiate next for this lineage
;;                         (advances to the new OUTER component on make-nested-component)
;;   :remote-head/:remote-rect - the fixed deepest origin (NEVER re-pointed)
;;   :main-head/:main-rect      - the current main (advances inward on make-nested-component)
;;   :nesting-count
;;   :nesting-data       - vector, one entry per nesting level i, each:
;;       {:main-head <id of that level's outer main>
;;        :nested-head <id of the subinstance head introduced at level i>
;;        :nested-rect <id of that nested head's rect, AT CREATION>
;;        :nested-head-parent <id of the shape containing the nested head>}
;;     The PARENT is the swap-stable anchor: a swap replaces the head in place but
;;     keeps its parent, so an assertion re-resolves parent -> current head -> rect.
;;
;; (Child shapes are addressed via the `*-rect` fields / `lineage-rect`; if other
;; child kinds are added later, they get parallel fields, not a restructure.)
;; ===========================================================================

(defn- get-component-obj
  "The component object named `name` from the situation (nil if absent)."
  [situation name]
  (get (tm/get-var situation :components {}) name))

(defn- put-component-obj
  "Store/replace the component object named `name`."
  [situation name obj]
  (tm/set-var situation :components
              (assoc (tm/get-var situation :components {}) name obj)))

(defn- update-component-obj
  "Apply `f` (obj -> obj) to the component object named `name`."
  [situation name f]
  (put-component-obj situation name (f (get-component-obj situation name))))

(defn lineage-component-id
  "The component id the lineage `name` will instantiate next."
  [situation name]
  (:main-component-id (get-component-obj situation name)))

(defn lineage-rect
  "The id of lineage `name`'s current main rect (the edit-to-main target)."
  [situation name]
  (:main-rect (get-component-obj situation name)))

(defn lineage-nesting
  "The `nesting-data` entry for level `i` of lineage `name`."
  [situation name i]
  (get-in (get-component-obj situation name) [:nesting-data i]))

;; Target helpers — return a (situation -> shape-id) fn for `change-property`'s
;; target (resolved at apply-time against the lineage object, so it follows the
;; current ids). `applied`-querying `Test`s read the same fields via the accessors
;; below.

(defn remote-rect-of
  "Target: lineage `name`'s fixed remote (deepest-origin) rect."
  [name]
  (fn [s] (:remote-rect (get-component-obj s name))))

(defn main-rect-of
  "Target: lineage `name`'s current main rect."
  [name]
  (fn [s] (:main-rect (get-component-obj s name))))

(defn copy-rect-of
  "Target: lineage `name`'s current copy rect (from the latest instantiate-copy)."
  [name]
  (fn [s] (:copy-rect (get-component-obj s name))))

(defn lineage-copy-rect
  "The id of lineage `name`'s current copy rect (for assertions)."
  [situation name]
  (:copy-rect (get-component-obj situation name)))

(defn level-rect
  "The id of the rect currently at lineage `name`'s nesting level `level`,
   re-resolved from the swap-stable :nested-head-parent: parent -> current
   subinstance head -> its rect descendant. Robust to a swap having replaced the
   head (and the rect) in place."
  [situation name level]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        nd      (lineage-nesting situation name level)
        parent  (:nested-head-parent nd)
        head    (->> (cfh/get-immediate-children objects parent)
                     (filter :component-id)
                     first
                     :id)
        ;; the rect is the (single) rect descendant under the head
        rect    (->> (cfh/get-children-ids objects head)
                     (map #(get objects %))
                     (filter #(= :rect (:type %)))
                     first
                     :id)]
    rect))

(defn level-rect-of
  "Target/accessor fn: the rect currently at lineage `name`'s nesting level
   `level` (see `level-rect`)."
  [name level]
  (fn [s] (level-rect s name level)))

;; ---------------------------------------------------------------------------
;; create-component — the starting operation: a component with a rect child
;; ---------------------------------------------------------------------------

(defrecord CreateComponent [name color]
  tm/IOperation
  (apply-to [this situation]
    ;; fresh, name-scoped labels so several lineages don't clash
    (let [head-label  (keyword (str "sync-" name "-head"))
          rect-label  (keyword (str "sync-" name "-rect"))
          comp-label  (keyword (str "sync-" name "-component"))
          file'       (-> (tm/file situation)
                          (tho/add-simple-component comp-label head-label rect-label
                                                    :child-params
                                                    {:fills (ths/sample-fills-color
                                                             :fill-color color)}))
          head-id     (thi/id head-label)
          rect-id     (thi/id rect-label)
          comp-id     (thi/id comp-label)]
      (-> situation
          (tm/with-file file')
          ;; create the lineage object: remote == main at creation (no nesting yet)
          (put-component-obj name
                             {:main-component-id comp-id
                              :remote-head head-id :remote-rect rect-id
                              :main-head   head-id :main-rect   rect-id
                              :nesting-count 0
                              :nesting-data []})
          (tm/record-application this {:component name :id comp-id})))))

(defn create-component
  "Starting operation: create a component lineage named `name` (root frame + one
   rect child of fill `color`) and track it as a component OBJECT. Sets
   remote==main head+rect, :main-component-id, nesting-count 0. Begin a scenario
   with this; create more lineages (e.g. swap targets) with further calls."
  [name color]
  (tm/assign-id (->CreateComponent name color)))

;; ---------------------------------------------------------------------------
;; make-nested-component — wrap the tracked component in a NEW OUTER component
;;
;; ONE specific notion of nesting (there are several): a new ENCLOSING component
;; whose main CONTAINS a COPY of the lineage's current component (contain-outward),
;; and the OUTER component becomes the lineage's new :main-component-id. The inner
;; copy's rect that corresponds (via :shape-ref) to the lineage's :remote-rect
;; becomes the new :main-rect, so an edit-to-main now targets one level deeper
;; while :remote-rect stays the fixed origin. Iterable: apply twice for 2 layers.
;; This is the structural family of penpot#9304 (a subinstance head inside a copy;
;; that issue adds a variant inner component + an extra frame — separate axes).
;; ---------------------------------------------------------------------------

(defn- nest-in-new-outer-component
  "Shared nesting mechanism (contain-outward): add a fresh outer board, run
   `instantiate-inner-fn` to place THE INNER INSTANCE inside it, turn the board
   into a NEW OUTER component, then advance lineage `name`'s object (main ->
   deeper rect, append a `nesting-data` entry with the swap-stable
   :nested-head-parent, bump nesting-count) and record `op`.

   `instantiate-inner-fn` is `(file outer-frame-label inner-copy-label) -> file`:
   it instantiates whatever is being nested (the lineage's own component, or a
   chosen variant) into `outer-frame-label`, registering `inner-copy-label` as
   the inner copy's root.

   `seek-rect-id` / `seek-head-id` are the ORIGIN rect and the ORIGIN instance head
   whose IMAGES inside the new inner copy become this level's nested rect and nested
   head — found by following the shape-ref chain. For nesting the lineage's own
   component these are the lineage's :remote-rect / :remote-head; for nesting a
   variant member they are THAT MEMBER's rect / root (the inner copy refs the member,
   not the lineage). `nested-head` is thus the DEEPEST instance (the image of the
   original component's instance), per spec — at level 0 it is the inner copy itself;
   at deeper levels it is the corresponding shape nested within. Everything else is
   identical across nesting flavours, so a new flavour supplies only these three."
  [situation name op seek-rect-id seek-head-id instantiate-inner-fn]
  (let [the-file     (tm/file situation)
        obj          (get-component-obj situation name)
        level        (:nesting-count obj)
        ;; level-scoped labels so repeated nestings of one lineage don't clash
        outer-frame  (keyword (str "sync-" name "-outer-" level))
        inner-copy   (keyword (str "sync-" name "-innercopy-" level))
        outer-comp   (keyword (str "sync-" name "-outercomp-" level))
        file'        (-> the-file
                         (tho/add-frame outer-frame {:name "OuterFrame"})
                         (instantiate-inner-fn outer-frame inner-copy)
                         (thc/make-component outer-comp outer-frame))
        objects      (:objects (thf/current-page file'))
        inner-copy-id (thi/id inner-copy)
        ;; images, inside the new inner copy, of the origin rect and origin instance
        new-main-rect (descendant-corresponding-to objects inner-copy-id seek-rect-id)
        ;; nested-head = the DEEPEST instance (image of the original component's
        ;; instance), per spec — the switchable subinstance. Its parent is the
        ;; swap-stable anchor for re-resolving it after a swap/switch replaces it.
        nested-head   (self-or-descendant-corresponding-to objects inner-copy-id seek-head-id)
        nested-parent (:parent-id (get objects nested-head))
        outer-id      (thi/id outer-comp)
        outer-head-id (thi/id outer-frame)]
    (-> situation
        (tm/with-file file')
        (update-component-obj
         name
         (fn [o]
           (-> o
               (assoc :main-component-id outer-id
                      :main-head outer-head-id
                      :main-rect new-main-rect
                      :nesting-count (inc level))
               (update :nesting-data conj
                       {:main-head outer-head-id
                        :nested-head nested-head
                        :nested-rect new-main-rect
                        :nested-head-parent nested-parent}))))
        (tm/record-application op {:component name :level level :outer outer-id}))))

(defrecord MakeNestedComponent [name]
  tm/IOperation
  (apply-to [this situation]
    ;; nest a COPY of the lineage's own current component. Seek the FIXED deepest
    ;; origin (:remote-rect / :remote-head): every level's copy refs back through it,
    ;; so its image identifies this level's nested rect and deepest instance. (Variant
    ;; nesting re-points :remote-* to the variant member, keeping this uniform.)
    (let [obj (get-component-obj situation name)]
      (nest-in-new-outer-component
       situation name this
       (:remote-rect obj)
       (:remote-head obj)
       (fn [file outer-frame inner-copy]
         (let [inner-label (thi/label (:main-component-id obj))]
           (thc/instantiate-component file inner-label inner-copy
                                      {:parent-label outer-frame})))))))

(defn make-nested-component
  "Wrap lineage `name`'s component in a NEW OUTER component (containing a copy of
   it) and make the OUTER component its next-to-instantiate; advance :main to the
   deeper rect while :remote stays fixed; append a `nesting-data` entry (with the
   swap-stable :nested-head-parent) and bump nesting-count. ONE notion of nesting
   (contain-outward). Apply repeatedly to deepen."
  [name]
  (tm/assign-id (->MakeNestedComponent name)))

;; ---------------------------------------------------------------------------
;; instantiate-copy — instantiate the lineage's current component, track the copy
;; ---------------------------------------------------------------------------

(defrecord InstantiateCopy [name]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file   (tm/file situation)
          obj        (get-component-obj situation name)
          comp-id    (:main-component-id obj)
          comp-label (thi/label comp-id)
          main-rect  (:main-rect obj)
          copy-head  (keyword (str "sync-" name "-copyhead-"
                                   (count (:copies obj))))
          file'      (-> the-file
                         (thc/instantiate-component comp-label copy-head))
          objects    (:objects (thf/current-page file'))
          copy-id    (thi/id copy-head)
          ;; the rect inside the copy corresponding to the current main rect
          copy-rect  (descendant-corresponding-to objects copy-id main-rect)]
      (-> situation
          (tm/with-file file')
          (update-component-obj
           name
           (fn [o]
             (-> o
                 (assoc :copy-head copy-id :copy-rect copy-rect)
                 (update :copies (fnil conj []) {:copy-head copy-id :copy-rect copy-rect}))))
          (tm/record-application this {:component name :copy copy-id})))))

(defn instantiate-copy
  "Instantiate lineage `name`'s current component; track the copy's head and the
   rect corresponding to its current main rect on the lineage object (as
   :copy-head/:copy-rect, and appended to :copies). The copy rect is the shape
   edits-to-copy target and assertions observe."
  [name]
  (tm/assign-id (->InstantiateCopy name)))

;; ---------------------------------------------------------------------------
;; reset-copy-instance — reset overrides on the tracked copy instance
;; ---------------------------------------------------------------------------

(defrecord ResetCopyInstance [name]
  tm/IOperation
  (apply-to [this situation]
    ;; Production reset path (generate-reset-component), validation OFF — mirrors
    ;; SyncFromLibrary. We call the generator directly rather than via
    ;; tho/reset-overrides because that helper validates the file, which the
    ;; assembled-context frontend store does not always satisfy; and it stays a
    ;; SYNC-op (`apply-to` on the live store file) rather than an event-op because
    ;; the real reset event transitively reads browser globals, so it cannot run
    ;; headless.
    (let [the-file  (tm/file situation)
          copy-id   (:copy-head (get-component-obj situation name))
          page      (thf/current-page the-file)
          container (ctn/make-container page :page)
          file-id   (:id the-file)
          changes   (-> (pcb/empty-changes)
                        (cll/generate-reset-component
                         the-file {file-id the-file} container copy-id))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:component name :reset copy-id})))))

(defn reset-copy-instance
  "Reset overrides on lineage `name`'s tracked copy instance (its :copy-head) —
   discards the copy's own divergences so it reflects its main again."
  [name]
  (tm/assign-id (->ResetCopyInstance name)))

;; ---------------------------------------------------------------------------
;; swap-component — replace a nested subinstance head with an instance of a
;; different component (the general "Swap component" action), in place.
;;
;; Targets lineage `name`'s nesting level `level` (its `nesting-data[level]
;; .nested-head`) and swaps it for lineage `target`'s component
;; (`:main-component-id`). Drives the production `generate-component-swap`
;; (validation off). The `apply-to` below is the pure realisation; the frontend
;; interpreter instead dispatches the real `dwl/component-swap` event, so the
;; watcher auto-propagates the swap. keep-touched? defaults
;; false (the general swap discards overrides; variant-switch would pass true).
;;
;; A swap REPLACES the head in place (Penpot keeps the head's id but rewrites it to
;; reference the new component and stamps a :swap-slot touched group). The level's
;; :nested-head-parent is unchanged, so assertions re-resolve parent -> current
;; head -> rect.
;; ---------------------------------------------------------------------------

(defrecord SwapComponent [name level target keep-touched?]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file    (tm/file situation)
          page        (thf/current-page the-file)
          objects     (:objects page)
          nested-head (:nested-head (lineage-nesting situation name level))
          shape       (get objects nested-head)
          target-id   (lineage-component-id situation target)
          libraries   {(:id the-file) the-file}
          orig-shapes (when keep-touched?
                        (cfh/get-children-with-self objects nested-head))
          [new-shape _parents changes]
          (cll/generate-component-swap (pcb/empty-changes)
                                       objects shape (:data the-file) page libraries
                                       target-id 0 nil {} (boolean keep-touched?))
          [changes _] (if keep-touched?
                        (clv/generate-keep-touched changes new-shape shape orig-shapes
                                                   page libraries (:data the-file))
                        [changes nil])
          file'       (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          ;; record the swapped-in head id on the level (its parent is unchanged)
          (tm/with-file file')
          (update-component-obj
           name
           (fn [o] (assoc-in o [:nesting-data level :swapped-head] (:id new-shape))))
          (tm/record-application this {:component name :level level
                                       :target target :new-head (:id new-shape)})))))

(defn swap-component
  "Swap lineage `name`'s nesting level `level` for lineage `target`'s component
   (the general Swap-component action). `keep-touched?` (default false) preserves
   the swapped instance's overrides (true = the variant-switch flavour)."
  [name level target & {:keys [keep-touched?] :or {keep-touched? false}}]
  (tm/assign-id (->SwapComponent name level target keep-touched?)))

;; ===========================================================================
;; VARIANT operations (case M) — the variant-switch flavour of the swap sweep.
;;
;; A VARIANT SET is N peer components grouped in a container, distinguished by a
;; selector PROPERTY (here a single property at pos 0). Instantiation selects a
;; member by its property VALUE; a later `switch-variant` re-selects within the
;; set. Under the hood a switch is a keep-touched swap whose target is resolved
;; by property value (see `app.main.data.workspace.variants/variant-switch`), so
;; it routes through the SAME `generate-component-swap` as `swap-component` and
;; the watcher auto-propagates it across nesting levels exactly like a swap.
;;
;; OP SPLIT: only the SWITCH is a real workspace event (`variant-switch` has no
;; pure generator to call directly), so `SwitchVariant` is an EVENT-op: its
;; `apply-to` throws, and the frontend interpreter realises it by dispatching the
;; event. The container build and the variant nesting are SYNC-ops (test-helper
;; assembly + the shared nesting helper), applied via `apply-to` against the live
;; store file; they record the variant-set in vars. Event-ops do NOT run
;; `apply-to` (only sync-ops do), but the switch needs no bookkeeping: the
;; asserter re-resolves heads via the swap-stable :nested-head-parent
;; (`nested-head-of`/`level-rect`), exactly as case L does for swaps.
;;
;; Member SELECTOR: `make-variant-container` assigns each member an EXPLICIT property
;; value we choose (the `value` in its [value color] specs). A member is addressed by
;; that value in `make-nested-component-with-variant` / `switch-variant`, resolved via
;; the recorded variant-set in vars.
;; ===========================================================================

(def ^:private variant-property-name
  "The single selector property's name (these tests use one property)."
  "Property 1")

(defn variant-set
  "Read the variant-set record `set-name` from the situation's vars (written by
   `make-variant-container`): {:variant-id, :container-label, :members [{:value
   :component-label :component-id} ...]}."
  [situation set-name]
  (or (tm/get-var situation [::variant-set set-name])
      (throw (ex-info "variant-set: no such set (was make-variant-container applied?)"
                      {:set set-name}))))

(defn variant-member
  "The member record of set `set-name` whose selector value is `value` ({:value
   :component-label :component-id :root-label :rect-label :rect-id}). Throws if no
   unique member matches — a structural invariant."
  [situation set-name value]
  (let [members (:members (variant-set situation set-name))
        matches (filter #(= value (:value %)) members)]
    (case (count matches)
      1 (first matches)
      0 (throw (ex-info "variant-member: no member has that value"
                        {:set set-name :value value
                         :available (mapv :value members)}))
      (throw (ex-info "variant-member: ambiguous value"
                      {:set set-name :value value :count (count matches)})))))

(defn variant-member-component-label
  "The component label of set `set-name`'s member whose selector value is `value`."
  [situation set-name value]
  (:component-label (variant-member situation set-name value)))

(defn variant-member-component-id
  "The component id of set `set-name`'s member whose selector value is `value`."
  [situation set-name value]
  (:component-id (variant-member situation set-name value)))

;; ---------------------------------------------------------------------------
;; make-variant-container — build a variant SET synchronously
;;
;; On our branch the only user-facing combine is the async `combine-as-variants`
;; (behind `penpot.createVariantFromComponents`), whose settlement + positional,
;; non-chosen property values make it a poor fit — and case M tests the SWITCH, not
;; the combine. So we assemble the container the way the variant test-helpers do
;; (`add-variant`): a container frame (:is-variant-container) whose children are the
;; member component roots, each carrying the shared :variant-id and an EXPLICIT
;; selector value we choose. This is synchronous (a sync-op, like create-component),
;; produces exactly the structure `variant-switch`/`find-variant-components`
;; consume, and lets us address members by a value of our choosing.
;;
;; `members` is a vector of [value color] — value = the selector value used by
;; `make-nested-component-with-variant` / `switch-variant`; color = the member's
;; rect fill (how the asserter tells members apart). The set is recorded in vars
;; under `name` (variant-id + per-member component labels/ids).
;; ---------------------------------------------------------------------------

(defrecord MakeVariantContainer [name members]
  tm/IOperation
  (apply-to [this situation]
    ;; Build the set exactly as the variant test-helpers' `add-variant` do (the
    ;; proven idiom): a container frame (:is-variant-container), then each member's
    ;; ROOT as a child carrying :variant-id + :variant-name, made into a component,
    ;; then `update-component` stamps :variant-id + :variant-properties ON THE
    ;; COMPONENT. (Routing this through add-simple-component's positional component
    ;; params silently dropped :variant-id — hence the explicit two-step here.)
    (let [container-label (keyword (str "sync-" name "-vcontainer"))
          ;; create the container frame FIRST, THEN read its id (thi/id is a lookup
          ;; that only resolves once the shape exists — reading it earlier yields nil).
          base-file       (-> (tm/file situation)
                              (ths/add-sample-shape container-label
                                                    :type :frame
                                                    :is-variant-container true))
          variant-id      (thi/id container-label)
          [file' member-recs]
          (reduce
           (fn [[file recs] [idx [value color]]]
             (let [comp-label (keyword (str "sync-" name "-v" idx "-component"))
                   root-label (keyword (str "sync-" name "-v" idx "-root"))
                   rect-label (keyword (str "sync-" name "-v" idx "-rect"))
                   file2 (-> file
                             ;; member root as a child of the container, with variant id/name
                             ;; (mirrors add-variant's `add-sample-shape :type :frame ...`)
                             (ths/add-sample-shape root-label
                                                   :type :frame
                                                   :parent-label container-label
                                                   :variant-id variant-id
                                                   :variant-name value)
                             (ths/add-sample-shape rect-label
                                                   :parent-label root-label
                                                   :fills (ths/sample-fills-color :fill-color color))
                             ;; make it a component, then stamp variant metadata ON THE COMPONENT
                             (thc/make-component comp-label root-label)
                             (thc/update-component
                              comp-label
                              {:variant-id variant-id
                               :variant-properties [{:name variant-property-name :value value}]}))]
               [file2 (conj recs {:value value
                                  :component-label comp-label
                                  :component-id (thi/id comp-label)
                                  :root-label root-label
                                  :rect-label rect-label
                                  :rect-id (thi/id rect-label)})]))
           [base-file []]
           (map-indexed vector members))]
      (-> situation
          (tm/with-file file')
          (tm/set-var [::variant-set name]
                      {:variant-id variant-id
                       :container-label container-label
                       :members member-recs})
          (tm/record-application this {:variant-set name :variant-id variant-id
                                       :count (count members)})))))

(defn make-variant-container
  "Build a variant SET named `name` synchronously (test-helper assembly, like the
   variant test-helpers' `add-variant`): a container holding N member components,
   each with the shared variant-id and an EXPLICIT selector value. `members` is a
   vector of [value color]. Members are addressed by `value` in
   `make-nested-component-with-variant` / `switch-variant`; `color` distinguishes
   them for the asserter."
  [name members]
  (tm/assign-id (->MakeVariantContainer name members)))

;; ---------------------------------------------------------------------------
;; make-nested-component-with-variant — nest a chosen VARIANT, deeply
;;
;; Same contain-outward nesting as `make-nested-component` (shares
;; `nest-in-new-outer-component`), except the inner instance is a SPECIFIC variant
;; member (selected by property `value`) rather than the lineage's own component.
;; So every nesting level introduces a variant subinstance head — the switch
;; target. Sync-op (test-helper instantiation), like `make-nested-component`.
;; ---------------------------------------------------------------------------

(defrecord MakeNestedComponentWithVariant [name set-name value]
  tm/IOperation
  (apply-to [this situation]
    (let [member   (variant-member situation set-name value)
          root-id  (thi/id (:root-label member))
          rect-id  (:rect-id member)]
      (-> situation
          (nest-in-new-outer-component
           name this
           rect-id                       ; origin rect = the variant member's own rect
           root-id                       ; origin head = the variant member's own root
           (fn [file outer-frame inner-copy]
             (thc/instantiate-component file (:component-label member) inner-copy
                                        {:parent-label outer-frame})))
          ;; nesting a variant makes the MEMBER the new deepest origin: re-point the
          ;; lineage's fixed origin so subsequent plain make-nested-component descends to the
          ;; variant's image (its nested-head) at every level.
          (update-component-obj name #(assoc % :remote-head root-id :remote-rect rect-id))))))

(defn make-nested-component-with-variant
  "Nest (contain-outward, like `make-nested-component`) a SPECIFIC variant member of set
   `set-name` — the member whose selector value is `value` — under lineage `name`,
   advancing `name`'s nesting. Every level so nested gets a variant subinstance
   head that `switch-variant` can later re-select. Apply repeatedly to deepen."
  [name set-name value]
  (tm/assign-id (->MakeNestedComponentWithVariant name set-name value)))

;; ---------------------------------------------------------------------------
;; switch-variant — switch a variant copy head to a sibling member
;;
;; The variant-switch action: switch the variant copy head bound to `target` to the
;; sibling member whose selector property (pos 0) has `value`. FRONTEND-only:
;; dispatches the production `variants-switch`, which DISCOVERS the sibling by value
;; within the variant container and routes through `component-swap` with
;; keep-touched? true — so the watcher auto-propagates it across nesting levels
;; exactly like case L's swap. A switch REPLACES the head in place; the head's
;; :parent is unchanged, so assertions re-resolve parent -> current head -> rect.
;;
;; `target` is the head to switch, resolved by the standard `target-shape-id` (role
;; | label | (situation -> id) fn, e.g. `nested-head-of` for the stored nested head
;; at a level — exactly the head `swap-component` targets). So the op knows NOTHING
;; about nesting.
;; ---------------------------------------------------------------------------

(defrecord SwitchVariant [target value]
  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "switch-variant is an EVENT-op with no pure `apply-to` realisation: "
                         "it is the real `variants-switch` workspace event (which discovers the "
                         "sibling via the variant container), dispatched by the frontend "
                         "interpreter. See the VARIANT operations note above.")
                    {:type ::switch-variant-is-event-op}))))

(defn switch-variant
  "Switch the variant copy head bound to `target` to the sibling member whose
   selector property (pos 0) has value `value`, via the real `variants-switch`
   machinery (which discovers the sibling within the container). Propagates across
   nesting exactly like case L's swap. `target` is resolved like any operation target
   (role | label | (situation -> id) fn, e.g. `nested-head-of`), so the op is
   structure-agnostic. FRONTEND-only."
  [target value]
  (tm/assign-id (->SwitchVariant target value)))

;; nested-head-of — a (situation -> id) target fn for the nested head at a level,
;; supplied to `switch-variant` so the operation needs no nesting knowledge.
(defn nested-head-of
  "Target fn: the subinstance head introduced at lineage `name`'s nesting level `i`
   — the `:nested-head` stored in nesting-data for that level (exactly the head
   `swap-component` targets). This is the variant instance to switch. For
   `switch-variant`'s `target`."
  [name i]
  (fn [s] (:nested-head (lineage-nesting s name i))))


;; ---------------------------------------------------------------------------
;; sync-from-library — pull a linked library's changes into the consuming file
;;
;; LOCALITY axis (case H): the main lives in a SEPARATE library file and the copy
;; in the consuming (current) file. Propagation here crosses a FILE boundary and
;; is NOT the in-file component watcher — on the real frontend it is the library
;; UPDATE action (the user accepts "library updated"), realised as
;; `sync-file file-id library-id`. This node models that action.
;;
;; The situation carries the consuming file as its primary `:file` and the library
;; as an AUXILIARY file (see `tm/with-aux-files`). The pure `apply-to` realisation
;; below builds the libraries map from BOTH and runs `generate-sync-file-changes`
;; for the whole library (asset-id nil), mirroring `test-sync-when-changing-
;; lower-remote`. The frontend interpreter instead dispatches the real
;; `sync-file` event (see its `op->events`).
;; ---------------------------------------------------------------------------

(defrecord SyncFromLibrary []
  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          file-id   (:id the-file)
          aux       (tm/aux-files situation)
          library-id (first (keys aux))
          libraries (assoc aux file-id the-file)
          changes   (-> (pcb/empty-changes)
                        (cll/generate-sync-file-changes
                         nil
                         :components
                         file-id
                         nil           ; whole library (no single asset-id)
                         library-id
                         libraries
                         file-id))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:library-id library-id})))))

(defn sync-from-library
  "Constructor for the cross-file library-sync node (case H). Takes no parameters;
   the situation supplies the consuming file (primary) and the library (auxiliary)."
  []
  (tm/assign-id (->SyncFromLibrary)))


;; ---------------------------------------------------------------------------
;; undo — reverse the immediately preceding operation(s)
;;
;; Undo is "just another operation node": the APP owns reversal; this node does
;; not snapshot-and-compare. It is an EVENT-op: the frontend interpreter
;; dispatches the real `dwu/undo` event, which pops the workspace undo stack
;; (maintained automatically by the prior operations' commits) and applies the
;; inverse changes. "Test undo everywhere" = append this node after any case; the
;; post-undo assertion is an ordinary condition about the resulting state.
;;
;; A pure `apply-to` realisation is NOT built: it would require every node to
;; stash its produced change value (the engine `:undo-changes`) into its record
;; so this node could apply the inverse — a retrofit across all nodes that no
;; case needs. It therefore throws loudly rather than pretending.
;; ---------------------------------------------------------------------------

(defrecord Undo []
  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "Undo is an EVENT-op with no pure `apply-to` realisation (change "
                         "values are not stashed per-node). It is realised by the frontend "
                         "interpreter via the real undo event.")
                    {:type ::undo-is-event-op}))))

(defn undo
  "Constructor for the undo node (case I). Takes no parameters; on the frontend it
   dispatches the real undo, reversing the immediately preceding operation(s)."
  []
  (tm/assign-id (->Undo)))


;; ---------------------------------------------------------------------------
;; add-child — add a new shape into a (main) component, structurally
;;
;; This is STRUCTURAL modification (changes the tree shape), as opposed to
;; change-attr (attribute modification). It mirrors the established
;; comp-sync-test "add shape" pattern: create a free shape on the page, then
;; relocate it INTO the target parent via the production `generate-relocate`
;; path. Propagation then materializes a corresponding child in copies.
;;
;; `parent` is the binding name of the shape to add the new child under (e.g.
;; :main-root). `new-label` is the label assigned to the created shape, so a
;; condition can find the corresponding copy child by what was recorded.
;; ---------------------------------------------------------------------------

(defrecord AddChild [parent new-label shape-params]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file   (tm/file situation)
          ;; strict-presence on the parent (it must exist before we add under it)
          parent-id  (tm/resolve-shape-id situation parent)
          ;; 1) create the free shape on the page (registers `new-label`)
          file-1     (ths/add-sample-shape the-file new-label (or shape-params {}))
          page       (thf/current-page file-1)
          new-id     (thi/id new-label)
          ;; 2) relocate it into the parent through the production change path
          changes    (cls/generate-relocate
                      (-> (pcb/empty-changes nil)
                          (pcb/with-page-id (:id page))
                          (pcb/with-objects (:objects page)))
                      parent-id        ; parent-id
                      0                ; to-index
                      #{new-id})       ; ids to move
          file'      (thf/apply-changes file-1 changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:parent parent :new-label new-label})))))

(defn add-child
  "Constructor for the structural add-child node: create a new shape (labeled
   `new-label`) and relocate it under the shape bound to `parent`. Stamps a node
   identity so the test can interrogate it."
  [parent new-label & {:as shape-params}]
  (tm/assign-id (->AddChild parent new-label shape-params)))


;; ---------------------------------------------------------------------------
;; remove-child — delete a shape from a (main) component, structurally
;;
;; Subtractive structural modification (twin of add-child). Mirrors the
;; established comp-sync-test "delete shape" pattern: `generate-delete-shapes`
;; on the named shape in the main. Propagation then removes the corresponding
;; child from clean copies. (Domain note from comp-sync-test: deleting from a
;; COPY would only hide; here we delete from the MAIN and propagate the removal.)
;;
;; `target` is the binding name (label) of the shape to remove from the main.
;; ---------------------------------------------------------------------------

(defrecord RemoveChild [target]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          ;; strict-presence: the target must exist before removal
          shape-id  (tm/resolve-shape-id situation target)
          page      (thf/current-page the-file)
          [_all-parents changes]
          (cls/generate-delete-shapes (pcb/empty-changes)
                                      the-file
                                      page
                                      (:objects page)
                                      #{shape-id}
                                      {:components-v2 true})
          file'     (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:target target})))))

(defn remove-child
  "Constructor for the structural remove-child node: delete the shape bound to
   `target` from the main. Stamps a node identity."
  [target]
  (tm/assign-id (->RemoveChild target)))


;; ---------------------------------------------------------------------------
;; move-child — reorder an existing shape within a (main) component
;;
;; Structural modification where nothing is created or destroyed; only ORDER
;; changes, and that order should propagate to clean copies while :shape-ref
;; identity stays decoupled from position. Mirrors the established comp-sync-test
;; "move shape" pattern: `generate-relocate` of an EXISTING child to a new index
;; under the same parent.
;;
;; `target` is the binding name (label) of the existing main child to move;
;; `to-index` is its destination index under `parent`.
;; ---------------------------------------------------------------------------

(defrecord MoveChild [target parent to-index]
  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          shape-id  (tm/resolve-shape-id situation target)
          parent-id (tm/resolve-shape-id situation parent)
          page      (thf/current-page the-file)
          changes   (cls/generate-relocate
                     (-> (pcb/empty-changes nil)
                         (pcb/with-page-id (:id page))
                         (pcb/with-objects (:objects page)))
                     parent-id
                     to-index
                     #{shape-id})
          file'     (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:target target :parent parent :to-index to-index})))))

(defn move-child
  "Constructor for the structural move-child node: relocate the existing shape
   bound to `target` to `to-index` under the shape bound to `parent`. Stamps a
   node identity."
  [target parent to-index]
  (tm/assign-id (->MoveChild target parent to-index)))


(defprotocol IStructuralCheck
  "Inspection capability of a structural node: retrieve the shapes involved in its
   own effect, so the test can state ref-integrity / placement assertions itself."
  (added-shape [node situation]
    "The shape this node added to the main (live, from the current file).")
  (materialized-instance-child [node situation container-shape]
    "The child of `container-shape` that is the materialized instance of this
     node's added shape (i.e. whose :shape-ref points at it), or nil. Retrieval
     only — the test asserts the relationship."))

(extend-type AddChild
  IStructuralCheck
  (added-shape [node situation]
    (ths/get-shape (tm/file situation) (:new-label node)))
  (materialized-instance-child [node situation container-shape]
    (let [the-file (tm/file situation)
          added-id (thi/id (:new-label node))]
      (->> (:shapes container-shape)
           (map #(ths/get-shape-by-id the-file %))
           (d/seek #(= added-id (:shape-ref %)))))))
