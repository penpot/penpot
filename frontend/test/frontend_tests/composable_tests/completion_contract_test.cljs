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
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
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

(t/use-fixtures :each ftm/fixture)

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
