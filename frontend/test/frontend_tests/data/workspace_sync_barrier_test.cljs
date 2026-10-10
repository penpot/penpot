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
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.ids-map :as thi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.store :as st]
   [app.plugins.reflow :as pwrf]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each ftm/fixture)

(def ^:private red "#ff0000")

(defn- sync-pending?
  [file-id]
  (contains? (get (wrf/pending) file-id) :sync-file))

(defn- run-commit
  "Install a component with a copy and emit the main child fill commit with
   `commit-opts`. Once the wait settles, call `check` (which may return a
   promise) with whether the sync wait was open right after the commit and an
   atom with the ids of the components the watcher touched. Fails the test
   when the wait does not settle."
  [done commit-opts check]
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (let [file-id (:id (ftm/current-file))
        touched (atom #{})
        sub     (rx/sub! (rx/filter (ptk/type? ::dwl/touch-component) st/stream)
                         #(swap! touched into @%))]
    (st/emit! (ftm/fill-commit :main-child red commit-opts))
    (let [open? (sync-pending? file-id)]
      (-> (pwrf/wait-for-layout-update nil 2000)
          (.then #(check open? touched)
                 #(t/is false "the sync wait did not settle"))
          (.catch #(t/is false (str "the check threw: " %)))
          (.finally (fn []
                      (rx/dispose! sub)
                      (done)))))))

(t/deftest user-edit-holds-the-wait-until-copies-sync
  (t/async done
    (run-commit done {}
                (fn [open? _]
                  (t/is open?)
                  (t/is (= red (ftm/fill-of :copy-child)))))))

(t/deftest undo-commit-holds-the-wait-until-the-watcher-handles-it
  ;; Undo and redo commit with `save-undo? false`. The watcher only touches
  ;; the component for them, after a timer turn; the wait must cover that.
  (t/async done
    (run-commit done {:save-undo? false}
                (fn [open? touched]
                  (t/is open?)
                  (t/is (= #{(thi/id :component1)} @touched) "the watcher touched it")
                  (t/is (= setup/labeled-fill (ftm/fill-of :copy-child)) "no sync")))))

(defn- dropped
  "The check of a commit the watcher drops: no wait opens, and the copies do
   not sync, even once everything it could start has settled."
  [open? touched]
  (t/is (not open?))
  (-> (async/settle)
      (.then #(pwrf/wait-for-layout-update nil 2000))
      (.then (fn [_]
               (t/is (= setup/labeled-fill (ftm/fill-of :copy-child)))
               (t/is (empty? @touched))))))

(t/deftest translation-commit-leaves-nothing-pending
  (t/async done
    (run-commit done {:translation? true} dropped)))

(t/deftest derived-commit-leaves-nothing-pending
  (t/async done
    (run-commit done {:skip-component-sync? true} dropped)))
