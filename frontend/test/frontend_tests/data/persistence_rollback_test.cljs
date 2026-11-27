;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-rollback-test
  "Tests for the rollback of a save the server partly rejected
  (`app.main.data.persistence`): which commits are reverted, how peers'
   changes survive, and how the UI state follows. See also
   `frontend-tests.data.persistence-commits-test` for pure bookkeeping."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.modal :as-alias modal]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.commits :as pcm]
   [app.main.data.persistence.order :as porder]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.notifications :as dwn]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.versions :as dwv]
   [app.main.errors :as errors]
   [app.main.store :as st]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [debug :as dbg]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
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
  "The forward changes of each commit in the `n`th save request."
  [requests n]
  (mapv :changes (get-in @requests [n 1 :commits])))

(defn- peer-edit
  [file-id revn changes]
  (dwn/handle-file-change {:type :file-change :file-id file-id :vern 0 :revn revn
                           :profile-id (uuid/next) :session-id (uuid/next)
                           :revision-ids [(uuid/next)] :changes changes}))

(t/deftest ^:async a-malformed-rejection-does-not-settle-the-head-or-roll-back
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store response requests requests$]}]
       (await
        (mock/with-mocks*
          {rx/timer (mock/stub (fn [_] (rx/subject)))}
          (let [page-id (uuid/next)
                shape-id (uuid/next)
                edit (thp/rename-commit file-id page-id shape-id "Original" "Local")]
            (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Local"))
                       edit ::dps/force-persist)
            (await (async/wait-for #(seq @requests) "the edit is sent" {:signal requests$}))
            (rx/push! response (assoc-in (thp/rejection (:id @edit)) [:rejected :failed-commit-id] (uuid/next)))
            (await (async/wait-for-store store #(= :retrying (get-in @store [:persistence :status]))
                                         "an invalid answer follows the unknown-outcome path"))
            (t/is (= [(:id @edit)] (vec (get-in @store [:persistence :queue]))))
            (t/is (= "Local" (thp/shape-name store file-id page-id shape-id)))
            (t/is (= [:update-file-v2] (mapv first @requests))))))))))

(t/deftest ^:async a-total-rejection-cannot-compensate-over-a-repair-not-applied-yet
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests requests$ store failures]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               group    (uuid/next)
               prefix   (grouped (thp/rename-commit file-id page-id shape-id "Original" "Saved") group)
               rejected (grouped (thp/rename-commit file-id page-id shape-id "Saved" "Rejected") group)
               prior    [{:id (uuid/next) :revn 2} {:id (uuid/next) :revn 3}]]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                      #(assoc-in % [:files file-id :vern] 0) prefix ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the prefix is sent" {:signal requests$}))
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Rejected"))
                      rejected ::dps/force-persist)
           (rx/push! save (thp/save-response 0))
           (await (async/wait-for #(= 2 (count @requests)) "the suffix is sent" {:signal requests$}))
           (rx/push! save (assoc (thp/rejection (:id @rejected))
                                 :revn 3
                                 :recovery (thp/recovery :reload 1 prior)))
           (await (async/wait-for #(seq @failures) "the rejection is reported"))
           (ptk/emit! store ::dps/force-persist)
           (await (async/wait-for #(= 3 (count @requests)) "the rejection needs a server read" {:signal requests$}))
           (t/is (= [:update-file-v2 :update-file-v2 :get-file] (mapv first @requests)))
           (t/is (= 1 (some-> @failures first ex-data :kept-saved)))
           (when (= :get-file (first (peek @requests)))
             (rx/push! read {:id file-id :vern 0 :revn 3
                             :data (thp/file-data page-id shape-id "Repaired")})
             (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                          "the authoritative file settles the queue")))
           (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(t/deftest ^:async a-partial-save-without-base-proof-keeps-the-prefix-and-defers-rollback-effects
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id (uuid/next)
             shape-id (uuid/next)
             group (uuid/next)
             kept (grouped (thp/rename-commit file-id page-id shape-id "Original" "Kept") group)
             rejected (grouped (thp/attr-commit file-id page-id shape-id :opacity 1 0.5) group)
             events (atom [])
             sub (->> (ptk/input-stream store)
                      (rx/filter #(contains? #{::dps/save-rejected ::dps/rolled-back} (ptk/type %)))
                      (rx/subs! (fn [event]
                                  (swap! events conj [(ptk/type event)
                                                      (thp/shape-attr store file-id page-id shape-id :opacity)]))))]
         (try
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Kept" :opacity 0.5))
                      kept rejected ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the two commits are sent" {:signal requests$}))
           (rx/push! response (assoc (thp/partial-rejection 3 (:id @rejected)) :recovery {:mode :reload}))
           (await (async/wait-for #(= 2 (count @requests)) "the unproven base requires a full read"
                                  {:signal requests$}))
           (t/is (= [[::dps/save-rejected 0.5]] @events))
           (t/is (= 0.5 (thp/shape-attr store file-id page-id shape-id :opacity))
                 "the old inverse must not run while the GET is pending")
           (rx/push! response {:id file-id :vern 0 :revn 4
                               :data (thp/file-data page-id shape-id "Kept" :opacity 0.8)})
           (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                    (= 2 (count @events)))
                                        "the accepted server base settles and releases rollback effects"))
           (t/is (= [[::dps/save-rejected 0.5] [::dps/rolled-back 0.8]] @events))
           (t/is (= "Kept" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= 0.8 (thp/shape-attr store file-id page-id shape-id :opacity)))
           (ptk/emit! store ::dps/force-persist)
           (await (async/settle))
           (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))
           (finally (rx/dispose! sub))))))))

