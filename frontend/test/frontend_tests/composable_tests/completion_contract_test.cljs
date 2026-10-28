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
   [app.common.test-helpers.composable.comp.files :as cf]
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
   [app.common.test-helpers.normalize :as thn]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.thumbnails-wasm :as dwthw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm]
   [app.render-wasm.api.fonts :as wasm.fonts]
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

(t/deftest ^:async deleting-a-flex-child-undoes-with-its-delayed-reflow
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (doseq [mode [:standalone :explicit-group :outer-transaction]]
       (let [file     (-> (thf/sample-file :file)
                          (tho/add-frame :main-root (merge cls/initial-flex-layout
                                                           {:width 300 :height 100
                                                            :layout-gap {:row-gap 5 :column-gap 9}}))
                          (tho/add-rect :child-a :parent-label :main-root :width 50 :height 30)
                          (tho/add-rect :child-b :parent-label :main-root :width 50 :height 30)
                          (tho/add-rect :child-c :parent-label :main-root :width 50 :height 30)
                          (thc/make-component :component :main-root)
                          (thc/instantiate-component :component :copy))
             _        (ftm/install! (tm/make-situation file))
             group    (uuid/next)
             outer-id (js/Symbol)]
         (t/is (= :settled (await (ftm/await-step
                                   [(ptk/data-event :layout/update {:ids [(thi/id :main-root) (thi/id :copy)]})]))))
         (let [before (ftm/snapshot)
               x      (:x (ths/get-shape (ftm/current-file) :child-a))
               events (cond-> []
                        (= mode :outer-transaction)
                        (into [(dwu/start-undo-transaction outer-id)
                               (dwsh/update-shapes #{(thi/id :main-root)} #(assoc % :opacity 0.4)
                                                   {:undo-group group})])
                        true
                        (conj (dwsh/delete-shapes nil #{(thi/id :child-b)}
                                                  (if (= mode :explicit-group) {:undo-group group} {})))
                        (= mode :outer-transaction)
                        (conj (dwu/commit-undo-transaction outer-id)))]
           (t/is (= :settled (await (ftm/await-step events))))
           (let [after (ftm/snapshot)]
             (t/is (nil? (ths/get-shape (ftm/current-file) :child-b)))
             (t/is (= 2 (count (:shapes (ths/get-shape (ftm/current-file) :copy)))))
             (t/is (not= x (:x (ths/get-shape (ftm/current-file) :child-a))))
             (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
             (t/is (uc/same-state? before (ftm/snapshot)) (str "one undo restores " mode))
             (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
             (t/is (uc/same-state? after (ftm/snapshot)) (str "one redo restores " mode)))))))))

(t/deftest ^:async capped-history-keeps-a-primary-and-layout-entry-together
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [initial (ftm/start-generated (cg/world [1 0 {:layout :flex :layout-children true}]))
           filled  (loop [situation initial index 0]
                     (if (< index (dec dwu/MAX-UNDO-SIZE))
                       (recur (await (ftm/run-generated-step
                                      situation
                                      (n/change-property :sync-random-0-copyhead-0 :opacity
                                                         (if (even? index) 0.4 0.7))))
                              (inc index))
                       situation))
           capped  (await (ftm/run-generated-step filled (n/remove-child :random-0-layout-item-1)))
           before  (ftm/snapshot)
           checked (await (ftm/finish-generated capped))]
       (t/is (empty? (uc/failures filled)))
       (t/is (= dwu/MAX-UNDO-SIZE (count (get-in @st/state [:workspace-undo :items]))))
       (t/is (empty? (uc/failures capped)))
       (t/is (empty? (uc/failures checked)))
       (t/is (uc/same-state? before (ftm/snapshot)))))))

(t/deftest ^:async capped-history-keeps-checking-steps-and-rejects-missing-undo
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (let [initial (ftm/start-generated (setup/simple-component-with-labeled-copy))
           filled  (loop [situation initial index 0]
                     (if (< index dwu/MAX-UNDO-SIZE)
                       (let [next (await (ftm/run-generated-step situation
                                                                 (n/change-property :copy-root :opacity
                                                                                    (if (even? index) 0.4 0.7))))]
                         (t/is (empty? (uc/failures next)))
                         (recur next (inc index)))
                       situation))
           capped  (await (ftm/run-generated-step filled (n/detach :copy-root)))
           before  (ftm/snapshot)
           checked (await (ftm/finish-generated capped))]
       (t/is (= dwu/MAX-UNDO-SIZE (count (get-in @st/state [:workspace-undo :items]))))
       (t/is (empty? (uc/failures capped)))
       (t/is (empty? (uc/failures checked)))
       (t/is (uc/same-state? before (ftm/snapshot)))
       (let [update-shapes dwsh/update-shapes
             missing (await
                      (mock/with-mocks*
                        {dwsh/update-shapes (mock/stub
                                             (fn [ids update-fn & [options]]
                                               (update-shapes ids update-fn (assoc options :save-undo? false))))}
                        (await (ftm/run-generated-step checked (n/change-property :copy-root :opacity 0.2)))))]
         (t/is (= 0.2 (:opacity (ths/get-shape (ftm/current-file) :copy-root))))
         (t/is (= :no-undo-entry (:phase (peek (uc/failures missing)))))
         (t/is (= (inc (count (uc/failures checked))) (count (uc/failures missing)))))))))

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

