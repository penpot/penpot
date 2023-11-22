;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.validate
  (:require
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
    :child-not-found
    :frame-not-found
    :invalid-frame
    :component-not-main
    :component-main-external
    :component-not-found
    :invalid-main-instance-id
    :invalid-main-instance-page
    :invalid-main-instance
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
    :component-nil-objects-not-allowed})

(def validation-error
  [:map {:title "ValidationError"}
   [:code {:optional false} [::sm/one-of error-codes]]
   [:hint {:optional false} :string]
   [:shape {:optional true} :map] ; Cannot validate a shape because here it may be broken
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR HANDLING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *errors* nil)

(defn report-error!
  [code hint shape file page & {:as args}]
  (if (some? *errors*)
    (vswap! *errors* conj {:code code
                           :hint hint
                           :shape shape
                           :file-id (:id file)
                           :page-id (:id page)
                           :args args})

    (let [explain (str/ffmt "file %, page %, shape %"
                            (:id file)
                            (:id page)
                            (:id shape))]
      (ex/raise :type :validation
                :code code
                :hint hint
                :args args
                :file-id (:id file)
                :page-id (:id page)
                :shape-id (:id shape)
                ::explain explain))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VALIDATION FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare validate-shape!)

(defn validate-geometry!
  "Validate that the shape has valid coordinates, selrect and points."
  [shape file page]
  (when (and (not (#{:path :bool} (:type shape)))
             (or (nil? (:x shape))       ; This may occur in root shape (uuid/zero) in old files
                 (nil? (:y shape))
                 (nil? (:width shape))
                 (nil? (:height shape))
                 (nil? (:selrect shape))
                 (nil? (:points shape))))
    (report-error! :invalid-geometry
                   "Shape greometry is invalid"
                   shape file page)))

(defn validate-parent-children!
  "Validate parent and children exists, and the link is bidirectional."
  [shape file page]
  (let [parent (ctst/get-shape page (:parent-id shape))]
    (if (nil? parent)
      (report-error! :parent-not-found
                     (str/ffmt "Parent % not found" (:parent-id shape))
                     shape file page)
      (do
        (when-not (cfh/root? shape)
          (when-not (some #{(:id shape)} (:shapes parent))
            (report-error! :child-not-in-parent
                           (str/ffmt "Shape % not in parent's children list" (:id shape))
                           shape file page)))

        (doseq [child-id (:shapes shape)]
          (let [child (ctst/get-shape page child-id)]
            (when (or (nil? child) (not= (:parent-id child) (:id shape)))
              (report-error! :child-not-found
                (str/ffmt "Child % not found" child-id)
                child file page
                :parent-id (:id shape)))))))))

(defn validate-frame!
  "Validate that the frame-id shape exists and is indeed a frame. Also
  it must point to the parent shape (if this is a frame) or to the
  frame-id of the parent (if not)."
  [shape file page]
  (let [frame (ctst/get-shape page (:frame-id shape))]
    (if (nil? frame)
      (report-error! :frame-not-found
                     (str/ffmt "Frame % not found" (:frame-id shape))
                     shape file page)
      (if (not= (:type frame) :frame)
        (report-error! :invalid-frame
                       (str/ffmt "Frame % is not actually a frame" (:frame-id shape))
                       shape file page)
        (let [parent (ctst/get-shape page (:parent-id shape))]
          (when (some? parent)
            (if (= (:type parent) :frame)
              (when-not (= (:frame-id shape) (:id parent))
                (report-error! :invalid-frame
                               (str/ffmt "Frame-id should point to parent %" (:id parent))
                               shape file page))
              (when-not (= (:frame-id shape) (:frame-id parent))
                (report-error! :invalid-frame
                               (str/ffmt "Frame-id should point to parent frame %" (:frame-id parent))
                               shape file page)))))))))

(defn validate-component-main-head!
  "Validate shape is a main instance head, component exists
  and its main-instance points to this shape."
  [shape file page libraries]
  (when (nil? (:main-instance shape))
    (report-error! :component-not-main
                   "Shape expected to be main instance"
                   shape file page))
  (when-not (= (:component-file shape) (:id file))
    (report-error! :component-main-external
                   "Main instance should refer to a component in the same file"
                   shape file page))
  (let [component (ctf/resolve-component shape file libraries :include-deleted? true)]
    (if (nil? component)
      (report-error! :component-not-found
                     (str/ffmt "Component % not found in file %" (:component-id shape) (:component-file shape))
                     shape file page)
      (do
        (when-not (= (:main-instance-id component) (:id shape))
          (report-error! :invalid-main-instance-id
                         (str/ffmt "Main instance id of component % is not valid" (:component-id shape))
                         shape file page))
        (when-not (= (:main-instance-page component) (:id page))
          (report-error! :invalid-main-instance-page
                         (str/ffmt "Main instance page of component % is not valid" (:component-id shape))
                         shape file page))))))

(defn validate-component-not-main-head!
  "Validate shape is a not-main instance head, component
  exists and its main-instance does not point to this
  shape."
  [shape file page libraries]
  (when (true? (:main-instance shape))
    (report-error! :component-not-main
                   "Shape not expected to be main instance"
                   shape file page))

  (let [library-exists? (or (= (:component-file shape) (:id file))
                           (contains? libraries (:component-file shape)))
        component (when library-exists?
                    (ctf/resolve-component shape file libraries {:include-deleted? true}))]
    (if (nil? component)
      (when library-exists?
        (report-error! :component-not-found
                       (str/ffmt "Component % not found in file %" (:component-id shape) (:component-file shape))
                       shape file page))
      (when (and (= (:main-instance-id component) (:id shape))
                 (= (:main-instance-page component) (:id page)))
        (report-error! :invalid-main-instance
                       (str/ffmt "Main instance of component % should not be this shape" (:id component))
                       shape file page)))))

(defn validate-component-not-main-not-head!
  "Validate that this shape is not main instance and not head."
  [shape file page]
  (when (true? (:main-instance shape))
    (report-error! :component-main
                   "Shape not expected to be main instance"
                   shape file page))
  (when (or (some? (:component-id shape))
            (some? (:component-file shape)))
    (report-error! :component-main
                   "Shape not expected to be component head"
                   shape file page)))

(defn validate-component-root!
  "Validate that this shape is an instance root."
  [shape file page]
  (when (nil? (:component-root shape))
    (report-error! :should-be-component-root
                   "Shape should be component root"
                   shape file page)))

(defn validate-component-not-root!
  "Validate that this shape is not an instance root."
  [shape file page]
  (when (true? (:component-root shape))
    (report-error! :should-not-be-component-root
                   "Shape should not be component root"
                   shape file page)))

(defn validate-component-ref!
  "Validate that the referenced shape exists in the near component."
  [shape file page libraries]
  (let [library-exists? (or (= (:component-file shape) (:id file))
                           (contains? libraries (:component-file shape)))
        ref-shape (when library-exists?
                    (ctf/find-ref-shape file page libraries shape :include-deleted? true))]
    (when (and library-exists? (nil? ref-shape))
      (report-error! :ref-shape-not-found
                     (str/ffmt "Referenced shape % not found in near component" (:shape-ref shape))
                     shape file page))))

(defn validate-component-not-ref!
  "Validate that this shape does not reference other one."
  [shape file page]
  (when (some? (:shape-ref shape))
    (report-error! :shape-ref-in-main
                   "Shape inside main instance should not have shape-ref"
                   shape file page)))

(defn validate-shape-main-root-top!
  "Root shape of a top main instance:

   - :main-instance
   - :component-id
   - :component-file
   - :component-root"
  [shape file page libraries]
  (validate-component-main-head! shape file page libraries)
  (validate-component-root! shape file page)
  (validate-component-not-ref! shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :main-top)))

(defn validate-shape-main-root-nested!
  "Root shape of a nested main instance
   - :main-instance
   - :component-id
   - :component-file"
  [shape file page libraries]
  (validate-component-main-head! shape file page libraries)
  (validate-component-not-root! shape file page)
  (validate-component-not-ref! shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :main-nested)))

(defn validate-shape-copy-root-top!
  "Root shape of a top copy instance
   - :component-id
   - :component-file
   - :component-root
   - :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-head! shape file page libraries)
  (validate-component-root! shape file page)
  (validate-component-ref! shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :copy-top)))

(defn validate-shape-copy-root-nested!
  "Root shape of a nested copy instance
   - :component-id
   - :component-file
   - :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-head! shape file page libraries)
  (validate-component-not-root! shape file page)
  (validate-component-ref! shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :copy-nested)))