(defn- queued
  [& entries]
  {:queue (into #queue [] (map :id) entries)
   :index (into {} (map (juxt :id identity)) entries)})

(t/deftest a-rejected-save-needs-every-predecessor-by-row-id
  (let [row-2   (uuid/next)
        row-3   (uuid/next)
        required [{:revn 2 :id row-2} {:revn 3 :id row-3}]
        answer  {:revn 3 :written [] :recovery (thp/recovery :wait 1 required)}
        base    (porder/rejected-base 1 answer)
        message (fn [revn id] {:revn revn :revision-ids [id]})
        loaded  (porder/loaded 0 1)
        applied (fn [id]
                  (-> loaded
                      (porder/applied (message 2 id))
                      (porder/applied (message 3 row-3))))]
    (t/is (nil? (porder/rejected-base 1 (update-in answer [:recovery :required] pop))))
    (t/is (nil? (porder/rejected-base 1 (assoc-in answer [:recovery :required 1 :revn] 2))))
    (t/is (= :wait (porder/save-decision loaded base)))
    (t/is (= :reconcile (porder/save-decision (applied (uuid/next)) base)))
    (t/is (= :apply (porder/save-decision (applied row-2) base)))))

(t/deftest confirmed-commits-are-not-pending-and-version-selection-is-exact
  (let [confirmed (assoc (pcm/queue-entry [{:id :confirmed :file-id :file :file-vern 0}])
                         :confirmed true)
        current   (pcm/queue-entry [{:id :queued :file-id :file :file-vern 0}])
        old       (pcm/queue-entry [{:id :old :file-id :file :file-vern 1}])
        pstate    (assoc (queued confirmed current old)
                         :buffer [{:id :buffered :file-id :file :file-vern 0}
                                  {:id :unknown-version :file-id :file}
                                  {:id :other-file :file-id :other :file-vern 0}])]
    (t/is (= [:queued :old :buffered :unknown-version]
             (mapv :id (pcm/pending-commits pstate :file))))
    (t/is (= [:queued :buffered]
             (mapv :id (pcm/pending-commits pstate :file 0))))
    (t/is (= [:unknown-version]
             (mapv :id (pcm/pending-commits pstate :file nil))))))

(t/deftest uncertain-undo-marks-survive-queueing-and-discard
  (let [edit            {:id :edit :file-id :library :file-vern 2 :undo-group :group}
        later           {:id :later :file-id :library :file-vern 2 :undo-group :group}
        old             {:id :old :file-id :library :file-vern 1 :undo-group :group}
        linked          {:id :linked :file-id :file :file-vern 0 :undo-group :group}
        saved           {:id :saved :file-id :library :file-vern 2 :undo-group :group}
        head            (pcm/queue-entry [edit])
        old-entry       (pcm/queue-entry [old])
        pstate          (-> (queued head old-entry)
                            (assoc :buffer [later linked] :saved [saved])
                            (pcm/mark-undo-uncertain :library 2))
        [later' linked'] (:buffer pstate)
        flushed         (pcm/queue-entry [later'])
        discarded       (-> (queued old-entry flushed)
                            (assoc :buffer [linked'] :saved (:saved pstate))
                            (pcm/keep-saved-commits
                             (assoc (get-in pstate [:index :edit]) :confirmed true)))]
    (t/is (= [:edit :later]
             (into [] (comp (filter :undo-uncertain) (map :id))
                   (pcm/pending-commits pstate :library))))
    (t/is (not (:undo-uncertain linked')))
    (t/is (true? (get-in flushed [:commits 0 :undo-uncertain])))
    (t/is (= [:saved :edit] (mapv :id (:saved discarded))))
    (t/is (every? :undo-uncertain (:saved discarded)))
    (t/is (empty? (:saved (pcm/keep-saved-commits (queued (assoc flushed :confirmed true))
                                                  (get-in pstate [:index :edit]))))
          "a confirmed entry does not keep a group pending")))

(t/deftest a-rollback-keeps-all-saved-commits-of-a-marked-group
  (let [accepted {:id :accepted :file-id :file :file-vern 0 :undo-group :affected}
        rejected {:id :rejected :file-id :file :file-vern 0 :undo-group :affected}
        later    {:id :later :file-id :file :file-vern 0 :undo-group :unaffected}
        marked   {:id :marked :file-id :library :file-vern 2 :undo-group :affected :undo-index 2}
        safe     {:id :safe :file-id :other :file-vern 0 :undo-group :unaffected :undo-index 7}
        head     (pcm/queue-entry [accepted rejected])
        pstate   (-> (queued head)
                     (assoc :buffer [later] :saved [marked safe])
                     (pcm/mark-undo-uncertain :library 2))
        plan     (pcm/rollback-plan pstate head {:commit-ids [:rejected]})
        cleaned  (pcm/without-rolled-back pstate head plan)]
    (t/is (= [:safe] (mapv :id (:compensate plan))))
    (t/is (= [:marked :accepted] (mapv :id (:kept-saved plan))))
    (t/is (= #{:file :library} (:reconcile plan)))
    (t/is (= #{:affected :unaffected} (:groups plan)) "both groups leave the undo history")
    (t/is (= 7 (:undo-index plan)) "a kept undo commit does not rewind the index")
    (t/is (= {:reverted 3 :files 2 :kept-saved 2} (pcm/rollback-summary plan)))
    (t/is (= #{:file :other} (:file-ids (pcm/rollback-targets plan))))
    (t/is (empty? (:saved cleaned)) "neither saved outcome remains eligible for compensation")
    (t/is (empty? (:buffer cleaned)))
    (t/is (= [:accepted] (mapv :id (get-in cleaned [:index :accepted :commits])))
          "the confirmed prefix stays in the head until discard")))

(t/deftest a-file-awaiting-reconciliation-cannot-be-compensated-on-its-old-base
  (let [rejected   {:id :rejected :file-id :file :file-vern 0 :undo-group :bad}
        later      {:id :later :file-id :file :file-vern 0 :undo-group :later}
        marked     {:id :marked :file-id :library :file-vern 0 :undo-group :bad :undo-uncertain true}
        old-base   {:id :old-base :file-id :library :file-vern 0 :undo-group :later}
        other-file {:id :other-file :file-id :other :file-vern 0 :undo-group :later}
        head       (pcm/queue-entry [rejected])
        pstate     (assoc (queued head) :buffer [later] :saved [marked old-base other-file])
        plan       (pcm/rollback-plan pstate head {:commit-ids [:rejected]})]
    (t/is (= #{:library} (:reconcile plan)))
    (t/is (= [:marked :old-base] (mapv :id (:kept-saved plan))))
    (t/is (= [:other-file] (mapv :id (:compensate plan))))))

(t/deftest a-rollback-reads-undo-marks-added-while-the-save-is-in-flight
  (let [kept   {:id :kept :file-id :file :file-vern 0 :undo-group :group :undo-index 2}
        bad    {:id :bad :file-id :file :file-vern 0 :undo-group :group}
        head   (pcm/queue-entry [kept bad])
        pstate (pcm/mark-undo-uncertain (queued head) :file 0)
        plan   (pcm/rollback-plan pstate head {:commit-ids [:bad]})]
    (t/is (empty? (:compensate plan)))
    (t/is (true? (get-in plan [:kept-saved 0 :undo-uncertain])))
    (t/is (nil? (:undo-index plan)))
    (t/is (= #{:file} (:reconcile plan)))))

(t/deftest a-rollback-reconciles-instead-of-using-the-undo-of-a-kept-pending-commit
  (let [before  {:id :before :file-id :library :file-vern 2 :undo-group :before}
        linked  {:id :linked :file-id :library :file-vern 2 :undo-group :group}
        bad     {:id :bad :file-id :file :file-vern 0 :undo-group :group}
        head    (pcm/queue-entry [bad])
        pstate  (-> (queued head (pcm/queue-entry [before]))
                    (pcm/mark-undo-uncertain :library 2)
                    (assoc :buffer [linked]))
        plan    (pcm/rollback-plan pstate head {:commit-ids [:bad]})
        cleaned (pcm/without-rolled-back pstate head plan)]
    (t/is (= #{:library} (:reconcile plan)))
    (t/is (= [:linked] (mapv :id (get-in plan [:files :library :reverted]))))
    (t/is (= [:before] (mapv :id (pcm/pending-commits cleaned :library 2))))
    (t/is (= {:reverted 2 :files 2} (pcm/rollback-summary plan)))))

(t/deftest a-retry-rejection-does-not-authorize-compensation
  (let [kept   {:id :kept :file-id :file :file-vern 0 :undo-group :group}
        bad    {:id :bad :file-id :file :file-vern 0 :undo-group :group}
        saved  {:id :saved :file-id :library :file-vern 0 :undo-group :group}
        head   (pcm/queue-entry [kept bad])
        pstate (assoc (queued head) :saved [saved])]
    (doseq [[rejected kept-saved] [[[:bad] [:saved :kept]]
                                   [[:kept :bad] [:saved]]]]
      (let [plan (pcm/rollback-plan pstate head {:commit-ids rejected :uncertain true})]
        (t/is (empty? (:compensate plan)))
        (t/is (= kept-saved (mapv :id (:kept-saved plan))))
        (t/is (= #{:file :library} (:reconcile plan)))))))

(t/deftest reconciliation-only-drops-unsent-compensations-of-the-matching-file-version
  (let [compensation (fn [id file-id vern]
                       {:id id :file-id file-id :file-vern vern :compensation true})
        attempted    (assoc (pcm/queue-entry [(compensation :attempted :file 0)]) :attempted true)
        empty-entry  (pcm/queue-entry [(compensation :only-compensation :file 0)])
        mixed-entry  (pcm/queue-entry [{:id :ordinary :file-id :file :file-vern 0}
                                       (compensation :queued-compensation :file 0)])
        other-entry  (pcm/queue-entry [(compensation :other-file :other 0)])
        pstate       (assoc (queued attempted empty-entry mixed-entry other-entry)
                            :buffer [(compensation :buffered-compensation :file 0)
                                     {:id :buffered-edit :file-id :file :file-vern 0}
                                     (compensation :other-version :file 1)])
        trimmed      (pcm/without-unsent-compensations pstate :file 0 (complement :attempted))]
    (t/is (= (:queue pstate) (:queue trimmed)) "the runner can still acknowledge the emptied entry")
    (t/is (= [] (get-in trimmed [:index :only-compensation :commits])))
    (t/is (= [:ordinary] (mapv :id (get-in trimmed [:index :ordinary :commits]))))
    (t/is (= attempted (get-in trimmed [:index :attempted])) "the attempted request stays intact")
    (t/is (= other-entry (get-in trimmed [:index :other-file])))
    (t/is (= [:buffered-edit :other-version] (mapv :id (:buffer trimmed))))))

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
     (^:async fn [{:keys [file-id response requests requests$ store failures]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the first two commits are sent" {:signal requests$}))
         (ptk/emit! store commit3 other ::dps/force-persist)
         ;; This test drives the pipeline step by step: these two waits are the
         ;; pause that lets the persistence side effect advance before the next
         ;; emit, not an effect to observe, so they keep the poll.
         (await (async/wait-for #(= 3 (count (get-in @store [:persistence :queue])))
                                "the third commit and the other file's edit are queued"))
         (ptk/emit! store commit4)
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "the fourth commit is buffered"))

         (rx/push! response (thp/partial-rejection 0 (:id @commit2)))
         (await (async/wait-for #(= 2 (count @requests)) "the other file's edit is sent next" {:signal requests$}))

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
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

;; Scenario: an edit is buffered while a save is in flight and the save comes
;; back rejected, so the rollback drops the edit; the 200 ms pending notice
;; of that edit fires afterwards. Proves: with nothing left to save the
;; notice is ignored and the status stays :saved, so waiters such as "Pin
;; this version" are not left hanging.
(t/deftest ^:async a-pending-notice-after-a-rollback-leaves-the-status-saved
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "C")))
         (ptk/emit! store commit1 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first commit is sent" {:signal requests$}))
         (ptk/emit! store commit2)
         (rx/push! response (thp/rejection (:id @commit1)))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/attr-commit file-id page-id shape-id :opacity 0.5 0.9)]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "B" :opacity 0.9)))
         (ptk/emit! store commit1 commit2)
         (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :buffer])))
                                      "both edits are buffered"))
         (ptk/emit! store (peer-edit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.3)]))
         (await (async/settle))
         (t/is (= 0.9 (thp/shape-attr store file-id page-id shape-id :opacity))
               "the local edit stays on top of the peer's while pending")
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edits are sent" {:signal requests$}))
         (rx/push! response (thp/partial-rejection 1 (:id @commit2)))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit0  (thp/attr-commit file-id page-id shape-id :opacity 0.5 0.7)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "A" :opacity 0.7)))
         (ptk/emit! store commit0)
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer])) "the edit is buffered"))
         (ptk/emit! store (peer-edit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.3)]))
         (await (async/wait-for-store store #(= 1 (get-in @store [:files file-id :revn])) "the peer edit is applied"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the opacity edit is sent" {:signal requests$}))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the opacity edit is saved"))
         (t/is (empty? (get-in @store [:persistence :remote-changes file-id]))
               "nothing pending, nothing kept")
         (ptk/emit! store
                    #(assoc-in % [:files file-id :data :pages-index page-id :objects shape-id :name] "C")
                    commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the renames are sent" {:signal requests$}))
         (rx/push! response (thp/partial-rejection 2 (:id @commit2)))
         (await (async/wait-for-store store #(= "B" (thp/shape-name store file-id page-id shape-id))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "this file's save is sent" {:signal requests$}))
         (rx/push! response (thp/partial-rejection 0 (:id @commit2)))
         (await (async/wait-for #(= 2 (count @requests)) "the library's save is sent next" {:signal requests$}))
         (t/is (= "B" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= "Y" (thp/shape-name store lib-id page-id lshape-id))
               "the library goes back to the edit made before the action")
         (t/is (= lib-id (get-in (second @requests) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "Y")]] (sent-changes requests 1))
               "only the library's edit made before the action is saved")
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

;; Scenario: one action makes a commit in a library and one in this file.
;; The library's is saved first; then this file's save comes back with the
;; action's commit rejected. The library's commit can no longer be dropped.
;; Proves: a new commit undoes the saved part of the rejected action.
(t/deftest ^:async a-rejection-undoes-the-saved-commits-of-the-same-action-with-a-new-commit
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first" {:signal requests$}))
         (t/is (= lib-id (get-in (first @requests) [1 :id])))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent" {:signal requests$}))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "a new commit undoes the library's"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent" {:signal requests$}))
         (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "X")]] (sent-changes requests 2)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= "X" (thp/shape-name store lib-id page-id lshape-id)))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

;; Scenario: as above, while the user has an undo transaction open. Proves:
;; the undo commit gets its own group, not the transaction's, so a later
;; rejection in the transaction does not revert it.
(t/deftest ^:async the-undo-of-a-saved-commit-stays-out-of-an-open-transaction
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first" {:signal requests$}))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent" {:signal requests$}))
         (ptk/emit! store (dwu/start-undo-transaction (js/Symbol) :timeout 0))
         (let [tx-group (get-in @store [:workspace-undo :transaction :commit-group])]
           (rx/push! response (thp/rejection (:id @commit)))
           (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                        "a new commit undoes the library's"))
           (t/is (some? tx-group))
           (t/is (not= tx-group (get-in @store [:persistence :buffer 0 :undo-group])))
           (ptk/emit! store ::dps/force-persist)
           (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent" {:signal requests$}))
           (rx/push! response (thp/save-response 1))
           (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                        "the queue drains"))))))))

;; Scenario: as above, but the library is no longer loaded when this file's
;; save comes back. Proves: the undo of the library's saved commit is still
;; sent, without touching local data the library no longer has.
(t/deftest ^:async a-rejection-queues-the-undo-of-a-saved-commit-of-a-file-not-loaded
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first" {:signal requests$}))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent" {:signal requests$}))
         (ptk/emit! store #(update % :files dissoc lib-id))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for #(= 3 (count @requests)) "the undo is queued and sent" {:signal requests$}))
         (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
         (t/is (= [[(thp/rename page-id lshape-id "X")]] (sent-changes requests 2)))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (nil? (get-in @store [:files lib-id])) "no local data is made up")
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

;; Scenario: one action makes two commits in this file and the save keeps
;; the first and rejects the second. Proves: the kept commit of the action
;; is undone too, with a new commit, so no action is left half done.
(t/deftest ^:async a-rejection-undoes-the-kept-commits-of-the-same-action
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             group    (uuid/next)
             commit1  (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)
             commit2  (grouped (thp/attr-commit file-id page-id shape-id :opacity 1 0.5) group)]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "B" :opacity 0.5)))
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the action is sent" {:signal requests$}))
         (rx/push! response (thp/partial-rejection 0 (:id @commit2)))
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "a new commit undoes the kept one"))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent" {:signal requests$}))
         (t/is (= [[(thp/rename page-id shape-id "A")]] (sent-changes requests 1)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 1 (thp/shape-attr store file-id page-id shape-id :opacity)))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

