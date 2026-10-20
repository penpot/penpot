;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-rollback-test
  "Tests for the rollback of a save the server partly rejected
  (`app.main.data.persistence`): which commits are reverted, how peers'
  changes survive, and how the UI state follows. The pure plan behind it
  is tested in `frontend-tests.data.persistence-commits-test`."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.modal :as-alias modal]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.persistence :as thp]
   [potok.v2.core :as ptk]))

(defn- grouped
  "`commit` with its undo group set to `group`: one action that makes
  several commits gives them the same undo group."
  [commit group]
  (ptk/data-event ::dch/commit (assoc @commit :undo-group group)))

(defn- add-shape-commit
  "A local commit of `file-id` that adds `shape` to the root of `page-id`."
  [file-id page-id {:keys [id] :as shape}]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :undo-group (uuid/next)
                   :redo-changes [{:type :add-obj :id id :page-id page-id
                                   :parent-id uuid/zero :frame-id uuid/zero :obj shape}]
                   :undo-changes [{:type :del-obj :id id :page-id page-id}]}))

(defn- sent-changes
  "The changes of each group of the `n`th save request."
  [requests n]
  (mapv :changes (get-in (nth @requests n) [1 :changes-with-metadata])))

;; Scenario: the first save carries two commits; while it is in flight a
;; third one is queued, an edit of another file is queued too, and a fourth
;; one waits in the buffer. The server keeps the first commit and rejects
;; the second. The file goes back to the first commit: the rejected one and
;; every later one of that file are undone and dropped from the queue, the
;; buffer and the undo history, while the other file's edit is still sent.
;; Proves: a rejection leaves the file as the server holds it and the queue
;; keeps going.
(t/deftest ^:async a-rejected-save-undoes-the-rejected-commit-and-every-later-one
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store failures]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             other-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")
             commit3  (thp/rename-commit file-id page-id shape-id "C" "D")
             commit4  (thp/rename-commit file-id page-id shape-id "D" "E")
             other    (thp/local-commit other-id)]
         ;; The local data already holds the four renames
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "E"))
                    #(-> %
                         (assoc-in [:files other-id] {:id other-id :revn 0})
                         (assoc :workspace-undo {:items (mapv thp/undo-entry [commit1 commit2 commit3 commit4])
                                                 :index 3})
                         (assoc-in [:workspace-local :selected] #{shape-id})))
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first two commits are sent"))
         (ptk/emit! store commit3 other ::dps/force-persist)
         (await (async/wait-for #(= 3 (count (get-in @store [:persistence :queue])))
                                "the third commit and the other file's edit are queued"))
         (ptk/emit! store commit4)
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "the fourth commit is buffered"))

         (rx/push! response (thp/rejection (:id @commit2)))
         (await (async/wait-for #(= 2 (count @requests)) "the other file's edit is sent next"))

         (t/is (= "B" (thp/shape-name store file-id page-id shape-id))
               "the file holds the commits the server kept")
         (t/is (= [other-id] (mapv #(get-in @store [:persistence :index % :file-id])
                                   (get-in @store [:persistence :queue])))
               "only the other file's edit is still queued")
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (= [(:undo-group @commit1)]
                  (mapv :undo-group (get-in @store [:workspace-undo :items]))))
         (t/is (= 0 (get-in @store [:workspace-undo :index])))
         (t/is (= 1 (count @failures)) "the user is told once")
         (t/is (= other-id (get-in (second @requests) [1 :id])))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; Scenario: an edit is buffered while a save is in flight and the save comes
;; back rejected, so the rollback drops the edit; the 200 ms pending notice
;; of that edit fires afterwards. Proves: with nothing left to save the
;; notice is ignored and the status stays :saved, so waiters such as "Pin
;; this version" are not left hanging.
(t/deftest ^:async a-pending-notice-after-a-rollback-leaves-the-status-saved
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "C")))
         (ptk/emit! store commit1 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first commit is sent"))
         (ptk/emit! store commit2)
         (rx/push! response (thp/rejection (:id @commit1)))
         (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                      (empty? (get-in @store [:persistence :buffer])))
                                "the rollback drops both commits"))
         ;; The notice the dropped edit scheduled
         (ptk/emit! store (#'dps/update-status :pending))
         (await (async/settle))
         (t/is (= :saved (get-in @store [:persistence :status]))))))))

