;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.repair-components-test
  "One test per component error code of `app.common.files.validate`: each
   builds a file broken in one way, checks the codes validation reports,
   repairs it as the backend does (validate and repair until valid, at most
   ten passes) and checks the result.

   Three codes have no test because no file can produce them: the checks that
   report `:component-not-main`, `:should-be-component-root` and
   `:not-component-not-allowed` only run on shapes that already satisfy them."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; The backend repairs a file at most this many times (`app.srepl.procs.file-repair`).
(def ^:private max-passes 10)

(defn- codes
  [errors]
  (into #{} (map :code) errors))

(defn- repair
  "Validate `file` and repair it until it validates, as the backend does.
   Returns `[codes file' errors']`: the codes found first, the repaired file
   and the errors left after the last pass (nil when it validates)."
  ([file] (repair file {}))
  ([file libraries]
   (let [errors (cfv/validate-file file libraries)]
     (loop [file   file
            errs   errors
            passes 0]
       (if (or (nil? errs) (= passes max-passes))
         [(codes errors) file errs]
         (let [changes (cfr/repair-file file libraries errs)
               file'   (thf/apply-changes file {:redo-changes changes} :validate? false)]
           (recur file' (cfv/validate-file file' libraries) (inc passes))))))))

;; ---- Configurations

(defn- simple
  "A component (:main-root with :main-child) and a copy (:copy-root with
   :copy-child)."
  [& {:as params}]
  (-> (thf/sample-file :file1 :page-label :page1)
      (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                          (merge {:copy-root-params {:children-labels [:copy-child]}}
                                                 params))))

(defn- nested
  "Component2 (:main2-root) nests a copy of component1 (:main2-nested-head);
   its copy :copy2-root holds :copy2-nested-head."
  []
  (-> (thf/sample-file :file1 :page-label :page1)
      (tho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                          :component2 :main2-root :main2-nested-head
                                          :copy2-root
                                          :copy2-root-params {:children-labels [:copy2-nested-head :copy2-child]})))

(defn- two-nested
  "Component2 (:main2-root) nests two copies of component1 (:nested-a,
   :nested-b); its copy :copy2-root holds their copies :copy-a and :copy-b."
  []
  (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                    (tho/add-simple-component :component1 :main1-root :main1-child)
                    (tho/add-frame :main2-root)
                    (thc/instantiate-component :component1 :nested-a :parent-label :main2-root)
                    (thc/instantiate-component :component1 :nested-b :parent-label :main2-root)
                    (thc/make-component :component2 :main2-root)
                    (thc/instantiate-component :component2 :copy2-root))
        objects (:objects (thf/current-page file))]
    (doseq [[main-label copy-label] [[:nested-a :copy-a] [:nested-b :copy-b]]]
      (thi/set-id! copy-label
                   (->> (get-in objects [(thi/id :copy2-root) :shapes])
                        (filter #(= (thi/id main-label) (get-in objects [% :shape-ref])))
                        (first))))
    file))

