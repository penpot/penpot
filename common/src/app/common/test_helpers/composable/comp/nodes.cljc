;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.nodes
  "The component operations of the composable test model (see
   `mem:frontend/composable-component-tests`). Each builds its changes with
   the production generators, applies them to the file and records what it
   did under its identity."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]))

;; ---------------------------------------------------------------------------
;; Operation kinds: how a runner treats a node.
;;   :assembly       builds the configuration with test helpers: no sync, no
;;                   undo group, no recorded changes.
;;   :user           a user action; records the changes it applied with
;;                   `record-changes` (nil when it changed nothing), which
;;                   the pure runner syncs from and pushes as an undo group.
;;   :undo, :redo    need a runner's undo or redo stack.
;;   :frontend-only  needs frontend code; the pure runner rejects it.
;; ---------------------------------------------------------------------------

(defprotocol IComponentOperation
  (op-kind [op]
    "How a runner treats `op`: :assembly, :user, :undo, :redo or
     :frontend-only."))

(defn record-changes
  "Store `changes`, the production changes a user operation applied (nil when
   it changed nothing), in `situation` for the runner to take."
  [situation changes]
  (assoc situation ::changes changes))

(defprotocol IAppliedResult
  (applied-result [op file-before file-after]
    "A map of what `op` did that its accessors need, read from the file before
     and after it. Both runners record it with the op's application."))

(defn take-changes
  "The changes recorded by the last operation and the situation without them,
   as `[recorded? changes situation]`. `recorded?` is false when the operation
   did not call `record-changes`."
  [situation]
  [(contains? situation ::changes)
   (::changes situation)
   (dissoc situation ::changes)])

;; ---------------------------------------------------------------------------
;; Properties: `set-property` writes one onto a shape (`change-property`)
;; and `read-property` reads it back (`has-property-of`). A new property is
;; one clause in each.
;; ---------------------------------------------------------------------------

(defn set-property
  "`shape` with `property` set to `value`. Public so the frontend interpreter
   makes the same edit."
  [shape property value]
  (case property
    :fills   (assoc shape :fills (ths/sample-fills-color :fill-color value))
    :opacity (assoc shape :opacity value)
    (throw (ex-info (str "set-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity)")
                    {:property property}))))

(defn- read-property
  "The comparable value of `property` in `shape`."
  [shape property]
  (case property
    :fills   (-> shape :fills first :fill-color)
    :opacity (:opacity shape)
    (throw (ex-info (str "read-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity)")
                    {:property property}))))

(defrecord ChangeProperty [target property value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
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
          (record-changes changes)
          (tm/record-application this {:target target :property property :value value})))))

(defn change-property
  "Constructor for the property-change operation: set `property` of the shape
   `target` names (a role, a label or a `(situation -> id)` fn) to `value`."
  [target property value]
  (tm/assign-id (->ChangeProperty target property value)))

(defprotocol IPropertyCheck
  "The check dual of an operation that changes a property: whether a shape the
   test supplies holds the value the operation set."
  (applied-property [node] "The property this node changed.")
  (applied-value [node] "The value this node applied.")
  (has-property-of [node shape]
    "Whether `shape` holds this node's property at this node's value."))

(extend-type ChangeProperty
  IPropertyCheck
  (applied-property [node] (:property node))
  (applied-value [node] (:value node))
  (has-property-of [node shape]
    (= (:value node) (read-property shape (:property node)))))

;; ---------------------------------------------------------------------------
;; edit-text — edit the content of a text shape
;;
;; An edit of the characters (`:text`) or font size (`:font-size`) of a text's
;; first span, as the text editor commits it when the edit ends: a new
;; `:content`, and a new name when the text still has the name generated from
;; its old content. The frontend interpreter commits it through
;; `v2-update-text-shape-content`, as the editor does on blur.
;; ---------------------------------------------------------------------------

(def ^:private first-span [:content :children 0 :children 0 :children 0])

(defn- generated-name
  "The name the text editor gives a text after its `content`."
  [content]
  (txt/generate-shape-name (txt/content->text content)))

(defn text-edit-commit
  "What the text editor commits when the `edit` (`:text` or `:font-size`) of
   the first span of `shape` becomes `value`: `:content`, and `:name` when the
   editor renames the shape after its text, which it does when the shape still
   has the name generated from its old content. Public so the frontend
   interpreter commits the same edit."
  [shape edit value]
  (when-not (contains? #{:text :font-size} edit)
    (throw (ex-info (str "text-edit-commit: unsupported edit " (pr-str edit)
                         " (supported: :text, :font-size)")
                    {:edit edit})))
  (let [content (assoc-in (:content shape) (conj (subvec first-span 1) edit) value)]
    (cond-> {:content content}
      (= (:name shape) (generated-name (:content shape)))
      (assoc :name (generated-name content)))))

(defn edit-text-content
  "`shape` after the text editor's commit of `text-edit-commit`."
  [shape edit value]
  (merge shape (text-edit-commit shape edit value)))

(defn text-span-value
  "The `edit` (`:text` or `:font-size`) of `shape`'s first span."
  [shape edit]
  (get-in shape (conj first-span edit)))

(defrecord EditText [target edit value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{shape-id}
                                               #(edit-text-content % edit value)
                                               (:objects page)
                                               {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :edit edit :value value})))))