(t/deftest ^:async a-peer-repair-discards-an-unsent-compensation-before-it-can-overwrite-the-server
  (let [save-answer (rx/subject)
        read-answer (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               group    (uuid/next)
               kept     (grouped (thp/rename-commit file-id page-id shape-id "Original" "Saved") group)
               rejected (grouped (thp/attr-commit file-id page-id shape-id :opacity 1 0.5) group)
               saves    #(filter (comp #{:update-file-v2} first) @requests)
               reads    #(into [] (comp (filter (comp #{:get-file} first)) (map second)) @requests)]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved" :opacity 0.5))
                      #(assoc-in % [:files file-id :vern] 0)
                      kept rejected ::dps/force-persist)
           (await (async/wait-for #(= 1 (count (saves))) "the two commits are sent" {:signal requests$}))
           (rx/push! save-answer (thp/partial-rejection 0 (:id @rejected)))
           (await (async/wait-for-store store
                                        #(and (empty? (get-in @store [:persistence :queue]))
                                              (seq (get-in @store [:persistence :buffer])))
                                        "the save settles with its compensation still buffered"))
           (t/is (= "Original" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= [(thp/rename page-id shape-id "Original")]
                    (get-in @store [:persistence :buffer 0 :redo-changes]))
                 "the locally applied compensation would overwrite the repair")
           (t/is (= 1 (get-in @store [:files file-id :revn])) "the peer's base revision is established")

           (ptk/emit! store
                      (dwn/handle-file-change
                       {:type :file-change :file-id file-id :vern 0
                        :profile-id (uuid/next) :session-id (uuid/next)
                        :repair-base-revn 1 :revn 3 :repaired true
                        :revision-ids [(uuid/next) (uuid/next)]
                        :changes [(thp/set-attr page-id shape-id :opacity 0.8)
                                  (thp/rename page-id shape-id "Repaired")]}))
           (await (async/wait-for #(seq (reads)) "the repair triggers a server read" {:signal requests$}))
           (t/is (= [file-id] (mapv :id (reads))))
           (rx/push! read-answer {:id file-id :vern 0 :revn 4
                                  :data (thp/file-data page-id shape-id "Authoritative" :opacity 0.8)})
           (await (async/wait-for-store store #(= "Authoritative" (thp/shape-name store file-id page-id shape-id))
                                        "the GET's newer content replaces the locally compensated view"))
           (await (async/wait-for-store store #(= :persistence-rejected-kept (get-in @store [:notification :tag]))
                                        "the editor warns that the compensation was skipped"))
           (t/is (= :warning (get-in @store [:notification :level])))

           (ptk/emit! store ::dps/force-persist)
           (await (async/settle))
           (t/is (= 1 (count (saves))) "forcing a flush does not POST the discarded compensation")
           (t/is (empty? (get-in @store [:persistence :buffer])))
           (t/is (empty? (get-in @store [:persistence :queue])))
           (t/is (= 4 (get-in @store [:files file-id :revn])))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save-answer :get-file read-answer (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(t/deftest ^:async a-fresh-bundle-discards-a-queued-compensation-before-a-covered-repair-arrives
  (let [save-answer (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               other-id (uuid/next)
               group    (uuid/next)
               kept     (grouped (thp/rename-commit file-id page-id shape-id "Original" "Saved") group)
               rejected (grouped (thp/attr-commit file-id page-id shape-id :opacity 1 0.5) group)
               other    (thp/local-commit other-id)
               saves    #(into [] (comp (filter (comp #{:update-file-v2} first)) (map (comp :id second)))
                               @requests)]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved" :opacity 0.5))
                      #(-> %
                           (assoc-in [:files file-id :vern] 0)
                           (assoc-in [:files other-id] {:id other-id :revn 0 :vern 0}))
                      kept rejected ::dps/force-persist)
           (await (async/wait-for #(= [file-id] (saves)) "the partial save is sent" {:signal requests$}))
           (ptk/emit! store other ::dps/force-persist)
           (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                        "the other file is queued before the compensation"))
           (rx/push! save-answer (thp/partial-rejection 0 (:id @rejected)))
           (await (async/wait-for #(= [file-id other-id] (saves)) "the other file's POST is in flight"
                                  {:signal requests$}))
           (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                        "the compensation is buffered"))
           (let [compensation (get-in @store [:persistence :buffer 0])
                 entry-path   [:persistence :index (:id compensation)]]
             (t/is (= [(thp/rename page-id shape-id "Original")] (:redo-changes compensation)))
             (ptk/emit! store ::dps/force-persist)
             (await (async/wait-for-store store #(get-in @store (conj entry-path :commits 0 :compensation))
                                          "the compensation is queued behind the other file"))
             (t/is (= [file-id other-id] (saves)) "the queued compensation has not been sent")

             ;; The new bundle can arrive before any of the intervening deltas.
             (ptk/emit! store (#'dw/bundle-fetched {:file-id file-id :thumbnails {}
                                                    :file {:id file-id :vern 0 :revn 4
                                                           :data (thp/file-data page-id shape-id "Authoritative")}}))
             (await (async/wait-for-store store #(= :persistence-rejected-kept (get-in @store [:notification :tag]))
                                          "the new file bundle covers the gap and drops the compensation"))
             (t/is (= "Authoritative" (thp/shape-name store file-id page-id shape-id))
                   "loading the file does not replay the queued compensation"))

           (let [processed (async/observe
                            (->> (ptk/input-stream store)
                                 (rx/filter (ptk/type? ::dch/rebase-commits))
                                 (rx/take 1)))]
             (ptk/emit! store
                        (dwn/handle-file-change
                         {:type :file-change :file-id file-id :vern 0
                          :profile-id (uuid/next) :session-id (uuid/next)
                          :repair-base-revn 1 :revn 3 :repaired true
                          :revision-ids [(uuid/next) (uuid/next)]
                          :changes [(thp/set-attr page-id shape-id :opacity 0.8)
                                    (thp/rename page-id shape-id "Repaired")]}))
             (await processed))

           (rx/push! save-answer (thp/save-response 0))
           (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                    (empty? (get-in @store [:persistence :queue])))
                                        "the queue drains after the other file's POST"))
           (t/is (= "Authoritative" (thp/shape-name store file-id page-id shape-id))
                 "the late repair does not replace the newer loaded data")
           (t/is (= [file-id other-id] (saves)) "the runner never sends the discarded compensation")
           (t/is (not-any? (comp #{:get-file} first) @requests)
                 "the installed bundle already covers the late repair")))
       (fn [cmd _]
         (if (= cmd :update-file-v2)
           (->> save-answer (rx/take 1) (rx/observe-on :async))
           (rx/empty)))))))

