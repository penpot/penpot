;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.validate
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
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
    :shape-ref-in-main
    :root-main-not-allowed
    :nested-main-not-allowed
    :root-copy-not-allowed
    :nested-copy-not-allowed
    :not-head-main-not-allowed
    :not-head-copy-not-allowed
    :not-component-not-allowed
    :component-nil-objects-not-allowed
    :instance-head-not-frame
    :misplaced-slot
    :missing-slot})

(def ^:private
  schema:error
  (sm/define
    [:map {:title "ValidationError"}
     [:code {:optional false} [::sm/one-of error-codes]]
     [:hint {:optional false} :string]
     [:shape {:optional true} :map] ; Cannot validate a shape because here it may be broken
     [:shape-id {:optional true} ::sm/uuid]
     [:file-id ::sm/uuid]
     [:page-id {:optional true} [:maybe ::sm/uuid]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR HANDLING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic ^:private *errors* nil)

(defn- library-exists?
  [file libraries shape]
  (or (= (:component-file shape) (:id file))
      (contains? libraries (:component-file shape))))

(defn- report-error
  [code hint shape file page & {:as args}]
  (let [error {:code code
               :hint hint
               :shape shape
               :file-id (:id file)
               :page-id (:id page)
               :shape-id (:id shape)
               :args args}]

    (dm/assert!
     "expected a valid `*errors*` dynamic binding"
     (some? *errors*))

    (dm/assert!
     "expected valid error"
     (sm/check! schema:error error))

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
          (when-not (some #(= shape-id %) (:shapes parent))
            (report-error :child-not-in-parent
                          (str/ffmt "Shape % not in parent's children list" shape-id)
                          shape file page)))

        (when-not (= (count shapes) (count (distinct shapes)))
          (report-error :duplicated-children
                        (str/ffmt "Shape % has duplicated children" shape-id)
                        shape file page))

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
                    (ctf/find-ref-shape file page libraries shape :include-deleted? true))]
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

(defn- check-empty-swap-slot
  "Validate that this shape does not have any swap slot."
  [shape file page]
  (when (some? (ctk/get-swap-slot shape))
    (report-error :misplaced-slot
                  "This shape should not have swap slot"
                  shape file page)))

(defn- has-duplicate-swap-slot?
  [shape container]
  (let [shapes   (map #(get (:objects container) %) (:shapes shape))
        slots    (->> (map #(ctk/get-swap-slot %) shapes)
                      (remove nil?))
        counts   (frequencies slots)]
    (some (fn [[_ count]] (> count 1)) counts)))

(defn- check-duplicate-swap-slot
  "Validate that the children of this shape does not have duplicated slots."
  [shape file page]
  (when (has-duplicate-swap-slot? shape page)
    (report-error :duplicate-slot
                  "This shape has children with the same swap slot"
                  shape file page)))

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
    (check-empty-swap-slot shape file page)
    (check-duplicate-swap-slot shape file page)
    (run! #(check-shape % file page libraries :context :copy-top :library-exists library-exists) (:shapes shape))))

(defn- check-shape-copy-root-nested
  "Root shape of a nested copy instance
   - :component-id
   - :component-file
   - :shape-ref"
  [shape file page libraries library-exists]
  (check-component-not-main-head shape file page libraries)
  (check-component-not-root shape file page)
  ;; We can have situations where the nested copy and the ancestor copy come from different libraries and some of them have been dettached
  ;; so we only validate the shape-ref if the ancestor is from a valid library
  (when library-exists
    (check-component-ref shape file page libraries))
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
  (check-empty-swap-slot shape file page)
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
                              "Nested main component only allowed inside other component"
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

          (if (ctn/inside-component-main? (:objects page) shape)
            (if-not (#{:main-top :main-nested :main-any} context)
              (report-error :not-head-main-not-allowed
                            "Non-root main only allowed inside a main component"
                            shape file page)
              (check-shape-main-not-root shape file page libraries))

            (if (#{:main-top :main-nested :main-any} context)
              (report-error :not-component-not-allowed
                            "Not compoments are not allowed inside a main"
                            shape file page)
              (check-shape-not-component shape file page libraries))))))))

(defn check-component-duplicate-swap-slot
  [component file]
  (let [shape (get-in component [:objects (:main-instance-id component)])]
    (when (has-duplicate-swap-slot? shape component)
      (report-error :component-duplicate-slot
                    "This deleted component has children with the same swap slot"
                    component file nil))))


(defn- check-component
  "Validate semantic coherence of a component. Report all errors found."
  [component file]
  (when (and (contains? component :objects) (nil? (:objects component)))
    (report-error :component-nil-objects-not-allowed
                  "Objects list cannot be nil"
                  component file nil))
  (when (:deleted component)
    (check-component-duplicate-swap-slot component file)))

(defn- get-orphan-shapes
  [{:keys [objects] :as page}]
  (let [xf (comp (map #(contains? objects (:parent-id %)))
                 (map :id))]
    (into [] xf (vals objects))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API: VALIDATION FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare check-swap-slots)

(defn validate-file
  "Validate full referential integrity and semantic coherence on file data.

  Return a list of errors or `nil`"
  [{:keys [data features] :as file} libraries]
  (when (contains? features "components/v2")
    (binding [*errors* (volatile! [])]

      (doseq [page (filter :id (ctpl/pages-seq data))]
        (check-shape uuid/zero file page libraries)
        (when (str/includes? (:name file) "check-swap-slot")
          (check-swap-slots uuid/zero file page libraries))
        (->> (get-orphan-shapes page)
             (run! #(check-shape % file page libraries))))

      (->> (vals (:components data))
           (run! #(check-component % file)))

      (-> *errors* deref not-empty))))

(defn validate-shape
  "Validate a shape and all its children. Returns a list of errors."
  [shape-id file page libraries]
  (binding [*errors* (volatile! [])]
    (check-shape shape-id file page libraries)
    (deref *errors*)))

(defn validate-component
  "Validate a component. Returns a list of errors."
  [component file]
  (binding [*errors* (volatile! [])]
    (check-component component file)
    (deref *errors*)))

(def ^:private valid-fdata?
  "Structural validation of file data using defined schema"
  (sm/lazy-validator ::ctf/data))

(def ^:private get-fdata-explain
  "Get schema explain data for file data"
  (sm/lazy-explainer ::ctf/data))

(defn validate-file-schema!
  "Validates the file itself, without external dependencies, it
  performs the schema checking and some semantical validation of the
  content."
  [{:keys [id data] :as file}]
  (when-not (valid-fdata? data)
    (ex/raise :type :validation
              :code :schema-validation
              :hint (str/ffmt "invalid file data structure found on file '%'" id)
              :file-id id
              ::sm/explain (get-fdata-explain data))))

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


(declare compare-slots)

;; Optional check to look for missing swap slots.
;; Search for copies that do not point the shape-ref to the near component but don't have swap slot
;; (looking for position relative to the parent, in the copy and the main).
;;
;; This check cannot be generally enabled, because files that have been migrated from components v1
;; may have copies with shapes that do not match by position, but have not been swapped. So we enable
;; it for specific files only. To activate the check, you need to add the string "check-swap-slot" to
;; the name of the file.
(defn- check-swap-slots
  [shape-id file page libraries]
  (let [shape (ctst/get-shape page shape-id)]
    (if (and (ctk/instance-root? shape) (ctk/in-component-copy? shape))
      (let [ref-shape (ctf/find-ref-shape file page libraries shape :include-deleted? true :with-context? true)
            container (:container (meta ref-shape))]
        (when (some? ref-shape)
          (compare-slots shape ref-shape file page container)))
      (doall (for [child-id (:shapes shape)]
               (check-swap-slots child-id file page libraries))))))

(defn- compare-slots
  [shape-copy shape-main file container-copy container-main]
  (if (and (not= (:shape-ref shape-copy) (:id shape-main))
           (nil? (ctk/get-swap-slot shape-copy)))
    (report-error :missing-slot
                  "Shape has been swapped, should have swap slot"
                  shape-copy file container-copy
                  :swap-slot (or (ctk/get-swap-slot shape-main) (:id shape-main)))
    (when (nil? (ctk/get-swap-slot shape-copy))
      (let [children-id-pairs (d/zip-all (:shapes shape-copy) (:shapes shape-main))]
        (doall (for [[child-copy-id child-main-id] children-id-pairs]
                 (let [child-copy (ctst/get-shape container-copy child-copy-id)
                       child-main (ctst/get-shape container-main child-main-id)]
                   (when (and (some? child-copy) (some? child-main))
                     (compare-slots child-copy child-main file container-copy container-main)))))))))
