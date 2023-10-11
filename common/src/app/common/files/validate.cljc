;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.validate
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.pages.helpers :as cph]
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
    :not-component-not-allowed})

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
(def ^:dynamic *throw-on-error* false)

(defn- report-error
  [code msg shape file page & args]
  (when (some? *errors*)
    (if (true? *throw-on-error*)
      (ex/raise {:type :validation
                 :code code
                 :hint msg
                 :args args
                 ::explain (str/format "file %s, page %s, shape %s"
                                       (:id file)
                                       (:id page)
                                       (:id shape))})
      (vswap! *errors* conj {:code code
                             :hint msg
                             :shape shape
                             :file-id (:id file)
                             :page-id (:id page)
                             :args args}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VALIDATION FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare validate-shape)

(defn validate-geometry
  "Validate that the shape has valid coordinates, selrect and points."
  [shape file page]
  (when (and (not (#{:path :bool} (:type shape)))
             (or (nil? (:x shape))       ; This may occur in root shape (uuid/zero) in old files
                 (nil? (:y shape))
                 (nil? (:width shape))
                 (nil? (:height shape))
                 (nil? (:selrect shape))
                 (nil? (:points shape))))
    (report-error :invalid-geometry
                  (str/format "Shape greometry is invalid")
                  shape file page)))

(defn validate-parent-children
  "Validate parent and children exists, and the link is bidirectional."
  [shape file page]
  (let [parent (ctst/get-shape page (:parent-id shape))]
    (if (nil? parent)
      (report-error :parent-not-found
                    (str/format "Parent %s not found" (:parent-id shape))
                    shape file page)
      (do
        (when-not (cph/root? shape)
          (when-not (some #{(:id shape)} (:shapes parent))
            (report-error :child-not-in-parent
                          (str/format "Shape %s not in parent's children list" (:id shape))
                          shape file page)))

        (doseq [child-id (:shapes shape)]
          (when (nil? (ctst/get-shape page child-id))
            (report-error :child-not-found
                          (str/format "Child %s not found" child-id)
                          shape file page
                          :child-id child-id)))))))

(defn validate-frame
  "Validate that the frame-id shape exists and is indeed a frame."
  [shape file page]
  (let [frame (ctst/get-shape page (:frame-id shape))]
    (if (nil? frame)
      (report-error :frame-not-found
                    (str/format "Frame %s not found" (:frame-id shape))
                    shape file page)
      (when (not= (:type frame) :frame)
        (report-error :invalid-frame
                      (str/format "Frame %s is not actually a frame" (:frame-id shape))
                      shape file page)))))

(defn validate-component-main-head
  "Validate shape is a main instance head, component exists and its main-instance points to this shape."
  [shape file page libraries]
  (when (nil? (:main-instance shape))
    (report-error :component-not-main
                  (str/format "Shape expected to be main instance")
                  shape file page))
  (when-not (= (:component-file shape) (:id file))
    (report-error :component-main-external
                  (str/format "Main instance should refer to a component in the same file")
                  shape file page))
  (let [component (ctf/resolve-component shape file libraries :include-deleted? true)]
    (if (nil? component)
      (report-error :component-not-found
                    (str/format "Component %s not found in file" (:component-id shape) (:component-file shape))
                    shape file page)
      (do
        (when-not (= (:main-instance-id component) (:id shape))
          (report-error :invalid-main-instance-id
                        (str/format "Main instance id of component %s is not valid" (:component-id shape))
                        shape file page))
        (when-not (= (:main-instance-page component) (:id page))
          (report-error :invalid-main-instance-page
                        (str/format "Main instance page of component %s is not valid" (:component-id shape))
                        shape file page))))))

(defn validate-component-not-main-head
  "Validate shape is a not-main instance head, component exists and its main-instance does not point to this shape."
  [shape file page libraries]
  (when (some? (:main-instance shape))
    (report-error :component-not-main
                  (str/format "Shape not expected to be main instance")
                  shape file page))
  (let [component (ctf/resolve-component shape file libraries {:include-deleted? true})]
    (if (nil? component)
      (report-error :component-not-found
                    (str/format "Component %s not found in file" (:component-id shape) (:component-file shape))
                    shape file page)
      (do
        (when (and (= (:main-instance-id component) (:id shape))
                   (= (:main-instance-page component) (:id page)))
          (report-error :invalid-main-instance
                        (str/format "Main instance of component %s should not be this shape" (:id component))
                        shape file page))))))

(defn validate-component-not-main-not-head
  "Validate that this shape is not main instance and not head."
  [shape file page]
  (when (some? (:main-instance shape))
    (report-error :component-main
                  (str/format "Shape not expected to be main instance")
                  shape file page))
  (when (or (some? (:component-id shape))
            (some? (:component-file shape)))
    (report-error :component-main
                  (str/format "Shape not expected to be component head")
                  shape file page)))

(defn validate-component-root
  "Validate that this shape is an instance root."
  [shape file page]
  (when (nil? (:component-root shape))
    (report-error :should-be-component-root
                  (str/format "Shape should be component root")
                  shape file page)))

(defn validate-component-not-root
  "Validate that this shape is not an instance root."
  [shape file page]
  (when (some? (:component-root shape))
    (report-error :should-not-be-component-root
                  (str/format "Shape should not be component root")
                  shape file page)))

(defn validate-component-ref
  "Validate that the referenced shape exists in the near component."
  [shape file page libraries]
  (let [ref-shape (ctf/find-ref-shape file page libraries shape :include-deleted? true)]
    (when (nil? ref-shape)
      (report-error :ref-shape-not-found
                    (str/format "Referenced shape %s not found in near component" (:shape-ref shape))
                    shape file page))))

(defn validate-component-not-ref
  "Validate that this shape does not reference other one."
  [shape file page]
  (when (some? (:shape-ref shape))
    (report-error :shape-ref-in-main
                  (str/format "Shape inside main instance should not have shape-ref")
                  shape file page)))

(defn validate-shape-main-root-top
  "Root shape of a top main instance
     :main-instance
     :component-id
     :component-file
     :component-root"
  [shape file page libraries]
  (validate-component-main-head shape file page libraries)
  (validate-component-root shape file page)
  (validate-component-not-ref shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :main-top)))

(defn validate-shape-main-root-nested
  "Root shape of a nested main instance
     :main-instance
     :component-id
     :component-file"
  [shape file page libraries]
  (validate-component-main-head shape file page libraries)
  (validate-component-not-root shape file page)
  (validate-component-not-ref shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :main-nested)))

(defn validate-shape-copy-root-top
  "Root shape of a top copy instance
     :component-id
     :component-file
     :component-root
     :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-head shape file page libraries)
  (validate-component-root shape file page)
  (validate-component-ref shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :copy-top)))

(defn validate-shape-copy-root-nested
  "Root shape of a nested copy instance
     :component-id
     :component-file
     :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-head shape file page libraries)
  (validate-component-not-root shape file page)
  (validate-component-ref shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :copy-nested)))