;; Scenario: applying a token to five shapes makes three commits inside one
;; undo transaction, all sent in one save; the server keeps the first two
;; and rejects the third. Proves: the whole action is reverted, the kept
;; commits by a new commit, so opacity and applied tokens are back to their
;; starting values on every shape.
(t/deftest ^:async a-rejection-reverts-every-commit-of-an-undo-transaction
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id     (uuid/next)
             shape-ids   (into [] (repeatedly 5 uuid/next))
             shapes      (map #(cts/setup-shape {:id % :type :rect :name "R" :opacity 1
                                                 :parent-id uuid/zero :frame-id uuid/zero})
                              shape-ids)
             clear-token (fn [id]
                           [(thp/set-attr page-id id :opacity 1)
                            (thp/set-attr page-id id :applied-tokens nil)])
             apply-to    (fn [ids]
                           (dch/commit-changes
                            {:redo-changes (into []
                                                 (mapcat (fn [id]
                                                           [(thp/set-attr page-id id :opacity 0.5)
                                                            (thp/set-attr page-id id :applied-tokens {:opacity "half"})]))
                                                 ids)
                             :undo-changes (into [] (mapcat clear-token) ids)}))
             tx-id       (js/Symbol)]
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
         (await (async/wait-for #(= 1 (count @requests)) "the action is sent" {:signal requests$}))
         (let [[cmd params] (first @requests)
               commits      (:commits params)
               kept         (subvec commits 0 2)]
           (t/is (= :update-file-v2 cmd))
           (t/is (= 3 (count commits)) "the save carries the three commits")
           (rx/push! response (thp/partial-rejection 0 (:commit-id (nth commits 2))))
           (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                        "a new commit undoes the kept ones"))
           (let [[compensation] (get-in @store [:persistence :buffer])
                 reversed-ids   (concat (subvec shape-ids 2 4) (subvec shape-ids 0 2))]
             (t/is (= (into [] (mapcat :changes) kept)
                      (:undo-changes compensation))
                   "the local inverse of compensation replays the accepted edits")
             (ptk/emit! store ::dps/force-persist)
             (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent" {:signal requests$}))
             (t/is (= [{:commit-id (:id compensation)
                        :changes (into [] (mapcat clear-token) reversed-ids)}]
                      (get-in @requests [1 1 :commits]))
                   "only the changes that revert the accepted edits are sent to the server")))
         (doseq [id shape-ids]
           (t/is (= 1 (thp/shape-attr store file-id page-id id :opacity)))
           (t/is (nil? (thp/shape-attr store file-id page-id id :applied-tokens))))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the queue drains")))))))

;; UI state after a rollback.

;; Scenario: the rejected commit added the text being edited. Proves: the
;; rollback leaves edition mode, so the editor does not keep pointing at a
;; shape that no longer exists.
(t/deftest ^:async a-rollback-leaves-the-edition-of-a-removed-shape
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the new text is sent" {:signal requests$}))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for-store store #(nil? (get-in @store [:workspace-local :edition]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for-store store #(nil? (get-in @store [:workspace-local :edition]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for-store store #(= "A" (thp/shape-name store file-id page-id shape-id))
                                      "the rollback lands"))
         (await (async/settle))
         (t/is (= other-id (get-in @store [:workspace-local :edition]))))))))

