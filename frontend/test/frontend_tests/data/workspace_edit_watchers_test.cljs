;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-edit-watchers-test
  "Tests the lifecycle of the workspace edit watchers
  (`dw/initialize-edit-watchers`): starting them again, as a file reload
  does, replaces the running ones instead of adding a second set."
  (:require
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

;; The watcher schedules thumbnail renders that reach `window`, absent
;; headless (see `ftm/install-thumbnail-noop!`).
(t/use-fixtures :each
  {:before (fn []
             (thi/reset-idmap!)
             (ftm/install-thumbnail-noop!))
   :after  ftm/restore-thumbnail!})

(defn- undo-entries-for-an-edit
  "Install a component with a copy, start the edit watchers `starts` times,
  edit the main child and resolve with the number of undo entries added."
  [starts]
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (dotimes [_ (dec starts)]
    (st/emit! (dw/initialize-edit-watchers)))
  (swap! st/state dissoc :workspace-undo)
  (-> (ftm/await-step
       [(dwsh/update-shapes #{(thi/id :main-child)}
                            #(assoc % :fills (ths/sample-fills-color :fill-color "#ff0000")))])
      (.then (fn [_]
               (count (get-in @st/state [:workspace-undo :items]))))))

(t/deftest ^:async restarted-edit-watchers-record-each-commit-once
  (let [once  (await (undo-entries-for-an-edit 1))
        twice (await (undo-entries-for-an-edit 2))]
    (t/is (pos? once))
    (t/is (= once twice))))
