;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variants-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :each thi/test-fixture)

#?(:cljs
   (defn- error-codes
     [error]
     (into #{} (map :code) (:details error))))

#?(:cljs
   (defn- validation-error
     "Apply the changes and return the ex-data of the :validate-shapes
     error they raise, or nil if they raise none."
     [file changes]
     (try
       (thf/apply-changes file changes :validate? false)
       nil
       (catch :default e
         (let [data (ex-data e)]
           (when (= :referential-integrity (:code data))
             data))))))

(defn- add-two-variants
  "Add two variant containers, :v01 named \"Board\" and :v02 named \"Other\"."
  [file]
  (-> file
      (thv/add-variant :v01 :c01 :m01 :c02 :m02)
      (thv/add-variant :v02 :c03 :m03 :c04 :m04)
      (ths/update-shape :v02 :name "Other")
      (ths/update-shape :m03 :name "Other")
      (ths/update-shape :m04 :name "Other")
      (thc/update-component :c03 {:name "Other"})
      (thc/update-component :c04 {:name "Other"})))

(defn- paste-changes
  "Build the changes of pasting `shape-label` into `parent-label`, as the
  workspace paste does: the pasted shape is re-parented before duplicating."
  [file shape-label parent-label]
  (let [page      (thf/current-page file)
        shape-id  (thi/id shape-label)
        parent-id (thi/id parent-label)
        objects   (-> (:objects page)
                      (update shape-id assoc :parent-id parent-id :frame-id parent-id))]
    (-> (pcb/empty-changes nil)
        (pcb/with-page-id (:id page))
        (pcb/with-library-data (:data file))
        (pcb/with-objects (:objects page))
        (cll/generate-duplicate-changes objects
                                        page
                                        #{shape-id}
                                        (gpt/point 0 0)
                                        {(:id file) file}
                                        (:data file)
                                        (:id file)))))

(t/deftest test-duplicate-variant-container
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        data    (:data file)
        page    (thf/current-page file)
        objects (:objects page)

        variant-container (ths/get-shape file :v01)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page-id (:id page))
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (cll/generate-duplicate-changes objects                    ;; objects
                                                    page                       ;; page
                                                    #{(:id variant-container)} ;; ids
                                                    (gpt/point 0 0)            ;; delta
                                                    {(:id  file) file}         ;; libraries
                                                    (:data file)               ;; library-data
                                                    (:id file)))               ;; file-id

        ;; ==== Get
        file'   (thf/apply-changes file changes)
        data'   (:data file')
        page'   (thf/current-page file')
        objects' (:objects page')]

    ;; ==== Check
    (thf/validate-file! file')
    (t/is (= (count (:components data)) 2))
    (t/is (= (count (:components data')) 4))
    (t/is (= (count objects) 4))
    (t/is (= (count objects') 7))))

(t/deftest test-delete-variant
  ;; When a variant container becomes empty, it id automatically deleted
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant-two-properties :v01 :c01 :m01 :c02 :m02))
        container (ths/get-shape file :v01)
        m01-id    (-> (ths/get-shape file :m01) :id)
        m02-id    (-> (ths/get-shape file :m02) :id)

        page    (thf/current-page file)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-page page)
                    (pcb/with-library-data (:data file))
                    (pcb/with-objects (:objects page))
                    (#(second (cls/generate-delete-shapes % #{m01-id m02-id} {}))))

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        container' (ths/get-shape file' :v01)]

    ;; ==== Check
    ;; The variant containew was not nil before the deletion
    (t/is (not (nil? container)))
    ;; The variant containew is nil after the deletion
    (t/is (nil? container'))))

(t/deftest test-instantiate-component-over-variant-container
  ;; When a component is instantiated at a position over a variant container,
  ;; the new copy must not become a child of the container (its children can
  ;; only be variant mains)
  (let [;; ==== Setup
        file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        container (ths/get-shape file :v01)
        page      (thf/current-page file)

        ;; ==== Action
        ;; Instantiate at a position inside the variant container, without
        ;; an explicit parent, so the destiny frame is chosen by position
        [new-shape changes]
        (cll/generate-instantiate-component (-> (pcb/empty-changes nil (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (:objects page)
                                            (:id file)
                                            (thi/id :c01)
                                            (gpt/point (:x container) (:y container))
                                            page
                                            {(:id file) file})

        file'      (thf/apply-changes file changes)

        ;; ==== Get
        new-shape' (ths/get-shape-by-id file' (:id new-shape))]

    ;; ==== Check
    (thf/validate-file! file')
    (t/is (some? new-shape'))
    (t/is (not= (:parent-id new-shape') (:id container)))))

(t/deftest test-paste-variant-into-another-container
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    add-two-variants)

        ;; ==== Action
        changes (paste-changes file :m01 :v02)
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        v02'    (ths/get-shape file' :v02)
        new-id  (last (:shapes v02'))
        new'    (ths/get-shape-by-id file' new-id)
        c03'    (thc/get-component file' :c03)
        newc'   (thc/get-component-by-id file' (:component-id new'))]

    ;; ==== Check
    (t/is (= 3 (count (:shapes v02'))))
    (t/is (= (:id v02') (:variant-id new')))
    (t/is (= (mapv :name (:variant-properties c03'))
             (mapv :name (:variant-properties newc'))))))

#?(:cljs
   (t/deftest test-paste-variant-into-another-container-validates
     (let [file    (-> (thf/sample-file :file1)
                       add-two-variants)
           changes (paste-changes file :m01 :v02)]

       (t/is (nil? (validation-error file changes))))))

#?(:cljs
   (t/deftest test-duplicate-variant-in-same-container-validates
     (let [file    (-> (thf/sample-file :file1)
                       add-two-variants)
           changes (paste-changes file :m01 :v01)]

       (t/is (nil? (validation-error file changes))))))

#?(:cljs
   (t/deftest test-duplicate-variant-in-same-container-detects-errors
     (let [file    (-> (thf/sample-file :file1)
                       add-two-variants
                       (ths/update-shape :m02 :variant-id (uuid/next)))
           changes (paste-changes file :m01 :v01)
           error   (validation-error file changes)]

       ;; Ctrl+D inside the same container also updates the last property value
       (t/is (str/includes? (:hint error) "generate-update-property-value")))))

(defn- relocate-changes
  [file shape-label parent-label]
  (let [page (thf/current-page file)]
    (cls/generate-relocate (-> (pcb/empty-changes nil)
                               (pcb/with-page-id (:id page))
                               (pcb/with-library-data (:data file))
                               (pcb/with-objects (:objects page)))
                           (thi/id parent-label) 0 #{(thi/id shape-label)})))

(t/deftest test-relocate-variant-into-another-container
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    add-two-variants)

        ;; ==== Action
        changes (relocate-changes file :m01 :v02)
        file'   (thf/apply-changes file changes)

        ;; ==== Get
        m01'    (ths/get-shape file' :m01)
        c01'    (thc/get-component file' :c01)]

    ;; ==== Check
    (t/is (= (thi/id :v02) (:parent-id m01')))
    (t/is (= (thi/id :v02) (:variant-id m01')))
    (t/is (= (thi/id :v02) (:variant-id c01')))))

#?(:cljs
   (t/deftest test-relocate-variant-into-another-container-validates
     (let [file    (-> (thf/sample-file :file1)
                       add-two-variants)
           changes (relocate-changes file :m01 :v02)]

       (t/is (nil? (validation-error file changes))))))

;; The workspace does not allow moving a main instance inside a component
;; (see ctn/invalid-structure-for-component?), but relocate may be called
;; directly. The moved variant is not a root anymore, so relocate validates
;; the root of the component that holds it.
#?(:cljs
   (t/deftest test-relocate-variant-into-component-validates-root
     (let [file    (-> (thf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (tho/add-simple-component :c03 :m03 :b03
                                                 :child-params {:type :frame})
                       (ths/update-shape :m03 :component-file (uuid/next)))
           changes (relocate-changes file :m01 :b03)
           error   (validation-error file changes)]

       (t/is (str/includes? (:hint error) "generate-relocate"))
       (t/is (contains? (error-codes error) :component-main-external)))))