;; Scenario: two edits wait in the buffer, the second one changing the
;; opacity, when a peer's opacity change arrives. The server keeps the first
;; edit and rejects the second. The server holds the peer's opacity, so the
;; local file must too, not the opacity from before the rejected edit.
;; Proves: the rollback applies the peer's changes again over the undone
;; commits.
(t/deftest ^:async a-rollback-keeps-a-peer-change-to-the-same-attribute
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/attr-commit file-id page-id shape-id :opacity 0.5 0.9)]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "B" :opacity 0.9)))
         (ptk/emit! store commit1 commit2)
         (await (async/wait-for #(= 2 (count (get-in @store [:persistence :buffer])))
                                "both edits are buffered"))
         (ptk/emit! store (thp/remote-commit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.3)]))
         (await (async/settle))
         (t/is (= 0.9 (thp/shape-attr store file-id page-id shape-id :opacity))
               "the local edit stays on top of the peer's while pending")
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edits are sent"))
         (rx/push! response (thp/rejection (:id @commit2)))
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the rollback settles"))
         (t/is (= 0.3 (thp/shape-attr store file-id page-id shape-id :opacity))
               "the peer's opacity, as on the server")
         (t/is (= "B" (thp/shape-name store file-id page-id shape-id))
               "the kept edit stays"))))))

;; Scenario: a peer's opacity change arrives while an opacity edit is
;; pending; that edit is saved (on the server it lands after the peer's).
;; Later two new edits are sent and the second is rejected. The rollback
;; must not apply the old peer change again, which would undo the saved
;; edit. Proves: remote changes older than the oldest pending commit are
;; forgotten once the commits pending before them are saved.
(t/deftest ^:async a-rollback-ignores-peer-changes-older-than-the-pending-commits
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit0  (thp/attr-commit file-id page-id shape-id :opacity 0.5 0.7)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "A" :opacity 0.7)))
         (ptk/emit! store commit0)
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer])) "the edit is buffered"))
         (ptk/emit! store (thp/remote-commit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.3)]))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the opacity edit is sent"))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the opacity edit is saved"))
         (t/is (empty? (get-in @store [:persistence :remote-changes file-id]))
               "nothing pending, nothing kept")
         (ptk/emit! store
                    #(assoc-in % [:files file-id :data :pages-index page-id :objects shape-id :name] "C")
                    commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the renames are sent"))
         (rx/push! response (thp/rejection (:id @commit2)))
         (await (async/wait-for #(= "B" (thp/shape-name store file-id page-id shape-id))
                                "the rejected rename is undone"))
         (t/is (= 0.7 (thp/shape-attr store file-id page-id shape-id :opacity))
               "the saved opacity edit stays"))))))

;; Undo groups across files and saves.