(defn edit-text
  "Constructor for the text edit operation: set the `edit` (`:text` or
   `:font-size`) of the first span of the text bound to `target` to `value`.
   On the frontend it dispatches the real `v2-update-text-shape-content`."
  [target edit value]
  (tm/assign-id (->EditText target edit value)))

;; ---------------------------------------------------------------------------
;; Geometry operations
;;
;; The frontend interpreter dispatches the real sidebar events, whose
;; transform step decides which copy shapes the change touches
;; (`check-delta`), frontend code. Their `apply-to` makes the same transform
;; without that step, so the pure runner rejects them.

(defrecord Rotate [target angle]
  IComponentOperation
  (op-kind [_] :frontend-only)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          shape    (get objects shape-id)
          ;; the whole subtree rotates, as with the real event
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
          (record-changes changes)
          (tm/record-application this {:target target :angle angle})))))

(defn rotate
  "Constructor for the rotation operation: rotate the shape `target` names,
   with its subtree, by `angle` degrees around its center
   (`dwt/increase-rotation` on the frontend)."
  [target angle]
  (tm/assign-id (->Rotate target angle)))

(defrecord ChangeHeight [target value]
  IComponentOperation
  (op-kind [_] :frontend-only)

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
          (record-changes changes)
          (tm/record-application this {:target target :value value})))))

(defrecord MoveShape [target dx dy]
  IComponentOperation
  (op-kind [_] :frontend-only)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{shape-id}
                                               #(gsh/move % (gpt/point dx dy))
                                               (:objects page)
                                               {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :dx dx :dy dy})))))

(defn move-shape
  "Constructor for the move operation: move the shape `target` names by
   (`dx`, `dy`) (`dwt/update-position` on the frontend)."
  [target dx dy]
  (tm/assign-id (->MoveShape target dx dy)))

(defn change-height
  "Constructor for the height-change operation: resize the shape `target`
   names to height `value` (`dwt/update-dimensions` on the frontend). It has
   `has-property-of`, like the property edits."
  [target value]
  (tm/assign-id (->ChangeHeight target value)))

(extend-type ChangeHeight
  IPropertyCheck
  (applied-property [_node] :height)
  (applied-value [node] (:value node))
  (has-property-of [node shape]
    (mth/close? (:value node) (:height shape))))

(extend-type EditText
  IPropertyCheck
  (applied-property [node] (:edit node))
  (applied-value [node] (:value node))
  (has-property-of [node shape]
    (= (:value node) (text-span-value shape (:edit node)))))

;; ===========================================================================
;; Scenario building blocks
;;
;; Operations that build a component configuration step by step and track
;; named lineages in the situation (see "Component lineages" below), so later
;; edits and assertions find their shapes at any nesting depth. A shape's
;; image in another layer is found by following the :shape-ref chain within
;; the page.
;; ===========================================================================

