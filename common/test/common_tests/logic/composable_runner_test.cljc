;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-runner-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private original "#abcdef")
(def ^:private red "#ff0000")
(def ^:private green "#00ff00")

(defn- fill-of
  [shape]
  (-> shape :fills first :fill-color))

(t/deftest main-edit-syncs-to-clean-copy
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (n/change-property :main-child :fills red)})
        copy      (setup/copy-instance situation)]
    (t/is (= red (fill-of copy)))
    (t/is (nil? (:touched copy)))))

(t/deftest main-edit-without-sync-leaves-copy-stale
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (n/change-property :main-child :fills red)}
                   {:sync? false})]
    (t/is (= red (fill-of (setup/main-instance situation))))
    (t/is (= original (fill-of (setup/copy-instance situation))))))

(t/deftest copy-override-survives-main-edit
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :copy-child :fills green)
                                 (n/change-property :main-child :fills red)])})
        copy      (setup/copy-instance situation)]
    (t/is (= green (fill-of copy)))
    (t/is (contains? (:touched copy) :fill-group))))

(t/deftest sync-cascades-through-nested-components
  ;; The edit is on the deepest main. Its copy inside the outer main changes,
  ;; so the outer component syncs in a second pass and reaches the outer copy.
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/make-nested-component m)
                                 (n/make-nested-component m)
                                 (n/instantiate-copy m)
                                 (n/change-property (n/remote-rect-of m) :fills red)])})
        copy-rect (tm/shape-by-id situation (n/lineage-copy-rect situation m))]
    (t/is (= red (fill-of copy-rect)))
    (t/is (nil? (:touched copy-rect)))))

(t/deftest undo-reverts-edit-and-its-sync
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/undo)])})
        copy      (setup/copy-instance situation)]
    (t/is (= original (fill-of (setup/main-instance situation))))
    (t/is (= original (fill-of copy)))
    (t/is (nil? (:touched copy)))
    (t/is (zero? (r/undo-depth situation)))))

(t/deftest undo-reverts-the-whole-sync-cascade
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/make-nested-component m)
                                 (n/instantiate-copy m)
                                 (n/change-property (n/remote-rect-of m) :fills red)
                                 (n/undo)])})]
    (t/is (= original (fill-of (tm/shape-by-id situation (n/lineage-rect situation m)))))
    (t/is (= original (fill-of (tm/shape-by-id situation (n/lineage-copy-rect situation m)))))))

(t/deftest undo-reverts-only-the-latest-user-operation
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/change-property :main-child :fills green)
                                 (n/undo)])})]
    (t/is (= red (fill-of (setup/main-instance situation))))
    (t/is (= red (fill-of (setup/copy-instance situation))))
    (t/is (= 1 (r/undo-depth situation)))))

(t/deftest undo-of-a-reset-restores-the-override
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/instantiate-copy m)
                                 (n/change-property (n/copy-rect-of m) :fills green)
                                 (n/reset-copy-instance m)
                                 (n/undo)])})
        copy-rect (tm/shape-by-id situation (n/lineage-copy-rect situation m))]
    (t/is (= green (fill-of copy-rect)))
    (t/is (contains? (:touched copy-rect) :fill-group))
    (t/is (= 1 (r/undo-depth situation)))))

(t/deftest redo-reapplies-edit-and-its-sync
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/undo)
                                 (n/redo)])})
        copy      (setup/copy-instance situation)]
    (t/is (= red (fill-of (setup/main-instance situation))))
    (t/is (= red (fill-of copy)))
    (t/is (nil? (:touched copy)))
    (t/is (= 1 (r/undo-depth situation)))
    (t/is (zero? (r/redo-depth situation)))))

(t/deftest redo-reapplies-groups-in-undo-order
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/change-property :main-child :fills green)
                                 (n/undo)
                                 (n/undo)
                                 (n/redo)])})]
    (t/is (= red (fill-of (setup/copy-instance situation))))
    (t/is (= 1 (r/undo-depth situation)))
    (t/is (= 1 (r/redo-depth situation)))))

(t/deftest user-operation-empties-the-redo-stack
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/undo)
                                 (n/change-property :main-child :fills green)
                                 (n/redo)])})]
    (t/is (= green (fill-of (setup/copy-instance situation))))
    (t/is (= 1 (r/undo-depth situation)))
    (t/is (zero? (r/redo-depth situation)))))

(t/deftest unchanged-user-operation-keeps-the-redo-stack
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/undo)
                                 (n/change-property :main-child :fills original)
                                 (n/redo)])})]
    (t/is (= red (fill-of (setup/copy-instance situation))))))

(t/deftest redo-with-an-empty-stack-changes-nothing
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :main-child :fills red)
                                 (n/redo)])})]
    (t/is (= red (fill-of (setup/copy-instance situation))))
    (t/is (= 1 (r/undo-depth situation)))))

