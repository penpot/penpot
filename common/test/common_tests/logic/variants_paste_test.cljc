;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.variants-paste-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.validate :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.variant-properties :as clvp]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [app.common.types.component :as ctk]
   [app.common.types.variant :as ctv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- paste-variants-into-container
  "Paste individual variants from file-a into file-b's variant container.
   Omit file-a from `libraries` to hit the unknown-component path; include
   it to hit `duplicate-variant`. Optional `variant-props` mirrors clipboard
   collection (Task 2)."
  [file-a file-b source-labels target-container-label
   & {:keys [libraries variant-props]
      :or   {libraries {(:id file-b) file-b}}}]
  (let [page-a      (thf/current-page file-a)
        page-b      (thf/current-page file-b)
        container-b (ths/get-shape file-b target-container-label)
        parent-id   (:id container-b)

        source-ids  (mapv thi/id source-labels)
        tree-ids    (into []
                          (mapcat #(cfh/get-children-ids-with-self (:objects page-a) %))
                          source-ids)

        pasted      (->> tree-ids
                         (map (d/getf (:objects page-a)))
                         (map (fn [shape]
                                (cond-> shape
                                  (contains? (set source-ids) (:id shape))
                                  (assoc :parent-id parent-id
                                         :frame-id parent-id))))
                         (d/index-by :id))

        all-objects (merge (:objects page-b) pasted)]
    (-> (pcb/empty-changes nil)
        (pcb/with-page-id (:id page-b))
        (pcb/with-library-data (:data file-b))
        (pcb/with-objects all-objects)
        (cll/generate-duplicate-changes all-objects
                                        page-b
                                        (set source-ids)
                                        (gpt/point 0 0)
                                        libraries
                                        (:data file-b)
                                        (:id file-b)
                                        {:variant-props variant-props}))))

(defn- property-names
  [component]
  (mapv :name (:variant-properties component)))

(defn- variant-mains-in
  [file container-label]
  (let [container (ths/get-shape file container-label)]
    (->> (vals (:objects (thf/current-page file)))
         (filter ctk/main-instance?)
         (filter #(= (:id container) (:variant-id %))))))

(defn- other-set-file
  []
  (-> (thf/sample-file :file-b)
      (thv/add-variant-two-properties :vb :cb1 :mb1 :cb2 :mb2)
      (ths/update-shape :vb :name "Other")
      (ths/update-shape :mb1 :name "Other")
      (ths/update-shape :mb2 :name "Other")
      (thc/update-component :cb1 {:name "Other"})
      (thc/update-component :cb2 {:name "Other"})))

(t/deftest test-paste-unknown-variant-into-container-validates
  ;; #10704: paste individual variants from file A into file B's set
  ;; when A is not in libraries must leave a valid file (desired behavior).
  (let [file-a  (-> (thf/sample-file :file-a)
                    (thv/add-variant :va :ca1 :ma1 :ca2 :ma2))
        file-b  (other-set-file)
        changes (paste-variants-into-container file-a file-b [:ma1 :ma2] :vb)
        file'   (thf/apply-changes file-b changes :validate? false)
        errors  (cfv/validate-file file' {})
        pasted  (->> (variant-mains-in file' :vb)
                     (remove #(#{(thi/id :mb1) (thi/id :mb2)} (:id %))))]

    (t/is (nil? errors))
    (doseq [shape pasted]
      (let [comp (thc/get-component-by-id file' (:component-id shape))]
        (t/is (= ["Property 1" "Property 2"] (property-names comp)))
        (t/is (= "Other" (:name shape)))
        (t/is (= (:variant-name shape)
                 (ctv/properties-to-name (:variant-properties comp))))))))

(t/deftest test-duplicate-variant-in-own-container-bumps-last-value
  ;; Same-container duplicate via duplicate-variant: originals keep their
  ;; props; the new main gets a bumped last-property value. Does not cover
  ;; generate-make-shapes-variant's already-aligned early-exit (new mains
  ;; enter with a nil variant-id).
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        c01-props (:variant-properties (thc/get-component file :c01))
        c02-props (:variant-properties (thc/get-component file :c02))
        file'     (tho/duplicate-shape file :m01)
        mains     (variant-mains-in file' :v01)
        new-main  (->> mains
                       (remove #(#{(thi/id :m01) (thi/id :m02)} (:id %)))
                       first)
        new-comp  (thc/get-component-by-id file' (:component-id new-main))]

    (thf/validate-file! file')
    (t/is (= 3 (count mains)))
    (t/is (= c01-props (:variant-properties (thc/get-component file' :c01))))
    (t/is (= c02-props (:variant-properties (thc/get-component file' :c02))))
    (t/is (= ["Property 1"] (property-names new-comp)))
    (t/is (= (str ctv/value-prefix 3)
             (-> new-comp :variant-properties last :value)))))

(t/deftest test-make-shapes-variant-skips-when-already-aligned
  ;; Locks the narrowed early-exit: a fully aligned child must not have its
  ;; props or variant-name rewritten.
  (let [file      (-> (thf/sample-file :file1)
                      (thv/add-variant :v01 :c01 :m01 :c02 :m02))
        page      (thf/current-page file)
        container (ths/get-shape file :v01)
        shape     (ths/get-shape file :m01)
        before    (:variant-properties (thc/get-component file :c01))
        before-vn (:variant-name shape)
        changes   (-> (pcb/empty-changes nil)
                      (pcb/with-page page)
                      (pcb/with-objects (:objects page))
                      (pcb/with-library-data (:data file))
                      (clvp/generate-make-shapes-variant [shape] container))
        file'     (thf/apply-changes file changes)
        shape'    (ths/get-shape-by-id file' (:id shape))]

    (t/is (= before (:variant-properties (thc/get-component file' :c01))))
    (t/is (= before-vn (:variant-name shape')))))

(t/deftest test-paste-known-variant-into-other-container-aligns
  ;; file A present in libraries → duplicate-variant path; props must match
  ;; the target set and the file must validate.
  (let [file-a    (-> (thf/sample-file :file-a)
                      (thv/add-variant :va :ca1 :ma1 :ca2 :ma2))
        file-b    (other-set-file)
        libraries {(:id file-a) file-a
                   (:id file-b) file-b}
        changes   (paste-variants-into-container file-a file-b [:ma1 :ma2] :vb
                                                 :libraries libraries)
        file'     (thf/apply-changes file-b changes :validate? false)
        errors    (cfv/validate-file file' {})
        pasted    (->> (variant-mains-in file' :vb)
                       (remove #(#{(thi/id :mb1) (thi/id :mb2)} (:id %))))]

    (t/is (nil? errors))
    (t/is (= 2 (count pasted)))
    (doseq [shape pasted]
      (let [comp (thc/get-component-by-id file' (:component-id shape))]
        (t/is (= ["Property 1" "Property 2"] (property-names comp)))
        (t/is (= "Other" (:name shape)))
        (t/is (= (:variant-name shape)
                 (ctv/properties-to-name (:variant-properties comp))))))))

(t/deftest test-paste-unknown-preserves-matching-property-values
  ;; Clipboard props (Task 2) whose names exist on the target survive regenerate.
  (let [file-a (-> (thf/sample-file :file-a)
                   (thv/add-variant :va :ca1 :ma1 :ca2 :ma2))
        file-b (other-set-file)
        props  {(:id (thc/get-component file-a :ca1))
                (:variant-properties (thc/get-component file-a :ca1))
                (:id (thc/get-component file-a :ca2))
                (:variant-properties (thc/get-component file-a :ca2))}
        changes (paste-variants-into-container file-a file-b [:ma1 :ma2] :vb
                                               :variant-props props)
        file'   (thf/apply-changes file-b changes :validate? false)
        pasted  (->> (variant-mains-in file' :vb)
                     (remove #(#{(thi/id :mb1) (thi/id :mb2)} (:id %)))
                     (map #(thc/get-component-by-id file' (:component-id %))))
        values  (->> pasted
                     (mapcat :variant-properties)
                     (filter #(= "Property 1" (:name %)))
                     (map :value)
                     set)]

    (thf/validate-file! file')
    (t/is (contains? values "Value1"))
    (t/is (contains? values "Value2"))))