;; Scenario: one action makes a commit in this file and one in a library
;; (as updating a main component from a copy does). The library also has an
;; unrelated edit before the action and another after it. This file's save
;; comes back with the action's commit rejected while the library's commits
;; are still queued. Proves: the rejection reverts the whole action, so the
;; library drops its commit of the action and every later one, and keeps
;; the edit made before it.
(t/deftest ^:async a-rejection-reverts-the-pending-commits-of-the-same-action-in-other-files
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [lib-id    (uuid/next)
             page-id   (uuid/next)
             shape-id  (uuid/next)
             lshape-id (uuid/next)
             group     (uuid/next)
             commit1   (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2   (grouped (thp/rename-commit file-id page-id shape-id "B" "C") group)
             before    (thp/rename-commit lib-id page-id lshape-id "X" "Y")
             linked    (grouped (thp/rename-commit lib-id page-id lshape-id "Y" "Z") group)
             after     (thp/rename-commit lib-id page-id lshape-id "Z" "W")]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "C")
                                                      (thp/file-data page-id lshape-id "W")))
         (ptk/emit! store commit1 commit2 before linked after ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "this file's save is sent"))
         (rx/push! response (thp/rejection (:id @commit2)))
         (await (async/wait-for #(= 2 (count @requests)) "the library's save is sent next"))
         (t/is (= "B" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= "Y" (thp/shape-name store lib-id page-id lshape-id))
               "the library goes back to the edit made before the action")
         (t/is (= lib-id (get-in (second @requests) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "Y")]] (sent-changes requests 1))
               "only the library's edit made before the action is saved")
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; Scenario: one action makes a commit in a library and one in this file.
;; The library's is saved first; then this file's save comes back with the
;; action's commit rejected. The library's commit can no longer be dropped.
;; Proves: a new commit undoes the saved part of the rejected action.
(t/deftest ^:async a-rejection-undoes-the-saved-commits-of-the-same-action-with-a-new-commit
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [lib-id    (uuid/next)
             page-id   (uuid/next)
             shape-id  (uuid/next)
             lshape-id (uuid/next)
             group     (uuid/next)
             linked    (grouped (thp/rename-commit lib-id page-id lshape-id "X" "Y") group)
             commit    (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "B")
                                                      (thp/file-data page-id lshape-id "Y")))
         (ptk/emit! store linked commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
         (t/is (= lib-id (get-in (first @requests) [1 :id])))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "a new commit undoes the library's"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent"))
         (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "X")]] (sent-changes requests 2)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= "X" (thp/shape-name store lib-id page-id lshape-id)))
         (rx/push! response {:revn 2})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; Scenario: as above, while the user has an undo transaction open. Proves:
;; the undo commit gets its own group, not the transaction's, so a later
;; rejection in the transaction does not revert it.
(t/deftest ^:async the-undo-of-a-saved-commit-stays-out-of-an-open-transaction
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [lib-id    (uuid/next)
             page-id   (uuid/next)
             shape-id  (uuid/next)
             lshape-id (uuid/next)
             group     (uuid/next)
             linked    (grouped (thp/rename-commit lib-id page-id lshape-id "X" "Y") group)
             commit    (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "B")
                                                      (thp/file-data page-id lshape-id "Y")))
         (ptk/emit! store linked commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
         (ptk/emit! store (dwu/start-undo-transaction (js/Symbol) :timeout 0))
         (let [tx-group (get-in @store [:workspace-undo :transaction :commit-group])]
           (rx/push! response (thp/rejection (:id @commit)))
           (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                  "a new commit undoes the library's"))
           (t/is (some? tx-group))
           (t/is (not= tx-group (get-in @store [:persistence :buffer 0 :undo-group])))
           (ptk/emit! store ::dps/force-persist)
           (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent"))
           (rx/push! response {:revn 2})
           (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                  "the queue drains"))))))))

;; Scenario: as above, but the library is no longer loaded when this file's
;; save comes back. Proves: the undo of the library's saved commit is still
;; sent, without touching local data the library no longer has.
(t/deftest ^:async a-rejection-queues-the-undo-of-a-saved-commit-of-a-file-not-loaded
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [lib-id    (uuid/next)
             page-id   (uuid/next)
             shape-id  (uuid/next)
             lshape-id (uuid/next)
             group     (uuid/next)
             linked    (grouped (thp/rename-commit lib-id page-id lshape-id "X" "Y") group)
             commit    (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "B")
                                                      (thp/file-data page-id lshape-id "Y")))
         (ptk/emit! store linked commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
         (ptk/emit! store #(update % :files dissoc lib-id))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(= 3 (count @requests)) "the undo is queued and sent"))
         (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "X")]] (sent-changes requests 2)))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (nil? (get-in @store [:files lib-id])) "no local data is made up")
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (rx/push! response {:revn 2})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; Scenario: one action makes two commits in this file and the save keeps
;; the first and rejects the second. Proves: the kept commit of the action
;; is undone too, with a new commit, so no action is left half done.
(t/deftest ^:async a-rejection-undoes-the-kept-commits-of-the-same-action
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             group    (uuid/next)
             commit1  (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)
             commit2  (grouped (thp/attr-commit file-id page-id shape-id :opacity 1 0.5) group)]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "B" :opacity 0.5)))
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the action is sent"))
         (rx/push! response (thp/rejection (:id @commit2)))
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "a new commit undoes the kept one"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent"))
         (t/is (= [[(thp/rename page-id shape-id "A")]] (sent-changes requests 1)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 1 (thp/shape-attr store file-id page-id shape-id :opacity)))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; Scenario: applying a token to five shapes makes three commits inside one