(t/deftest assembly-operations-push-no-undo-group
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/instantiate-copy m)
                                 (n/undo)])})]
    (t/is (zero? (r/undo-depth situation)))
    (t/is (some? (tm/shape-by-id situation (n/lineage-copy-rect situation m))))))

(t/deftest added-child-reaches-copy
  (let [add       (n/add-child :main-root :new-child)
        situation (r/run-variant
                   {:setup     setup/simple-component-with-copy
                    :operation add})]
    (t/is (some? (n/materialized-instance-child add situation (setup/copy-root situation))))))

(t/deftest undo-removes-added-child-from-main-and-copy
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-copy
                    :operation (tm/in-sequence
                                [(n/add-child :main-root :new-child)
                                 (n/undo)])})]
    (t/is (nil? (tm/shape-by-id situation (thi/id :new-child))))
    (t/is (= 1 (count (:shapes (setup/main-root situation)))))
    (t/is (= 1 (count (:shapes (setup/copy-root situation)))))))

(defn- error-type
  "The `:type` of the error that running `op` on a simple component throws, or
   nil when it throws none."
  [op]
  (try
    (r/run-variant {:setup     setup/simple-component-with-copy
                    :operation op})
    nil
    (catch #?(:clj Exception :cljs :default) e
      (:type (ex-data e)))))

(t/deftest frontend-only-operations-are-rejected
  (t/is (= ::r/frontend-only (error-type (n/rotate :main-root 45))))
  (t/is (= ::r/frontend-only (error-type (n/change-height :main-root 50)))))

;; A user operation that forgets to record its changes.
(defrecord UnrecordedEdit []
  n/IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [_ situation] situation))

;; An assembly operation that records changes as if it were a user operation.
(defrecord RecordingAssembly []
  n/IComponentOperation
  (op-kind [_] :assembly)

  tm/IOperation
  (apply-to [_ situation] (n/record-changes situation (pcb/empty-changes))))

(t/deftest user-operation-must-record-its-changes
  (t/is (= ::r/changes-not-recorded (error-type (->UnrecordedEdit)))))

(t/deftest only-user-operations-record-changes
  (t/is (= ::r/unexpected-changes (error-type (->RecordingAssembly)))))

;; A user operation that points the copy root at a main shape that does not
;; exist, leaving the file invalid.
(defrecord BreakCopyRef []
  n/IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [_ situation]
    (let [file    (tm/file situation)
          page    (thf/current-page file)
          changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id (setup/copy-root situation))}
                                              #(assoc % :shape-ref (uuid/next))
                                              (:objects page)
                                              {})]
      (-> situation
          (tm/with-file (thf/apply-changes file changes :validate? false))
          (n/record-changes changes)))))

(t/deftest settled-file-is-validated
  (t/is (= :validation (error-type (->BreakCopyRef)))))

(t/deftest run-all-records-each-choice
  (let [to-red    (n/change-property :main-child :fills red)
        to-green  (n/change-property :main-child :fills green)
        choice    (tm/one-of [to-red to-green])
        results   (r/run-all {:setup     setup/simple-component-with-labeled-copy
                              :operation (tm/in-sequence [choice])})]
    (t/is (= [to-red to-green] (mapv #(tm/get-choice % choice) results)))
    (t/is (= [red green] (mapv (comp fill-of setup/copy-instance) results)))))

(defn- level-fill
  [situation name level]
  (fill-of (tm/shape-by-id situation (n/level-rect situation name level))))

(def ^:private switch-scenario
  ;; The variant head is nested at level 0; level 1 wraps level 0, so it holds
  ;; a copy of it that only sync updates.
  (let [m "main"]
    [(n/create-component m original)
     (n/make-variant-container "vset" [["a" original] ["b" red]])
     (n/make-nested-component-with-variant m "vset" "a")
     (n/make-nested-component m)
     (n/switch-variant (n/nested-head-of m 0) "b")]))

(t/deftest variant-switch-syncs-to-outer-level
  (let [situation (r/run-variant {:setup     setup/empty-situation
                                  :operation (tm/in-sequence switch-scenario)})]
    (t/is (= red (level-fill situation "main" 0)))
    (t/is (= red (level-fill situation "main" 1)))))

(t/deftest variant-switch-without-sync-leaves-outer-level
  (let [situation (r/run-variant {:setup     setup/empty-situation
                                  :operation (tm/in-sequence switch-scenario)}
                                 {:sync? false})]
    (t/is (= red (level-fill situation "main" 0)))
    (t/is (= original (level-fill situation "main" 1)))))

(t/deftest undo-reverts-variant-switch-and-its-sync
  (let [situation (r/run-variant {:setup     setup/empty-situation
                                  :operation (tm/in-sequence (conj switch-scenario (n/undo)))})]
    (t/is (= original (level-fill situation "main" 0)))
    (t/is (= original (level-fill situation "main" 1)))))

(t/deftest variant-switch-to-current-value-changes-nothing
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/make-variant-container "vset" [["a" original] ["b" red]])
                                 (n/make-nested-component-with-variant m "vset" "a")
                                 (n/switch-variant (n/nested-head-of m 0) "a")])})]
    (t/is (= original (level-fill situation m 0)))
    (t/is (zero? (r/undo-depth situation)))))

