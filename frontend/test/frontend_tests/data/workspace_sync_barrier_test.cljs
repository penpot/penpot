;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-sync-barrier-test
  "Tests the `:sync-file` wait the component watcher holds for each local
  commit until it has decided what to do with it: sync, only touch the
  component, or drop the commit. Plugin `waitForLayoutUpdate` and the
  composable interpreter rely on it."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.store :as st]
   [app.plugins.reflow :as pwrf]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

;; The watcher schedules thumbnail renders that reach `window`, absent
;; headless (see `ftm/install-thumbnail-noop!`).
(t/use-fixtures :each
  {:before (fn []
             (thi/reset-idmap!)
             (ftm/install-thumbnail-noop!))
   :after  ftm/restore-thumbnail!})

(def ^:private red "#ff0000")

(defn- main-child-fill-commit
  "A commit that sets the main child's fill to red, with `opts` merged into the
   commit params."
  [opts]
  (let [file    (ftm/current-file)
        page    (thf/current-page file)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(thi/id :main-child)}
                 #(assoc % :fills (ths/sample-fills-color :fill-color red))
                 (:objects page)
                 {})]
    (dch/commit-changes (merge {:redo-changes (:redo-changes changes)
                                :undo-changes (:undo-changes changes)}
                               opts))))

(defn- sync-pending?
  [file-id]
  (contains? (get (wrf/pending) file-id) :sync-file))

(defn- fill-of
  [label]
  (-> (ths/get-shape (ftm/current-file) label) :fills first :fill-color))

(defn- run-commit
  "Install a component with a copy and emit the main child fill commit with
   `commit-opts`. Once the wait settles, call `check` with whether the sync
   wait was open right after the commit. Fails the test when the wait does not
   settle."
  [done commit-opts check]
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (let [file-id (:id (ftm/current-file))]
    (st/emit! (main-child-fill-commit commit-opts))
    (let [open? (sync-pending? file-id)]
      (-> (pwrf/wait-for-layout-update nil 2000)
          (.then #(check open?)
                 #(t/is false "the sync wait did not settle"))
          (.catch #(t/is false (str "the check threw: " %)))
          (.finally done)))))

(t/deftest user-edit-holds-the-wait-until-copies-sync
  (t/async done
    (run-commit done {}
                (fn [open?]
                  (t/is open?)
                  (t/is (= red (fill-of :copy-child)))))))

(t/deftest undo-commit-holds-the-wait-until-the-watcher-handles-it
  ;; Undo and redo commit with `save-undo? false`. The watcher only touches
  ;; the component for them, after a timer turn; the wait must cover that.
  (t/async done
    (run-commit done {:save-undo? false}
                (fn [open?]
                  (t/is open?)))))

(t/deftest translation-commit-leaves-nothing-pending
  (t/async done
    (run-commit done {:translation? true}
                (fn [open?]
                  (t/is (not open?))))))

(t/deftest derived-commit-leaves-nothing-pending
  (t/async done
    (run-commit done {:skip-component-sync? true}
                (fn [open?]
                  (t/is (not open?))))))
