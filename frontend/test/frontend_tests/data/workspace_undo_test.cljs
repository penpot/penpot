;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-undo-test
  "Tests for the undo groups of commits and history entries, and for
  forgetting them after a rollback."
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dcmt]
   [app.main.data.fonts :as df]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- rename-page
  "Commits a page rename, with `params` for `commit-changes`."
  [page-id name & {:as params}]
  (dch/commit-changes (merge {:redo-changes [{:type :mod-page :id page-id :name name}]
                              :undo-changes [{:type :mod-page :id page-id :name "Page 1"}]}
                             params)))

(defn- run-commits
  "Runs `events` on a store of a sample file and calls `check` with the
  undo groups of the commits made and the final state."
  [done events check]
  (let [file   (cthf/sample-file :file1 :page-label :page1)
        store  (ths/setup-store file)
        groups (atom [])
        sub    (->> (ptk/input-stream store)
                    (rx/filter dch/commit?)
                    (rx/subs! #(swap! groups conj (:undo-group (deref %)))))]
    (ths/run-store store done (events (cthf/current-page-id file))
                   (fn [state]
                     (rx/dispose! sub)
                     (check @groups state)))))

;; Scenario: an action commits twice inside one undo transaction (as applying
;; a token to several shapes does). Proves: both commits carry the undo group
;; of the transaction, which its history entry takes too, so a rejected save
;; reverts the whole Ctrl+Z step, not half of it.
(t/deftest commits-inside-a-transaction-share-its-undo-group
  (t/async
    done
    (let [tx       (js/Symbol)
          tx-group (atom nil)
          spy      (ptk/reify ::spy-transaction
                     ptk/EffectEvent
                     (effect [_ state _]
                       (reset! tx-group (get-in state [:workspace-undo :transaction :commit-group]))))]
      (run-commits done
                   (fn [page-id]
                     [(dwu/start-undo-transaction tx)
                      spy
                      (rename-page page-id "A")
                      (rename-page page-id "B")
                      (dwu/commit-undo-transaction tx)])
                   (fn [groups _]
                     (t/is (some? @tx-group) "the transaction has its own group")
                     (t/is (= [@tx-group @tx-group] groups)))))))

;; Scenario: commits made with no transaction open. Proves: each one keeps
;; its own undo group.
(t/deftest commits-outside-a-transaction-get-their-own-undo-group
  (t/async
    done
    (run-commits done
                 (fn [page-id]
                   [(rename-page page-id "A")
                    (rename-page page-id "B")])
                 (fn [groups _]
                   (t/is (= 2 (count (distinct groups))))))))

;; Scenario: inside a transaction, a commit names its undo group (e.g. a
;; component update that also writes the library). Proves: the explicit group
;; is kept.
(t/deftest an-explicit-undo-group-is-kept-inside-a-transaction
  (t/async
    done
    (let [tx    (js/Symbol)
          group (uuid/next)]
      (run-commits done
                   (fn [page-id]
                     [(dwu/start-undo-transaction tx)
                      (rename-page page-id "A" :undo-group group)
                      (dwu/commit-undo-transaction tx)])
                   (fn [groups _]
                     (t/is (= [group] groups)))))))

;; Scenario: a rollback forgets undo groups while a transaction is open, then
;; the transaction commits again. Proves: the later commit keeps the group of
;; the earlier one, so the transaction stays one rollback unit.
(t/deftest a-transaction-keeps-its-undo-group-after-forget-undo-groups
  (t/async
    done
    (let [tx (js/Symbol)]
      (run-commits done
                   (fn [page-id]
                     [(dwu/start-undo-transaction tx)
                      (rename-page page-id "A")
                      (ptk/reify ::forget-the-transaction-group
                        ptk/UpdateEvent
                        (update [_ state]
                          (let [group (get-in state [:workspace-undo :transaction :commit-group])]
                            ;; The transaction holds the first commit, as `append-undo` records it
                            (->> (assoc-in state [:workspace-undo :transaction :undo-groups] #{group})
                                 (ptk/update (dwu/forget-undo-groups #{group} nil))))))
                      (rename-page page-id "B")
                      (dwu/commit-undo-transaction tx)])
                   (fn [[first-group second-group :as groups] _]
                     (t/is (= 2 (count groups)))
                     (t/is (some? first-group))
                     (t/is (= first-group second-group)))))))

(defn- entry
  [page-id name group]
  {:undo-changes [{:type :mod-page :id page-id :name "Page 1"}]
   :redo-changes [{:type :mod-page :id page-id :name name}]
   :undo-group group
   :tags #{}
   :translation false})

;; Scenario: inside one transaction, a commit names another undo group (as a
;; component update does). Proves: the history entry records both groups,
;; so a rollback of either one finds it.
(t/deftest a-transaction-entry-records-every-group-it-holds
  (let [page-id (uuid/next)
        [g1 g2] (repeatedly 2 uuid/next)
        tx      (js/Symbol)
        state   (reduce #(ptk/update %2 %1)
                        {}
                        [(dwu/start-undo-transaction tx)
                         (dwu/append-undo (entry page-id "A" g1) false)
                         (dwu/append-undo (entry page-id "B" g2) false)
                         (dwu/commit-undo-transaction tx)])]
    (t/is (= #{g1 g2} (get-in state [:workspace-undo :items 0 :undo-groups])))))

;; Scenario: a commit stacks onto the last history entry. Proves: the entry
;; records the stacked commit's group too.
(t/deftest a-stacked-commit-adds-its-group-to-the-entry
  (let [page-id (uuid/next)
        [g1 g2] (repeatedly 2 uuid/next)
        state   (reduce #(ptk/update %2 %1)
                        {}
                        [(dwu/append-undo (entry page-id "A" g1) false)
                         (dwu/append-undo (entry page-id "B" g2) true)])]
    (t/is (= 1 (count (get-in state [:workspace-undo :items]))))
    (t/is (= #{g1 g2} (get-in state [:workspace-undo :items 0 :undo-groups])))))

;; Scenario: a commit stacks onto the last history entry. Proves: it takes
;; that entry's undo group, so the commits it starts (e.g. a component sync)
;; inherit it, and one undo reverts the whole action.
(t/deftest a-stacked-commit-takes-the-undo-group-of-its-entry
  (t/async
    done
    (let [group (uuid/next)]
      (run-commits done
                   (fn [page-id]
                     [#(assoc % :workspace-undo {:items [(entry page-id "A" group)] :index 0})
                      (rename-page page-id "B" :stack-undo? true)])
                   (fn [groups _]
                     (t/is (= [group] groups)))))))

;; Scenario: after an undo, the undo index sits below the end of the history
;; when a commit stacks onto it. Proves: the commit takes the group of the
;; entry at the index, where `append-undo` stacks it, not the group of the
;; undone entry at the end.
(t/deftest a-stacked-commit-after-an-undo-takes-the-group-of-the-entry-at-the-index
  (t/async
    done
    (let [[group-a group-b] (repeatedly 2 uuid/next)]
      (run-commits done
                   (fn [page-id]
                     [#(assoc % :workspace-undo {:items [(entry page-id "A" group-a)
                                                         (entry page-id "B" group-b)]
                                                 :index 0})
                      (rename-page page-id "C" :stack-undo? true)])
                   (fn [groups _]
                     (t/is (= [group-a] groups)))))))

;; Scenario: a commit stacks onto an empty undo history. Proves: with no
;; entry to join, it gets a group of its own.
(t/deftest a-stacked-commit-on-an-empty-history-gets-its-own-undo-group
  (t/async
    done
    (run-commits done
                 (fn [page-id]
                   [#(assoc % :workspace-undo {})
                    (rename-page page-id "A" :stack-undo? true)])
                 (fn [[group :as groups] _]
                   (t/is (= 1 (count groups)))
                   (t/is (some? group))))))

;; Scenario: the user edits the font size of a main text, which then resizes
;; itself in its own undo transaction with a stacked commit (`resize-text`).
;; Proves: the resize takes the group of the user's edit, not the one the
;; transaction was opened with. Before, the component sync that the resize
;; starts got a group of its own, and the first Ctrl+Z undid only that sync.
(t/deftest a-stacked-commit-in-an-empty-transaction-takes-the-group-of-its-entry
  (t/async
    done
    (let [group (uuid/next)
          tx    (js/Symbol)]
      (run-commits done
                   (fn [page-id]
                     [#(assoc % :workspace-undo {:items [(entry page-id "A" group)] :index 0})
                      (dwu/start-undo-transaction tx)
                      (rename-page page-id "B" :stack-undo? true)
                      (dwu/commit-undo-transaction tx)])
                   (fn [groups _]
                     (t/is (= [group] groups)))))))

;; Scenario: an open transaction already holds changes when a stacked commit
;; arrives. Proves: the stacked commit joins the transaction and its group,
;; not the group of the last history entry.
(t/deftest a-stacked-commit-in-a-transaction-with-changes-takes-its-group
  (t/async
    done
    (let [[entry-group tx-group] (repeatedly 2 uuid/next)]
      (run-commits done
                   (fn [page-id]
                     (let [held (entry page-id "B" tx-group)]
                       [#(assoc % :workspace-undo
                                {:items       [(entry page-id "A" entry-group)]
                                 :index       0
                                 :transaction (select-keys held [:undo-changes :redo-changes :undo-group])})
                        (rename-page page-id "C" :stack-undo? true)]))
                   (fn [groups _]
                     (t/is (= [tx-group] groups)))))))

;; Scenario: an automatic heal commits with `:stack-undo? true` and
;; `:save-undo? false` (the auto-grow text heals). Proves: it does not take
;; the group of the user's last action, the rollback unit, since it never
;; joins that history entry. Inside an empty transaction it takes the
;; transaction's group.
(t/deftest a-stacked-commit-without-undo-keeps-its-own-undo-group
  (t/async
    done
    (let [group    (uuid/next)
          tx       (js/Symbol)
          tx-group (atom nil)
          spy      (ptk/reify ::spy-heal-transaction
                     ptk/EffectEvent
                     (effect [_ state _]
                       (reset! tx-group (get-in state [:workspace-undo :transaction :commit-group]))))]
      (run-commits done
                   (fn [page-id]
                     [#(assoc % :workspace-undo {:items [(entry page-id "A" group)] :index 0})
                      (rename-page page-id "B" :stack-undo? true :save-undo? false)
                      (dwu/start-undo-transaction tx)
                      spy
                      (rename-page page-id "C" :stack-undo? true :save-undo? false)
                      (dwu/commit-undo-transaction tx)])
                   (fn [[heal-group tx-heal-group] _]
                     (t/is (some? heal-group))
                     (t/is (not= group heal-group))
                     (t/is (= @tx-group tx-heal-group)))))))

(defn- run-edit-resize-and-sync
  "Runs the user's edit of a main text (rename to A), its resize in its own
  undo transaction with a stacked commit (B) and the component sync the
  resize starts (C), which inherits the resize's group. Then runs
  `then-events` and calls `check` with the page id and the final state."
  [done then-events check]
  (let [file       (cthf/sample-file :file1 :page-label :page1)
        page-id    (cthf/current-page-id file)
        store      (ths/setup-store file)
        last-group (atom nil)
        sub        (->> (ptk/input-stream store)
                        (rx/filter dch/commit?)
                        (rx/subs! #(reset! last-group (:undo-group (deref %)))))
        tx         (js/Symbol)
        ;; The component sync: commits with the group of the commit it
        ;; follows, as `component-changed` does
        sync       (ptk/reify ::sync
                     ptk/WatchEvent
                     (watch [_ _ _]
                       (rx/of (rename-page page-id "C"
                                           :undo-changes [{:type :mod-page :id page-id :name "B"}]
                                           :undo-group @last-group))))]
    (ths/run-store store done
                   (concat [(tohs/watch-undo-stack)
                            (rename-page page-id "A")
                            (dwu/start-undo-transaction tx)
                            (rename-page page-id "B"
                                         :undo-changes [{:type :mod-page :id page-id :name "A"}]
                                         :stack-undo? true)
                            (dwu/commit-undo-transaction tx)
                            sync]
                           then-events)
                   (fn [state]
                     (rx/dispose! sub)
                     (check page-id state)))))

;; Scenario: the user edits a main text; the text resizes itself in its own
;; undo transaction with a stacked commit, and that resize starts a component
;; sync that inherits the resize's group. Proves: one Ctrl+Z reverts the
;; edit, the resize and the sync. Before, the sync was an undo entry of its
;; own and the first Ctrl+Z undid only the sync.
(t/deftest one-undo-reverts-an-edit-its-stacked-resize-and-their-sync
  (t/async
    done
    (run-edit-resize-and-sync done
                              [dwu/undo]
                              (fn [page-id state]
                                (t/is (= 2 (count (get-in state [:workspace-undo :items]))))
                                (t/is (= -1 (get-in state [:workspace-undo :index])))
                                (t/is (= "Page 1" (:name (dsh/lookup-page state page-id))))))))

;; Scenario: the same action as above is undone and then redone. Proves: one
;; redo restores the edit, the resize and the sync together.
(t/deftest one-redo-restores-an-edit-its-stacked-resize-and-their-sync
  (t/async
    done
    (run-edit-resize-and-sync done
                              [dwu/undo dwu/redo]
                              (fn [page-id state]
                                (t/is (= 1 (get-in state [:workspace-undo :index])))
                                (t/is (= "C" (:name (dsh/lookup-page state page-id))))))))

;; Scenario: the user presses Ctrl+Z on a page rename. Proves: the commit it
;; makes can be undone (its undo changes redo the rename) and names the index
;; the history moved from, so a rollback can put both back.
(t/deftest an-undo-commit-can-be-undone
  (t/async
    done
    (let [file    (cthf/sample-file :file1 :page-label :page1)
          page-id (cthf/current-page-id file)
          store   (ths/setup-store file)
          commits (atom [])
          sub     (->> (ptk/input-stream store)
                       (rx/filter dch/commit?)
                       (rx/subs! #(swap! commits conj (deref %))))
          item    (assoc (entry page-id "A" (uuid/next)) :undo-groups #{})]
      (ths/run-store store done
                     [#(assoc % :workspace-undo {:items [item] :index 0})
                      dwu/undo]
                     (fn [state]
                       (rx/dispose! sub)
                       (let [[commit] @commits]
                         (t/is (= 1 (count @commits)))
                         (t/is (= (:undo-changes item) (:redo-changes commit)))
                         (t/is (= (:redo-changes item) (:undo-changes commit)))
                         (t/is (= 0 (:undo-index commit)))
                         (t/is (= -1 (get-in state [:workspace-undo :index])))))))))

;; Forgetting the undo groups of a rollback.

(defn- entry-of-groups
  "An undo entry holding commits of `groups`, the first one naming it."
  [& groups]
  {:undo-group (first groups) :undo-groups (set groups)})

(defn- forget
  "The undo state after forgetting `groups` from `undo-state`."
  [undo-state groups undo-index]
  (:workspace-undo (ptk/update (dwu/forget-undo-groups groups undo-index)
                               {:workspace-undo undo-state})))

(t/deftest forgetting-undo-groups-cuts-the-history-at-the-first-match
  (let [[g1 g2 g3] (repeatedly 3 uuid/next)
        undo       (forget {:items [(entry-of-groups g1) (entry-of-groups g2) (entry-of-groups g3)]
                            :index 2
                            :transaction {:undo-group g3 :undo-changes [{:type :mod-page}]}}
                           #{g2 g3} nil)]
    (t/is (= [g1] (mapv :undo-group (:items undo))))
    (t/is (= 0 (:index undo)))
    (t/is (empty? (get-in undo [:transaction :undo-changes])) "the open transaction is emptied")
    (t/is (= g3 (get-in undo [:transaction :commit-group]))
          "its later commits keep the group of its earlier ones")))

(t/deftest forgetting-undo-groups-matches-any-group-an-entry-holds
  (let [[g1 g2 g3] (repeatedly 3 uuid/next)
        undo       (forget {:items [(entry-of-groups g1) (entry-of-groups g2 g3)] :index 1}
                           #{g3} nil)]
    (t/is (= [g1] (mapv :undo-group (:items undo))))
    (t/is (= 0 (:index undo)))))

(t/deftest forgetting-undo-groups-keeps-the-history-without-a-match
  (let [[g1 g2] (repeatedly 2 uuid/next)
        tx      {:undo-group g2 :undo-groups #{g2} :undo-changes [{:type :mod-page}]}
        undo    (forget {:items [(entry-of-groups g1)] :index 0 :transaction tx}
                        #{(uuid/next)} nil)]
    (t/is (= [g1] (mapv :undo-group (:items undo))))
    (t/is (= 0 (:index undo)))
    (t/is (= tx (:transaction undo)) "an unrelated open transaction stays")))

(t/deftest forgetting-undo-groups-empties-a-transaction-that-holds-one
  (let [[g1 g2] (repeatedly 2 uuid/next)
        tx      {:undo-group g2 :undo-groups #{g2} :undo-changes [{:type :mod-page}]}
        undo    (forget {:items [(entry-of-groups g1)] :index 0 :transaction tx}
                        #{g2} nil)]
    (t/is (= [g1] (mapv :undo-group (:items undo))) "the history stays")
    (t/is (empty? (get-in undo [:transaction :undo-changes])))
    (t/is (= g2 (get-in undo [:transaction :commit-group])))))

(t/deftest forgetting-undo-groups-points-back-to-the-index-of-an-undone-undo
  (let [[g1 g2 g3] (repeatedly 3 uuid/next)
        undo-state {:items [(entry-of-groups g1) (entry-of-groups g2) (entry-of-groups g3)]
                    :index 0}]
    (t/is (= 2 (:index (forget undo-state #{(uuid/next)} 2))))
    (t/is (= 0 (:index (forget undo-state #{g2} 2)))
          "a cut history cannot point past its end")))

;; Scenario: the workspace opens and then reloads twice (a peer's restore,
;; the rollback fallback), each reload running `initialize-workspace` again
;; without leaving the workspace; then one commit is made. Proves: each
;; reload replaces the workspace streams, so the commit adds one undo entry,
;; not one per reload.
(t/deftest ^:async a-workspace-reload-adds-one-undo-entry-per-commit
  (let [file    (cthf/sample-file :file1 :page-label :page1)
        team-id (uuid/next)
        store   (ths/setup-store file {:renderer :svg})
        loaded  (fn [type] (mock/stub (fn [& _] (ptk/data-event type {}))))]
    (await
     (mock/with-mocks*
       {dw/initialize-file             (loaded ::file-requested)
        dcmt/retrieve-comment-threads  (loaded ::threads-requested)
        dcmt/fetch-profiles            (loaded ::profiles-requested)
        df/fetch-fonts                 (loaded ::fonts-requested)}
       (try
         (ptk/emit! store
                    #(assoc % :current-team-id team-id)
                    (dw/initialize-workspace team-id (:id file))
                    (ptk/event ::dw/reload-current-file)
                    (ptk/event ::dw/reload-current-file))
         (await (async/settle))
         (ptk/emit! store (rename-page (cthf/current-page-id file) "Renamed"))
         (await (async/wait-for-store store #(seq (get-in @store [:workspace-undo :items]))
                                      "the commit reaches the undo history"))
         (await (async/settle))
         (t/is (= 1 (count (get-in @store [:workspace-undo :items]))))
         (finally
           (rx/dispose! store)))))))