(defn- reparent
  "Move the shape `label` under the shape `parent-id`, keeping the links
   consistent."
  [file label parent-id]
  (let [objects (:objects (thf/current-page file))
        shape   (get objects (thi/id label))
        old-id  (:parent-id shape)
        parent  (get objects parent-id)]
    (-> file
        (ths/update-shape-by-id old-id :shapes (filterv #(not= (:id shape) %) (:shapes (get objects old-id))))
        (ths/update-shape-by-id parent-id :shapes (conj (vec (:shapes parent)) (:id shape)))
        (ths/update-shape label :parent-id parent-id)
        (ths/update-shape label :frame-id (if (= :frame (:type parent)) parent-id (:frame-id parent))))))

(defn- delete-main
  "Delete the main `label`, which deletes its component."
  [file label]
  (let [page        (thf/current-page file)
        [_ changes] (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                file page (:objects page) #{(thi/id label)} {})]
    (thf/apply-changes file changes :validate? false)))

(defn- update-deleted-object
  "Apply `f` to the shape `label` stored in the deleted component
   `component-label`."
  [file component-label label f]
  (ctf/update-file-data file
                        (fn [data]
                          (ctkl/update-component data (thi/id component-label)
                                                 #(update-in % [:objects (thi/id label)] f)))))

(defn- deleted-object
  [file component-label label]
  (get-in (ctkl/get-component (:data file) (thi/id component-label) true)
          [:objects (thi/id label)]))

(defn- slot-group
  [id]
  #{(ctk/build-swap-slot-group id)})

;; ---- Main heads

(t/deftest component-main-external
  ;; the main points at a library that also has its component
  (let [file      (ths/update-shape (simple) :main-root :component-file (thi/new-id! :library))
        library   (assoc file :id (thi/id :library))
        [found file' errors] (repair file {(:id library) library})]
    (t/is (= #{:component-main-external} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :file1) (:component-file (ths/get-shape file' :main-root))))))

(t/deftest component-not-found
  (let [[found file' errors] (repair (ths/update-shape (simple) :copy-root :component-id (uuid/next)))]
    (t/is (= #{:component-not-found :ref-shape-not-found} found))
    (t/is (nil? errors))
    ;; the copy is detached
    (t/is (nil? (:component-id (ths/get-shape file' :copy-root))))))

(t/deftest invalid-main-instance-id
  (let [[found file' errors] (repair (thc/update-component (simple) :component1 :main-instance-id (uuid/next)))]
    (t/is (= #{:invalid-main-instance-id :ref-shape-not-found} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :main-root) (:main-instance-id (thc/get-component file' :component1))))))

(t/deftest invalid-main-instance-page
  (let [file (-> (simple)
                 (thf/add-sample-page :page2)
                 (thf/switch-to-page :page1)
                 (thc/update-component :component1 :main-instance-page (thi/id :page2)))
        [found file' errors] (repair file)]
    (t/is (= #{:invalid-main-instance-page :ref-shape-not-found} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :page1) (:main-instance-page (thc/get-component file' :component1))))))

(t/deftest invalid-main-instance
  ;; the component names the copy as its main; the repair of
  ;; :invalid-main-instance does nothing, the main's own error fixes it
  (let [[found file' errors] (repair (thc/update-component (simple) :component1 :main-instance-id (thi/id :copy-root)))]
    (t/is (= #{:invalid-main-instance :invalid-main-instance-id :ref-shape-not-found} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :main-root) (:main-instance-id (thc/get-component file' :component1))))))

(t/deftest shape-ref-in-main
  (let [[found file' errors] (repair (ths/update-shape (simple) :main-root :shape-ref (thi/id :copy-root)))]
    (t/is (= #{:shape-ref-in-main} found))
    (t/is (nil? errors))
    (t/is (nil? (:shape-ref (ths/get-shape file' :main-root))))))

;; ---- Shapes inside mains

(t/deftest component-main-flag-on-a-child
  (let [[found file' errors] (repair (ths/update-shape (simple) :main-child :main-instance true))]
    (t/is (= #{:component-main} found))
    (t/is (nil? errors))
    (t/is (nil? (:main-instance (ths/get-shape file' :main-child))))))

(t/deftest component-main-file-on-a-child
  ;; the repair only removes :main-instance, so the error never goes away
  (let [[found _ errors] (repair (ths/update-shape (simple) :main-child :component-file (thi/id :file1)))]
    (t/is (= #{:component-main} found))
    (tm/known-failure "F47" nil #{:component-main} (codes errors))))

(t/deftest should-not-be-component-root
  (let [[found file' errors] (repair (ths/update-shape (simple) :main-child :component-root true))]
    (t/is (= #{:should-not-be-component-root} found))
    (t/is (nil? errors))
    (t/is (nil? (:component-root (ths/get-shape file' :main-child))))))

(t/deftest not-head-main-not-allowed
  ;; a main root without component is no longer a head; repair detaches the
  ;; main and then its copy
  (let [[found file' errors] (repair (ths/update-shape (simple) :main-root :component-id nil))]
    (t/is (= #{:not-head-main-not-allowed :ref-shape-is-not-head} found))
    (t/is (nil? errors))
    (t/is (nil? (:main-instance (ths/get-shape file' :main-root))))
    (t/is (nil? (:shape-ref (ths/get-shape file' :copy-root))))))

;; ---- Copies

(t/deftest ref-shape-not-found
  (let [[found file' errors] (repair (ths/update-shape (nested) :copy2-nested-head :shape-ref (uuid/next)))]
    (t/is (= #{:ref-shape-not-found :missing-slot} found))
    (t/is (nil? errors))
    ;; no matching shape: the nested copy is detached
    (t/is (nil? (:shape-ref (ths/get-shape file' :copy2-nested-head))))))

(t/deftest ref-shape-not-found-in-a-copy-child
  ;; the reference of a non-head copy shape is never checked: the check reads
  ;; the library from the shape itself, and only heads have one
  (let [errors (cfv/validate-file (ths/update-shape (simple) :copy-child :shape-ref (uuid/next)) {})]
    (tm/known-failure "F46" #{:ref-shape-not-found} #{} (codes errors))))

(t/deftest ref-shape-is-head
  ;; the check reports no component, so the repair cannot make the shape a
  ;; head again
  (let [file (-> (nested)
                 (ths/update-shape :copy2-nested-head :component-id nil)
                 (ths/update-shape :copy2-nested-head :component-file nil))
        [found _ errors] (repair file)]
    (t/is (= #{:ref-shape-is-head} found))
    (tm/known-failure "F48" nil #{:ref-shape-is-head} (codes errors))))

(t/deftest ref-shape-is-not-head
  ;; a copy child made a head while the shape it references is not one
  (let [file (-> (simple :main-child-params {:type :frame})
                 (ths/update-shape :copy-child :component-id (thi/id :component1))
                 (ths/update-shape :copy-child :component-file (thi/id :file1)))
        [found file' errors] (repair file)]
    (t/is (= #{:ref-shape-is-not-head :component-id-mismatch} found))
    (t/is (nil? errors))
    (t/is (not (ctk/instance-head? (ths/get-shape file' :copy-child))))))

(t/deftest component-id-mismatch
  (let [[found file' errors] (repair (ths/update-shape (nested) :copy2-nested-head :component-id (thi/id :component2)))]
    (t/is (= #{:component-id-mismatch} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :component1) (:component-id (ths/get-shape file' :copy2-nested-head))))))

(t/deftest not-head-copy-not-allowed
  (let [[found file' errors] (repair (reparent (simple) :copy-child uuid/zero))]
    (t/is (= #{:not-head-copy-not-allowed} found))
    (t/is (nil? errors))
    (t/is (nil? (:shape-ref (ths/get-shape file' :copy-child))))))

(t/deftest instance-head-not-frame
  (let [[found file' errors] (repair (ths/update-shape (simple) :copy-root :type :rect))]
    (t/is (= #{:instance-head-not-frame} found))
    (t/is (nil? errors))
    (t/is (= :frame (:type (ths/get-shape file' :copy-root))))))

(t/deftest invalid-text-touched
  (let [[found file' errors] (repair (ths/update-shape (simple) :copy-child :touched #{:text-content-text}))]
    (t/is (= #{:invalid-text-touched} found))
    (t/is (nil? errors))
    (t/is (contains? (:touched (ths/get-shape file' :copy-child)) :content-group))))

;; ---- Nesting

(t/deftest root-main-not-allowed
  (let [file (-> (simple)
                 (tho/add-simple-component :component2 :main2-root :main2-child)
                 (reparent :main-root (thi/id :main2-root)))
        [found file' errors] (repair file)
        main (ths/get-shape file' :main-root)]
    (t/is (= #{:root-main-not-allowed :nested-main-not-allowed} found))
    (t/is (nil? errors))
    ;; moved back to the page root, as a top main
    (t/is (= uuid/zero (:parent-id main)))
    (t/is (true? (:component-root main)))))

(t/deftest nested-main-not-allowed
  (let [[found file' errors] (repair (ths/update-shape (simple) :main-root :component-root nil))]
    (t/is (= #{:nested-main-not-allowed} found))
    (t/is (nil? errors))
    (t/is (true? (:component-root (ths/get-shape file' :main-root))))))

(t/deftest root-copy-not-allowed
  (let [[found file' errors] (repair (reparent (simple) :copy-root (thi/id :main-root)))]
    (t/is (= #{:root-copy-not-allowed} found))
    (t/is (nil? errors))
    ;; it stays in the main, as a nested copy
    (t/is (nil? (:component-root (ths/get-shape file' :copy-root))))))

(t/deftest nested-copy-not-allowed
  (let [[found file' errors] (repair (ths/update-shape (simple) :copy-root :component-root nil))]
    (t/is (= #{:nested-copy-not-allowed} found))
    (t/is (nil? errors))
    (t/is (true? (:component-root (ths/get-shape file' :copy-root))))))

;; ---- Components

(t/deftest component-nil-objects-not-allowed
  (let [[found file' errors] (repair (thc/update-component (simple) :component1 :objects nil))]
    (t/is (= #{:component-nil-objects-not-allowed} found))
    (t/is (nil? errors))
    (t/is (not (contains? (thc/get-component file' :component1) :objects)))))

(t/deftest non-deleted-component-cannot-have-objects
  (let [[found file' errors] (repair (thc/update-component (simple) :component1 :objects {(uuid/next) {}}))]
    (t/is (= #{:non-deleted-component-cannot-have-objects} found))
    (t/is (nil? errors))
    (t/is (not (contains? (thc/get-component file' :component1) :objects)))))

(t/deftest shape-ref-cycle
  (let [file (-> (two-nested)
                 (delete-main :main2-root)
                 (update-deleted-object :component2 :nested-a #(assoc % :shape-ref (:id %))))
        [found file' errors] (repair file)]
    (t/is (= #{:shape-ref-cycle} found))
    (t/is (nil? errors))
    (t/is (not= (thi/id :nested-a) (:shape-ref (deleted-object file' :component2 :nested-a))))))

;; ---- Swap slots

(t/deftest misplaced-slot
  (let [[found file' errors] (repair (ths/update-shape (simple) :copy-root :touched (slot-group (uuid/next))))]
    (t/is (= #{:misplaced-slot} found))
    (t/is (nil? errors))
    (t/is (nil? (ctk/get-swap-slot (ths/get-shape file' :copy-root))))))

(t/deftest duplicate-slot
  (let [file (-> (two-nested)
                 (ths/update-shape :copy-a :touched (slot-group (thi/id :nested-a)))
                 (ths/update-shape :copy-b :touched (slot-group (thi/id :nested-a))))
        [found file' errors] (repair file)]
    (t/is (= #{:duplicate-slot} found))
    (t/is (nil? errors))
    (t/is (nil? (ctk/get-swap-slot (ths/get-shape file' :copy-b))))))

(t/deftest component-duplicate-slot
  (let [file (-> (two-nested)
                 (delete-main :main2-root)
                 (update-deleted-object :component2 :nested-a #(assoc % :touched (slot-group (thi/id :nested-a))))
                 (update-deleted-object :component2 :nested-b #(assoc % :touched (slot-group (thi/id :nested-a)))))
        [found file' errors] (repair file)]
    (t/is (= #{:component-duplicate-slot} found))
    (t/is (nil? errors))
    (t/is (nil? (ctk/get-swap-slot (deleted-object file' :component2 :nested-b))))))

(t/deftest missing-slot
  (let [file (-> (two-nested)
                 (tho/add-simple-component :component3 :main3-root :main3-child)
                 (tho/swap-component-in-shape :copy-a :component3 :new-shape-label :swapped)
                 (ths/update-shape :swapped :touched nil))
        [found file' errors] (repair file)]
    (t/is (= #{:missing-slot} found))
    (t/is (nil? errors))
    (t/is (= (thi/id :nested-a) (ctk/get-swap-slot (ths/get-shape file' :swapped))))))
