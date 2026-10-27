;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.completion-contract-test
  "Tests the interpreter's completion contract: a step resolves only once the
  work it caused has drained, whatever kind of commit it makes, and reports
  the pending work when it does not drain in time. Component sync is slowed
  down on purpose, so a step that resolved early would see a half-synced
  file."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.time :as ct]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.thumbnails-wasm :as dwthw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm]
   [app.util.text-editor :as ted]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.test.check.generators :as gen]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.async :as ha]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each ftm/fixture)

(t/deftest ^:async swapping-growing-text-undoes-with-the-component-choice
  (let [features (:features @st/state)]
    (thw/setup-wasm-mocks!)
    (try
      (await
       (mock/with-mocks*
         {wasm/text-editor-has-focus? (mock/stub (constantly false))
          dwthw/render-thumbnail (mock/stub (fn [& _] (ptk/data-event ::thumbnail-skipped)))}
         (doseq [action [:swap :switch :multi-swap]]
           (let [file (-> (thf/sample-file :file)
                          (tho/add-frame :root-a)
                          (thc/make-component :a :root-a)
                          (tho/add-frame-with-text :root-b :text-b "hello")
                          (ths/update-shape :text-b :grow-type :auto-height)
                          (thc/make-component :b :root-b)
                          (thc/instantiate-component :a :copy)
                          (thc/instantiate-component :a :copy2))
                 situation (ftm/install! (tm/make-situation file))
                 before (ftm/snapshot)]
             (st/emit! #(update % :features (fnil conj #{}) "render-wasm/v1"))
             (t/is (= :settled
                      (await (ftm/await-step
                              [(if (= action :multi-swap)
                                 (dwl/component-multi-swap [(ths/get-shape file :copy)
                                                            (ths/get-shape file :copy2)]
                                                           (:id file) (thi/id :b))
                                 (dwl/component-swap (ths/get-shape file :copy)
                                                     (:id file) (thi/id :b) (= action :switch)))]))))
             (let [after (ftm/snapshot)
                   root (ths/get-shape (ftm/current-file) :copy)
                   text (tm/shape-by-id (ftm/refresh-generated situation) (first (:shapes root)))]
               (t/is (= (thi/id :b) (:component-id root)))
               (t/is (mth/close? 20 (:height text)))
               (t/is (empty? (:touched text)))
               (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
               (t/is (uc/same-state? before (ftm/snapshot)) "one undo restores the old component")
               (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
               (t/is (uc/same-state? after (ftm/snapshot)) "one redo restores the measured result"))))))
      (finally
        (thw/teardown-wasm-mocks!)
        (st/emit! #(assoc % :features features))))))

(t/deftest ^:async applying-typography-settles-the-reference-and-undoes-in-one-step
  (doseq [mode [:partial :inherited :editing-inherited]]
    (let [expected [[{:line-height 1.6}
                     ["Read the guide" {:family "Roboto" :weight 700 :italic true :size 22
                                        :spacing 2 :case "uppercase" :typography :heading}]]]
          file (-> (thf/sample-file :file1)
                   (ths/add-sample-typography :heading :font-id "gfont-roboto" :font-family "Roboto"
                                              :font-variant-id "700italic" :font-weight "700"
                                              :font-style "italic" :font-size "22"
                                              :line-height "1.6" :letter-spacing "2"
                                              :text-transform "uppercase" :path "Saved styles")
                   (thtx/add-text-component-with-copy (if (= mode :partial)
                                                        [[["Read the guide" {:size 22}]]]
                                                        expected)))
          _ (ftm/install! (tm/make-situation file))
          before (ftm/snapshot)
          typography (get-in file [:data :typographies (thi/id :heading)])]
      (when (= mode :editing-inherited)
        (st/emit! #(assoc-in % [:workspace-editor-state (thi/id :copy-child)]
                             (-> (ths/get-shape file :copy-child) :content ted/import-content
                                 ted/create-editor-state ted/editor-select-all))))
      (t/is (= :settled
               (await (ftm/await-step [(dwt/apply-typography #{(thi/id :copy-child)} typography (:id file))]))))
      (let [after (ftm/snapshot)
            after-file (ftm/current-file)
            shape (ths/get-shape after-file :copy-child)
            design #(oracle/canonical (tm/make-situation %) {:free-head-placement false})
            main-edit (dwt/update-text-attrs {:id (thi/id :main-child) :attrs {:font-size "30"}})]
        (t/is (= expected (thtx/view (:content shape))))
        (t/is (= #{:content-group :text-content-typography :text-content-family
                   :text-content-weight :text-content-size :text-content-line-height
                   :text-content-spacing :text-content-case}
                 (:touched shape)))
        (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
        (t/is (uc/same-state? before (ftm/snapshot)))
        (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
        (t/is (uc/same-state? after (ftm/snapshot)))
        (t/is (= :settled
                 (await (ftm/await-step [(dwl/sync-file (:id file) (:id file) :components (thi/id :component1))]))))
        (t/is (oracle/equivalent? (design after-file) (design (ftm/current-file))))
        (t/is (= :settled (await (ftm/await-step [main-edit]))))
        (t/is (= expected (thtx/text-of (ftm/current-file) :copy-child))
              "the applied typography keeps its size when the main changes")))))

(t/deftest ^:async sidebar-font-size-and-wasm-measurement-undo-together
  (let [features (:features @st/state)
        measured (atom nil)
        font-size #(get-in % [:content :children 0 :children 0 :children 0 :font-size])]
    (thw/setup-wasm-mocks!)
    (try
      (await
       (mock/with-mocks*
         {wasm/text-editor-has-focus? (mock/stub (constantly false))
          wasm/set-shape-text-content (mock/stub (fn [_ content] (reset! measured content)))
          wasm/get-text-dimensions (mock/stub (fn [] {:x 0 :y 0 :width 100 :max-width 100
                                                      :height (if (= "18" (font-size {:content @measured})) 29 35)}))
          dwthw/render-thumbnail (mock/stub (fn [& _] (ptk/data-event ::thumbnail-skipped)))}
         (let [text [[{:line-height 1.6}
                      ["Read the guide" {:family "Roboto" :weight 700 :italic true :size 22
                                         :spacing 2 :case "uppercase" :typography :heading}]]]
               file (-> (thf/sample-file :file1)
                        (ths/add-sample-typography :heading :font-id "gfont-roboto" :font-family "Roboto"
                                                   :font-variant-id "700italic" :font-weight "700"
                                                   :font-style "italic" :font-size "22"
                                                   :line-height "1.6" :letter-spacing "2" :text-transform "uppercase")
                        (thtx/add-text-component-with-copy text))
               _ (ftm/install! (tm/make-situation file))
               typography (get-in file [:data :typographies (thi/id :heading)])]
           (st/emit! #(update % :features (fnil conj #{}) "render-wasm/v1"))
           (t/is (= :settled
                    (await (ftm/await-step [(dwwt/resize-wasm-text-all [(thi/id :main-child) (thi/id :copy-child)])
                                            (dwt/apply-typography #{(thi/id :copy-child)} typography (:id file))]))))
           (let [before (ftm/snapshot)]
             (t/is (= :settled
                      (await (ftm/await-step [(dwt/update-all-attrs [(thi/id :main-child)]
                                                                    {:font-size "18" :typography-ref-id nil
                                                                     :typography-ref-file nil})]))))
             (let [after (ftm/snapshot)]
               (t/is (= "18" (font-size (ths/get-shape (ftm/current-file) :main-child))))
               (t/is (= "22" (font-size (ths/get-shape (ftm/current-file) :copy-child))))
               (t/is (mth/close? 35 (:height (ths/get-shape (ftm/current-file) :copy-child))))
               (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
               (t/is (uc/same-state? before (ftm/snapshot)) "one undo restores styles and measured geometry")
               (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
               (t/is (uc/same-state? after (ftm/snapshot)) "one redo restores styles and measured geometry"))))))
      (finally
        (thw/teardown-wasm-mocks!)
        (st/emit! #(assoc % :features features))))))

(t/deftest ^:async rotation-nodes-compose-successive-turns
  (let [initial (ftm/start-generated (setup/simple-component-with-labeled-copy))
        first-turn (await (ftm/run-generated-step initial (n/rotate :copy-root 45)))
        second-turn (await (ftm/run-generated-step first-turn (n/rotate :copy-root 90)))
        shape (ths/get-shape (ftm/current-file) :copy-root)]
    (t/is (mth/close? 135 (:rotation shape) 0.01))
    (t/is (empty? (uc/failures second-turn)))))

(t/deftest ^:async hiding-a-grid-main-child-then-moving-its-main-stays-synced
  (loop [live (ftm/start-generated (cg/world [2 1 {:text true :text-styles true :layout :grid}]))
         tuples [[:style 3 1 3 0] [:transform 0 0 0 0]]]
    (when-let [tuple (first tuples)]
      (let [resolved (cg/resolve-op live {} tuple)
            next-live (await (ftm/run-generated-step live (:op resolved)))]
        (t/is (some? next-live))
        (t/is (empty? (uc/failures next-live)))
        (t/is (nil? (oracle/check next-live {})))
        (recur next-live (rest tuples))))))

(def ^:private original "#abcdef")
(def ^:private red "#ff0000")

;; How much later than asked the slowed-down sync starts.
(def ^:private sync-delay-ms 150)

(defn- deferred-sync-file
  "A replacement for `dwl/sync-file` that holds its sync wait open and runs
  `work` on the event that `sync-file`, the real constructor, builds."
  [sync-file work]
  (mock/stub
   (fn [& args]
     (let [event   (apply sync-file args)
           file-id (first args)]
       (ptk/reify ::deferred-sync-file
         ptk/WatchEvent
         (watch [_ _ _]
           (wrf/with-pending :sync-file [file-id] (work event))))))))

(defn- slow-sync
  [event]
  (->> (rx/of event)
       (rx/delay sync-delay-ms)))

(defn- endless-sync
  [_]
  (rx/create (fn [_] nil)))

(defn- fill-main-child
  [color]
  (dwsh/update-shapes #{(thi/id :main-child)}
                      #(assoc % :fills (ths/sample-fills-color :fill-color color))))

(defn- ^:async pending-sync-burst
  [seed undo-before-release]
  (let [sync-file dwl/sync-file
        release (rx/behavior-subject false)
        tail (gen/generate (gen/vector (gen/tuple (gen/return :edit)
                                                  (gen/choose 0 30) (gen/choose 0 1)
                                                  (gen/choose 1 2) (gen/return 0))
                                       2 8) 20 seed)
        tuples (into [[:edit 1 0 1 0]] tail)
        initial (cg/initial-state [2 (mod seed 3)])]
    (try
      (await
       (mock/with-mocks*
         {dwl/sync-file (deferred-sync-file sync-file
                                            (fn [event]
                                              ;; The cascade is synchronous. Hold
                                              ;; completion, not its input state.
                                              (rx/concat (rx/of event)
                                                         (->> release (rx/filter true?)
                                                              (rx/take 1) (rx/ignore)))))}
         (let [live (ftm/start-generated (:situation initial))
               result (reduce (fn [{:keys [state operations]} tuple]
                                (let [resolved (cg/resolve-op (:situation state) (:model state) tuple)]
                                  {:state (-> state (cg/step tuple) cg/settle-state)
                                   :operations (conj operations (:op resolved))}))
                              {:state initial :operations []} tuples)
               operations (:operations result)]
           (doseq [event (ftm/op->events (first operations) live)] (st/emit! event))
           (await (ha/wait-for #(seq (wrf/pending)) "first edit opens the held sync"))
           (doseq [op (rest operations)
                   event (ftm/op->events op live)]
             (st/emit! event))
           (t/is (seq (wrf/pending)) "later edits arrived while sync was pending")
           (when undo-before-release (st/emit! dwu/undo))
           (let [expected (cond-> (:state result)
                            undo-before-release (-> (cg/step [:undo]) cg/settle-state))]
             (rx/push! release true)
             (t/is (= :settled (await (ftm/await-step []))))
             (oracle/check (ftm/refresh-generated live) (:model expected))
             (t/is (uc/same-state? (oracle/snapshot (:situation expected))
                                   (:files (ftm/snapshot)))
                   (str "seed " seed ", undo-before-release " undo-before-release))
             (let [depth (r/undo-depth (:situation expected))
                   undone (loop [state expected remaining depth]
                            (if (zero? remaining)
                              state
                              (let [state (-> state (cg/step [:undo]) cg/settle-state)]
                                (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
                                (t/is (uc/same-state? (oracle/snapshot (:situation state))
                                                      (:files (ftm/snapshot)))
                                      (str "burst undo seed " seed ", remaining " remaining))
                                (recur state (dec remaining)))))]
               (loop [state undone remaining depth]
                 (when (pos? remaining)
                   (let [state (-> state (cg/step [:redo]) cg/settle-state)]
                     (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
                     (oracle/check (ftm/refresh-generated live) (:model state))
                     (t/is (uc/same-state? (oracle/snapshot (:situation state))
                                           (:files (ftm/snapshot)))
                           (str "burst redo seed " seed ", remaining " remaining))
                     (recur state (dec remaining))))))))))
      (finally
        (ftm/uninstall!)
        (rx/end! release)))))

(t/deftest ^:async seeded-edits-arrive-during-pending-sync
  (doseq [seed (range 12)]
    (await (pending-sync-burst seed false))))

(t/deftest ^:async seeded-undo-arrives-during-pending-sync
  (doseq [seed (range 12)]
    (await (pending-sync-burst seed true))))

(t/deftest ^:async library-notification-keeps-copies-stale-but-reset-uses-the-new-main
  (let [situation (cg/world [2 1 {:library true}])
        library (first (vals (tm/aux-files situation)))
        page (thf/current-page library)
        child (:shape (first (filter #(and (= :rect (get-in % [:shape :type]))
                                           (nil? (get-in % [:shape :shape-ref])))
                                     (cg/tree (tm/with-file situation library)))))
        root (get-in page [:objects (:parent-id child)])
        copies (filter #(= (:id child) (get-in % [:shape :shape-ref])) (cg/tree situation))
        first-copy (:shape (first copies))
        other-copy (:shape (second copies))
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page)) [(:id child)]
                                            #(n/set-property % :fills red) (:objects page) {})
        edited (thf/apply-changes library changes)
        cascaded (r/sync-cascade edited {} [(:component-id root)])
        changes (reduce pcb/concat-changes changes (:syncs cascaded))
        live (ftm/start-generated situation)
        fill (fn [id] (get-in (tm/shape-by-id (ftm/refresh-generated live) id) [:fills 0 :fill-color]))]
    (t/is (= :settled (await (ftm/await-step [(dwl/ext-library-changed (:id library) (ct/now) 1
                                                                       (:redo-changes changes))]))))
    (t/is (= setup/labeled-fill (fill (:id first-copy))))
    (t/is (= setup/labeled-fill (fill (:id other-copy))))
    (t/is (= :settled (await (ftm/await-step [(dwl/reset-component (:parent-id first-copy))]))))
    (t/is (= red (fill (:id first-copy))))
    (t/is (= setup/labeled-fill (fill (:id other-copy))))))

(t/deftest ^:async edit-step-waits-for-slow-sync
  (let [sync-file dwl/sync-file]
    (await
     (mock/with-mocks*
       {dwl/sync-file (deferred-sync-file sync-file slow-sync)}
       (ftm/install! (setup/simple-component-with-labeled-copy))
       (t/is (= :settled (await (ftm/await-step [(fill-main-child red)]))))
       (t/is (= red (ftm/fill-of :copy-child)))))))

(t/deftest ^:async undo-and-redo-steps-follow-a-slow-sync
  ;; Undo must find the slow sync already committed: it reverts the edit and
  ;; its sync together only when both are on the undo stack.
  (let [sync-file dwl/sync-file]
    (await
     (mock/with-mocks*
       {dwl/sync-file (deferred-sync-file sync-file slow-sync)}
       (ftm/install! (setup/simple-component-with-labeled-copy))
       (await (ftm/await-step [(fill-main-child red)]))
       (t/is (= red (ftm/fill-of :copy-child)) "the edit step ends synced")

       (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
       (t/is (= original (ftm/fill-of :main-child)))
       (t/is (= original (ftm/fill-of :copy-child)))

       (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
       (t/is (= red (ftm/fill-of :main-child)))
       (t/is (= red (ftm/fill-of :copy-child)))))))

(defn- watch-touches
  "Collect the ids of the components that `touch-component` events touch from
  now on. Returns `[ids-atom subscription]`."
  []
  (let [touched (atom #{})
        sub     (rx/sub! (rx/filter (ptk/type? ::dwl/touch-component) st/stream)
                         (fn [event] (swap! touched into @event)))]
    [touched sub]))

(t/deftest ^:async undo-and-redo-steps-wait-for-the-watcher
  ;; Undo and redo start no sync: the watcher only touches the component, a
  ;; timer turn after the commit. A step that resolved on the commit alone
  ;; would finish before that touch.
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (await (ftm/await-step [(fill-main-child red)]))
  (let [component     #{(thi/id :component1)}
        [touched sub] (watch-touches)]
    (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
    (t/is (= component @touched) "the undo step waited for the watcher")
    (reset! touched #{})
    (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
    (t/is (= component @touched) "the redo step waited for the watcher")
    (rx/dispose! sub)))

(t/deftest ^:async dropped-commit-steps-settle
  ;; Translation and derived commits start no sync; their steps must still
  ;; resolve.
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (t/is (= :settled (await (ftm/await-step [(ftm/fill-commit :main-child red {:translation? true})]))))
  (t/is (= :settled (await (ftm/await-step [(ftm/fill-commit :main-child red {:skip-component-sync? true})])))))

(defn- run-endless-step
  "Install a component whose sync never finishes and run a main edit step with
  a short timeout. Resolves with the step result and the file id."
  []
  (let [sync-file dwl/sync-file]
    (mock/with-mocks*
      {dwl/sync-file (deferred-sync-file sync-file endless-sync)}
      (ftm/install! (setup/simple-component-with-labeled-copy))
      (-> (ftm/await-step [(fill-main-child red)] 100)
          (.then (fn [result] [result (:id (ftm/current-file))]))))))

(t/deftest ^:async step-that-never-settles-reports-the-pending-work
  (let [[result file-id] (await (run-endless-step))]
    (t/is (contains? (get-in result [:timeout file-id]) :sync-file))))

(t/deftest ^:async timed-out-step-does-not-hold-up-the-next-variant
  ;; The endless sync stays pending; installing the next variant forgets it.
  (await (run-endless-step))
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (t/is (empty? (wrf/pending)))
  (t/is (= :settled (await (ftm/await-step [(fill-main-child red)]))))
  (t/is (= red (ftm/fill-of :copy-child))))

(t/deftest ^:async install-forgets-work-no-watcher-owns
  ;; With no layout watcher running, stopping one clears nothing: the install
  ;; itself must forget the pending work.
  (ftm/uninstall!)
  (wrf/start! :sync-file [(random-uuid)])
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (t/is (empty? (wrf/pending))))

(t/deftest skipped-optional-keeps-the-undo-baseline
  ;; The end-of-variant round trip undoes back to the last file install. A
  ;; skipped optional installs nothing, so the baseline stays at the setup,
  ;; before the edit, where the undo index is -1.
  (t/async done
    (let [later (n/change-property :copy-instance :fills red)]
      (ftm/check done
                 {:setup     setup/simple-component-with-labeled-copy
                  :operation (tm/in-sequence [(n/change-property :main-instance :fills red)
                                              (tm/optional later)])}
                 (fn [situation]
                   ;; neither the skip nor the user step moves it
                   (t/is (= -1 (:depth (uc/baseline situation)))
                         (if (tm/applied? situation later) "with the later edit" "skipped")))))))
