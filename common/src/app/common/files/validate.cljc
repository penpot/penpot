;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.files.validate
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.path-names :as cpn]
   [app.common.schema :as sm]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def error-codes
  #{:invalid-geometry
    :parent-not-found
    :child-not-in-parent
    :duplicated-children
    :child-not-found
    :frame-not-found
    :invalid-frame
    :component-duplicate-slot
    :component-not-main
    :component-main-external
    :component-not-found
    :duplicate-slot
    :invalid-main-instance-id
    :invalid-main-instance-page
    :invalid-main-instance
    :invalid-parent
    :component-main
    :should-be-component-root
    :should-not-be-component-root
    :ref-shape-not-found
    :ref-shape-is-head
    :ref-shape-is-not-head
    :shape-ref-in-main
    :component-id-mismatch
    :root-main-not-allowed
    :nested-main-not-allowed
    :root-copy-not-allowed
    :nested-copy-not-allowed
    :not-head-main-not-allowed
    :not-head-copy-not-allowed
    :not-component-not-allowed
    :component-nil-objects-not-allowed
    :non-deleted-component-cannot-have-objects
    :instance-head-not-frame
    :invalid-text-touched
    :misplaced-slot
    :missing-slot
    :shape-ref-cycle
    :main-instance-not-a-variant
    :main-instance-invalid-variant-id
    :invalid-variant-properties
    :variant-not-main
    :parent-not-variant
    :variant-main-bad-name
    :variant-main-bad-variant-name
    :variant-component-bad-name
    :variant-component-bad-id})

(def ^:private schema:error
  [:map {:title "ValidationError"}
   [:code {:optional false} [::sm/one-of error-codes]]
   [:hint {:optional false} :string]
   [:shape {:optional true} :map] ; Cannot validate a shape because here it may be broken
   [:shape-id {:optional true} ::sm/uuid]
   [:file-id ::sm/uuid]
   [:page-id {:optional true} [:maybe ::sm/uuid]]])

(def check-error
  (sm/check-fn schema:error))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR HANDLING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic ^:private *errors* nil)

;; Per-page volatile map used to memoize `ctf/find-ref-shape` calls during a
;; single validation pass. Keys are shape-ids; values are the returned ref-shape
;; (or `nil` when the shape has no ref). The cache is reset once per page so
;; that stale results never cross page boundaries.
(def ^:dynamic ^:private *ref-shape-cache* nil)

;; Per-page pre-computed map from parent-id to the set of its children ids.
;; Enables O(1) containment checks in `check-parent-children` instead of the
;; default O(k) linear `(some #(= shape-id %) (:shapes parent))` scan.
;; Bound as a plain immutable map (not a volatile) since it is read-only during
;; a page's validation pass.
(def ^:dynamic ^:private *children-sets* nil)

(defn- build-children-sets
  "Return a {parent-id → #{child-ids}} map built from `objects` in a single
  `reduce-kv` pass.  Only shapes that have at least one child get an entry."
  [objects]
  (reduce-kv (fn [m _ shape]
               (if-let [kids (not-empty (:shapes shape))]
                 (assoc m (:id shape) (set kids))
                 m))
             {}
             objects))

(defn- find-ref-shape*
  "Cached wrapper around `ctf/find-ref-shape` with `:include-deleted? true`.
  When `*ref-shape-cache*` is bound, each shape-id is resolved at most once
  per validation pass regardless of how many check functions request it.

  Cache miss detection uses `contains?` rather than a sentinel default
  (e.g. `(get cache id ::miss)` + `identical?`). The reason: in
  ClojureScript, `identical?` on namespace-qualified keywords is not
  reliable across compilation units because keyword interning is not
  guaranteed — a sentinel retrieved from `get` may not be `===` to the
  same literal written in code, causing every lookup to appear as a miss
  and breaking the whole optimisation."
  [file page libraries shape]
  (if-let [cache *ref-shape-cache*]
    (let [id (:id shape)]
      (if (contains? @cache id)
        (get @cache id)
        (let [result (ctf/find-ref-shape file page libraries shape :include-deleted? true)]
          (vswap! cache assoc id result)
          result)))
    (ctf/find-ref-shape file page libraries shape :include-deleted? true)))

(defn- library-exists?
  [file libraries shape]
  (or (= (:component-file shape) (:id file))
      (contains? libraries (:component-file shape))))