(defn- ref-chain-ids
  "The ids on `shape`'s :shape-ref chain within `objects`: its own, its near
   main's, that one's near main's, and so on."
  [objects shape]
  (loop [s shape, acc #{}]
    (let [acc (conj acc (:id s))]
      (if-let [ref (:shape-ref s)]
        (recur (get objects ref) acc)
        acc))))

(defn- descendant-corresponding-to
  "The id of the shape in `head-id`'s subtree whose :shape-ref chain passes
   through `target-id`. Throws when there is none or more than one."
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
  "Like `descendant-corresponding-to`, with `head-id` itself as a candidate."
  [objects head-id target-id]
  (if (contains? (ref-chain-ids objects (get objects head-id)) target-id)
    head-id
    (descendant-corresponding-to objects head-id target-id)))

;; ===========================================================================
;; Component lineages
;;
;; A lineage is a map under `:vars :components`, keyed by its name (e.g.
;; "main"); several can coexist, e.g. swap targets. It has:
;;   :main-component-id         the component to instantiate next (the new
;;                              outer one after `make-nested-component`)
;;   :remote-head/:remote-rect  the original main and its rect
;;   :main-head/:main-rect      the current outer main and the image of the
;;                              remote rect in it
;;   :copy-head/:copy-rect      the latest copy; all of them under :copies
;;   :size                      the root size `create-component` got
;;   :nesting-count
;;   :nesting-data              per nesting level: :main-head (the level's
;;                              outer main), :nested-head (the copy nested at
;;                              that level), :nested-rect and
;;                              :nested-head-parent. A swap replaces the
;;                              head but keeps its parent, so accessors find
;;                              the current head through the parent.
;; ===========================================================================

(defn- get-component-obj
  "The lineage `name`, or nil."
  [situation name]
  (get (tm/get-var situation :components {}) name))

(defn- put-component-obj
  "`situation` with the lineage `name` set to `obj`."
  [situation name obj]
  (tm/set-var situation :components
              (assoc (tm/get-var situation :components {}) name obj)))

(defn- update-component-obj
  "`situation` with `f` applied to the lineage `name`."
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

;; Targets: `(situation -> shape id)` fns, resolved when the operation
;; applies, so they follow the lineage as it changes.

(defn remote-rect-of
  "Target: lineage `name`'s remote rect."
  [name]
  (fn [s] (:remote-rect (get-component-obj s name))))

(defn main-rect-of
  "Target: lineage `name`'s current main rect."
  [name]
  (fn [s] (:main-rect (get-component-obj s name))))

(defn copy-rect-of
  "Target: lineage `name`'s latest copy rect."
  [name]
  (fn [s] (:copy-rect (get-component-obj s name))))

(defn copy-head-of
  "Target: lineage `name`'s latest copy head."
  [name]
  (fn [s] (:copy-head (get-component-obj s name))))

(defn main-head-of
  "Target: lineage `name`'s current main head."
  [name]
  (fn [s] (:main-head (get-component-obj s name))))

(defn nested-head-under
  "Target: the first nested copy head among the children of the shape the
   target `parent` names. Found through the parent, so it still names the
   head after a swap or switch replaced it in place."
  [parent]
  (fn [s]
    (let [objects (:objects (thf/current-page (tm/file s)))]
      (->> (cfh/get-immediate-children objects (tm/target-shape-id s parent))
           (d/seek :component-id)
           (:id)))))

(defn nested-head-in
  "Target: the nested copy head directly inside lineage `name`'s current copy
   head (the copy of a level-0 nesting)."
  [name]
  (nested-head-under (copy-head-of name)))

(defn nested-head-of
  "Target: the nested copy head introduced at lineage `name`'s nesting level
   `i`, on the main side (`:nested-head` of that level)."
  [name i]
  (fn [s] (:nested-head (lineage-nesting s name i))))

(defn corresponding-shape-id
  "The id of the shape in `head-id`'s subtree (the head included) whose
   :shape-ref chain passes through `target-id`: its image in that copy."
  [situation head-id target-id]
  (self-or-descendant-corresponding-to (:objects (thf/current-page (tm/file situation)))
                                       head-id
                                       target-id))

(defn lineage-copy-rect
  "The id of lineage `name`'s latest copy rect."
  [situation name]
  (:copy-rect (get-component-obj situation name)))

(defn lineage-nth-copy-rect
  "The id of the rect of lineage `name`'s `i`-th copy, in instantiation order."
  [situation name i]
  (get-in (get-component-obj situation name) [:copies i :copy-rect]))

(defn nth-copy-rect-of
  "Target: the rect of lineage `name`'s `i`-th copy, in instantiation order."
  [name i]
  (fn [s] (lineage-nth-copy-rect s name i)))

(defn nth-copy-head-of
  "Target: the head of lineage `name`'s `i`-th copy, in instantiation order."
  [name i]
  (fn [s] (get-in (get-component-obj s name) [:copies i :copy-head])))

(defn level-rect
  "The id of the rect currently at lineage `name`'s nesting level `level`,
   found from the level's :nested-head-parent, so it holds after a swap
   replaced the head (and the rect) in place."
  [situation name level]
  (let [objects (:objects (thf/current-page (tm/file situation)))
        parent  (:nested-head-parent (lineage-nesting situation name level))
        head    ((nested-head-under (constantly parent)) situation)]
    (->> (cfh/get-children-ids objects head)
         (map #(get objects %))
         (d/seek #(= :rect (:type %)))
         (:id))))

;; ---------------------------------------------------------------------------
;; create-component — the starting operation: a component with a rect child
;; ---------------------------------------------------------------------------

(defrecord CreateComponent [name color size]
  IComponentOperation
  (op-kind [_] :assembly)

  tm/IOperation
  (apply-to [this situation]
    ;; fresh, name-scoped labels so several lineages don't clash
    (let [head-label  (keyword (str "sync-" name "-head"))
          rect-label  (keyword (str "sync-" name "-rect"))
          comp-label  (keyword (str "sync-" name "-component"))
          file'       (-> (tm/file situation)
                          (tho/add-simple-component comp-label head-label rect-label
                                                    :root-params
                                                    (when size {:width size :height size})
                                                    :child-params
                                                    (cond-> {:fills (ths/sample-fills-color
                                                                     :fill-color color)}
                                                      size (assoc :width (/ size 2)
                                                                  :height (/ size 2)))))
          head-id     (thi/id head-label)
          rect-id     (thi/id rect-label)
          comp-id     (thi/id comp-label)]
      (-> situation
          (tm/with-file file')
          (put-component-obj name
                             {:main-component-id comp-id
                              :remote-head head-id :remote-rect rect-id
                              :main-head   head-id :main-rect   rect-id
                              :size size
                              :nesting-count 0
                              :nesting-data []})
          (tm/record-application this {:component name :id comp-id})))))

(defn create-component
  "Create a component, a root frame with one rect of fill `color`, and track
   it as lineage `name`. `:size` gives the root that width and height, and
   the rect half of it
   (nesting gives the outer frames that size too); without it they all have
   the minimal default size."
  [name color & {:keys [size]}]
  (tm/assign-id (->CreateComponent name color size)))

;; ---------------------------------------------------------------------------
;; make-nested-component — wrap the lineage's component in a new outer one
;;
;; The new component's main holds a copy of the lineage's current component
;; and becomes the lineage's next component. The image of the remote rect in
;; that copy becomes the main rect, so edits to the main go one level deeper.
;; Apply it again for more levels.
;; ---------------------------------------------------------------------------

(defn- nest-in-new-outer-component
  "Add an outer board, place a copy in it with `instantiate-inner-fn`
   (`(file outer-frame-label inner-copy-label) -> file`), make the board a
   new component and advance lineage `name` one nesting level; record `op`.
   The images, in the inner copy, of `seek-rect-id` and `seek-head-id` (the
   original rect and head) become the level's nested rect and nested head."
  [situation name op seek-rect-id seek-head-id instantiate-inner-fn]
  (let [the-file     (tm/file situation)
        obj          (get-component-obj situation name)
        level        (:nesting-count obj)
        outer-frame  (keyword (str "sync-" name "-outer-" level))
        inner-copy   (keyword (str "sync-" name "-innercopy-" level))
        outer-comp   (keyword (str "sync-" name "-outercomp-" level))
        size         (:size obj)
        file'        (-> the-file
                         (tho/add-frame outer-frame (cond-> {:name "OuterFrame"}
                                                      size (assoc :width size :height size)))
                         (instantiate-inner-fn outer-frame inner-copy)
                         (thc/make-component outer-comp outer-frame))
        objects      (:objects (thf/current-page file'))
        inner-copy-id (thi/id inner-copy)
        new-main-rect (descendant-corresponding-to objects inner-copy-id seek-rect-id)
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

(defn- nest-once
  "Wrap lineage `name`'s component once in a new outer component; record `op`."
  [situation name op]
  (let [obj (get-component-obj situation name)]
    (nest-in-new-outer-component
     situation name op
     (:remote-rect obj)
     (:remote-head obj)
     (fn [file outer-frame inner-copy]
       (let [inner-label (thi/label (:main-component-id obj))]
         (thc/instantiate-component file inner-label inner-copy
                                    {:parent-label outer-frame}))))))

(defrecord MakeNestedComponent [name levels]
  IComponentOperation
  (op-kind [_] :assembly)

  tm/IOperation
  (apply-to [this situation]
    (reduce (fn [situation _] (nest-once situation name this))
            situation
            (range levels))))

(defn make-nested-component
  "Wrap lineage `name`'s component in a new outer component, whose main holds
   a copy of it; `levels` times (default 1), each wrapping the previous one."
  ([name] (make-nested-component name 1))
  ([name levels]
   (tm/assign-id (->MakeNestedComponent name levels))))

(defn nesting-depth
  "How many times lineage `name`'s component has been nested."
  [situation name]
  (:nesting-count (get-component-obj situation name)))

;; ---------------------------------------------------------------------------
;; instantiate-copy — a copy of the lineage's current component
;; ---------------------------------------------------------------------------

(defrecord InstantiateCopy [name]
  IComponentOperation
  (op-kind [_] :assembly)

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
  "Instantiate lineage `name`'s current component and track the copy's head
   and its image of the main rect."
  [name]
  (tm/assign-id (->InstantiateCopy name)))

(defn- libraries
  "The situation's files by id: the current one and the auxiliary ones."
  [situation]
  (let [the-file (tm/file situation)]
    (assoc (tm/aux-files situation) (:id the-file) the-file)))

;; ---------------------------------------------------------------------------
;; reset — reset the overrides of a copy head
;;
;; The "Reset overrides" action on the copy head bound to `target`, a nested
;; head included, through the production `generate-reset-component`. The
;; frontend interpreter runs it as a file install: the real reset event reads
;; browser globals, so it cannot run headless.
;; ---------------------------------------------------------------------------

(defrecord ResetOverrides [target]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          shape-id  (tm/target-shape-id situation target)
          page      (thf/current-page the-file)
          changes   (-> (pcb/empty-changes)
                        (cll/generate-reset-component the-file
                                                      (libraries situation)
                                                      (ctn/make-container page :page)
                                                      shape-id))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :reset shape-id})))))

(defn reset
  "Reset the overrides of the copy head bound to `target` (a role, a label or
   a `(situation -> id)` fn)."
  [target]
  (tm/assign-id (->ResetOverrides target)))

(defn reset-copy-instance
  "Reset the overrides of lineage `name`'s latest copy."
  [name]
  (reset (copy-head-of name)))

;; ---------------------------------------------------------------------------
;; detach — break the link between a copy and its component
;;
;; The "Detach instance" action on the copy head bound to `target`, through
;; `generate-detach-component`. Its first-level nested copies stay copies,
;; one nesting level up (`mem:common/component-sync-contract`).
;; ---------------------------------------------------------------------------

(defrecord Detach [target]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          changes  (cll/generate-detach-component (pcb/empty-changes)
                                                  shape-id
                                                  (:data the-file)
                                                  (:id page)
                                                  (libraries situation))
          file'    (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :detached shape-id})))))

(defn detach
  "Constructor for the detach operation: detach the copy head `target` names
   (`detach-component` on the frontend)."
  [target]
  (tm/assign-id (->Detach target)))

;; ---------------------------------------------------------------------------
;; update-main — push a copy's overrides to its main
;;
;; The "Update main component" action on the copy head bound to `target`,
;; through `generate-sync-shape-inverse`. The frontend commits the copy's and
;; the main's changes apart, by `:local-change?`; here they go as one.
;; ---------------------------------------------------------------------------

(defn- strip-local-change
  [changes]
  (let [strip (partial mapv #(dissoc % :local-change?))]
    (-> changes
        (update :redo-changes strip)
        (update :undo-changes strip))))

(defrecord UpdateMain [target]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          shape-id  (tm/target-shape-id situation target)
          page      (thf/current-page the-file)
          container (ctn/make-container page :page)
          changes   (-> (pcb/empty-changes)
                        (pcb/with-container container)
                        (cll/generate-sync-shape-inverse (:data the-file)
                                                         (libraries situation)
                                                         container
                                                         shape-id)
                        (strip-local-change))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes (when (seq (:redo-changes changes)) changes))
          (tm/record-application this {:target target :updated shape-id})))))

(defn update-main
  "Constructor for the update-main operation: push the overrides of the copy
   head `target` names to its main (`update-component-sync` on the
   frontend)."
  [target]
  (tm/assign-id (->UpdateMain target)))

;; ---------------------------------------------------------------------------
;; delete-component / restore-component
;;
;; Deleting a component deletes its main (`generate-delete-shapes`); the
;; component stays in the file, marked deleted. Restoring it
;; (`generate-restore-component`) brings the main back. The restore node asks
;; the delete node which component it deleted.
;; ---------------------------------------------------------------------------

(defrecord DeleteComponent [target]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file      (tm/file situation)
          root-id       (tm/target-shape-id situation target)
          page          (thf/current-page the-file)
          component-id  (:component-id (get (:objects page) root-id))
          [_ changes]   (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                    (:data the-file)
                                                    page
                                                    (:objects page)
                                                    #{root-id}
                                                    {})
          file'         (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :component-id component-id})))))

(defn delete-component
  "Constructor for the delete-component operation: delete the component whose
   main root `target` names (`delete-component` on the frontend)."
  [target]
  (tm/assign-id (->DeleteComponent target)))

(defn- target-id
  "The shape id `target` names, whether or not the shape still exists."
  [situation target]
  (cond
    (fn? target)                    (target situation)
    (tm/has-role? situation target) (get-in situation [:roles target])
    :else                           (thi/id target)))

(defn deleted-component-id
  "The id of the component the `delete-component` node `node` deleted, read
   from the file."
  [situation node]
  (let [root-id (target-id situation (:target node))]
    (or (->> (get-in (tm/file situation) [:data :components])
             (vals)
             (d/seek #(= root-id (:main-instance-id %)))
             (:id))
        (throw (ex-info "deleted-component-id: no component has that main root"
                        {:type ::no-component
                         :root root-id})))))

(defrecord RestoreComponent [deletion]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file     (tm/file situation)
          data         (:data the-file)
          component-id (deleted-component-id situation deletion)
          component    (get-in data [:components component-id])
          page         (get-in data [:pages-index (:main-instance-page component)])
          changes      (cll/generate-restore-component (pcb/empty-changes)
                                                       data
                                                       component-id
                                                       (:id the-file)
                                                       page
                                                       (:objects page))
          file'        (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:component-id component-id})))))

(defn restore-component
  "Constructor for the restore-component operation: restore the component
   the `delete-component` node `deletion` deleted."
  [deletion]
  (tm/assign-id (->RestoreComponent deletion)))

;; ---------------------------------------------------------------------------
;; duplicate — duplicate a shape in place
;;
;; The "Duplicate" action on the shape bound to `target`, with no offset,
;; through `generate-duplicate-changes`. `duplicate-head-of` finds the new
;; subtree in the file.
;; ---------------------------------------------------------------------------

(defn- new-subtree-head
  "The id of the shape in `page-after` that is not in `page-before` but whose
   parent is: the head of the one subtree added between them."
  [page-before page-after]
  (let [before (:objects page-before)]
    (->> (vals (:objects page-after))
         (filter #(and (not (contains? before (:id %)))
                       (contains? before (:parent-id %))))
         (map :id)
         (first))))

(defrecord Duplicate [target]
  IComponentOperation
  (op-kind [_] :user)

  IAppliedResult
  (applied-result [_ file-before file-after]
    {:new-head (new-subtree-head (thf/current-page file-before)
                                 (thf/current-page file-after))})

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          changes  (-> (pcb/empty-changes nil)
                       (cll/generate-duplicate-changes objects
                                                       page
                                                       #{shape-id}
                                                       (gpt/point 0 0)
                                                       (libraries situation)
                                                       (:data the-file)
                                                       (:id the-file))
                       (cll/generate-duplicate-changes-update-indices objects #{shape-id}))
          file'    (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this (merge {:target target}
                                             (applied-result this the-file file')))))))

(defn duplicate
  "Constructor for the duplicate operation: duplicate the shape `target`
   names in place (`duplicate-shapes` on the frontend)."
  [target]
  (tm/assign-id (->Duplicate target)))

(defn duplicate-head-of
  "Target: the head of the subtree that the `duplicate` node `node` added."
  [node]
  (fn [s] (:new-head (tm/node-data s node))))

;; ---------------------------------------------------------------------------
;; nest-copy — move an existing copy into a main
;;
;; Moves the copy head bound to `copy` under the shape bound to `into`, as its
;; last child, through `generate-relocate`, as dragging it in the layers
;; panel does. Unlike `make-nested-component` it is a user action, so the
;; receiving main's copies sync and the step goes through undo.
;; ---------------------------------------------------------------------------

(defn nest-target-index
  "The index `nest-copy` moves a copy to under the shape `parent-id`: after
   its last child."
  [situation parent-id]
  (count (:shapes (tm/shape-by-id situation parent-id))))

(defrecord NestCopy [copy into]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          copy-id   (tm/target-shape-id situation copy)
          parent-id (tm/target-shape-id situation into)
          page      (thf/current-page the-file)
          changes   (cls/generate-relocate (-> (pcb/empty-changes nil)
                                               (pcb/with-page-id (:id page))
                                               (pcb/with-objects (:objects page)))
                                           parent-id
                                           (nest-target-index situation parent-id)
                                           #{copy-id})
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:copy copy :into into})))))

(defn nest-copy
  "Constructor for the nest-copy operation: move the copy head `copy` names
   under the shape `into` names, as its last child."
  [copy into]
  (tm/assign-id (->NestCopy copy into)))

;; ---------------------------------------------------------------------------
;; swap-shape — swap a copy head for another component
;;
;; The "Swap component" action on the copy head bound to `target`, through
;; `generate-component-swap-in-place` (`component-swap` on the frontend). The
;; new head keeps the old one's id, so a target that named it still does.
;; ---------------------------------------------------------------------------

(defn swap-target-id
  "The component id `swap-shape`'s `to` names: a lineage name (a string) or a
   component label (a keyword)."
  [situation to]
  (if (keyword? to)
    (thi/id to)
    (lineage-component-id situation to)))

(defrecord SwapShape [target to keep-touched?]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file      (tm/file situation)
          page          (thf/current-page the-file)
          shape         (get (:objects page) (tm/target-shape-id situation target))
          [_ changes _] (clv/generate-component-swap-in-place (pcb/empty-changes nil (:id page))
                                                              page
                                                              (libraries situation)
                                                              (:data the-file)
                                                              shape
                                                              (swap-target-id situation to)
                                                              (boolean keep-touched?))
          file'         (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :to to :keep-touched? keep-touched?})))))

(defn swap-shape
  "Swap the copy head `target` names for the component `to` names.
   `keep-touched?` (default false) keeps the head's overrides."
  [target to & {:keys [keep-touched?] :or {keep-touched? false}}]
  (tm/assign-id (->SwapShape target to keep-touched?)))

(defn swap-component
  "Swap the nested head at lineage `name`'s nesting level `level` for lineage
   `target`'s component, as `swap-shape` does."
  [name level target & {:keys [keep-touched?] :or {keep-touched? false}}]
  (swap-shape (nested-head-of name level) target :keep-touched? keep-touched?))

;; ---------------------------------------------------------------------------
;; move-to-cell — move a child of a grid board to another cell
;;
;; Moves the child bound to `child` to the cell at `row`, `column` of its grid
;; board `board`: the board gets new `:layout-grid-cells`, as a canvas drop
;; commits.
;; ---------------------------------------------------------------------------

(defn move-to-cell-fn
  "The update of a grid board that moves its child `child-id` to the cell at
   `row`, `column`. Public so the frontend interpreter makes the same update."
  [child-id row column]
  (fn [board objects]
    (-> board
        (ctl/free-cell-shapes [child-id])
        (ctl/push-into-cell [child-id] row column)
        (ctl/assign-cells objects))))

(defrecord MoveToCell [board child row column]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          board-id (tm/target-shape-id situation board)
          child-id (tm/target-shape-id situation child)
          page     (thf/current-page the-file)
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{board-id}
                                               (move-to-cell-fn child-id row column)
                                               (:objects page)
                                               {:with-objects? true})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:board board :child child :row row :column column})))))