;; undo transaction, all sent in one save; the server keeps the first two
;; and rejects the third. Proves: the whole action is reverted, the kept
;; commits by a new commit, so opacity and applied tokens are back to their
;; starting values on every shape.
(t/deftest ^:async a-rejection-reverts-every-commit-of-an-undo-transaction
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id   (uuid/next)
             shape-ids (into [] (repeatedly 5 uuid/next))
             shapes    (map #(cts/setup-shape {:id % :type :rect :name "R" :opacity 1
                                               :parent-id uuid/zero :frame-id uuid/zero})
                            shape-ids)
             apply-to  (fn [ids]
                         (dch/commit-changes
                          {:redo-changes (into []
                                               (mapcat (fn [id]
                                                         [(thp/set-attr page-id id :opacity 0.5)
                                                          (thp/set-attr page-id id :applied-tokens {:opacity "half"})]))
                                               ids)
                           :undo-changes (into []
                                               (mapcat (fn [id]
                                                         [(thp/set-attr page-id id :opacity 1)
                                                          (thp/set-attr page-id id :applied-tokens nil)]))
                                               ids)}))
             tx-id     (js/Symbol)]
         (ptk/emit! store (thp/open-file file-id page-id
                                         {:pages [page-id]
                                          :pages-index {page-id {:id page-id
                                                                 :name "Page"
                                                                 :objects (into {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame
                                                                                                             :shapes shape-ids})}
                                                                                (map (juxt :id identity))
                                                                                shapes)}}}))
         (ptk/emit! store
                    (dwu/start-undo-transaction tx-id :timeout 0)
                    (apply-to (subvec shape-ids 0 2))
                    (apply-to (subvec shape-ids 2 4))
                    (apply-to (subvec shape-ids 4 5))
                    (dwu/commit-undo-transaction tx-id)
                    ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the action is sent"))
         (let [groups (get-in (first @requests) [1 :changes-with-metadata])]
           (t/is (= 3 (count groups)) "the save carries the three commits")
           (rx/push! response (thp/rejection (:commit-id (nth groups 2)))))
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "a new commit undoes the kept ones"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent"))
         (doseq [id shape-ids]
           (t/is (= 1 (thp/shape-attr store file-id page-id id :opacity)))
           (t/is (nil? (thp/shape-attr store file-id page-id id :applied-tokens))))
         (t/is (= #{1}
                  (into #{}
                        (comp (mapcat :changes)
                              (mapcat :operations)
                              (filter #(= :opacity (:attr %)))
                              (map :val))
                        (get-in (second @requests) [1 :changes-with-metadata])))
               "the new commit puts the kept opacity back on the server")
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the queue drains")))))))

;; UI state after a rollback.

;; Scenario: the rejected commit added the text being edited. Proves: the
;; rollback leaves edition mode, so the editor does not keep pointing at a
;; shape that no longer exists.
(t/deftest ^:async a-rollback-leaves-the-edition-of-a-removed-shape
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             text-id  (uuid/next)
             text     (cts/setup-shape {:id text-id :type :text :parent-id uuid/zero :frame-id uuid/zero})
             commit   (add-shape-commit file-id page-id text)]
         (ptk/emit! store
                    (thp/open-file file-id page-id (-> (thp/file-data page-id shape-id "A")
                                                       (thp/add-shape page-id text)))
                    #(assoc-in % [:workspace-local :edition] text-id))
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the new text is sent"))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(nil? (get-in @store [:workspace-local :edition]))
                                "the rollback leaves edition mode"))
         (t/is (nil? (thp/shape-attr store file-id page-id text-id :id))))))))