(t/deftest ^:async a-delayed-font-resize-stays-with-its-font-edit
  (doseq [component? [false true]]
    (let [features (:features @st/state)
          ready (atom true)
          face {:font-id "gfont-roboto" :weight 400 :style 0 :emoji? false}
          measuring (atom nil)]
      (thw/setup-wasm-mocks!)
      (try
        (await
         (mock/with-mocks*
           {wasm/text-editor-has-focus? (mock/stub (constantly false))
            wasm.fonts/get-content-fonts (mock/stub (constantly [face]))
            wasm.fonts/make-font-data (mock/stub identity)
            wasm.fonts/font-ready? (mock/stub (fn [_] @ready))
            wasm/set-shape-text-content (mock/stub (fn [id _] (reset! measuring id)))
            wasm/get-text-dimensions (mock/stub (fn [] {:x 0 :y 0 :width 100 :max-width 100
                                                        :height (if @ready
                                                                  (if (= @measuring (thi/id :copy-child)) 50 40)
                                                                  20)}))
            dwthw/render-thumbnail (mock/stub (fn [& _] (ptk/data-event ::thumbnail-skipped)))}
           (let [file (cond-> (thf/sample-file :file1)
                        component?
                        (thtx/add-text-component-with-copy [[["Read the guide"]]])
                        (not component?)
                        (ths/add-sample-shape :text :type :text :width 100 :height 20
                                              :grow-type :auto-height
                                              :content (thtx/content [[["Read the guide"]]])))
                 text-id (thi/id (if component? :main-child :text))
                 file (-> file
                          (ths/add-sample-shape :rect :type :rect :opacity 1))
                 _ (ftm/install! (tm/make-situation file))
                 before (ftm/snapshot)]
             (st/emit! #(update % :features (fnil conj #{}) "render-wasm/v1"))
             (reset! ready false)
             (st/emit! (dwt/update-all-attrs [text-id]
                                             {:font-id "gfont-roboto" :font-family "Roboto"
                                              :font-variant-id "regular"}))
             (await (ha/wait-for #(some (comp seq :font) (vals (wrf/pending)))
                                 "font edit is pending"))
             (st/emit! (dwsh/update-shapes [(thi/id :rect)] #(assoc % :opacity 0.4)))
             (await (ha/wait-for #(= 0.4 (:opacity (ths/get-shape (ftm/current-file) :rect)))
                                 "unrelated edit commits before the font arrives"))
             (let [text (ths/get-shape-by-id (ftm/current-file) text-id)
                   faces (map (comp wasm.fonts/font-data-key wasm.fonts/make-font-data)
                              (wasm.fonts/get-content-fonts (:content text)))]
               (t/is (seq faces) "the font wait has a face to release")
               (reset! ready true)
               (doseq [face faces] (rx/push! wasm.fonts/font-stored-stream face)))
             (t/is (= :settled (await (ftm/await-step []))))
             (t/is (mth/close? 40 (:height (ths/get-shape-by-id (ftm/current-file) text-id))))
             (let [after (ftm/snapshot)]
               (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
               (t/is (= 1 (:opacity (ths/get-shape (ftm/current-file) :rect))))
               (t/is (mth/close? 40 (:height (ths/get-shape-by-id (ftm/current-file) text-id)))
                     "undoing the unrelated edit keeps the font's measured height")
               (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
               (t/is (uc/same-state? before (ftm/snapshot)))
               (t/is (= :settled (await (ftm/await-step [dwu/redo dwu/redo]))))
               (t/is (uc/same-state? after (ftm/snapshot)))))))
        (finally
          (thw/teardown-wasm-mocks!)
          (st/emit! #(assoc % :features features)))))))

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

(defn- ^:async source-acceptance-history
  [tuple transform-tuple]
  (let [initial (cg/initial-state [2 1 {:library true :stale-libraries true :library-text true}])
        situation (:situation initial)
        resolved (cg/resolve-op situation {} tuple)
        owner (:library-id resolved)
        transform-tuple (when transform-tuple
                          (assoc transform-tuple 1
                                 (first (keep-indexed
                                         (fn [index entry]
                                           (when (= (thi/id :library-text-component)
                                                    (get-in entry [:shape :component-id])) index))
                                         (cg/candidates situation {} :transform 4)))))
        edited (-> initial (cg/step tuple) cg/settle-state)
        group (peek (get-in edited [:situation :library-histories owner :runner ::r/undo-stack]))
        changes (mapcat :redo-changes group)
        ;; The notification envelope advances the loaded library revision.
        edited (update edited :situation
                       #(tm/with-aux-files % (update (tm/aux-files %) owner assoc :revn 1)))
        _ (ftm/start-generated situation)
        current-id (:id (tm/file situation))
        before (get-in (ftm/snapshot) [:files current-id])]
    (t/is (seq changes) (str "source edit changes " tuple))
    (when (#{:library-text :library-text-style} (first tuple))
      (t/is (not= (thtx/view (:content (:shape resolved)))
                  (thtx/normalize (:value (:op resolved))))
            (str "meaningful source text action " tuple)))
    (t/is (= :settled (await (ftm/await-step [(dwl/ext-library-changed owner (ct/now) 1 changes)]))))
    (t/is (= before (get-in (ftm/snapshot) [:files current-id])) "notification keeps consumer copies stale")
    (t/is (empty? (get-in @st/state [:workspace-undo :items])) "notification creates no consumer history")
    (when transform-tuple
      (let [before-transform (ftm/snapshot)
            resolved (cg/resolve-op situation {} transform-tuple)
            content (fn []
                      (into {} (map (fn [{:keys [shape]}]
                                      [(:id shape) (select-keys shape [:content :name :fills :strokes :shapes :opacity])]))
                            (cg/tree (ftm/refresh-generated situation))))
            before-content (content)]
        (t/is (= :settled (await (ftm/await-step (ftm/op->events (:op resolved) situation)))))
        (t/is (= before-content (content)) "a transform keeps pending source content and structure stale")
        (let [after-transform (ftm/snapshot)]
          (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
          (t/is (uc/same-state? before-transform (ftm/snapshot)))
          (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
          (t/is (uc/same-state? after-transform (ftm/snapshot))))))
    (let [edited (cond-> edited transform-tuple (cg/step transform-tuple))
          before-accept (ftm/snapshot)
          expected (-> edited (cg/step [:library-sync]) cg/settle-state)]
      (t/is (= :settled (await (ftm/await-step [(dwl/sync-file current-id owner)]))))
      (let [actual (ftm/refresh-generated (:situation expected))
            after-accept (ftm/snapshot)
            expected-design (oracle/canonical (:situation expected))
            actual-design (oracle/canonical actual)]
        (t/is (true? (oracle/equivalent? expected-design actual-design))
              (str "consumer acceptance " tuple ": " (pr-str (thn/diff expected-design actual-design))))
        (t/is (nil? (oracle/check actual (:model expected))))
        (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
        (t/is (uc/same-state? before-accept (ftm/snapshot)) "undo restores the stale consumer only")
        (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
        (t/is (uc/same-state? after-accept (ftm/snapshot)) "redo restores accepted geometry and text")))))

(t/deftest ^:async source-geometry-and-text-wait-for-consumer-acceptance
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (doseq [tuple (concat (map #(vector :library-transform 0 % 4 1) (range 6))
                           [[:library-text 0 0 1]]
                           (map #(vector :library-text-style 0 % 1) (range 12)))]
       (await (source-acceptance-history tuple nil))))))

(t/deftest ^:async a-transform-keeps-library-text-stale-until-acceptance
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (await (source-acceptance-history [:library-text 0 1 1] [:transform 8 3 3 4])))))

(defn- get-editor-situation
  [situation owner]
  (let [files (cf/all-files situation)]
    (-> situation
        (tm/with-file (get files owner))
        (tm/with-aux-files (dissoc files owner)))))

(defn- get-live-text
  [owner shape-id]
  (thtx/view (:content (tm/shape-by-id (tm/make-situation (get-in @st/state [:files owner])) shape-id))))

(defn- ^:async transitive-text-acceptance-history
  [tuple]
  (let [initial (cg/initial-state [2 0 {:library true :stale-libraries true
                                        :library-dependency true :library-text true}])
        situation (:situation initial)
        {:keys [source-id dependent-id source-text-id dependent-text-id
                consumer-text-ids direct-text-ids]} (cg/get-library-text-chain situation)
        current-id (:id (tm/file situation))
        source (get (tm/aux-files situation) source-id)
        original (thtx/view (:content (tm/shape-by-id (tm/make-situation source) source-text-id)))
        expected (thtx/normalize (:value (:op (cg/resolve-op situation {} tuple))))
        edited (-> initial (cg/step tuple) cg/settle-state)
        source-group (peek (get-in edited [:situation :library-histories source-id :runner ::r/undo-stack]))
        source-changes (vec (mapcat :redo-changes source-group))
        upstream (-> edited (cg/step [:library-accept 0]) cg/settle-state)
        accepted (-> upstream (cg/step [:library-sync 0]) cg/settle-state)
        dependent-context (get-editor-situation situation dependent-id)
        _ (ftm/start-generated dependent-context)
        before-source-notification (ftm/snapshot)]
    (t/is (not= original expected) (str "meaningful source action " tuple))
    (t/is (seq source-changes))
    (t/is (= :settled (await (ftm/await-step [(dwl/ext-library-changed source-id (ct/now) 1 source-changes)]))))
    (t/is (= (dissoc (:files before-source-notification) source-id)
             (dissoc (:files (ftm/snapshot)) source-id))
          "upstream notification changes only the loaded source")
    (t/is (empty? (get-in @st/state [:workspace-undo :items])))
    (t/is (= expected (get-live-text source-id source-text-id)))
    (t/is (= original (get-live-text dependent-id dependent-text-id)))
    (let [before (ftm/snapshot)]
      (t/is (= :settled (await (ftm/await-step [(dwl/sync-file dependent-id source-id)]))))
      (t/is (= expected (get-live-text dependent-id dependent-text-id)))
      (t/is (= (repeat 2 original) (map #(get-live-text current-id %) consumer-text-ids)))
      (t/is (= (dissoc (:files before) dependent-id) (dissoc (:files (ftm/snapshot)) dependent-id)))
      (let [after (ftm/snapshot)
            actual (ftm/refresh-generated (get-editor-situation (:situation upstream) dependent-id))
            dependent-changes (:redo-changes (peek (get-in @st/state [:workspace-undo :items])))]
        (t/is (seq dependent-changes))
        (t/is (nil? (oracle/check actual (:model upstream))))
        (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
        (t/is (uc/same-state? before (ftm/snapshot)) "dependent undo retains the loaded source")
        (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
        (t/is (uc/same-state? after (ftm/snapshot)) "dependent redo restores accepted text")
        ;; Install the separate consumer editor with its original stale data.
        ;; Deliver the real dependent acceptance changes as its notification.
        (ftm/start-generated situation)
        (let [before-notifications (get-in (ftm/snapshot) [:files current-id])]
          (t/is (= :settled (await (ftm/await-step [(dwl/ext-library-changed source-id (ct/now) 1 source-changes)]))))
          (t/is (= :settled (await (ftm/await-step [(dwl/ext-library-changed dependent-id (ct/now) 1 dependent-changes)]))))
          (t/is (= before-notifications (get-in (ftm/snapshot) [:files current-id])))
          (t/is (empty? (get-in @st/state [:workspace-undo :items])))
          (t/is (= expected (get-live-text source-id source-text-id)))
          (t/is (= expected (get-live-text dependent-id dependent-text-id)))
          (t/is (= (repeat 2 original) (map #(get-live-text current-id %) consumer-text-ids))))
        (let [before (ftm/snapshot)]
          (t/is (= :settled (await (ftm/await-step [(dwl/sync-file current-id dependent-id)]))))
          (t/is (= (repeat 2 expected) (map #(get-live-text current-id %) consumer-text-ids)))
          (t/is (= (repeat 2 original) (map #(get-live-text current-id %) direct-text-ids))
                "accepting the dependent leaves direct source copies stale")
          (t/is (= (dissoc (:files before) current-id) (dissoc (:files (ftm/snapshot)) current-id)))
          (let [after (ftm/snapshot)
                actual (ftm/refresh-generated (:situation accepted))]
            (t/is (nil? (oracle/check actual (:model accepted))))
            (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
            (t/is (uc/same-state? before (ftm/snapshot)) "consumer undo retains both loaded libraries")
            (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
            (t/is (uc/same-state? after (ftm/snapshot)) "consumer redo restores accepted text")))))))

(t/deftest ^:async transitive-text-waits-for-both-library-acceptances
  (await
   (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
     (doseq [tuple (into [[:library-text 0 0 1]]
                         (map #(vector :library-text-style 0 % 1) (range 12)))]
       (await (transitive-text-acceptance-history tuple))))))

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