(defn move-to-cell
  "Constructor for the grid cell move: move the child bound to `child` to the
   cell at `row`, `column` of the grid board bound to `board`."
  [board child row column]
  (tm/assign-id (->MoveToCell board child row column)))

;; ===========================================================================
;; Variants
;;
;; A variant set is a container of member components told apart by the value
;; of one property. A switch is a keep-touched swap to the member with
;; another value. Building the set and nesting a member are assembly steps;
;; the switch is a user action.
;; ===========================================================================

(def ^:private variant-property-name
  "The name of the one property of the variant sets."
  "Property 1")

(defn variant-set
  "The variant set `set-name` that `make-variant-container` recorded:
   `{:variant-id :container-label :members}`."
  [situation set-name]
  (or (tm/get-var situation [::variant-set set-name])
      (throw (ex-info "variant-set: no such set (was make-variant-container applied?)"
                      {:set set-name}))))

(defn variant-member
  "The member of set `set-name` with value `value`: `{:value :component-label
   :component-id :root-label :rect-label :rect-id}`. Throws unless exactly one
   matches."
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

(defn variant-member-component-id
  "The component id of set `set-name`'s member with value `value`."
  [situation set-name value]
  (:component-id (variant-member situation set-name value)))

;; ---------------------------------------------------------------------------
;; make-variant-container — build a variant set
;;
;; Built with test helpers, as `thv/add-variant` does: a variant container
;; frame whose children are the member roots, each a component with the set's
;; variant id and the value given to it. The app's combine is asynchronous
;; and picks the values itself. `members` is a vector of [value color], color
;; the fill of the member's rect.
;; ---------------------------------------------------------------------------

(defrecord MakeVariantContainer [name members size]
  IComponentOperation
  (op-kind [_] :assembly)

  tm/IOperation
  (apply-to [this situation]
    (let [container-label (keyword (str "sync-" name "-vcontainer"))
          ;; the container's id exists once the container does
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
                             (ths/add-sample-shape root-label
                                                   (cond-> {:type :frame
                                                            :parent-label container-label
                                                            :variant-id variant-id
                                                            :variant-name value}
                                                     size (assoc :width size :height size)))
                             (ths/add-sample-shape rect-label
                                                   (cond-> {:parent-label root-label
                                                            :fills (ths/sample-fills-color :fill-color color)}
                                                     size (assoc :width (/ size 2) :height (/ size 2))))
                             ;; the component gets the variant data too
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
  "Build a variant set `name` with one member per [value color] of
   `members`. `:size` sizes the member roots as in `create-component`."
  [name members & {:keys [size]}]
  (tm/assign-id (->MakeVariantContainer name members size)))

;; ---------------------------------------------------------------------------
;; make-nested-component-with-variant — nest a variant member
;;
;; As `make-nested-component`, but the copy in the new outer main is one of a
;; variant member, which becomes the lineage's original: later plain nestings
;; follow its image.
;; ---------------------------------------------------------------------------

(defrecord MakeNestedComponentWithVariant [name set-name value]
  IComponentOperation
  (op-kind [_] :assembly)

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
          (update-component-obj name #(assoc % :remote-head root-id :remote-rect rect-id))))))

(defn make-nested-component-with-variant
  "Wrap a copy of the member of set `set-name` with value `value` in a new
   outer component of lineage `name`."
  [name set-name value]
  (tm/assign-id (->MakeNestedComponentWithVariant name set-name value)))

;; ---------------------------------------------------------------------------
;; switch-variant — switch a variant copy head to a sibling member
;;
;; Switches the variant copy head bound to `target` to the member whose
;; property has `value`, through `find-switch-target` and a keep-touched
;; `generate-component-swap-in-place` (`variants-switch` on the frontend). The
;; event's follow-ups (token propagation, WASM text resize, layout update) are
;; frontend code, left out here.
;; ---------------------------------------------------------------------------

(defrecord SwitchVariant [target value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          page      (thf/current-page the-file)
          objects   (:objects page)
          libraries (libraries situation)
          shape     (get objects (tm/target-shape-id situation target))
          component (clv/find-switch-target libraries shape 0 value)]
      (cond
        ;; the value is already `value`, or no sibling has it: nothing happens
        (nil? component)
        (-> situation
            (record-changes nil)
            (tm/record-application this {:target-component nil}))

        (clv/swap-nesting-loop? objects
                                shape
                                (get-in libraries [(:component-file shape) :data])
                                (:id component))
        (throw (ex-info "switch-variant: the switch would nest a component inside itself"
                        {:type ::switch-nesting-loop
                         :shape (:id shape)
                         :component (:id component)}))

        :else
        (let [ldata         (get-in libraries [(:component-file shape) :data])
              [_ changes _] (clv/generate-component-swap-in-place
                             (pcb/empty-changes nil (:id page))
                             page libraries ldata shape (:id component) true)
              file'         (thf/apply-changes the-file changes :validate? false)]
          (-> situation
              (tm/with-file file')
              (record-changes changes)
              (tm/record-application this {:target-component (:id component)})))))))

(defn switch-variant
  "Switch the variant copy head `target` names to the member whose property
   has value `value`."
  [target value]
  (tm/assign-id (->SwitchVariant target value)))



;; ---------------------------------------------------------------------------
;; sync-from-library — pull a linked library's changes into the consuming file
;;
;; The library update action, accepted by the user: the copies in the current
;; file sync from the situation's auxiliary library file, through
;; `generate-sync-file-changes` (`sync-file` on the frontend). The component
;; watcher never crosses files.
;; ---------------------------------------------------------------------------

(defn library-id
  "The id of the library file of a cross-file situation, its only auxiliary
   file."
  [situation]
  (-> situation tm/aux-files keys first))

(defrecord SyncFromLibrary []
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          file-id  (:id the-file)
          lib-id   (library-id situation)
          changes  (-> (pcb/empty-changes)
                       (cll/generate-sync-file-changes nil
                                                       :components
                                                       file-id
                                                       nil ; the whole library
                                                       lib-id
                                                       (libraries situation)
                                                       file-id))
          file'    (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:library-id lib-id})))))

(defn sync-from-library
  "Constructor for the library update of the current file from the
   situation's library."
  []
  (tm/assign-id (->SyncFromLibrary)))


;; ---------------------------------------------------------------------------
;; undo — revert the latest operations
;;
;; Runs on a runner's undo stack: the workspace's (`dwu/undo`) in the frontend
;; interpreter, the recorded changes in the pure runner. `apply-to` alone has
;; no stack and throws.
;; ---------------------------------------------------------------------------

(defrecord Undo [times]
  IComponentOperation
  (op-kind [_] :undo)

  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "Undo has no `apply-to` realisation: it needs an undo stack. "
                         "Run it through the pure runner (comp.runner) or the frontend "
                         "interpreter.")
                    {:type ::undo-is-event-op}))))

(defn undo
  "Constructor for the undo node: undo the latest `times` (default 1) user
   operations, each with the syncs it caused."
  ([] (undo 1))
  ([times]
   (tm/assign-id (->Undo times))))

;; ---------------------------------------------------------------------------
;; redo — apply again the latest undone operation(s)
;;
;; Runs on a runner's redo stack, which a new user operation empties.
;; ---------------------------------------------------------------------------

(defrecord Redo []
  IComponentOperation
  (op-kind [_] :redo)

  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "Redo has no `apply-to` realisation: it needs a redo stack. "
                         "Run it through the pure runner (comp.runner) or the frontend "
                         "interpreter.")
                    {:type ::redo-is-event-op}))))

(defn redo
  "Constructor for the redo node: apply again the latest undone operation."
  []
  (tm/assign-id (->Redo)))

;; ---------------------------------------------------------------------------
;; undo-before-sync — undo an edit before its sync runs
;;
;; Changes `property` of the shape bound to `target` and undoes it at once, in
;; one step, so the undo commits while the component watcher still holds the
;; edit's sync (it inspects commits in batches, a timer turn later). Only the
;; frontend has that window, so the node is frontend-only.
;; ---------------------------------------------------------------------------

(defrecord UndoBeforeSync [target property value]
  IComponentOperation
  (op-kind [_] :frontend-only)

  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "UndoBeforeSync has no `apply-to` realisation: only the "
                         "frontend interpreter has a pending sync to undo before.")
                    {:type ::undo-before-sync-is-frontend-only}))))

(defn undo-before-sync
  "Constructor for the undo-before-sync node: set `property` of the shape
   `target` names to `value` and undo it while its sync is still pending."
  [target property value]
  (tm/assign-id (->UndoBeforeSync target property value)))


;; ---------------------------------------------------------------------------
;; add-child — add a shape
;;
;; Adds a shape labelled `new-label` under the shape labelled `parent`,
;; through `prepare-add-shape`, as the frontend `add-shape` event does.
;; ---------------------------------------------------------------------------

(defn child-shape
  "The shape `add-child` adds under the shape `parent`: a sample shape
   labelled `new-label` with `shape-params`. Public so the frontend
   interpreter adds the same shape."
  [parent new-label shape-params]
  (-> (ths/sample-shape new-label (or shape-params {}))
      (assoc :parent-id (:id parent)
             :frame-id  (if (cfh/frame-shape? parent)
                          (:id parent)
                          (:frame-id parent)))))

(defrecord AddChild [parent new-label shape-params]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file    (tm/file situation)
          parent-id   (tm/resolve-shape-id situation parent)
          page        (thf/current-page the-file)
          objects     (:objects page)
          ;; registers `new-label`
          shape       (child-shape (get objects parent-id) new-label shape-params)
          [_ changes] (cfsh/prepare-add-shape (pcb/empty-changes nil (:id page))
                                              shape
                                              objects)
          file'       (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:parent parent :new-label new-label})))))

(defn add-child
  "Constructor for the add-child node: add a shape labelled `new-label` under
   the shape labelled `parent`."
  [parent new-label & {:as shape-params}]
  (tm/assign-id (->AddChild parent new-label shape-params)))


;; ---------------------------------------------------------------------------
;; remove-child — delete a shape
;;
;; Deletes the shape labelled `target`, in a main or a copy, through
;; `generate-delete-shapes`.
;; ---------------------------------------------------------------------------

(defrecord RemoveChild [target]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
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
          (record-changes changes)
          (tm/record-application this {:target target})))))

(defn remove-child
  "Constructor for the remove-child node: delete the shape labelled
   `target`."
  [target]
  (tm/assign-id (->RemoveChild target)))


;; ---------------------------------------------------------------------------
;; move-child — reorder a shape
;;
;; Moves the shape labelled `target` to index `to-index` under the shape
;; labelled `parent`, through `generate-relocate`.
;; ---------------------------------------------------------------------------

(defrecord MoveChild [target parent to-index]
  IComponentOperation
  (op-kind [_] :user)

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
          (record-changes changes)
          (tm/record-application this {:target target :parent parent :to-index to-index})))))

(defn move-child
  "Constructor for the move-child node: move the shape labelled `target` to
   `to-index` under the shape labelled `parent`."
  [target parent to-index]
  (tm/assign-id (->MoveChild target parent to-index)))

(defprotocol IStructuralCheck
  "The shapes a structural node's effect involves, for the test to assert on."
  (added-shape [node situation]
    "The live shape this node added.")
  (materialized-instance-child [node situation container-shape]
    "The child of `container-shape` whose :shape-ref is this node's added
     shape, or nil."))

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