;; Scenario: the color picker is open on a new rectangle when the save that
;; created it is rejected. Proves: the rollback closes the picker, which
;; would otherwise edit a shape that no longer exists.
(t/deftest ^:async a-rollback-closes-the-color-picker-of-a-removed-shape
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
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
         (await (async/wait-for #(= 1 (count @requests)) "the new rectangle is sent" {:signal requests$}))
         (rx/push! response (thp/rejection (:id @commit)))
         (await (async/wait-for-store store #(empty? (get-in @store [:workspace-local :selected]))
                                      "the rollback deselects the removed shape"))
         (t/is (nil? (::modal/modal @store)) "the color picker is closed"))))))

;; Scenario: the user undoes a rename with Ctrl+Z and the server rejects the
;; commit the undo makes. Proves: the rollback puts the rename back on
;; screen and the history back where it was, instead of clearing it.
(t/deftest ^:async a-rejected-undo-puts-the-change-and-the-history-back
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             item     (thp/undo-entry (thp/rename-commit file-id page-id shape-id "A" "B"))]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    #(assoc % :workspace-undo {:items [item] :index 0}))
         (ptk/emit! store dwu/undo)
         (await (async/wait-for-store store #(= "A" (thp/shape-name store file-id page-id shape-id))
                                      "the rename is undone"))
         (t/is (= -1 (get-in @store [:workspace-undo :index])))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the undo is sent" {:signal requests$}))
         (rx/push! response (thp/rejection
                             (get-in @requests [0 1 :commits 0 :commit-id])))
         (await (async/wait-for-store store #(= "B" (thp/shape-name store file-id page-id shape-id))
                                      "the rename is back"))
         (t/is (= [item] (get-in @store [:workspace-undo :items])) "the history stays")
         (t/is (= 0 (get-in @store [:workspace-undo :index])) "it points at the rename again")
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the rollback settles")))))))

;; Version restores.

(defn- restore-version
  "The load of `file-id` that a peer's restore causes: another version
  with `data`."
  [file-id data]
  (#'dw/bundle-fetched {:file {:id file-id :revn 0 :vern 1 :data data}
                        :file-id file-id
                        :thumbnails {}}))

