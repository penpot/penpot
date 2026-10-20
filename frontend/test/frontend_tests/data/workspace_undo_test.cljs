;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-undo-test
  "Tests for the undo groups of commits made inside an undo transaction."
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
                      (dwu/forget-undo-groups #{(uuid/next)})
                      (rename-page page-id "B")
                      (dwu/commit-undo-transaction tx)])
                   (fn [[first-group second-group :as groups] _]
                     (t/is (= 2 (count groups)))
                     (t/is (some? first-group))
                     (t/is (= first-group second-group)))))))