;; Scenario: the user edits the nodes of a saved path and the rejected commit
;; changed that path. The path editor works on its own copy of the shape,
;; which still holds the rejected content. Proves: the rollback leaves the
;; edition and drops that copy, so the editor neither shows nor saves the
;; reverted content.
(t/deftest ^:async a-rollback-leaves-the-edition-of-a-changed-path
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit   (thp/rename-commit file-id page-id shape-id "A" "B")]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    #(-> %
                         (assoc-in [:workspace-local :edition] shape-id)
                         (assoc-in [:workspace-local :edit-path shape-id]
                                   {:edit-mode :move :old-content [:rejected]})
                         (assoc-in [:workspace-drawing :object]
                                   {:id shape-id :content [:rejected]})))
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent"))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(nil? (get-in @store [:workspace-local :edition]))
                                "the rollback leaves edition mode"))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (t/is (nil? (get-in @store [:workspace-local :edit-path shape-id]))
               "the editor's state is dropped")
         (t/is (nil? (get-in @store [:workspace-drawing :object]))
               "the editor's copy of the path is dropped")
         (await (async/settle))
         (t/is (= 1 (count @requests)) "closing the editor saves nothing"))))))

;; Scenario: a rollback changes a shape other than the one being edited.
;; Proves: the edition stays open.
(t/deftest ^:async a-rollback-keeps-the-edition-of-an-unchanged-shape
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             other-id (uuid/next)
             other    (cts/setup-shape {:id other-id :type :rect :name "Other"
                                        :parent-id uuid/zero :frame-id uuid/zero})
             commit   (thp/rename-commit file-id page-id shape-id "A" "B")]
         (ptk/emit! store
                    (thp/open-file file-id page-id (-> (thp/file-data page-id shape-id "B")
                                                       (thp/add-shape page-id other)))
                    #(assoc-in % [:workspace-local :edition] other-id))
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent"))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(= "A" (thp/shape-name store file-id page-id shape-id))
                                "the rollback lands"))
         (await (async/settle))
         (t/is (= other-id (get-in @store [:workspace-local :edition]))))))))

;; Scenario: the color picker is open on a new rectangle when the save that
;; created it is rejected. Proves: the rollback closes the picker, which
;; would otherwise edit a shape that no longer exists.
(t/deftest ^:async a-rollback-closes-the-color-picker-of-a-removed-shape
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             rect-id  (uuid/next)
             rect     (cts/setup-shape {:id rect-id :type :rect :parent-id uuid/zero :frame-id uuid/zero})
             commit   (add-shape-commit file-id page-id rect)]
         (ptk/emit! store
                    (thp/open-file file-id page-id (-> (thp/file-data page-id shape-id "A")
                                                       (thp/add-shape page-id rect)))
                    #(-> %
                         (assoc-in [:workspace-local :selected] #{rect-id})
                         (assoc ::modal/modal {:id (uuid/next) :type :colorpicker :props {}})))
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the new rectangle is sent"))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(empty? (get-in @store [:workspace-local :selected]))
                                "the rollback deselects the removed shape"))
         (t/is (nil? (::modal/modal @store)) "the color picker is closed"))))))

;; Scenario: the user undoes a rename with Ctrl+Z and the server rejects the
;; commit the undo makes. Proves: the rollback puts the rename back on
;; screen and the history back where it was, instead of clearing it.
(t/deftest ^:async a-rejected-undo-puts-the-change-and-the-history-back
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             item     (thp/undo-entry (thp/rename-commit file-id page-id shape-id "A" "B"))]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    #(assoc % :workspace-undo {:items [item] :index 0}))
         (ptk/emit! store dwu/undo)
         (await (async/wait-for #(= "A" (thp/shape-name store file-id page-id shape-id))
                                "the rename is undone"))
         (t/is (= -1 (get-in @store [:workspace-undo :index])))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the undo is sent"))
         (rx/push! response (thp/rejection
                             (get-in (first @requests) [1 :changes-with-metadata 0 :commit-id])))
         (await (async/wait-for #(= "B" (thp/shape-name store file-id page-id shape-id))
                                "the rename is back"))
         (t/is (= [item] (get-in @store [:workspace-undo :items])) "the history stays")
         (t/is (= 0 (get-in @store [:workspace-undo :index])) "it points at the rename again")
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the rollback settles")))))))