;; The diagnostic is armed before sending a rename. A rollback that only
;; reapplies the armed rename must not consume it. When the rename itself
;; is rejected, the local rollback fails and ::rolled-back waits for reload.
(t/deftest ^:async a-rollback-that-reloads-the-file-is-reported-after-the-reload
  (let [reloads  (atom 0)
        original (get-method ptk/resolve ::dw/reload-current-file)]
    (-add-method ptk/resolve ::dw/reload-current-file
                 (fn [_ _]
                   (swap! reloads inc)
                   (ptk/data-event ::reloaded {})))
    (try
      (await
       (thp/with-persistence
         (^:async fn [{:keys [file-id response requests requests$ store]}]
           (let [page-id  (uuid/next)
                 shape-id (uuid/next)
                 other-id (uuid/next)
                 group    (uuid/next)
                 first-save (grouped (thp/local-commit other-id) group)
                 commit   (thp/rename-commit file-id page-id shape-id "A" "B")
                 later    (grouped (thp/rename-commit file-id page-id shape-id "B" "C") group)
                 seen     (atom [])
                 sub      (->> (ptk/input-stream store)
                               (rx/filter (ptk/type? ::dps/rolled-back))
                               (rx/subs! (fn [event]
                                           (swap! seen conj
                                                  [(:shape-ids @event)
                                                   (thp/shape-name store file-id page-id shape-id)]))))]
             (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "C"))
                        first-save ::dps/force-persist)
             (await (async/wait-for #(= 1 (count @requests)) "the other file is sent" {:signal requests$}))
             (ptk/emit! store commit)
             (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                          "the rename is buffered"))
             (let [pending (pcm/pending-commits (:persistence @store) file-id)]
               (await
                (mock/with-mocks*
                  {st/state store}
                  (dbg/failNextRollback)
                  (await (async/wait-for-store store #(contains? @store ::dps/rollback-failure)
                                               "the local diagnostic is armed"))))
               (t/is (= pending (pcm/pending-commits (:persistence @store) file-id))
                     "arming does not change the pending commits"))
             (ptk/emit! store later ::dps/force-persist)
             (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                          "both renames are queued"))
             (rx/push! response (thp/rejection (:id @first-save)))
             (await (async/wait-for #(= 2 (count @requests)) "the kept rename is sent" {:signal requests$}))
             (t/is (= [[#{shape-id} "B"]] @seen) "the armed rename was reapplied without a reload")
             (t/is (zero? @reloads))
             (let [[cmd params] (second @requests)]
               (t/is (= :update-file-v2 cmd))
               (t/is (= [{:commit-id (:id @commit)
                          :changes (:redo-changes @commit)}]
                        (:commits params))
                     "the local fault does not travel to the server")
               (rx/push! response (thp/rejection (get-in params [:commits 0 :commit-id]))))
             (await (async/wait-for #(= 1 @reloads) "the rollback reloads the file"))
             (await (async/settle))
             (t/is (= [[#{shape-id} "B"]] @seen) "nothing new is reported before the reload")
             (t/is (not (contains? @store ::dps/rollback-failure)) "a matching rollback disarms the fault")
             (ptk/emit! store (#'dw/bundle-fetched {:file {:id file-id :revn 0
                                                           :data (thp/file-data page-id shape-id "A")}
                                                    :file-id file-id
                                                    :thumbnails {}}))
             (await (async/wait-for #(= 2 (count @seen)) "the second rollback is reported"))
             (t/is (= [[#{shape-id} "B"] [#{shape-id} "A"]] @seen)
                   "the listener reads the reloaded data")
             (rx/dispose! sub)))))
      (finally
        (-add-method ptk/resolve ::dw/reload-current-file original)))))

;; Scenario: a save of two renames is in flight and a third rename waits in
;; the queue when a peer restores an earlier version; the file reloads to
;; it and a peer's change to it arrives. The answer then comes: the server
;; kept the first rename and rejected the second, before the restore.
;; Proves: neither the peer's change, the answer nor its rollback replays
;; the renames on the reloaded data, the user gets no rejection warning,
;; and the queued rename, made on the replaced version, is dropped unsent.
(t/deftest ^:async an-answer-from-before-a-restore-leaves-the-reloaded-file-alone
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store failures]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit1  (thp/rename-commit file-id page-id shape-id "A" "B")
             commit2  (thp/rename-commit file-id page-id shape-id "B" "C")
             commit3  (thp/rename-commit file-id page-id shape-id "C" "D")]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "D"))
                    #(assoc-in % [:files file-id :vern] 0))
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first two renames are sent" {:signal requests$}))
         (ptk/emit! store commit3 ::dps/force-persist)
         (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                      "the third rename is queued"))

         (ptk/emit! store (restore-version file-id (thp/file-data page-id shape-id "Restored")))
         (ptk/emit! store (thp/remote-commit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.5)]))
         (await (async/settle))
         (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id))
               "a peer's change does not replay the renames")
         (rx/push! response (thp/partial-rejection 0 (:id @commit2)))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the queue drains"))
         (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id))
               "the file keeps the restored version")
         (t/is (= 0.5 (thp/shape-attr store file-id page-id shape-id :opacity)))
         (t/is (empty? @failures) "no rejection warning for a replaced version")
         (t/is (= 1 (count @requests)) "the rename made on the replaced version is not sent"))))))

;; Scenario: a save is in flight when a peer restores an earlier version and
;; the file reloads; the server handled the save after the restore, so it
;; refuses it for its old version. Proves: the failure of a save made on a
;; replaced version drops it instead of halting the queue and locking the
;; workspace.
(t/deftest ^:async a-save-refused-after-a-restore-is-dropped
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store failures]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    #(assoc-in % [:files file-id :vern] 0))
         (ptk/emit! store (thp/rename-commit file-id page-id shape-id "A" "B") ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the rename is sent" {:signal requests$}))
         (ptk/emit! store (restore-version file-id (thp/file-data page-id shape-id "Restored")))
         (rx/error! response (ex-info "A different version has been restored for the file."
                                      {:type :validation :code :vern-conflict}))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the refused save is dropped"))
         (t/is (empty? @failures))
         (t/is (nil? (get-in @store [:workspace-global :persistence-lock])))
         (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id))))))))

(defn- comment-thread
  "A comment thread of `file-id` on `page-id`, pinned on `frame-id`."
  [file-id page-id frame-id position]
  {:id (uuid/next) :page-id page-id :file-id file-id :project-id (uuid/next)
   :owner-id (uuid/next) :file-name "File" :seqn 1 :content "Note"
   :participants #{} :created-at (ct/now) :modified-at (ct/now)
   :position position :frame-id frame-id})

