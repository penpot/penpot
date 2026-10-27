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
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.uuid :as uuid]
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

(t/deftest ^:async delayed-derived-changes-do-not-join-an-unrelated-transaction
  (let [file (-> (thf/sample-file :file)
                 (ths/add-sample-shape :first :type :rect :opacity 1)
                 (ths/add-sample-shape :second :type :rect :opacity 1))
        _ (ftm/install! (tm/make-situation file))
        before (ftm/snapshot)
        first-group (uuid/next)
        second-group (uuid/next)
        transaction (js/Symbol)
        edit (fn [label attr value opts]
               (let [page (thf/current-page (ftm/current-file))
                     changes (cls/generate-update-shapes
                              (pcb/empty-changes nil (:id page)) [(thi/id label)]
                              #(assoc % attr value) (:objects page) {})]
                 (dch/commit-changes (merge changes opts))))]
    (t/is (= :settled (await (ftm/await-step [(edit :first :opacity 0.4 {:undo-group first-group})]))))
    (t/is (= :settled
             (await (ftm/await-step [(dwu/start-undo-transaction transaction)
                                     (edit :second :opacity 0.5 {:undo-group second-group})
                                     (edit :first :r1 8 {:undo-group first-group :stack-undo? true})
                                     (dwu/commit-undo-transaction transaction)]))))
    (let [after (ftm/snapshot)]
      (t/is (= [first-group second-group] (mapv :undo-group (get-in @st/state [:workspace-undo :items]))))
      (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
      (t/is (= 1 (:opacity (ths/get-shape (ftm/current-file) :second))))
      (t/is (= 8 (:r1 (ths/get-shape (ftm/current-file) :first))))
      (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
      (t/is (uc/same-state? before (ftm/snapshot)))
      (t/is (= :settled (await (ftm/await-step [dwu/redo dwu/redo]))))
      (t/is (uc/same-state? after (ftm/snapshot))))))

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
