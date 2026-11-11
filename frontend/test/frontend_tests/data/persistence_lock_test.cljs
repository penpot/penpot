;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-lock-test
  "Tests for the commits made while saves fail and the workspace is
  locked (`app.main.data.persistence.lock`): user edits are refused, the
  undo of rolled-back commits and derived data still commit."
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.lock :as plk]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- server-error
  "A save answer that fails for good: an unexpected server error."
  []
  (rp/handle-response {:status 500
                       :body {:type :server-error :code :unhandled}
                       :uri "https://penpot.example.com/api/main/methods/update-file"}))

(defn- answers
  "A transport that gives each save, in order, the next of `responses`
  (functions returning the answer's observable)."
  [& responses]
  (let [pending (atom responses)]
    (fn [_ _]
      (let [respond (first @pending)]
        (swap! pending rest)
        (respond)))))

(defn- saved
  [revn]
  #(rx/of {:revn revn}))

(def ^:private lock
  "The lock a failed save leaves, set by hand."
  #(assoc-in % [:workspace-global :persistence-lock] {:read-only? false}))

(defn- record-commits
  "Records, for each commit on `store`, whether the workspace was locked
  when it was made. Answers the subscription and the atom."
  [store]
  (let [made (atom [])
        sub  (->> (ptk/input-stream store)
                  (rx/filter dch/commit?)
                  (rx/subs! (fn [_] (swap! made conj (plk/locked? @store)))))]
    [sub made]))

;; Scenario: a save fails for good and the workspace locks; the user then
;; renames the shape through some UI the lock did not hide. Proves: the
;; edit makes no commit, so nothing queues behind the failed save and the
;; shape keeps its name.
(t/deftest ^:async an-edit-while-locked-makes-no-commit
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             [sub made] (record-commits store)]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    (thp/rename-commit file-id page-id shape-id "A" "B")
                    ::dps/force-persist)
         (await (async/wait-for #(plk/locked? @store) "the failed save locks the workspace"))
         (reset! made [])
         (ptk/emit! store
                    (dch/commit-changes {:redo-changes [(thp/rename page-id shape-id "C")]
                                         :undo-changes [(thp/rename page-id shape-id "B")]})
                    ::dps/force-persist)
         (await (async/settle))
         (rx/dispose! sub)
         (t/is (empty? @made) "no commit is made")
         (t/is (= "B" (thp/shape-name store file-id page-id shape-id)))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (= 1 (count (get-in @store [:persistence :queue])))
               "the queue holds only the failed edit")))
     (answers server-error))))