;; Scenario: comment #1 sits on board Y. During a save Y is moved, which
;; moves pin #1 in the same action, and board R is drawn and gets comment
;; #2. The server rejects the move, so the move, the pin move and R are
;; rolled back. Comment positions are saved by their own requests, so the
;; server already holds the moved pin and #2 on R. Proves: pin #1 goes back
;; to its spot on screen and on the server, and #2, whose board is gone,
;; moves to the page root, as deleting the board does.
(defn- ^:async check-comment-pin-rollback
  [recovery]
  (let [answer   (rx/subject)
        snapshot (rx/subject)
        history  (rx/subject)
        respond  (fn [cmd _]
                   (->> (case cmd
                          :update-file-v2 answer
                          :get-file snapshot
                          :get-file-snapshot history
                          (rx/of nil))
                        (rx/take 1)
                        (rx/observe-on :async)))]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               board-y  (cts/setup-shape {:id (uuid/next) :type :frame :name "Y"
                                          :parent-id uuid/zero :frame-id uuid/zero})
               board-r  (cts/setup-shape {:id (uuid/next) :type :frame :name "R"
                                          :parent-id uuid/zero :frame-id uuid/zero})
               [p0 p1 p2] [(gpt/point 610 10) (gpt/point 760 10) (gpt/point 20 20)]
               thread-1 (comment-thread file-id page-id (:id board-y) p1)
               thread-2 (comment-thread file-id page-id (:id board-r) p2)
               group    (uuid/next)
               move     (grouped (thp/rename-commit file-id page-id (:id board-y) "Y" "Y moved") group)
               pin      (grouped (ptk/data-event
                                  ::dch/commit
                                  {:id (uuid/next) :file-id file-id :file-revn 0 :file-vern 0
                                   :source :local :features #{}
                                   :redo-changes [{:type :set-comment-thread-position :page-id page-id
                                                   :comment-thread-id (:id thread-1)
                                                   :frame-id (:id board-y) :position p1}]
                                   :undo-changes [{:type :set-comment-thread-position :page-id page-id
                                                   :comment-thread-id (:id thread-1)
                                                   :frame-id (:id board-y) :position p0}]})
                                 group)
               draw     (add-shape-commit file-id page-id board-r)
               original (-> (thp/file-data page-id shape-id "A")
                            (thp/add-shape page-id board-y)
                            (assoc-in [:pages-index page-id :comment-thread-positions]
                                      {(:id thread-1) {:frame-id (:id board-y) :position p0}}))
               data     (-> (thp/file-data page-id shape-id "A")
                            (thp/add-shape page-id (assoc board-y :name "Y moved"))
                            (thp/add-shape page-id board-r)
                            (assoc-in [:pages-index page-id :comment-thread-positions]
                                      {(:id thread-1) {:frame-id (:id board-y) :position p1}
                                       (:id thread-2) {:frame-id (:id board-r) :position p2}}))
               sent     (fn [cmd] (into [] (comp (filter #(= cmd (first %))) (map second)) @requests))
               preview? (contains? #{:preview :exit-first :new-opening :restored} recovery)
               tokens   (when (= :preview recovery)
                          (ctob/add-set (ctob/make-tokens-lib)
                                        (ctob/make-token-set
                                         :name "Recovered"
                                         :tokens {"size" (ctob/make-token :name "size" :type :sizing :value "8")})))
               propagated (atom [])]
           (when tokens
             (->> (ptk/input-stream store)
                  (rx/filter (ptk/type? ::dwtp/propagate-workspace-tokens))
                  (rx/subs! (fn [_]
                              (swap! propagated conj (some-> (get-in @store [:files file-id :data :tokens-lib])
                                                             ctob/get-set-names
                                                             vec))))))
           ;; The local data and the rendered pins already hold the edits
           (ptk/emit! store
                      (thp/open-file file-id page-id data)
                      #(-> %
                           (assoc :comment-threads {(:id thread-1) thread-1 (:id thread-2) thread-2})
                           (assoc-in [:files file-id :vern] 0)
                           (assoc :workspace-load-id (uuid/next)))
                      (dwcm/watch-comment-thread-position-changes (rx/subject)))
           (ptk/emit! store move pin draw ::dps/force-persist)
           (await (async/wait-for #(seq (sent :update-file-v2)) "the edits are sent"
                                  {:signal requests$}))
           (when preview?
             (ptk/emit! store (dwv/enter-preview (uuid/next)))
             (await (async/wait-for #(seq (sent :get-file-snapshot)) "the preview is requested"
                                    {:signal requests$}))
             (rx/push! history {:id file-id :vern 0 :revn 0
                                :data (thp/file-data page-id shape-id "Historical")})
             (await (async/wait-for-store store #(= "Historical" (thp/shape-name store file-id page-id shape-id))
                                          "the historical data is visible")))
           (when (#{:read :retry :preview} recovery)
             ;; An unsafe absolute inverse must be replaced by a fresh read.
             (ptk/emit! store #(update % :persistence pcm/mark-undo-uncertain file-id 0)))
           (rx/push! answer (thp/rejection (:id @move) (:id @pin) (:id @draw)))
           (when recovery
             (await (async/wait-for #(seq (sent :get-file)) "the rollback awaits the file" {:signal requests$}))
             (when (= :retry recovery)
               (rx/push! snapshot nil)
               (await (async/wait-for-store store #(= :persistence-reconciliation-failed
                                                      (get-in @store [:notification :tag]))
                                            "the failed read is retryable"))
               (t/is (empty? (sent :update-comment-thread-position)))
               (ptk/emit! store (#'dps/reconcile-file file-id {:retry true}))
               (await (async/wait-for #(= 2 (count (sent :get-file))) "the retry awaits the file"
                                      {:signal requests$})))
             (await (async/settle))
             (t/is (empty? (sent :update-comment-thread-position)))
             (t/is (empty? (sent :update-comment-thread-frame)))
             (when (= :exit-first recovery)
               (ptk/emit! store (dwv/exit-preview))
               (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :preview-id]))
                                            "the live file is visible before the read")))
             (when tokens
               (ptk/emit! store (dwn/handle-file-change
                                 {:type :file-change :file-id file-id :profile-id (uuid/next)
                                  :session-id (uuid/next) :vern 0 :revn 1
                                  :library-change true :changes []})))
             (rx/push! snapshot {:id file-id :vern 0 :revn (if tokens 1 0)
                                 :data (cond-> original
                                         tokens (assoc :id file-id :tokens-lib tokens
                                                       :tokens-status (ctos/make-tokens-status
                                                                       :active-set-ids (into #{} (map ctob/get-id)
                                                                                             (ctob/get-sets tokens)))))}))
           (when (and preview? (not= :exit-first recovery))
             (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                          "the queue settles during the preview"))
             (t/is (= "Historical" (thp/shape-name store file-id page-id shape-id)))
             (t/is (= p0 (get-in @store [:workspace-versions :backup :data :pages-index page-id
                                         :comment-thread-positions (:id thread-1) :position]))
                   "the GET restores the live backup, not the historical data")
             (t/is (empty? (sent :update-comment-thread-position)))
             (t/is (empty? (sent :update-comment-thread-frame)))
             (when tokens
               (t/is (empty? @propagated) "the same read defers token propagation"))
             (case recovery
               :new-opening
               (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)) (dwv/exit-preview))

               :restored
               (ptk/emit! store (restore-version file-id (thp/file-data page-id shape-id "Restored"))
                          (#'dwv/clear-preview-state)
                          (dps/finish-preview-effects file-id))

               (ptk/emit! store (dwv/exit-preview)))
             (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :preview-id]))
                                          "the preview ends")))
           (if (#{:new-opening :restored} recovery)
             (do
               (await (async/settle))
               (t/is (empty? (sent :update-comment-thread-position)))
               (t/is (empty? (sent :update-comment-thread-frame))))
             (do
               (await (async/wait-for #(seq (sent :update-comment-thread-frame))
                                      "the rollback updates the comments" {:signal requests$}))
               (t/is (nil? (thp/shape-attr store file-id page-id (:id board-r) :id)) "R is gone")
               (t/is (= p0 (get-in @store [:comment-threads (:id thread-1) :position]))
                     "pin #1 is back on screen")
               (t/is (= [{:id (:id thread-1) :position p0 :frame-id (:id board-y)}]
                        (sent :update-comment-thread-position))
                     "and on the server")
               (t/is (= uuid/zero (get-in @store [:comment-threads (:id thread-2) :frame-id]))
                     "comment #2 leaves the removed board")
               (t/is (= p2 (get-in @store [:comment-threads (:id thread-2) :position]))
                     "and keeps its spot")
               (t/is (= [{:id (:id thread-2) :frame-id uuid/zero}]
                        (sent :update-comment-thread-frame)))))
           (when tokens
             (await (async/settle))
             (t/is (= [["Recovered"]] @propagated) "the live read restores the pins and propagates tokens once"))
           (rx/end! answer)))
       respond))))

