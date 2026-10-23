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
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
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
   :tags #{}})

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