(defn validate-shape-main-not-root!
  "Not-root shape of a main instance (not any attribute)"
  [shape file page libraries]
  (validate-component-not-main-not-head! shape file page)
  (validate-component-not-root! shape file page)
  (validate-component-not-ref! shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :main-any)))

(defn validate-shape-copy-not-root!
  "Not-root shape of a copy instance :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-not-head! shape file page)
  (validate-component-not-root! shape file page)
  (validate-component-ref! shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :copy-any)))

(defn validate-shape-not-component!
  "Shape is not in a component or is a fostered children (not any
  attribute)"
  [shape file page libraries]
  (validate-component-not-main-not-head! shape file page)
  (validate-component-not-root! shape file page)
  (validate-component-not-ref! shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape! child-id file page libraries :context :not-component)))

(defn validate-shape!
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
  [shape-id file page libraries & {:keys [context] :or {context :not-component}}]
  (let [shape (ctst/get-shape page shape-id)]

    ;; If this happens it's a bug in this validate functions
    (dm/verify!
     ["Shape % not found" shape-id]
     (some? shape))

    (validate-geometry! shape file page)
    (validate-parent-children! shape file page)
    (validate-frame! shape file page)

    (if (ctk/instance-head? shape)
      (if (not= :frame (:type shape))
        (report-error! :instance-head-not-frame
                       "Instance head should be a frame"
                       shape file page)

        (if (ctk/instance-root? shape)
          (if (ctk/main-instance? shape)
            (if (not= context :not-component)
              (report-error! :root-main-not-allowed
                             "Root main component not allowed inside other component"
                             shape file page)
              (validate-shape-main-root-top! shape file page libraries))

            (if (not= context :not-component)
              (report-error! :root-copy-not-allowed
                             "Root copy component not allowed inside other component"
                             shape file page)
              (validate-shape-copy-root-top! shape file page libraries)))

          (if (ctk/main-instance? shape)
            (if (= context :not-component)
              (report-error! :nested-main-not-allowed
                             "Nested main component only allowed inside other component"
                             shape file page)
              (validate-shape-main-root-nested! shape file page libraries))

            (if (= context :not-component)
              (report-error! :nested-copy-not-allowed
                             "Nested copy component only allowed inside other component"
                             shape file page)
              (validate-shape-copy-root-nested! shape file page libraries)))))

      (if (ctk/in-component-copy? shape)
        (if-not (#{:copy-top :copy-nested :copy-any} context)
          (report-error! :not-head-copy-not-allowed
                         "Non-root copy only allowed inside a copy"
                         shape file page)
          (validate-shape-copy-not-root! shape file page libraries))

        (if (ctn/inside-component-main? (:objects page) shape)
          (if-not (#{:main-top :main-nested :main-any} context)
            (report-error! :not-head-main-not-allowed
                           "Non-root main only allowed inside a main component"
                           shape file page)
            (validate-shape-main-not-root! shape file page libraries))

          (if (#{:main-top :main-nested :main-any} context)
            (report-error! :not-component-not-allowed
                           "Not compoments are not allowed inside a main"
                           shape file page)
            (validate-shape-not-component! shape file page libraries)))))))

(defn validate-shape
  "Validate a shape and all its children. Returns a list of errors."
  [shape-id file page libraries]
  (binding [*errors* (volatile! [])]
    (validate-shape! shape-id file page libraries)
    (deref *errors*)))

(defn validate-component!
  "Validate semantic coherence of a component. Report all errors found."
  [component file]
  (when (and (contains? component :objects) (nil? (:objects component)))
    (report-error! :component-nil-objects-not-allowed
                   "Objects list cannot be nil"
                   component file nil)))

(defn validate-component
  "Validate a component. Returns a list of errors."
  [component file]
  (binding [*errors* (volatile! [])]
    (validate-component! component file)
    (deref *errors*)))

(def valid-fdata?
  "Structural validation of file data using defined schema"
  (sm/lazy-validator ::ctf/data))

(def get-fdata-explain
  "Get schema explain data for file data"
  (sm/lazy-explainer ::ctf/data))

(defn validate-file-schema!
  [{:keys [id data] :as file}]
  (when-not (valid-fdata? data)
    (if (some? *errors*)
      (vswap! *errors* conj
              {:code :invalid-file-data-structure
               :hint (str/ffmt "invalid file data structure found on file '%'" id)
               :file-id id})
      (ex/raise :type :validation
                :code :data-validation
                :hint (str/ffmt "invalid file data structure found on file '%'" id)
                :file-id id
                ::sm/explain (get-fdata-explain data))))
  file)

(defn validate-file!
  "Validate full referential integrity and semantic coherence on file data.

  Raises a validation exception on first error found."
  [{:keys [data features] :as file} libraries]
  (when (contains? features "components/v2")

    (doseq [page (filter :id (ctpl/pages-seq data))]
      (validate-shape! uuid/zero file page libraries))

    (doseq [component (vals (:components data))]
      (validate-component! component file)))

  file)

(defn validate-file
  "Validate structure, referencial integrity and semantic coherence of
  all contents of a file. Returns a list of errors."
  [file libraries]
  (binding [*errors* (volatile! [])]
    (validate-file-schema! file)
    (validate-file! file libraries)
    (deref *errors*)))