(t/deftest ^:async a-rollback-puts-comment-pins-back-and-detaches-them-from-removed-boards
  (await (check-comment-pin-rollback nil)))

(t/deftest ^:async a-rollback-waits-for-reconciliation-before-saving-comment-pins
  (await (check-comment-pin-rollback :read)))

(t/deftest ^:async a-rollback-retries-the-read-before-saving-comment-pins-once
  (await (check-comment-pin-rollback :retry)))

(t/deftest ^:async a-rollback-during-preview-saves-comment-pins-once-the-live-file-is-visible
  (await (check-comment-pin-rollback :preview)))

(t/deftest ^:async leaving-preview-before-the-read-does-not-duplicate-the-rollback-effects
  (await (check-comment-pin-rollback :exit-first)))

(t/deftest ^:async a-deferred-rollback-does-not-apply-to-another-opening
  (await (check-comment-pin-rollback :new-opening)))

(t/deftest ^:async a-deferred-rollback-does-not-apply-to-a-restored-version
  (await (check-comment-pin-rollback :restored)))

(t/deftest ^:async a-mixed-rollback-notifies-each-file-when-its-own-data-is-ready
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [library-id (uuid/next)
               page-id    (uuid/next)
               shape-id   (uuid/next)
               asset-id   (uuid/next)
               group      (uuid/next)
               local      (grouped (thp/rename-commit file-id page-id shape-id "A" "B") group)
               linked     (grouped (thp/rename-commit library-id page-id asset-id "X" "Y") group)
               events     (atom [])
               sub        (->> (ptk/input-stream store)
                               (rx/filter (ptk/type? ::dps/rolled-back))
                               (rx/subs! #(swap! events conj @%)))]
           (try
             (ptk/emit! store
                        (thp/open-file-with-library file-id library-id page-id
                                                    (thp/file-data page-id shape-id "B")
                                                    (thp/file-data page-id asset-id "Y"))
                        local linked ::dps/force-persist)
             (await (async/wait-for #(= 1 (count @requests)) "the file save is sent" {:signal requests$}))
             (ptk/emit! store #(update % :persistence pcm/mark-undo-uncertain file-id 0))
             (rx/push! save (thp/rejection (:id @local)))
             (await (async/wait-for #(= :get-file (first (peek @requests))) "the file waits for a read"
                                    {:signal requests$}))
             (await (async/wait-for-store store #(seq @events) "the linked library is rolled back locally"))
             (t/is (= [#{library-id}] (mapv :file-ids @events)))
             (t/is (= [#{asset-id}] (mapv :shape-ids @events)))
             (t/is (= "X" (thp/shape-name store library-id page-id asset-id)))
             (t/is (= "B" (thp/shape-name store file-id page-id shape-id)))
             (rx/push! read {:id file-id :vern 0 :revn 0 :data (thp/file-data page-id shape-id "A")})
             (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                          "the store settles after both rollbacks"))
             (t/is (= [#{library-id} #{file-id}] (mapv :file-ids @events)))
             (t/is (= [#{asset-id} #{shape-id}] (mapv :shape-ids @events)))
             (t/is (= "A" (thp/shape-name store file-id page-id shape-id)))
             (finally
               (rx/dispose! sub)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(defn- rejection-notice
  "Saves a rename of the open file and answers it with `answer` applied to
  a rejection of it, after `prepare` updates the state (given the file
  id) while the save is in flight. Resolves with the params the notice was
  flashed with; `tr` answers each key with its arguments."
  [prepare answer]
  (let [flashes (atom [])]
    (-> (thp/with-persistence
          (^:async fn [{:keys [file-id response requests requests$ store]}]
            (await
             (mock/with-mocks*
               {errors/flash (fn [& {:as params}]
                               (swap! flashes conj params)
                               (js/Promise.resolve nil))
                i18n/tr      (fn ([key] [key]) ([key & args] (into [key] args)))}
               (let [page-id  (uuid/next)
                     shape-id (uuid/next)
                     commit1  (thp/rename-commit file-id page-id shape-id "A" "B")]
                 (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "B")))
                 (ptk/emit! store commit1 ::dps/force-persist)
                 (await (async/wait-for #(= 1 (count @requests)) "the rename is sent" {:signal requests$}))
                 (ptk/emit! store #(prepare % file-id))
                 (rx/push! response (answer (thp/rejection (:id @commit1))))
                 (await (async/wait-for #(seq @flashes) "the notice is flashed")))))))
        (.then (fn [_] (first @flashes))))))

;; Scenario: an edit made while the save was in flight is undone with the
;; rejected one. Proves: the notice says the latest changes were undone,
;; and goes away by itself.
(t/deftest ^:async a-rejection-notice-says-the-latest-changes-were-undone
  (let [{:keys [hint tag timeout]} (await (rejection-notice (fn [state _] state) identity))]
    (t/is (= ["errors.save-rejected-changes-undone"] hint))
    (t/is (= dps/rejected-tag tag))
    (t/is (some? timeout))))

;; Scenario: the user went to another file before the answer came back.
;; Proves: the notice names the file it is about.
(t/deftest ^:async a-rejection-in-a-file-not-open-names-it
  (let [{:keys [hint]} (await (rejection-notice
                               (fn [state file-id]
                                 (-> state
                                     (assoc-in [:files file-id :name] "Brand kit")
                                     (assoc :current-file-id (uuid/next))))
                               identity))]
    (t/is (= ["errors.save-rejected-changes-undone-in-file" "Brand kit"] hint))))

;; Scenario: the page holds data that was invalid before the change, so the
;; backend flags the rejection. Proves: the notice says the data is damaged.
(t/deftest ^:async a-rejection-on-damaged-data-says-so
  (let [{:keys [hint]} (await (rejection-notice
                               (fn [state _] state)
                               #(assoc-in % [:rejected :preexisting-damage] true)))]
    (t/is (= ["errors.save-rejected-damaged-data"] hint))))

;; Scenario: the repair notice of the open file is still on screen when a
;; save of it comes back rejected. The store holds one toast. Proves: the
;; notice tells both, and stays like the repair notice did.
(t/deftest ^:async a-rejection-keeps-the-repair-notice-on-screen
  (let [{:keys [hint tag timeout]}
        (await (rejection-notice
                (fn [state _]
                  (assoc state :notification {:content "repaired" :tag dps/file-repaired-tag}))
                identity))]
    (t/is (= ["workspace.notifications.file-repaired-changes-undone"] hint))
    (t/is (= dps/repaired-rejected-tag tag))
    (t/is (nil? timeout))))
