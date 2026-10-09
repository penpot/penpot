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
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

;; The watcher schedules thumbnail renders that reach `window`, absent
;; headless (see `ftm/install-thumbnail-noop!`).
(t/use-fixtures :each
  {:before (fn []
             (thi/reset-idmap!)
             (ftm/install-thumbnail-noop!))
   :after  ftm/restore-thumbnail!})

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

(defn- pause
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- fill-main-child
  [color]
  (dwsh/update-shapes #{(thi/id :main-child)}
                      #(assoc % :fills (ths/sample-fills-color :fill-color color))))

(defn- fill-of
  [label]
  (-> (ths/get-shape (ftm/current-file) label) :fills first :fill-color))

(defn- raw-fill-commit
  "A main child fill commit with `opts` merged into its params."
  [opts]
  (let [page    (thf/current-page (ftm/current-file))
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(thi/id :main-child)}
                 #(assoc % :fills (ths/sample-fills-color :fill-color red))
                 (:objects page)
                 {})]
    (dch/commit-changes (merge {:redo-changes (:redo-changes changes)
                                :undo-changes (:undo-changes changes)}
                               opts))))

(t/deftest ^:async edit-step-waits-for-slow-sync
  (let [sync-file dwl/sync-file]
    (await
     (mock/with-mocks*
       {dwl/sync-file (deferred-sync-file sync-file slow-sync)}
       (ftm/install! (setup/simple-component-with-labeled-copy))
       (t/is (= :settled (await (ftm/await-step [(fill-main-child red)]))))
       (t/is (= red (fill-of :copy-child)))))))

(t/deftest ^:async undo-and-redo-steps-follow-a-slow-sync
  ;; Undo must find the slow sync already committed: it reverts the edit and
  ;; its sync together only when both are on the undo stack. The pause after
  ;; the undo step lets any work that outlived the edit step land.
  (let [sync-file dwl/sync-file]
    (await
     (mock/with-mocks*
       {dwl/sync-file (deferred-sync-file sync-file slow-sync)}
       (ftm/install! (setup/simple-component-with-labeled-copy))
       (await (ftm/await-step [(fill-main-child red)]))

       (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
       (await (pause (* 2 sync-delay-ms)))
       (t/is (= original (fill-of :main-child)))
       (t/is (= original (fill-of :copy-child)))

       (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
       (t/is (= red (fill-of :main-child)))
       (t/is (= red (fill-of :copy-child)))))))

(defn- watch-touches
  "Count the `touch-component` events emitted from now on. Returns
  `[count-atom subscription]`."
  []
  (let [touches (atom 0)
        sub     (rx/sub! (rx/filter (ptk/type? ::dwl/touch-component) st/stream)
                         (fn [_] (swap! touches inc)))]
    [touches sub]))

(t/deftest ^:async undo-and-redo-steps-wait-for-the-watcher
  ;; Undo and redo start no sync: the watcher only touches the component, a
  ;; timer turn after the commit. A step that resolved on the commit alone
  ;; would finish before that touch.
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (await (ftm/await-step [(fill-main-child red)]))
  (let [[touches sub] (watch-touches)]
    (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
    (t/is (= 1 @touches) "the undo step waited for the watcher")
    (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
    (t/is (= 2 @touches) "the redo step waited for the watcher")
    (rx/dispose! sub)))

(t/deftest ^:async dropped-commit-steps-settle
  ;; Translation and derived commits start no sync; their steps must still
  ;; resolve.
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (t/is (= :settled (await (ftm/await-step [(raw-fill-commit {:translation? true})]))))
  (t/is (= :settled (await (ftm/await-step [(raw-fill-commit {:skip-component-sync? true})])))))

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
  (t/is (= red (fill-of :copy-child))))
