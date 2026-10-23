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
   [app.common.test-helpers.normalize :as thn]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest undo-comparison-allows-only-negligible-geometry-noise
  (let [situation (setup/simple-component-with-labeled-copy)
        file (tm/file situation)
        id (:id (tm/role-shape situation :copy-instance))
        path [:data :pages-index (:id (thf/current-page file)) :objects id]
        before (-> (uc/snapshot file)
                   (assoc-in (conj path :height) 0.01)
                   (assoc-in (conj path :opacity) 0.4))
        failures #(uc/failures (uc/compare-states {} nil :undo before %))]
    (t/is (empty? (failures (assoc-in before (conj path :height) 0.010000000000005116))))
    (t/is (seq (failures (assoc-in before (conj path :height) 0.01001))))
    (t/is (seq (failures (assoc-in before (conj path :opacity) (+ 0.4 5e-15)))))
    (t/is (seq (failures (assoc-in before (conj path :touched) #{:geometry-group}))))))

(t/deftest undo-main-child-structure-restores-remote-sync-state
  (let [initial (setup/nested-component-with-copy)
        updated (-> initial
                    (r/step (n/change-property :main2-nested-head :opacity 0.4)) r/settle
                    (r/step (n/update-main :main2-nested-head)) r/settle)]
    (t/is (:remote-synced (tm/resolve-shape updated :main2-nested-head)))
    (doseq [op [(n/remove-child :main1-child) (n/add-child :main1-root :added-child)]]
      (let [changed (-> updated (r/step op) r/settle)
            undone (r/step changed (n/undo))
            redone (r/step undone (n/redo))]
        (t/is (true? (= (thn/normalize-file (tm/file updated)) (thn/normalize-file (tm/file undone)))))
        (t/is (true? (= (thn/normalize-file (tm/file changed)) (thn/normalize-file (tm/file redone)))))))))

(def ^:private original setup/labeled-fill)
(def ^:private red "#ff0000")
(def ^:private green "#00ff00")

(defn- fill-of
  [shape]
  (-> shape :fills first :fill-color))

(t/deftest main-edit-without-sync-leaves-copy-stale
  (let [situation (r/run-variant
                   {:setup     setup/simple-component-with-labeled-copy
                    :operation (n/change-property :main-child :fills red)}
                   {:sync? false})]
    (t/is (= red (fill-of (setup/main-instance situation))))
    (t/is (= original (fill-of (setup/copy-instance situation))))))

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
  (t/is (= ::r/frontend-only (error-type (n/move-shape :main-root 20 0))))
  (t/is (= ::r/frontend-only (error-type (n/undo-before-sync :main-child :fills red)))))

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

;; A user operation that changes the opacity of the copy root and whose
;; undo changes also rename it.
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
(defrecord EditWithoutUndo []
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
    (t/is (= (repeat 3 #{[:file :file1 :object :copy-root :name]})
             (map :signature (uc/failures situation))))
    (t/is (= {:name "Wrong"}
             (-> (uc/failures situation) first :diff second :files :file1 :data :pages-index
                 vals first :objects (get :copy-root))))))

(t/deftest round-trip-reports-a-change-with-no-undo-entry
  (let [situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation (->EditWithoutUndo)})]
    (t/is (= [:no-undo-entry] (phases situation)))))

(t/deftest round-trip-can-be-turned-off
  (let [situation (r/run-variant {:setup      setup/simple-component-with-copy
                                  :operation  (->EditWithoutUndo)
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

(t/deftest signature-names-what-differs
  (let [shape    {:id (thi/new-id! :shape) :name "A" :opacity 1}
        other    {:id (thi/new-id! :other) :name "B"}
        page-id  (thi/new-id! :page)
        file     (fn [objects] {:data {:pages-index {page-id {:id page-id :objects objects}}}})]
    (t/is (= #{[:object :shape :opacity] [:object :other :extra]}
             (uc/signature (file {(:id shape) shape})
                           (file {(:id shape) (assoc shape :opacity 0.5)
                                  (:id other) other}))))
    (t/is (= #{[:object :unlabelled :missing]}
             (uc/signature (file {(uuid/next) shape}) (file {}))))))

(t/deftest known-failures-pin-how-the-check-fails
  (let [edit      (tm/assign-id (->WrongUndoEdit))
        situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation edit})
        pin       #{[:file :file1 :object :copy-root :name]}
        known     {:bug "F0" :fails {:undo pin :redo pin :variant-undo pin} :op edit}
        verdict   #(uc/verdict situation {:undo-check {:known-failures %}})]
    (t/is (= {:unexpected [] :resolved []} (verdict [known])))
    (t/is (= [:undo :redo :variant-undo]
             (mapv :phase (:unexpected (uc/verdict situation {})))))
    (t/testing "a failure that differs from its pin stays a failure"
      (let [{:keys [unexpected resolved]}
            (verdict [(assoc-in known [:fails :undo] #{[:object :copy-root :opacity]})])]
        (t/is (= [:undo] (mapv :phase unexpected)))
        (t/is (= [:undo] (mapv :phase resolved)))))
    (t/testing "a pinned phase that does not fail is resolved"
      (t/is (= [:variant-redo]
               (mapv :phase (:resolved (verdict [(assoc-in known [:fails :variant-redo] pin)]))))))
    (t/testing "a mark applies only where its step ran and its runner runs"
      (t/is (= [] (:resolved (verdict [known (assoc known :op (tm/assign-id (->WrongUndoEdit)))]))))
      (t/is (= [] (:resolved (verdict [known (assoc known :runners #{:frontend} :op nil)])))))
    (t/testing "a mark must pin its failures"
      (t/is (thrown? #?(:clj Exception :cljs :default)
                     (verdict [(dissoc known :fails)]))))))

(t/deftest known-failure-holds-only-the-pinned-value
  (let [reports (fn [actual]
                  (let [types (atom [])]
                    (binding [t/report #(swap! types conj (:type %))]
                      (tm/known-failure "F0" :right :wrong actual))
                    @types))]
    (t/is (= [:pass] (reports :wrong)))
    (t/is (= [:fail] (reports :right)))
    (t/is (= [:fail] (reports :other)))))

(defn- report-types
  "The types of the assertions `f` reports, in order, without reporting them."
  [f]
  (let [types (atom [])]
    (binding [t/report #(swap! types conj (:type %))]
      (f))
    @types))

(t/deftest known-failure-refuses-a-pin-equal-to-the-expected-value
  (t/is (thrown? #?(:clj Exception :cljs :default)
                 (tm/known-failure "F0" :right :right :right))))

(t/deftest check-asserts-each-unpinned-or-resolved-failure
  (let [edit      (tm/assign-id (->WrongUndoEdit))
        situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation edit})
        pin       #{[:file :file1 :object :copy-root :name]}
        known     {:bug "F0" :fails {:undo pin :redo pin :variant-undo pin} :op edit}
        check     #(report-types (fn [] (uc/check! situation {:undo-check %})))]
    (t/is (= [:fail :fail :fail] (check {})))
    (t/is (= [] (check {:known-failures [known]})))
    (t/is (= [:fail] (check {:known-failures [(assoc-in known [:fails :variant-redo] pin)]})))
    (t/is (= [] (check {:off "testing the switch"})))))

(t/deftest known-failure-marks-apply-by-runner-condition-and-step
  (let [wrong     (tm/assign-id (->WrongUndoEdit))
        right     (n/change-property :main-child :fills red)
        situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation (tm/in-sequence [wrong right])})
        pin       #{[:file :file1 :object :copy-root :name]}
        known     {:bug "F0" :fails {:undo pin :redo pin :variant-undo pin} :op wrong}
        verdict   #(uc/verdict situation {:undo-check {:known-failures [%]}})
        phases    (fn [k mark] (mapv :phase (k (verdict mark))))]
    (t/is (= [] (phases :unexpected known)))
    (t/testing "a mark of another runner covers nothing"
      (t/is (= [:undo :redo :variant-undo] (phases :unexpected (assoc known :runners #{:frontend}))))
      (t/is (= [] (phases :resolved (assoc known :runners #{:frontend})))))
    (t/testing "a mark whose condition fails covers nothing"
      (t/is (= [:undo :redo :variant-undo]
               (phases :unexpected (assoc known :when (constantly false))))))
    (t/testing "a mark covers only the failures of its own step"
      (let [mark (assoc known :op right :fails {:undo pin :redo pin})]
        (t/is (= [:undo :redo :variant-undo] (phases :unexpected mark)))
        (t/is (= [:undo :redo] (phases :resolved mark)))))))

(t/deftest a-mark-that-applies-to-no-variant-fails
  (let [edit      (tm/assign-id (->WrongUndoEdit))
        pin       #{[:file :file1 :object :copy-root :name]}
        mark      {:bug "F0" :fails {:undo pin} :op edit}
        case-map  {:undo-check {:known-failures [mark]}}]
    (t/is (= [:fail] (report-types #(uc/check-marks-applied! case-map :pure []))))
    (t/is (= [] (report-types #(uc/check-marks-applied! case-map :pure [mark]))))
    (t/is (= [] (report-types #(uc/check-marks-applied!
                                {:undo-check {:known-failures [(assoc mark :runners #{:pure})]}}
                                :frontend []))))))

;; A user operation whose undo points the copy root at a shape that does not
;; exist, so the undo leaves an invalid file.
(defrecord BreakingUndoEdit []
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
                                                                     :attr :shape-ref
                                                                     :val (uuid/next)
                                                                     :ignore-touched true
                                                                     :ignore-geometry false}))))]
      (-> situation
          (tm/with-file (thf/apply-changes file changes))
          (n/record-changes changes)
          (tm/record-application this {})))))

(t/deftest round-trip-records-an-error-with-its-type
  (let [situation (r/run-variant {:setup     setup/simple-component-with-copy
                                  :operation (tm/in-sequence
                                              [(n/change-property :main-child :fills red)
                                               (->BreakingUndoEdit)])})
        [undo variant-undo] (uc/failures situation)]
    (t/is (= [:undo :variant-undo] (phases situation)))
    (t/is (= #{[:error :validation]} (:signature undo)))
    (t/is (= #{[:error :validation]} (:signature variant-undo)))
    (t/testing "the variant goes on from the state after the step"
      (t/is (= 0.5 (:opacity (setup/copy-root situation))))
      (t/is (= red (fill-of (setup/copy-instance situation)))))))

;; An operation that implements no component operation kind.
(defrecord KindlessOp []
  tm/IOperation
  (apply-to [_ situation] situation))

(t/deftest an-operation-without-a-kind-is-rejected
  (t/is (= ::r/unknown-op (error-type (->KindlessOp)))))

(t/deftest one-of-needs-alternatives
  (t/is (thrown? #?(:clj Exception :cljs :default) (tm/one-of []))))

(t/deftest run-each-judges-a-variant-while-its-labels-hold
  (let [choice (tm/one-of [(n/change-property :main-child :fills red)
                           (n/change-property :main-child :fills green)])
        seen   (atom [])]
    (r/run-each {:setup     setup/simple-component-with-labeled-copy
                 :operation (tm/in-sequence [choice])}
                (fn [situation]
                  (swap! seen conj [(= (thi/id :copy-child) (:id (setup/copy-instance situation)))
                                    (fill-of (setup/copy-instance situation))])))
    (t/is (= [[true red] [true green]] @seen))))

(t/deftest counting-assertions-counts-every-report
  (let [counted (atom nil)]
    (report-types
     #(tm/counting-assertions
       (fn [made]
         (t/is true)
         (t/is false)
         (reset! counted @made))))
    (t/is (= 2 @counted))))

(t/deftest duplicate-result-finds-the-user-subtree-after-propagation
  (let [before (r/run-variant
                {:setup setup/empty-situation
                 :operation (tm/in-sequence [(n/create-component "a" original :size 100)
                                             (n/make-nested-component "a" 1)
                                             (n/instantiate-copy "a")
                                             (n/instantiate-copy "a")])})
        duplicate (n/duplicate (n/nested-head-of "a" 0))
        after (r/settle (r/step before duplicate))
        result (n/applied-result duplicate (tm/file before) (tm/file after))
        new-head (tm/shape-by-id after (:new-head result))]
    (t/is (= ((n/main-head-of "a") before) (:parent-id new-head)))))

(t/deftest missing-shape-diagnostics-accept-mixed-label-types
  (let [situation (setup/simple-component-with-labeled-copy)
        error (try (tm/resolve-shape situation :missing)
                   (catch #?(:clj Exception :cljs js/Error) error error))]
    (t/is (= ::tm/unbound-shape-name (:type (ex-data error))))
    (t/is (some #{:copy-child} (:present (ex-data error))))))
