;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-undo-test
  "Tests undo and redo of an undo entry with no undo group. The entry schema
  requires a group, but release builds elide the check, so a commit that
  passes a nil `:undo-group` stores such an entry; undo and redo take a
  branch of their own for it."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each ftm/fixture)

(def ^:private red "#ff0000")

(defn- fill-main-child-changes
  "The changes that fill the main child of the live file `color`."
  [color]
  (let [page (thf/current-page (ftm/current-file))]
    (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                #{(thi/id :main-child)}
                                #(assoc % :fills (ths/sample-fills-color :fill-color color))
                                (:objects page)
                                {})))

(defn- push-entry-without-group
  "An event that adds `changes` to the undo stack as an entry with no undo
  group, as a release build stores a commit whose `:undo-group` is nil."
  [changes]
  (ptk/reify ::push-entry-without-group
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo
              (fn [undo]
                (let [items (conj (vec (:items undo))
                                  {:undo-changes (:undo-changes changes)
                                   :redo-changes (:redo-changes changes)
                                   :tags #{}})]
                  (assoc undo :items items :index (dec (count items)))))))))

(defn- undo-index
  []
  (get-in @st/state [:workspace-undo :index]))

(t/deftest ^:async undo-and-redo-an-entry-without-undo-group
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (let [changes (fill-main-child-changes red)]
    (await (ftm/await-step [(dch/commit-changes {:redo-changes (:redo-changes changes)
                                                 :undo-changes (:undo-changes changes)
                                                 :save-undo? false})
                            (push-entry-without-group changes)]))
    (t/is (= red (ftm/fill-of :main-child)))
    (t/is (= 0 (undo-index)))

    (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
    (t/is (= setup/labeled-fill (ftm/fill-of :main-child)))
    (t/is (= -1 (undo-index)))

    (t/is (= :settled (await (ftm/await-step [dwu/redo]))))
    (t/is (= red (ftm/fill-of :main-child)))
    (t/is (= 0 (undo-index)))))