(defn validate-shape-main-not-root
  "Not-root shape of a main instance
     (not any attribute)"
  [shape file page libraries]
  (validate-component-not-main-not-head shape file page)
  (validate-component-not-root shape file page)
  (validate-component-not-ref shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :main-any)))

(defn validate-shape-copy-not-root
  "Not-root shape of a copy instance
     :shape-ref"
  [shape file page libraries]
  (validate-component-not-main-not-head shape file page)
  (validate-component-not-root shape file page)
  (validate-component-ref shape file page libraries)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :copy-any)))

(defn validate-shape-not-component
  "Shape is not in a component or is a fostered children
     (not any attribute)"
  [shape file page libraries]
  (validate-component-not-main-not-head shape file page)
  (validate-component-not-root shape file page)
  (validate-component-not-ref shape file page)
  (doseq [child-id (:shapes shape)]
    (validate-shape child-id file page libraries :context :not-component)))

(defn validate-shape
  "Validate referential integrity and semantic coherence of a shape and all its children.

   The context is the situation of the parent in respect to components:
     :not-component
     :main-top
     :main-nested
     :copy-top
     :copy-nested
     :main-any
     :copy-any"
  [shape-id file page libraries & {:keys [context throw?]
                                   :or {context :not-component
                                        throw? nil}}]
  (binding [*throw-on-error* (if (some? throw?) throw? *throw-on-error*)
            *errors* (or *errors* (volatile! []))]
    (let [shape (ctst/get-shape page shape-id)]

                                        ; If this happens it's a bug in this validate functions
      (dm/verify! (str/format "Shape %s not found" shape-id) (some? shape))

      (validate-geometry shape file page)
      (validate-parent-children shape file page)
      (validate-frame shape file page)

    (validate-parent-children shape file page)
    (validate-frame shape file page)

    (if (ctk/instance-head? shape)
      
      (if (ctk/instance-root? shape)

        (if (ctk/main-instance? shape)
          (if (not= context :not-component)
            (report-error :root-main-not-allowed
                          (str/format "Root main component not allowed inside other component")
                          shape file page)
            (validate-shape-main-root-top shape file page libraries))

          (if (not= context :not-component)
            (report-error :root-copy-not-allowed
                          (str/format "Root copy component not allowed inside other component")
                          shape file page)
            (validate-shape-copy-root-top shape file page libraries)))

        (if (ctk/main-instance? shape)
          (if (= context :not-component)
            (report-error :nested-main-not-allowed
                          (str/format "Nested main component only allowed inside other component")
                          shape file page)
            (validate-shape-main-root-nested shape file page libraries))

          (if (= context :not-component)
            (report-error :nested-copy-not-allowed
                          (str/format "Nested copy component only allowed inside other component")
                          shape file page)
            (validate-shape-copy-root-nested shape file page libraries))))

      (if (ctk/in-component-copy? shape)
        (if-not (#{:copy-top :copy-nested :copy-any} context)
          (report-error :not-head-copy-not-allowed
                        (str/format "Non-root copy only allowed inside a copy")
                        shape file page)
          (validate-shape-copy-not-root shape file page libraries))

        (if (ctn/inside-component-main? (:objects page) shape)
          (if-not (#{:main-top :main-nested :main-any} context)
            (report-error :not-head-main-not-allowed
                          (str/format "Non-root main only allowed inside a main component")
                          shape file page)
            (validate-shape-main-not-root shape file page libraries))

          (if (#{:main-top :main-nested :main-any} context)
            (report-error :not-component-not-allowed
                          (str/format "Not compoments are not allowed inside a main")
                          shape file page)
            (validate-shape-not-component shape file page libraries)))))

          (deref *errors*))))

(defn validate-file
  "Validate referencial integrity and semantic coherence of all contents of a file."
  [file libraries & {:keys [throw?] :or {throw? false}}]
  (binding [*throw-on-error* throw?
            *errors* (volatile! [])]
    (->> (ctpl/pages-seq (:data file))
         (filter #(some? (:id %)))
         (run! #(validate-shape uuid/zero file % libraries :throw? throw?)))

    (deref *errors*)))