;; Scenario: the user presses Ctrl+Z while the workspace is locked.
;; Proves: the history stays where it was, since its commit would be
;; refused after the index moved.
(t/deftest ^:async undo-and-redo-do-nothing-while-locked
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-rect :rect1 :name "A"))
        store   (ths/setup-store file {:renderer :svg})
        page-id (cthf/current-page-id file)
        rect-id (cthi/id :rect1)
        rename  (fn [from to]
                  (dch/commit-changes {:redo-changes [(thp/rename page-id rect-id to)]
                                       :undo-changes [(thp/rename page-id rect-id from)]}))
        group   (uuid/next)
        entry   {:undo-changes [(thp/rename page-id rect-id "A")]
                 :redo-changes [(thp/rename page-id rect-id "B")]
                 :undo-group group
                 :undo-groups #{group}
                 :tags #{}}
        name-of #(:name (cths/get-shape (ths/get-file-from-state @store) :rect1))]
    (ptk/emit! store (rename "A" "B") #(assoc % :workspace-undo {:items [entry] :index 0}))
    (await (async/wait-for #(= "B" (name-of)) "the shape is renamed"))
    (ptk/emit! store lock dwu/undo)
    (await (async/settle))
    (t/is (= "B" (name-of)) "undo changes nothing")
    (t/is (= 0 (get-in @store [:workspace-undo :index])))
    (ptk/emit! store #(update % :workspace-global dissoc :persistence-lock) dwu/undo)
    (await (async/wait-for #(= "A" (name-of)) "undo works once unlocked"))
    (ptk/emit! store lock dwu/redo)
    (await (async/settle))
    (t/is (= "A" (name-of)) "redo changes nothing")
    (t/is (= -1 (get-in @store [:workspace-undo :index])))))

;; Scenario: one action renames a shape in a library and in this file;
;; the library's save lands, this file's fails for good and the workspace
;; locks. Edit rights come back with a role change, which releases the
;; failed edit while the lock still holds. Proves: the undo of the
;; library's saved rename still commits and is saved, so the library goes
;; back with the released edit.
(t/deftest ^:async a-release-undoes-saved-commits-while-locked
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store requests]}]
       (let [lib-id    (uuid/next)
             page-id   (uuid/next)
             shape-id  (uuid/next)
             lshape-id (uuid/next)
             group     (uuid/next)
             linked    (ptk/data-event ::dch/commit
                                       (assoc @(thp/rename-commit lib-id page-id lshape-id "X" "Y")
                                              :undo-group group))
             commit    (ptk/data-event ::dch/commit
                                       (assoc @(thp/rename-commit file-id page-id shape-id "A" "B")
                                              :undo-group group))
             [sub made] (record-commits store)]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "B")
                                                      (thp/file-data page-id lshape-id "Y")))
         (ptk/emit! store linked commit ::dps/force-persist)
         (await (async/wait-for #(plk/locked? @store) "this file's failed save locks the workspace"))
         (reset! made [])
         (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
         (await (async/wait-for #(seq @made) "the library's rename is undone"))
         (t/is (= [true] @made) "the undo commits while the lock holds")
         (t/is (= "X" (thp/shape-name store lib-id page-id lshape-id)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the undo is saved"))
         (rx/dispose! sub)
         (t/is (= 3 (count @requests)))
         (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
         (t/is (not (plk/locked? @store)))))
     (answers (saved 1) server-error (saved 2)))))

;; Scenario: texts without layout data are measured while the workspace is
;; locked, as on a reload during the lock. Proves: the derived layout still
;; commits, so the text and its layout data stay in step.
(t/deftest ^:async text-layout-data-commits-while-locked
  (let [file          (-> (cthf/sample-file :file1)
                          (ctho/add-text :text1 "hello"))
        store         (ths/setup-store file {:renderer :svg})
        position-data [{:x 0 :y 0 :width 80 :height 16 :fills [] :text "hello"}]
        [sub made]    (record-commits store)]
    (await
     (mock/with-mocks*
       {wasm.api/calculate-position-data (mock/stub (fn [_] position-data))}
       (ptk/emit! store lock (dw/update-page-position-data))
       (await (async/wait-for #(seq @made) "the layout data is committed"))))
    (rx/dispose! sub)
    (t/is (= [true] @made))
    (t/is (= position-data
             (:position-data (cths/get-shape (ths/get-file-from-state @store) :text1))))))

;; Scenario: a main component changes while the workspace is locked, by the
;; undo of a rolled-back commit. Proves: its modification time still
;; commits, so other files see the component changed again.
(t/deftest ^:async a-component-touch-commits-while-locked
  (let [file       (-> (cthf/sample-file :file1)
                       (ctho/add-simple-component :component1 :main-root :main-child))
        comp-id    (cthi/id :component1)
        ;; No modification time yet, so the touch shows whatever the clock
        file       (update-in file [:data :components comp-id] dissoc :modified-at)
        store      (ths/setup-store file {:renderer :svg})
        [sub made] (record-commits store)]
    (ptk/emit! store lock (dwl/touch-component comp-id))
    (await (async/wait-for #(seq @made) "the touch is committed"))
    (rx/dispose! sub)
    (t/is (= [true] @made))
    (t/is (some? (:modified-at (cthc/get-component (ths/get-file-from-state @store) :component1))))))