;; A user operation whose undo changes put back a different fill.
(defrecord WrongUndoEdit []
  n/IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [file    (tm/file situation)
          page    (thf/current-page file)
          changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id (setup/copy-root situation))}
                                              #(assoc % :opacity 0.5)
                                              (:objects page)
                                              {})
          changes (update changes :undo-changes
                          (partial mapv #(cond-> %
                                           (= :mod-obj (:type %))
                                           (update :operations conj {:type :set
                                                                     :attr :name
                                                                     :val "Wrong"
                                                                     :ignore-touched true
                                                                     :ignore-geometry false}))))]
      (-> situation
          (tm/with-file (thf/apply-changes file changes))
          (n/record-changes changes)
          (tm/record-application this {})))))

;; A user operation that changes the file with no undo changes.
(defrecord UndoableEdit []
  n/IComponentOperation
  (op-kind [_] :user)

  tm/IOperation
  (apply-to [this situation]
    (let [file    (tm/file situation)
          page    (thf/current-page file)
          changes (-> (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                  #{(:id (setup/copy-root situation))}
                                                  #(assoc % :opacity 0.5)
                                                  (:objects page)
                                                  {})
                      (assoc :undo-changes []))]
      (-> situation
          (tm/with-file (thf/apply-changes file changes))
          (n/record-changes changes)
          (tm/record-application this {})))))

(defn- phases
  [situation]
  (mapv :phase (uc/failures situation)))

(t/deftest round-trip-passes-for-exact-inverses
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (tm/in-sequence
                                [(n/change-property :copy-child :fills green)
                                 (n/change-property :main-child :fills red)
                                 (n/undo)
                                 (n/redo)])})]
    (t/is (empty? (uc/failures situation)))
    (t/is (= red (fill-of (setup/main-instance situation))))))

(t/deftest round-trip-reports-a-wrong-undo
  (let [situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation (->WrongUndoEdit)})]
    ;; the redo leaves the wrong name in place, and so does the variant undo
    (t/is (= [:undo :redo :variant-undo] (phases situation)))
    (t/is (= {:name "Wrong"}
             (-> (uc/failures situation) first :diff second :data :pages-index
                 vals first :objects (get :copy-root))))))

(t/deftest round-trip-reports-a-change-with-no-undo-entry
  (let [situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation (->UndoableEdit)})]
    (t/is (= [:no-undo-entry] (phases situation)))))

(t/deftest round-trip-can-be-turned-off
  (let [situation (r/run-variant {:setup      setup/simple-component-with-copy
                                  :operation  (->UndoableEdit)
                                  :undo-check {:off "testing the switch"}})]
    (t/is (empty? (uc/failures situation)))))

(t/deftest round-trip-never-undoes-past-an-assembly-step
  ;; The edit before the copy exists is not undone at the end of the variant,
  ;; so the main keeps it.
  (let [m         "main"
        situation (r/run-variant
                   {:setup     setup/empty-situation
                    :operation (tm/in-sequence
                                [(n/create-component m original)
                                 (n/change-property (n/main-rect-of m) :fills red)
                                 (n/instantiate-copy m)
                                 (n/change-property (n/copy-rect-of m) :fills green)])})]
    (t/is (empty? (uc/failures situation)))
    (t/is (= red (fill-of (tm/shape-by-id situation (n/lineage-rect situation m)))))))

(t/deftest known-failures-explain-only-what-happens
  (let [edit      (tm/assign-id (->WrongUndoEdit))
        situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation edit})
        known     {:bug "F0" :phases #{:undo :redo :variant-undo} :op edit}
        other     {:bug "F1" :phases #{:variant-redo}}]
    (t/is (= {:unexpected [] :resolved []}
             (uc/verdict situation {:undo-check {:known-failures [known]}})))
    (t/is (= [:undo :redo :variant-undo]
             (mapv :phase (:unexpected (uc/verdict situation {})))))
    (t/is (= [other]
             (:resolved (uc/verdict situation {:undo-check {:known-failures [known other]}}))))
    (t/is (= [] (:resolved (uc/verdict situation
                                       {:undo-check {:known-failures
                                                     [known (assoc other :when (constantly false))]}}))))))
