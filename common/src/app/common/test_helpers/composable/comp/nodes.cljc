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
   [app.common.files.tokens :as cfo]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.duplicate :as cld]
   [app.common.logic.instances :as cli]
   [app.common.logic.libraries :as cll]
   [app.common.logic.placement :as clp]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.logic.variants :as clv]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]))

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
    :name    (assoc shape :name value)
    :margin  (assoc shape
                    :layout-item-margin-type :simple
                    :layout-item-margin {:m1 value :m2 value :m3 value :m4 value})
    (throw (ex-info (str "set-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity, :name, :margin)")
                    {:property property}))))

(defn- read-property
  "The comparable value of `property` in `shape`."
  [shape property]
  (case property
    :fills   (-> shape :fills first :fill-color)
    :opacity (:opacity shape)
    :name    (:name shape)
    :margin  (when (= :simple (:layout-item-margin-type shape))
               (let [{:keys [m1 m2 m3 m4]} (:layout-item-margin shape)]
                 (when (= m1 m2 m3 m4) m1)))
    (throw (ex-info (str "read-property: unsupported property " (pr-str property)
                         " (supported: :fills, :opacity, :name, :margin)")
                    {:property property}))))

(defn- applied-value*
  "The value a change of `property` to `value` leaves: a rename cleans the
   path, as the layers panel does."
  [property value]
  (if (= :name property) (cpn/clean-path value) value))

