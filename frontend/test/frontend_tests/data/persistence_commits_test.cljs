;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-commits-test
  "Tests for the pure commit bookkeeping of persistence
  (`app.main.data.persistence.commits`)."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.persistence.commits :as pcm]
   [cljs.test :as t :include-macros true]))

(defn- commit
  [file-id undo-group]
  {:id (uuid/next)
   :file-id file-id
   :file-revn 0
   :file-vern 0
   :features #{}
   :source :local
   :undo-group undo-group
   :redo-changes [{:type :mod-page :id (uuid/next) :name "redo"}]
   :undo-changes [{:type :mod-page :id (uuid/next) :name "undo"}]
   :remote-seq 0})

(defn- queued
  "Persistence state with `entries` queued, in order."
  [& entries]
  {:queue (into #queue [] (map :id) entries)
   :index (into {} (map (juxt :id identity)) entries)})

(t/deftest a-queue-entry-keeps-only-what-a-save-needs
  (let [file-id (uuid/next)
        c1      (commit file-id (uuid/next))
        c2      (commit file-id (uuid/next))
        entry   (pcm/queue-entry [c1 c2])]
    (t/is (= (:id c1) (:id entry)))
    (t/is (= file-id (:file-id entry)))
    (t/is (not (contains? entry :redo-changes)))
    (t/is (= [(:id c1) (:id c2)] (mapv :id (:commits entry))))
    (t/is (not (contains? (first (:commits entry)) :source)))))

(t/deftest pending-commits-are-the-queued-then-the-buffered-of-one-file
  (let [file-id  (uuid/next)
        other-id (uuid/next)
        c1       (commit file-id nil)
        c2       (commit other-id nil)
        c3       (commit file-id nil)
        pstate   (assoc (queued (pcm/queue-entry [c1]) (pcm/queue-entry [c2]))
                        :buffer [c3])]
    (t/is (= [(:id c1) (:id c3)] (mapv :id (pcm/pending-commits pstate file-id))))))

(t/deftest a-rollback-plan-reverts-whole-undo-groups-across-files
  (let [file-id (uuid/next)
        lib-id  (uuid/next)
        group   (uuid/next)
        kept    (commit file-id (uuid/next))
        bad     (commit file-id (uuid/next))
        lib-1   (commit lib-id (uuid/next))
        lib-2   (commit lib-id (uuid/next))
        linked  (commit lib-id group)
        later   (commit file-id group)
        saved   (assoc (commit lib-id group) :file-id lib-id)
        head    (pcm/queue-entry [kept bad])
        pstate  (-> (queued head (pcm/queue-entry [lib-1 linked lib-2]))
                    (assoc :buffer [later]
                           :saved [saved]))
        plan    (pcm/rollback-plan pstate head {:commit-ids [(:id bad)]})]
    (t/testing "this file reverts from the rejected commit on"
      (t/is (= [(:id bad) (:id later)]
               (mapv :id (get-in plan [:files file-id :reverted])))))
    (t/testing "the library reverts from the commit that shares a group on"
      (t/is (= [(:id linked) (:id lib-2)]
               (mapv :id (get-in plan [:files lib-id :reverted]))))
      (t/is (= [(:id lib-1)] (mapv :id (get-in plan [:files lib-id :redo])))))
    (t/testing "saved commits of a reverted group are compensated"
      (t/is (= [(:id saved)] (mapv :id (:compensate plan)))))
    (t/is (= {:reverted 5 :files 2} (pcm/rollback-summary plan)))

    (t/testing "the rolled back commits leave the queue and the buffer"
      (let [pstate' (pcm/without-rolled-back pstate head plan)]
        (t/is (= [(:id kept)] (mapv :id (get-in pstate' [:index (:id head) :commits]))))
        (t/is (= [(:id lib-1)]
                 (mapv :id (get-in pstate' [:index (:id lib-1) :commits]))))
        (t/is (empty? (:buffer pstate')))
        (t/is (empty? (:saved pstate')))))))

(t/deftest an-entry-left-without-commits-leaves-the-queue
  (let [file-id (uuid/next)
        lib-id  (uuid/next)
        group   (uuid/next)
        bad     (commit file-id group)
        linked  (commit lib-id group)
        head    (pcm/queue-entry [bad])
        lib     (pcm/queue-entry [linked])
        pstate  (queued head lib)
        plan    (pcm/rollback-plan pstate head {:commit-ids [(:id bad)]})
        pstate' (pcm/without-rolled-back pstate head plan)]
    (t/is (= [(:id head)] (vec (:queue pstate'))) "the in-flight entry stays until it is discarded")
    (t/is (not (contains? (:index pstate') (:id lib))))))

(t/deftest compensation-undoes-newest-first
  (let [c1 {:redo-changes [:r1] :undo-changes [:u1]}
        c2 {:redo-changes [:r2] :undo-changes [:u2]}]
    (t/is (= {:redo-changes [:u2 :u1] :undo-changes [:r1 :r2]}
             (pcm/compensation-changes [c1 c2])))))

(t/deftest a-rollback-plan-names-the-index-before-the-oldest-reverted-undo
  (let [file-id (uuid/next)
        bad     (commit file-id (uuid/next))
        undo-1  (assoc (commit file-id (uuid/next)) :undo-index 4)
        undo-2  (assoc (commit file-id (uuid/next)) :undo-index 3)
        head    (pcm/queue-entry [bad undo-1])
        pstate  (assoc (queued head) :buffer [undo-2])]
    (t/is (= 4 (:undo-index (pcm/rollback-plan pstate head {:commit-ids [(:id bad) (:id undo-1)]}))))
    (t/is (nil? (:undo-index (pcm/rollback-plan (queued (pcm/queue-entry [bad]))
                                                (pcm/queue-entry [bad])
                                                {:commit-ids [(:id bad)]}))))))

(t/deftest saved-commits-are-kept-while-a-pending-commit-shares-their-group
  (let [file-id (uuid/next)
        group   (uuid/next)
        shared  (commit file-id group)
        alone   (commit file-id (uuid/next))
        pending (commit file-id group)
        old     (commit file-id (uuid/next))
        entry   (pcm/queue-entry [shared alone])
        pstate  (pcm/keep-saved-commits {:queue #queue [] :index {} :buffer [pending] :saved [old]}
                                        entry)]
    (t/is (= [(:id shared)] (mapv :id (:saved pstate))))
    (t/is (= file-id (:file-id (first (:saved pstate))))
          "a saved commit carries the file it was saved to")))

(t/deftest remote-changes-older-than-the-oldest-pending-commit-are-forgotten
  (let [file-id (uuid/next)
        pending (assoc (commit file-id nil) :remote-seq 2)
        pstate  {:queue #queue [] :index {} :buffer [pending]
                 :remote-changes {file-id [{:seq 1 :changes [:old]} {:seq 3 :changes [:new]}]}}]
    (t/is (= [{:seq 3 :changes [:new]}]
             (get-in (pcm/prune-remote-changes pstate file-id) [:remote-changes file-id])))
    (t/is (not (contains? (:remote-changes (pcm/prune-remote-changes (dissoc pstate :buffer) file-id))
                          file-id))
          "a file with nothing pending keeps no remote changes")))

(t/deftest a-release-plan-reverts-every-pending-commit-of-the-file-and-its-groups
  (let [file-id (uuid/next)
        lib-id  (uuid/next)
        group   (uuid/next)
        edit    (commit file-id (uuid/next))
        linked  (commit file-id group)
        lib     (commit lib-id group)
        pstate  (assoc (queued (pcm/queue-entry [edit linked]))
                       :buffer [lib])
        plan    (pcm/release-plan pstate file-id)]
    (t/is (= [(:id edit) (:id linked)] (mapv :id (get-in plan [:files file-id :reverted]))))
    (t/is (empty? (get-in plan [:files file-id :redo])))
    (t/is (= [(:id lib)] (mapv :id (get-in plan [:files lib-id :reverted])))
          "the rest of the undo group goes too")))