(defn- report-error
  [code hint shape file page & {:as args}]
  (let [error (d/without-nils
               {:code code
                :hint hint
                :shape shape
                :file-id (:id file)
                :page-id (:id page)
                :shape-id (:id shape)
                :args args})]

    (assert (some? *errors*) "expected a valid `*errors*` dynamic binding")
    (assert (check-error error))

    (vswap! *errors* conj error)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRIVATE API: VALIDATION FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare check-shape)

(defn- check-geometry
  "Validate that the shape has valid coordinates, selrect and points."
  [shape file page]
  (when (and (not (or (cfh/path-shape? shape)
                      (cfh/bool-shape? shape)))
             (or (nil? (:x shape))       ; This may occur in root shape (uuid/zero) in old files
                 (nil? (:y shape))
                 (nil? (:width shape))
                 (nil? (:height shape))
                 (nil? (:selrect shape))
                 (nil? (:points shape))))
    (report-error :invalid-geometry
                  "Shape geometry is invalid"
                  shape file page)))

(defn- check-parent-children
  "Validate parent and children exists, and the link is bidirectional."
  [shape file page]
  (let [parent   (ctst/get-shape page (:parent-id shape))
        shape-id (:id shape)
        shapes   (:shapes shape)]

    (if (nil? parent)
      (report-error :parent-not-found
                    (str/ffmt "Parent % not found" (:parent-id shape))
                    shape file page)
      (do
        (when-not (cfh/root? shape)
          ;; Fast path: O(1) set lookup when `*children-sets*` is pre-computed
          ;; for the current page.  Falls back to an O(k) linear scan via
          ;; `some` when the var is unbound (e.g. in isolated single-shape
          ;; validation calls outside a full page pass).
          (when-not (if-let [cs *children-sets*]
                      (contains? (get cs (:id parent)) shape-id)
                      (some #(= shape-id %) (:shapes parent)))
            (report-error :child-not-in-parent
                          (str/ffmt "Shape % not in parent's children list" shape-id)
                          shape file page)))

        ;; Single-pass duplicate detection: walk the children list once,
        ;; accumulating seen IDs into a set and short-circuiting on the
        ;; first duplicate.  This replaces the previous two-pass
        ;; `(not= (count shapes) (count (distinct shapes)))` approach
        ;; which allocated an intermediate sequence and scanned twice.
        (let [dup? (reduce (fn [seen id]
                             (if (contains? seen id)
                               (reduced true)
                               (conj seen id)))
                           #{}
                           shapes)]
          (when (true? dup?)
            (report-error :duplicated-children
                          (str/ffmt "Shape % has duplicated children" shape-id)
                          shape file page)))

        (doseq [child-id shapes]
          (let [child (ctst/get-shape page child-id)]
            (if (nil? child)
              (report-error :child-not-found
                            (str/ffmt "Child % not found in parent %"  child-id shape-id)
                            shape file page
                            :parent-id shape-id
                            :child-id child-id)
              (when (not= (:parent-id child) shape-id)
                (report-error :invalid-parent
                              (str/ffmt "Child % has invalid parent %" child-id shape-id)
                              child file page
                              :parent-id shape-id)))))))))

(defn- check-frame
  "Validate that the frame-id shape exists and is indeed a frame. Also
  it must point to the parent shape (if this is a frame) or to the
  frame-id of the parent (if not)."
  [{:keys [frame-id] :as shape} file page]
  (let [frame (ctst/get-shape page frame-id)]
    (if (nil? frame)
      (report-error :frame-not-found
                    (str/ffmt "Frame % not found" frame-id)
                    shape file page)
      (if (not= (:type frame) :frame)
        (report-error :invalid-frame
                      (str/ffmt "Frame % is not actually a frame" frame-id)
                      shape file page)
        (let [parent (ctst/get-shape page (:parent-id shape))]
          (when (some? parent)
            (if (= (:type parent) :frame)
              (when-not (= frame-id (:id parent))
                (report-error :invalid-frame
                              (str/ffmt "Frame-id should point to parent %" (:id parent))
                              shape file page))
              (when-not (= frame-id (:frame-id parent))
                (report-error :invalid-frame
                              (str/ffmt "Frame-id should point to parent frame %" frame-id)
                              shape file page)))))))))

(defn- check-component-main-head
  "Validate shape is a main instance head, component exists
  and its main-instance points to this shape."
  [shape file page libraries]
  (when (nil? (:main-instance shape))
    (report-error :component-not-main
                  "Shape expected to be main instance"
                  shape file page))
  (when-not (= (:component-file shape) (:id file))
    (report-error :component-main-external
                  "Main instance should refer to a component in the same file"
                  shape file page))
  (let [component (ctf/resolve-component shape file libraries :include-deleted? true)]
    (if (nil? component)
      (report-error :component-not-found
                    (str/ffmt "Component % not found in file %" (:component-id shape) (:component-file shape))
                    shape file page)
      (do
        (when-not (= (:main-instance-id component) (:id shape))
          (report-error :invalid-main-instance-id
                        (str/ffmt "Main instance id of component % is not valid" (:component-id shape))
                        shape file page))
        (when-not (= (:main-instance-page component) (:id page))
          (let [component-page (ctf/get-component-page (:data file) component)
                main-component (ctst/get-shape component-page (:main-instance-id component))]
            ;; We must check if the same component has main instances in different pages.
            ;; In that case one of those instances shouldn't be main
            (if (:main-instance main-component)
              (report-error :component-main
                            "Shape not expected to be main instance"
                            shape file page)
              (report-error :invalid-main-instance-page
                            (str/ffmt "Main instance page of component % is not valid" (:component-id shape))
                            shape file page))))))))

(defn- check-component-not-main-head
  "Validate shape is a not-main instance head, component
  exists and its main-instance does not point to this
  shape."
  [shape file page libraries]
  (when (true? (:main-instance shape))
    (report-error :component-not-main
                  "Shape not expected to be main instance"
                  shape file page))

  (let [library-exists (library-exists? file libraries shape)
        component (when library-exists
                    (ctf/resolve-component shape file libraries {:include-deleted? true}))]
    (if (nil? component)
      (when library-exists
        (report-error :component-not-found
                      (str/ffmt "Component % not found in file %" (:component-id shape) (:component-file shape))
                      shape file page))
      (when (and (= (:main-instance-id component) (:id shape))
                 (= (:main-instance-page component) (:id page)))
        (report-error :invalid-main-instance
                      (str/ffmt "Main instance of component % should not be this shape" (:id component))
                      shape file page)))))

(defn- check-component-not-main-not-head
  "Validate that this shape is not main instance and not head."
  [shape file page]
  (when (true? (:main-instance shape))
    (report-error :component-main
                  "Shape not expected to be main instance"
                  shape file page))
  (when (or (some? (:component-id shape))
            (some? (:component-file shape)))
    (report-error :component-main
                  "Shape not expected to be component head"
                  shape file page)))

(defn- check-component-root
  "Validate that this shape is an instance root."
  [shape file page]
  (when (nil? (:component-root shape))
    (report-error :should-be-component-root
                  "Shape should be component root"
                  shape file page)))

(defn- check-component-not-root
  "Validate that this shape is not an instance root."
  [shape file page]
  (when (true? (:component-root shape))
    (report-error :should-not-be-component-root
                  "Shape should not be component root"
                  shape file page)))

(defn- check-component-ref
  "Validate that the referenced shape exists in the near component."
  [shape file page libraries]
  (let [library-exists (library-exists? file libraries shape)
        ref-shape (when library-exists
                    (find-ref-shape* file page libraries shape))]
    (when (and library-exists (nil? ref-shape))
      (report-error :ref-shape-not-found
                    (str/ffmt "Referenced shape % not found in near component" (:shape-ref shape))
                    shape file page))))

(defn- check-component-not-ref
  "Validate that this shape does not reference other one."
  [shape file page]
  (when (some? (:shape-ref shape))
    (report-error :shape-ref-in-main
                  "Shape inside main instance should not have shape-ref"
                  shape file page)))

(defn- check-ref-is-not-head
  "Validate that the referenced shape is not a nested copy root."
  [shape file page libraries]
  (let [ref-shape (find-ref-shape* file page libraries shape)]
    (when (and (some? ref-shape)
               (ctk/instance-head? ref-shape))
      (report-error :ref-shape-is-head
                    (str/ffmt "Referenced shape % is a component, so the copy must also be" (:shape-ref shape))
                    shape file page))))

(defn- check-ref-is-head
  "Validate that the referenced shape is a nested copy root."
  [shape file page libraries]
  (let [ref-shape (find-ref-shape* file page libraries shape)]
    (when (and (some? ref-shape)
               (not (ctk/instance-head? ref-shape)))
      (report-error :ref-shape-is-not-head
                    (str/ffmt "Referenced shape % of a head copy must also be a head" (:shape-ref shape))
                    shape file page
                    :component-file (:component-file ref-shape)
                    :component-id (:component-id ref-shape)))))

(defn- check-ref-component-id
  "Validate that if the copy has not been swapped, the component-id and component-file are
   the same as in the referenced shape in the near main."
  [shape file page libraries]
  (when (nil? (ctk/get-swap-slot shape))
    (when-let [ref-shape (find-ref-shape* file page libraries shape)]
      (when (or (not= (:component-id shape) (:component-id ref-shape))
                (not= (:component-file shape) (:component-file ref-shape)))
        (report-error :component-id-mismatch
                      "Nested copy component-id and component-file must be the same as the near main"
                      shape file page
                      :component-id (:component-id ref-shape)
                      :component-file (:component-file ref-shape))))))

(defn- check-empty-swap-slot
  "Validate that this shape does not have any swap slot."
  [shape file page]
  (when (some? (ctk/get-swap-slot shape))
    (report-error :misplaced-slot
                  "This shape should not have swap slot"
                  shape file page)))

(defn- has-duplicate-swap-slot?
  "Returns true if any two children of `shape` share the same swap slot.
  Uses a single pass with early exit on the first duplicate, avoiding
  the intermediate `frequencies` map allocation."
  [shape container]
  (let [objects (:objects container)
        result  (reduce (fn [seen child-id]
                          (let [slot (ctk/get-swap-slot (get objects child-id))]
                            (if (nil? slot)
                              seen
                              (if (contains? seen slot)
                                (reduced true)
                                (conj seen slot)))))
                        #{}
                        (:shapes shape))]
    (true? result)))

(defn- check-duplicate-swap-slot
  "Validate that the children of this shape does not have duplicated slots."
  [shape file page]
  (when (has-duplicate-swap-slot? shape page)
    (report-error :duplicate-slot
                  "This shape has children with the same swap slot"
                  shape file page)))

(defn- check-required-swap-slot
  "Validate that the shape has swap-slot if it's a subinstance head and the ref shape is not the
   matching shape by position in the near main."
  [shape file page libraries]
  ;; Guard first: if the shape already has a swap slot the invariant is satisfied
  ;; and we can avoid the expensive `find-near-match` call entirely.
  (when (nil? (ctk/get-swap-slot shape))
    (let [near-match (ctf/find-near-match file page libraries shape :include-deleted? true :with-context? false)]
      (when (and (some? near-match)
                 (not= (:shape-ref shape) (:id near-match)))
        (report-error :missing-slot
                      "Shape has been swapped, should have swap slot"
                      shape file page
                      :swap-slot (or (ctk/get-swap-slot near-match) (:id near-match)))))))

(defn- check-valid-touched
  "Validate that the text touched flags are coherent."
  [shape file page]
  (let [touched-groups (ctk/normal-touched-groups shape)
        content-touched? (touched-groups :content-group)
        text-touched?    (or (touched-groups :text-content-text)
                             (touched-groups :text-content-attribute)
                             (touched-groups :text-content-structure))]
    ;; For now we only check this combination, that has been reported in some bugs
    (when (and text-touched? (not content-touched?))
      (report-error :invalid-text-touched
                    "This thape has text type touched but not content touched"
                    shape file page))))

(defn- check-shape-main-root-top
  "Root shape of a top main instance:

   - :main-instance
   - :component-id
   - :component-file
   - :component-root"
  [shape file page libraries]
  (check-component-main-head shape file page libraries)
  (check-component-root shape file page)
  (check-component-not-ref shape file page)
  (check-empty-swap-slot shape file page)
  (check-duplicate-swap-slot shape file page)
  (run! #(check-shape % file page libraries :context :main-top) (:shapes shape)))

(defn- check-shape-main-root-nested
  "Root shape of a nested main instance
   - :main-instance
   - :component-id
   - :component-file"
  [shape file page libraries]
  (check-component-main-head shape file page libraries)
  (check-component-not-root shape file page)
  (check-component-not-ref shape file page)
  (check-empty-swap-slot shape file page)
  (run! #(check-shape % file page libraries :context :main-nested) (:shapes shape)))

(defn- check-shape-copy-root-top
  "Root shape of a top copy instance
   - :component-id
   - :component-file
   - :component-root
   - :shape-ref"
  [shape file page libraries]
  ;; We propagate have to propagate to nested shapes if library is valid or not
  (let [library-exists (library-exists? file libraries shape)]
    (check-component-not-main-head shape file page libraries)
    (check-component-root shape file page)
    (check-component-ref shape file page libraries)
    (check-ref-is-head shape file page libraries)
    (check-empty-swap-slot shape file page)
    (check-duplicate-swap-slot shape file page)
    (check-valid-touched shape file page)
    (run! #(check-shape % file page libraries :context :copy-top :library-exists library-exists) (:shapes shape))))

(defn- check-shape-copy-root-nested
  "Root shape of a nested copy instance
   - :component-id
   - :component-file
   - :shape-ref"
  [shape file page libraries library-exists]
  (check-component-not-main-head shape file page libraries)
  (check-component-not-root shape file page)
  (check-valid-touched shape file page)
  (check-ref-component-id shape file page libraries)
  (check-required-swap-slot shape file page libraries)
  ;; We can have situations where the nested copy and the ancestor copy come from different libraries and some of them have been dettached
  ;; so we only validate the shape-ref if the ancestor is from a valid library
  (when library-exists
    (check-component-ref shape file page libraries)
    (check-ref-is-head shape file page libraries))
  (run! #(check-shape % file page libraries :context :copy-nested) (:shapes shape)))

(defn- check-shape-main-not-root
  "Not-root shape of a main instance (not any attribute)"
  [shape file page libraries]
  (check-component-not-main-not-head shape file page)
  (check-component-not-root shape file page)
  (check-component-not-ref shape file page)
  (check-empty-swap-slot shape file page)
  (run! #(check-shape % file page libraries :context :main-any) (:shapes shape)))

(defn- check-shape-copy-not-root
  "Not-root shape of a copy instance :shape-ref"
  [shape file page libraries]
  (check-component-not-main-not-head shape file page)
  (check-component-not-root shape file page)
  (check-component-ref shape file page libraries)
  (check-ref-is-not-head shape file page libraries)
  (check-empty-swap-slot shape file page)
  (check-valid-touched shape file page)
  (run! #(check-shape % file page libraries :context :copy-any) (:shapes shape)))

(defn- check-shape-not-component
  "Shape is not in a component or is a fostered children (not any
  attribute)"
  [shape file page libraries]
  (check-component-not-main-not-head shape file page)
  (check-component-not-root shape file page)
  (check-component-not-ref shape file page)
  (check-empty-swap-slot shape file page)
  (run! #(check-shape % file page libraries :context :not-component) (:shapes shape)))

(defn- check-variant-container
  "Shape is a variant container, so:
     -all its children should be variants with variant-id equals to the shape-id
     -all the components should have the same properties"
  [shape file page]
  (let [shape-id    (:id shape)
        shapes      (:shapes shape)
        objects     (:objects page)
        file-data   (:data file)
        first-child (get objects (first shapes))
        prop-names  (cfv/extract-properties-names first-child file-data)]
    (run! (fn [child-id]
            (when-let [child (get objects child-id)]
              (if (not (ctk/is-variant? child))
                (report-error :main-instance-not-a-variant
                              (str/ffmt "Main instance shape % should be a variant" (:id child))
                              child file page
                              :variant-id shape-id)
                (do
                  (when (not= (:variant-id child) shape-id)
                    (report-error :main-instance-invalid-variant-id
                                  (str/ffmt "Main instance in variant % should have the variant-id of the container but has %" (:id child) (:variant-id child))
                                  child file page
                                  :variant-id shape-id))
                  (when (not= prop-names (cfv/extract-properties-names child file-data))
                    (report-error :invalid-variant-properties
                                  (str/ffmt "Variant % has invalid properties %" (:id child) (vec prop-names))
                                  child file page
                                  :prop-names prop-names))))))
          shapes)))

(defn- check-variant
  "Shape is a variant, so
     -it should be a main component
     -its parent should be a variant-container
     -its variant-name is derived from the properties
     -its name should be the same as its parent's
   "
  [shape file page]
  (let [parent       (ctst/get-shape page (:parent-id shape))
        component    (ctkl/get-component (:data file) (:component-id shape) true)
        variant-name (ctv/properties-to-name (:variant-properties component))]
    (when-not (ctk/main-instance? shape)
      (report-error :variant-not-main
                    (str/ffmt "Variant % is not a main instance" (:id shape))
                    shape file page))
    (when-not (ctk/is-variant-container? parent)
      (report-error :parent-not-variant
                    (str/ffmt "Variant % has an invalid parent" (:id shape))
                    shape file page))
    (when-not (= variant-name (:variant-name shape))
      (report-error :variant-main-bad-variant-name
                    (str/ffmt "Variant % has an invalid variant-name" (:id shape))
                    shape file page
                    :variant-name variant-name))
    (when-not (= (:name parent) (:name shape))
      (report-error :variant-main-bad-name
                    (str/ffmt "Main instance inside variant % has an invalid name" (:id shape))
                    shape file page
                    :variant-name (:name parent)))
    (when-not (= (:name parent) (cpn/merge-path-item (:path component) (:name component)))
      (report-error :variant-component-bad-name
                    (str/ffmt "Component % has an invalid name" (:id shape))
                    shape file page
                    :variant-container-name (:name parent)))
    (when-not (= (:variant-id component) (:variant-id shape))
      (report-error :variant-component-bad-id
                    (str/ffmt "Variant % has adifferent variant-id than its component" (:id shape))
                    shape file page
                    :variant-id (:variant-id component)))))

(defn- check-shape
  "Validate referential integrity and semantic coherence of
  a shape and all its children. Report all errors found.

  The context is the situation of the parent in respect to components:
   - :not-component
   - :main-top
   - :main-nested
   - :copy-top
   - :copy-nested
   - :main-any
   - :copy-any
  "
  [shape-id file page libraries & {:keys [context library-exists] :or {context :not-component library-exists false}}]
  (let [shape (ctst/get-shape page shape-id)]
    (when (some? shape)
      (check-geometry shape file page)
      (check-parent-children shape file page)
      (check-frame shape file page)

      (when (ctk/is-variant-container? shape)
        (check-variant-container shape file page))

      (when (ctk/is-variant? shape)
        (check-variant shape file page))

      (if (ctk/instance-head? shape)
        (if (not= :frame (:type shape))
          (report-error :instance-head-not-frame
                        "Instance head should be a frame"
                        shape file page)

          (if (ctk/instance-root? shape)
            (if (ctk/main-instance? shape)
              (if (not= context :not-component)
                (report-error :root-main-not-allowed
                              "Root main component not allowed inside other component"
                              shape file page)
                (check-shape-main-root-top shape file page libraries))

              (if (not= context :not-component)
                (report-error :root-copy-not-allowed
                              "Root copy component not allowed inside other component"
                              shape file page)
                (check-shape-copy-root-top shape file page libraries)))

            (if (ctk/main-instance? shape)
              ;; mains can't be nested into mains
              (if (or (= context :not-component) (= context :main-top))
                (report-error :nested-main-not-allowed
                              "Component main not allowed inside other component"
                              shape file page)
                (check-shape-main-root-nested shape file page libraries))

              (if (= context :not-component)
                (report-error :nested-copy-not-allowed
                              "Nested copy component only allowed inside other component"
                              shape file page)
                (check-shape-copy-root-nested shape file page libraries library-exists)))))

        (if (ctk/in-component-copy? shape)
          (if-not (#{:copy-top :copy-nested :copy-any} context)
            (report-error :not-head-copy-not-allowed
                          "Non-root copy only allowed inside a copy"
                          shape file page)
            (check-shape-copy-not-root shape file page libraries))

          ;; Short-circuit `inside-component-main?` when the propagated
          ;; `context` already classifies this sub-tree as belonging to a
          ;; main component.  `inside-component-main?` performs an O(depth)
          ;; upward ancestor walk; skipping it for every non-head shape that
          ;; sits inside a known-main context avoids redundant tree traversals
          ;; that would otherwise dominate validation time on deep hierarchies.
          (let [in-main? (or (#{:main-top :main-nested :main-any} context)
                             (ctn/inside-component-main? (:objects page) shape))]
            (if in-main?
              (if-not (#{:main-top :main-nested :main-any} context)
                (report-error :not-head-main-not-allowed
                              "Non-root main only allowed inside a main component"
                              shape file page)
                (check-shape-main-not-root shape file page libraries))

              (if (#{:main-top :main-nested :main-any} context)
                (report-error :not-component-not-allowed
                              "Not components are not allowed inside a main"
                              shape file page)
                (check-shape-not-component shape file page libraries)))))))))

(defn check-component-duplicate-swap-slot
  [component file]
  (let [shape (get-in component [:objects (:main-instance-id component)])]
    (when (has-duplicate-swap-slot? shape component)
      (report-error :component-duplicate-slot
                    "This deleted component has children with the same swap slot"
                    component file nil))))

(defn check-ref-cycles
  [component file]
  (let [cycles-ids (-> (reduce-kv (fn [acc id shape]
                                    (if (= id (:shape-ref shape))
                                      (conj! acc id)
                                      acc))
                                  (transient [])
                                  (:objects component))
                       (persistent!))]

    (when (seq cycles-ids)
      (report-error :shape-ref-cycle
                    "This deleted component has shapes with shape-ref pointing to self"
                    component file nil :cycles-ids cycles-ids))))

(defn- check-variant-component
  "Component is a variant, so:
     -Its main should be a variant
     -It should have at least one variant property"
  [component file]
  (let [component-page (ctf/get-component-page (:data file) component)
        main-instance  (if (:deleted component)
                         (dm/get-in component [:objects (:main-instance-id component)])
                         (ctst/get-shape component-page (:main-instance-id component)))]
    (when (and main-instance
               (not (ctk/is-variant? main-instance)))
      (report-error :main-instance-not-a-variant
                    (str/ffmt "Main instance shape % should be a variant" (:id main-instance))
                    main-instance file component-page
                    :variant-id (:variant-id component)))))

(defn- check-main-inside-main
  [component file]
  (let [component-page (ctf/get-component-page (:data file) component)
        main-instance  (ctst/get-shape component-page (:main-instance-id component))
        main-parents?  (->> main-instance
                            :id
                            (cfh/get-parents (:objects component-page))
                            (some ctk/main-instance?)
                            boolean)]
    (when main-parents?
      (report-error :nested-main-not-allowed
                    "Component main not allowed inside other component"
                    main-instance file component-page))))

(defn- check-not-objects
  [component file]
  (when (d/not-empty? (:objects component))
    (report-error :non-deleted-component-cannot-have-objects
                  "A non-deleted component cannot have shapes inside"
                  component file nil)))

(defn- check-component
  "Validate semantic coherence of a component. Report all errors found."
  [component file]
  (when (and (contains? component :objects) (nil? (:objects component)))
    (report-error :component-nil-objects-not-allowed
                  "Objects list cannot be nil"
                  component file nil))
  (when-not (:deleted component)
    (check-main-inside-main component file)
    (check-not-objects component file))
  (when (:deleted component)
    (check-component-duplicate-swap-slot component file)
    (check-ref-cycles component file))

  (when (ctk/is-variant? component)
    (check-variant-component component file)))

(defn- get-orphan-shapes
  "Return the ids of shapes whose parent does not exist in the objects
  map (i.e. shapes unreachable from the root traversal). The root
  shape itself is excluded since it is always validated separately.

  Implemented with `reduce-kv` rather than a `map`/`filter` pipeline.
  The previous implementation mapped over `objects` and returned a
  lazy seq that could contain `nil` entries (for shapes that were
  *not* orphans), meaning `check-shape` was called with `nil` IDs and
  orphaned shapes were silently skipped.  The `reduce-kv` approach
  builds a plain vector of IDs and never yields `nil` entries."
  [{:keys [objects] :as _page}]
  (persistent!
   (reduce-kv (fn [result id shape]
                (if (and (not (cfh/root? shape))
                         (not (contains? objects (:parent-id shape))))
                  (conj! result id)
                  result))
              (transient [])
              objects)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API: VALIDATION FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-file
  "Validate full referential integrity and semantic coherence on file data.

  Return a list of errors or `nil`"
  [{:keys [data features] :as file} libraries]
  (when (contains? features "components/v2")
    (binding [*errors* (volatile! [])]

      ;; `reduce-kv` is used throughout this function (and `validate-file-affected`)
      ;; instead of `(run! f (vals m))` or `(doseq [[k v] m])` because persistent
      ;; maps implement `IKVReduce`, which drives iteration internally without
      ;; first materialising an intermediate sequence of key-value pairs.
      (reduce-kv
       (fn [_ _ page]
         (when (some? page)
           ;; Both performance caches are scoped to a single page: ref-shape
           ;; lookups and parent→children sets are page-local and must never
           ;; carry over to the next page.  A fresh volatile is created for
           ;; each page so stale entries cannot corrupt subsequent pages.
           (binding [*ref-shape-cache* (volatile! {})
                     *children-sets* (build-children-sets (:objects page))]
             (check-shape uuid/zero file page libraries)
             (run! #(check-shape % file page libraries)
                   (get-orphan-shapes page)))))
       nil
       (:pages-index data))

      (reduce-kv
       (fn [_ _ component]
         (check-component component file))
       nil
       (:components data))

      (-> *errors* deref not-empty))))

(defn validate-shape
  "Validate a shape and all its children. Returns a list of errors."
  [shape-id file page libraries]
  (binding [*errors* (volatile! [])
            *ref-shape-cache* (volatile! {})
            *children-sets* (build-children-sets (:objects page))]
    (check-shape shape-id file page libraries)
    (deref *errors*)))

(defn validate-component
  "Validate a component. Returns a list of errors."
  [component file]
  (binding [*errors* (volatile! [])]
    (check-component component file)
    (deref *errors*)))

(defn- extract-affected-ids
  "Single reduce pass over a changes batch.
  Returns {:page-ids #{uuid …} :component-ids #{uuid …}}.

  Only entities that need re-validation after applying `changes` are
  included.  Deleted entities and pure file-level changes (colors,
  tokens, typography…) produce no entries because there is nothing left
  to check."
  [changes]

  (loop [changes (seq changes)
         page-ids #{}
         component-ids #{}]
    (if-let [change (first changes)]
      (let [{:keys [type page-id component-id id]} change
            page-ids
            (case type
              ;; Shape-level ops are scoped to either a page or a component
              (:add-obj :mod-obj :del-obj :fix-obj :mov-objects :reorder-children :reg-objects)
              (cond-> page-ids
                page-id (conj page-id))

              ;; A new or modified page needs a full page sweep
              (:add-page :mod-page)
              (conj page-ids id)

              ;; restore-component resurrects a deleted component (touches the
              ;; component definition) and places its main instance on a page
              ;; (touches that page's shape tree)
              :restore-component
              (conj page-ids page-id)

              ;; Otherwise don't change the ids
              page-ids)

            component-ids
            (case type
              ;; Shape-level ops are scoped to either a page or a component
              (:add-obj :mod-obj :del-obj :fix-obj :mov-objects :reorder-children :reg-objects)
              (cond-> component-ids
                component-id (conj component-id))

              ;; A new, modified, restored component needs component-level checking
              (:add-component :mod-component :restore-component)
              (conj component-ids id)

              ;; Otherwise don't change the ids
              component-ids)]
        (recur (rest changes) page-ids component-ids))

      ;; Return result of accumulated ids
      {:page-ids page-ids :component-ids component-ids})))

(defn validate-file-affected
  "Validate only the pages and components touched by `changes`.

  Semantics are identical to `validate-file` but the work is bounded
  to the entities that were actually mutated, making it safe and cheap
  to call on every incremental file update.

  Returns a list of errors or `nil`."
  [{:keys [data features] :as file} libraries changes]
  (when (contains? features "components/v2")
    (let [{:keys [page-ids component-ids]} (extract-affected-ids changes)]
      (binding [*errors* (volatile! [])]

        (reduce-kv
         (fn [_ page-id page]
           (when (and (some? page) (contains? page-ids page-id))
             ;; Both performance caches are scoped to a single page: ref-shape
             ;; lookups and parent→children sets are page-local and must never
             ;; carry over to the next page.  A fresh volatile is created for
             ;; each page so stale entries cannot corrupt subsequent pages.
             (binding [*ref-shape-cache* (volatile! {})
                       *children-sets*   (build-children-sets (:objects page))]
               (check-shape uuid/zero file page libraries)
               (run! #(check-shape % file page libraries)
                     (get-orphan-shapes page)))))
         nil
         (:pages-index data))

        (reduce-kv
         (fn [_ id component]
           (when (contains? component-ids id)
             (check-component component file)))
         nil
         (:components data))

        (-> *errors* deref not-empty)))))

(defn validate-file-affected!
  "Like `validate-file-affected` but raises on the first non-empty
  error list instead of returning it."
  [file libraries changes]
  (when-let [errors (validate-file-affected file libraries changes)]
    (ex/raise :type :validation
              :code :referential-integrity
              :hint "error on validating file referential integrity"
              :file-id (:id file)
              :details errors)))

(defn validate-file-schema!
  "Validates the file itself, without external dependencies, it
  performs the schema checking and some semantical validation of the
  content."
  [file]
  (update file :data ctf/check-file-data))

(defn validate-file!
  "Validate full referential integrity and semantic coherence on file data.

  Raises an exception"
  [file libraries]
  (when-let [errors (validate-file file libraries)]
    (ex/raise :type :validation
              :code :referential-integrity
              :hint "error on validating file referential integrity"
              :file-id (:id file)
              :details errors)))