(defrecord ChangeProperty [target property value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          shape    (get-in page [:objects shape-id])
          value'   (applied-value* property value)
          changes  (cond-> (cls/generate-update-shapes
                            (pcb/empty-changes nil (:id page))
                            #{shape-id}
                            (fn [shape] (set-property shape property value'))
                            (:objects page)
                            {})
                     ;; renaming a main root renames its component too
                     (and (= :name property) (ctk/main-instance? shape))
                     (cll/generate-rename-component (:component-id shape) value' (:data the-file)))
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
  (applied-value [node] (applied-value* (:property node) (:value node)))
  (has-property-of [node shape]
    (= (applied-value node) (read-property shape (:property node)))))

;; ---------------------------------------------------------------------------
;; edit-text — edit the content of a text shape
;;
;; An edit of a text as the text editor commits it when the edit ends: a new
;; `:content`, and a new name when the text still has the name generated from
;; its old content. The edit is one of
;;   :text        the characters of the first span;
;;   :font-size   the font size of the first span;
;;   :fills       the fills of all of the text (select all, then a color);
;;   :font-family the font family of all of the text;
;;   :content     the whole text, written in the notation of
;;                `app.common.test-helpers.texts`.
;; The frontend interpreter commits it through
;; `v2-update-text-shape-content`, as the editor does on blur.
;; ---------------------------------------------------------------------------

(def ^:private first-span [:content :children 0 :children 0 :children 0])

(def ^:private text-edits #{:text :font-size :fills :font-family :content})

(defn- generated-name
  "The name the text editor gives a text after its `content`."
  [content]
  (txt/generate-shape-name (txt/content->text content)))

(defn- restyle-all
  [content attrs]
  (txt/transform-nodes (some-fn txt/is-text-node? txt/is-paragraph-node?) #(merge % attrs) content))

(defn text-edit-commit
  "What the text editor commits when the `edit` (see `text-edits`) of
   `shape` becomes `value`: `:content`, and `:name` when the editor renames
   the shape after its text, which it does when the shape still has the name
   generated from its old content. Public so the frontend interpreter commits
   the same edit."
  [shape edit value]
  (when-not (contains? text-edits edit)
    (throw (ex-info (str "text-edit-commit: unsupported edit " (pr-str edit)
                         " (supported: " (pr-str text-edits) ")")
                    {:edit edit})))
  (let [content (case edit
                  (:text :font-size)
                  (assoc-in (:content shape) (conj (subvec first-span 1) edit) value)

                  :fills
                  (restyle-all (:content shape) {:fills value})

                  :font-family
                  (txt/transform-nodes (some-fn txt/is-text-node? txt/is-paragraph-node?)
                                       #(assoc %
                                               :font-family value
                                               :font-id (get thtx/font-ids value)
                                               :font-variant-id (thtx/variant-id value
                                                                                 (d/parse-integer (:font-weight %))
                                                                                 (= "italic" (:font-style %))))
                                       (:content shape))

                  :content
                  (thtx/content value))]
    (cond-> {:content content}
      (= (:name shape) (generated-name (:content shape)))
      (assoc :name (generated-name content)))))

(defn edit-text-content
  "`shape` after the text editor's commit of `text-edit-commit`."
  [shape edit value]
  (merge shape (text-edit-commit shape edit value)))

(defn text-span-value
  "The `edit` (see `text-edits`) of `shape`: of its first span, or for
   `:content` its whole text in the notation of `app.common.test-helpers.texts`."
  [shape edit]
  (if (= edit :content)
    (thtx/view (:content shape))
    (get-in shape (conj first-span edit))))

(defrecord EditText [target edit value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          ;; as the editor's commit, the name that follows the content is a
          ;; derived write
          changes  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               #{shape-id}
                                               #(edit-text-content % edit value)
                                               (:objects page)
                                               {:ignore-touched #{:name}})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :edit edit :value value})))))

(defn edit-text
  "Constructor for the text edit operation: set the `edit` (see
   `text-edits`) of the text bound to `target` to `value`. On the frontend it
   dispatches the real `v2-update-text-shape-content`."
  [target edit value]
  (tm/assign-id (->EditText target edit value)))

;; ---------------------------------------------------------------------------
;; Geometry operations
;;
;; The frontend interpreter dispatches the sidebar events. The pure runner
;; uses the same constraints and placement logic for resize and rotation.
;; MoveShape still needs the frontend position event.

(defn transform-changes
  "Apply a transform through constraints and the shared placement step."
  [page shape modifiers]
  (let [objects (:objects page)
        tree (gm/set-objects-modifiers {(:id shape) {:modifiers modifiers}} objects)
        transform (clp/modifiers-transform tree)]
    (cls/generate-update-shapes
     (pcb/empty-changes nil (:id page)) (keys tree) transform objects
     {:ignore-tree (clp/calculate-ignore-tree objects (keys tree) transform #{(:id shape)})
      :attrs clp/transform-attrs})))

(defrecord Rotate [target angle]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          shape    (get objects shape-id)
          center   (gsh/shape->center shape)
          changes  (transform-changes page shape (ctm/rotation-modifiers shape center angle))
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
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          objects  (:objects page)
          shape    (get objects shape-id)
          changes  (transform-changes page shape (ctm/change-size shape nil value))
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
  (applied-value [node]
    (cond-> (:value node)
      (= :content (:edit node)) (thtx/normalize)))
  (has-property-of [node shape]
    (= (applied-value node) (text-span-value shape (:edit node)))))

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
;; head included, through the production `generate-reset-component`
;; (`reset-component` on the frontend).
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
          changes  (cli/generate-detach-component (pcb/empty-changes)
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
;; the main's changes apart (`split-local-changes`); here they go as one, in
;; the order the sync built them.
;; ---------------------------------------------------------------------------

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
                        (cll/generate-sync-shape-inverse (libraries situation)
                                                         container
                                                         shape-id)
                        (cf/route-update-main (:id the-file) (:component-file (get-in page [:objects shape-id]))))
          situation (cf/apply-changes situation changes)]
      (-> situation
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
          changes      (cli/generate-restore-component (pcb/empty-changes)
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
   parent is, outside component copies. Sync can add matching subtrees in
   copies too; only the user's new subtree identifies the duplicate."
  [page-before page-after]
  (let [before (:objects page-before)
        heads  (->> (vals (:objects page-after))
                    (filter #(and (not (contains? before (:id %)))
                                  (contains? before (:parent-id %))
                                  (empty? (ctn/get-parent-copy-heads (:objects page-after)
                                                                     (get-in page-after [:objects (:parent-id %)])))))
                    (map :id))]
    (when-not (= 1 (count heads))
      (throw (ex-info (str "Expected one new subtree, found " (count heads))
                      {:type ::new-subtree-head :heads heads})))
    (first heads)))

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
          _        (when-not (ctk/allow-duplicate? objects (get objects shape-id))
                     ;; the frontend's `duplicate-shapes` drops it and does nothing
                     (throw (ex-info "The shape cannot be duplicated (inside a copy)"
                                     {:type ::duplicate-refused :target target})))
          changes  (-> (pcb/empty-changes nil)
                       (cld/generate-duplicate-changes objects
                                                       page
                                                       #{shape-id}
                                                       (gpt/point 0 0)
                                                       (libraries situation)
                                                       (:data the-file)
                                                       (:id the-file))
                       (cld/generate-duplicate-changes-update-indices objects #{shape-id}))
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
;; paste-to-root — copy a shape and paste it at the page root
;;
;; As the clipboard does: the copy advances the shape refs of the nested copy
;; heads in the copied subtree by the copy levels they leave, merging the
;; touched groups of the ref chain they skip (`copy-selected`), and the paste
;; duplicates the copied subtree under the root (`paste-shapes`, through
;; `generate-duplicate-changes`).
;; ---------------------------------------------------------------------------

(defn- copied-objects
  "The subtree of `id` as the clipboard copies it."
  [file page libraries id]
  (cld/advance-copied-objects file
                              page
                              libraries
                              (into {} (map (juxt :id identity))
                                    (cfh/get-children-with-self (:objects page) id))
                              [id]))

(defrecord PasteToRoot [target]
  IComponentOperation
  (op-kind [_] :user)

  IAppliedResult
  (applied-result [_ file-before file-after]
    {:new-head (new-subtree-head (thf/current-page file-before)
                                 (thf/current-page file-after))})

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          page      (thf/current-page the-file)
          libs      (libraries situation)
          id        (tm/target-shape-id situation target)
          copied    (-> (copied-objects the-file page libs id)
                        (update id assoc :parent-id uuid/zero :frame-id uuid/zero))
          changes   (-> (pcb/empty-changes nil (:id page))
                        (cld/generate-duplicate-changes (merge (:objects page) copied)
                                                        page
                                                        #{id}
                                                        (gpt/point 0 0)
                                                        libs
                                                        (:data the-file)
                                                        (:id the-file)))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this (merge {:target target}
                                             (applied-result this the-file file')))))))

(defn paste-to-root
  "Copy the shape `target` names and paste it at the page root."
  [target]
  (tm/assign-id (->PasteToRoot target)))

;; ---------------------------------------------------------------------------
;; nest-copy — move an existing copy into a main
;;
;; Moves the copy head bound to `copy` under the shape bound to `into`, as its
;; last child, through `generate-relocate`, as dragging it in the layers
;; panel does; with a `cell` ([row column] of a grid `into`), into that cell,
;; as a canvas drop does. Unlike `make-nested-component` it is a user action, so the
;; receiving main's copies sync and the step goes through undo.
;; ---------------------------------------------------------------------------

(defn nest-target-index
  "The index `nest-copy` moves a copy to under the shape `parent-id`: after
   its last child."
  [situation parent-id]
  (count (:shapes (tm/shape-by-id situation parent-id))))

(defrecord NestCopy [copy into cell]
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
                                               (pcb/with-objects (:objects page))
                                               (pcb/with-library-data (:data the-file)))
                                           parent-id
                                           (nest-target-index situation parent-id)
                                           #{copy-id}
                                           :cell cell)
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:copy copy :into into :cell cell})))))

(defn nest-copy
  "Constructor for the nest-copy operation: move the copy head `copy` names
   under the shape `into` names, as its last child, or into the grid cell
   `cell` ([row column]) when given."
  [copy into & {:keys [cell]}]
  (tm/assign-id (->NestCopy copy into cell)))

;; ---------------------------------------------------------------------------
;; swap-shape — swap a copy head for another component
;;
;; The "Swap component" action on the copy head bound to `target`, through
;; `generate-component-swap-in-place` (`component-swap` on the frontend). The
;; new head keeps the old one's id, so a target that named it still does.
;; ---------------------------------------------------------------------------

(defn swap-target-id
  "Resolve a lineage name, component label, or situation-to-component fn."
  [situation to]
  (cond
    (fn? to) (to situation)
    (keyword? to) (thi/id to)
    :else (lineage-component-id situation to)))

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
;; property at position `pos` has `value`, through `find-switch-target` and a
;; keep-touched `generate-component-swap-in-place` (`variants-switch` on the
;; frontend). The
;; event's follow-ups (token propagation, WASM text resize, layout update) are
;; frontend code, left out here.
;; ---------------------------------------------------------------------------

(defn switch-pos
  "The property position the `switch-variant` node `op` switches by."
  [situation {:keys [pos]}]
  (if (fn? pos) (pos situation) pos))

(defn switch-value
  "The property value the `switch-variant` node `op` switches to."
  [situation {:keys [value]}]
  (if (fn? value) (value situation) value))

(defrecord SwitchVariant [target value pos]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          page      (thf/current-page the-file)
          objects   (:objects page)
          libraries (libraries situation)
          shape     (get objects (tm/target-shape-id situation target))
          component (clv/find-switch-target libraries shape
                                            (switch-pos situation this)
                                            (switch-value situation this))]
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
   at position `:pos` (default 0) has value `value`. Both may be
   `(situation -> x)` fns, for sets an action built."
  [target value & {:keys [pos] :or {pos 0}}]
  (tm/assign-id (->SwitchVariant target value pos)))

;; ---------------------------------------------------------------------------
;; Variant properties — add, remove, rename, reorder a property of a set, set
;; the value or the error of a member, rename a member in the layers panel,
;; or remove the properties no member gives a value
;;
;; The edits of the design tab's variant properties and of the layers panel,
;; through the `app.common.logic.variant-properties` generators (the `dwv`
;; events on the frontend). `variant-property-call` resolves an edit to the generator's
;; arguments, which the frontend events take too. A member is named by the
;; value it was built with (`variant-member`), whatever its value now.
;; ---------------------------------------------------------------------------

(defn variant-set-id
  "The variant id of set `set-name`."
  [situation set-name]
  (:variant-id (variant-set situation set-name)))

(defn member-component
  "The component of set `set-name`'s member built with value `value`, read
   from the file."
  [situation set-name value]
  (ctkl/get-component (:data (tm/file situation))
                      (variant-member-component-id situation set-name value)))

(defn component-properties
  "The variant properties of the component `component-id`, as
   `[[name value] ...]`."
  [situation component-id]
  (->> (ctkl/get-component (:data (tm/file situation)) component-id)
       :variant-properties
       (mapv (juxt :name :value))))

(defn member-properties
  "The variant properties of set `set-name`'s member built with value `value`,
   as `[[name value] ...]`."
  [situation set-name value]
  (component-properties situation (variant-member-component-id situation set-name value)))

(defn member-root
  "The main root of set `set-name`'s member built with value `value`."
  [situation set-name value]
  (tm/shape-by-id situation (thi/id (:root-label (variant-member situation set-name value)))))

(defn variant-property-call
  "The edit of `op` as `[edit & args]`, the arguments the generator and the
   frontend event of `edit` take after the changes."
  [situation {:keys [set-name edit params]}]
  (let [variant-id (variant-set-id situation set-name)]
    (case edit
      :add       [:add variant-id (select-keys params [:property-name :property-value])]
      :remove    [:remove variant-id (:pos params)]
      :rename    [:rename variant-id (:pos params) (:name params)]
      :reorder   [:reorder variant-id (:from params) (:to params)]
      :set-value [:set-value
                  (variant-member-component-id situation set-name (:member params))
                  (:pos params)
                  (:value params)]
      :rename-in-layers [:rename-in-layers
                         (variant-member-component-id situation set-name (:member params))
                         variant-id
                         (:name params)]
      :remove-empty [:remove-empty variant-id]
      :error        [:error
                     (variant-member-component-id situation set-name (:member params))
                     (:value params)])))

(defn- variant-property-changes
  [changes [edit & args]]
  (case edit
    :add       (let [[variant-id opts] args]
                 (clvp/generate-add-new-property changes variant-id opts))
    :remove    (apply clvp/generate-remove-property changes args)
    :rename    (apply clvp/generate-update-property-name changes args)
    :reorder   (apply clvp/generate-reorder-variant-properties changes args)
    :set-value (apply clvp/generate-update-property-value changes args)
    :rename-in-layers (apply clvp/generate-rename-member changes args)
    :remove-empty (apply clvp/generate-remove-empty-properties changes args)
    :error (apply clvp/generate-set-variant-error changes args)))

(defrecord EditVariantProperty [set-name edit params]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [the-file (tm/file situation)
          page     (thf/current-page the-file)
          changes  (-> (pcb/empty-changes nil (:id page))
                       (pcb/with-library-data (:data the-file))
                       (pcb/with-objects (:objects page))
                       (variant-property-changes (variant-property-call situation this)))
          file'    (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes (when (seq (:redo-changes changes)) changes))
          (tm/record-application this {:set-name set-name :edit edit :params params})))))

(defn add-variant-property
  "Add a property `:name` to set `set-name`, with value `:value` on every
   member (empty when not given)."
  [set-name & {:keys [name value]}]
  (tm/assign-id (->EditVariantProperty set-name :add (cond-> {}
                                                       name  (assoc :property-name name)
                                                       value (assoc :property-value value)))))

(defn remove-variant-property
  "Remove the property at position `pos` from set `set-name`."
  [set-name pos]
  (tm/assign-id (->EditVariantProperty set-name :remove {:pos pos})))

(defn rename-variant-property
  "Rename the property at position `pos` of set `set-name` to `name`."
  [set-name pos name]
  (tm/assign-id (->EditVariantProperty set-name :rename {:pos pos :name name})))

(defn reorder-variant-properties
  "Move the property at position `from` of set `set-name` to the gap before
   position `to`."
  [set-name from to]
  (tm/assign-id (->EditVariantProperty set-name :reorder {:from from :to to})))

(defn rename-variant-in-layers
  "Rename set `set-name`'s member built with value `member` in the layers
   panel to the properties formula of `properties` (`[[name value] ...]`;
   the ones with no value left out), which also removes the properties no
   member gives a value (`generate-rename-member`)."
  [set-name member properties]
  (tm/assign-id
   (->EditVariantProperty set-name :rename-in-layers
                          {:member member
                           :name   (ctv/properties-map->formula
                                    (map (fn [[name value]] {:name name :value value}) properties))})))

(defn remove-empty-properties
  "Remove the properties of set `set-name` whose value is empty in every
   member (`generate-remove-empty-properties`)."
  [set-name]
  (tm/assign-id (->EditVariantProperty set-name :remove-empty {})))

(defn set-variant-error
  "Set the error of set `set-name`'s member built with value `member` to
   `value`, nil to clear it (`generate-set-variant-error`)."
  [set-name member value]
  (tm/assign-id (->EditVariantProperty set-name :error {:member member :value value})))

(defn set-variant-property-value
  "Set the value of the property at position `pos` of set `set-name`'s member
   built with value `member` to `value`."
  [set-name member pos value]
  (tm/assign-id (->EditVariantProperty set-name :set-value
                                       {:member member :pos pos :value value})))

;; ---------------------------------------------------------------------------
;; add-variant — add a member to a variant set
;;
;; The "add variant" action on a member: a duplicate of its component joins
;; the set, its last property set to the next "Value n", through
;; `clv/generate-add-new-variant` (`add-new-variant` on the frontend, which
;; also places the new main in the container and selects it).
;; ---------------------------------------------------------------------------

(defn- new-component-id
  "The id of the component that `file-after` has and `file-before` has not."
  [file-before file-after]
  (let [before (set (map :id (ctkl/components-seq (:data file-before))))]
    (->> (ctkl/components-seq (:data file-after))
         (map :id)
         (remove before)
         (first))))

(defrecord AddVariant [set-name member]
  IComponentOperation
  (op-kind [_] :user)

  IAppliedResult
  (applied-result [_ file-before file-after]
    {:component-id (new-component-id file-before file-after)})

  tm/IOperation
  (apply-to [this situation]
    (let [the-file  (tm/file situation)
          page      (thf/current-page the-file)
          root      (member-root situation set-name member)
          component (member-component situation set-name member)
          changes   (-> (pcb/empty-changes nil (:id page))
                        (pcb/with-library-data (:data the-file))
                        (pcb/with-objects (:objects page))
                        (clv/generate-add-new-variant root
                                                      (:variant-id component)
                                                      (uuid/next)
                                                      (uuid/next)
                                                      (dec (count (:variant-properties component)))))
          file'     (thf/apply-changes the-file changes :validate? false)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this (merge {:set-name set-name :member member}
                                             (applied-result this the-file file')))))))

(defn add-variant
  "Add a member to set `set-name`, a duplicate of its member built with value
   `member`."
  [set-name member]
  (tm/assign-id (->AddVariant set-name member)))

(defn added-variant-component-id
  "The id of the component that the `add-variant` node `node` added."
  [situation node]
  (:component-id (tm/node-data situation node)))

;; ---------------------------------------------------------------------------
;; Variant actions with no common generator (frontend only)
;;
;; Transform a component into a variant, combine components as variants,
;; rename a variant, and the shortcuts that add a variant to selected
;; members. Their logic is in the `dwv` events, so the frontend
;; interpreter sends them and the pure runner rejects them. A set these
;; actions build has no `variant-set` record: read it from the file
;; (`component-of`, `variant-component-ids`).
;; ---------------------------------------------------------------------------

(defrecord VariantAction [action params]
  IComponentOperation
  (op-kind [_] :frontend-only)

  tm/IOperation
  (apply-to [_ _]
    (throw (ex-info (str "variant action " action " runs only in the frontend")
                    {:action action}))))

(defn- variant-action
  [action params]
  (tm/assign-id (->VariantAction action params)))

(defn transform-in-variant
  "Turn the component whose main root `target` names into the first member of
   a new variant set (`transform-in-variant`), which also adds a second
   member."
  [target]
  (variant-action :transform {:target target}))

(defn combine-as-variants
  "Combine the components whose main roots `targets` name into a new variant
   set (`combine-as-variants`), or with `:selected?`, select them and combine
   the selection (`combine-selected-as-variants`)."
  [targets & {:keys [selected?]}]
  (variant-action :combine {:targets targets :selected? selected?}))

(defn rename-variant
  "Rename the variant set of the member whose main root `target` names, its
   container and every member's main (`rename-comp-or-variant-and-main`)."
  [target name]
  (variant-action :rename {:target target :name name}))

(defn variant-shortcut
  "Select the shapes `targets` name and press the shortcut `kind`:
   `:create-component` (`add-component-or-variant`) or `:duplicate`
   (`duplicate-or-add-variant`)."
  [kind targets]
  (variant-action :shortcut {:kind kind :targets targets}))

(defn component-of
  "The component of the shape `target` names, read from the file."
  [situation target]
  (let [shape (tm/shape-by-id situation (tm/target-shape-id situation target))]
    (ctkl/get-component (:data (tm/file situation)) (:component-id shape))))

(defn variant-component-ids
  "The ids of the components of the variant set `variant-id`, in the order of
   its container's children."
  [situation variant-id]
  (let [page (thf/current-page (tm/file situation))]
    (->> (get-in page [:objects variant-id :shapes])
         (mapv #(get-in page [:objects % :component-id])))))



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
          parent-id   (tm/target-shape-id situation parent)
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
          shape-id  (tm/target-shape-id situation target)
          page      (thf/current-page the-file)
          [_all-parents changes]
          (cls/generate-delete-shapes (pcb/empty-changes)
                                      (:data the-file)
                                      page
                                      (:objects page)
                                      #{shape-id}
                                      {})
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
                         (pcb/with-objects (:objects page))
                         (pcb/with-library-data (:data the-file)))
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

;; ===========================================================================
;; Tokens
;;
;; A token applied to a shape is recorded in its `:applied-tokens` (attribute
;; -> token name) and sets the attribute to the token's value. Sync copies
;; both to an untouched copy (`update-tokens`). Only border radius tokens
;; (attributes :r1..:r4) are modelled.
;; ===========================================================================

(def radius-attrs
  "The attributes a border radius token applies to."
  #{:r1 :r2 :r3 :r4})

(defrecord AddTokens [tokens]
  IComponentOperation
  (op-kind [_] :assembly)

  tm/IOperation
  (apply-to [this situation]
    (let [set-id   (uuid/next)
          theme-id (uuid/next)
          add      (fn [lib [name value]]
                     (ctob/add-token lib set-id (ctob/make-token :name name
                                                                 :type :border-radius
                                                                 :value value)))
          file'    (-> (tm/file situation)
                       (tht/add-tokens-lib)
                       (tht/update-tokens-lib
                        #(as-> % lib
                           (ctob/add-set lib (ctob/make-token-set :id set-id :name "composable"))
                           (ctob/add-theme lib (ctob/make-token-theme :id theme-id
                                                                      :name "composable"
                                                                      :sets #{"composable"}))
                           (reduce add lib tokens)))
                       (tht/update-tokens-status #(ctos/set-tokens-status % #{theme-id} #{set-id})))]
      (-> situation
          (tm/with-file file')
          (tm/record-application this {:tokens tokens})))))

(defn add-tokens
  "Give the file an active set of border radius tokens, `tokens` being
   `[[name value] ...]`."
  [tokens]
  (tm/assign-id (->AddTokens tokens)))

(defrecord ApplyToken [target token-name value]
  IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    ;; as `apply-token` commits them: the token, then its value without
    ;; touching
    (let [the-file (tm/file situation)
          shape-id (tm/target-shape-id situation target)
          page     (thf/current-page the-file)
          changes  (-> (pcb/empty-changes nil (:id page))
                       (cls/generate-update-shapes
                        #{shape-id}
                        #(update % :applied-tokens merge (zipmap radius-attrs (repeat token-name)))
                        (:objects page)
                        {})
                       (cls/generate-update-shapes
                        #{shape-id}
                        #(ctsr/set-radius-for-corners % radius-attrs value)
                        (:objects page)
                        {:ignore-touched true :attrs radius-attrs}))
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes changes)
          (tm/record-application this {:target target :token-name token-name :value value})))))

(defn apply-token
  "Apply the border radius token `token-name`, of value `value`, to every
   corner of the shape `target` names (`apply-token` on the frontend)."
  [target token-name value]
  (tm/assign-id (->ApplyToken target token-name value)))

(defrecord UnapplyToken [target token-name]
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
                    (fn [shape]
                      (update shape :applied-tokens
                              #(when % (cfo/remove-attributes-for-token radius-attrs token-name %))))
                    (:objects page)
                    {})
          file'    (thf/apply-changes the-file changes)]
      (-> situation
          (tm/with-file file')
          (record-changes (when (seq (:redo-changes changes)) changes))
          (tm/record-application this {:target target :token-name token-name})))))

(defn unapply-token
  "Remove the border radius token `token-name` from the shape `target` names,
   keeping its radius (`unapply-token` on the frontend)."
  [target token-name]
  (tm/assign-id (->UnapplyToken target token-name)))

(defn has-token?
  "Whether `shape` has the border radius token `token-name` on every corner,
   with radius `value`."
  [shape token-name value]
  (every? #(and (= token-name (get-in shape [:applied-tokens %]))
                (= value (get shape %)))
          radius-attrs))
