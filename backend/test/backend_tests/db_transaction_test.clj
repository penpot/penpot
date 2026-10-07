;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.db-transaction-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(t/deftest after-commit-runs-after-a-successful-transaction
  (let [events (atom [])]
    (db/tx-run! th/*pool*
                (fn [_]
                  (db/after-commit #(swap! events conj :committed))))
    (t/is (= [:committed] @events))))

(t/deftest after-commit-is-discarded-on-rollback
  (let [events (atom [])]
    (t/is (thrown? Exception
                   (db/tx-run! th/*pool*
                               (fn [_]
                                 (db/after-commit #(swap! events conj :committed))
                                 (throw (ex-info "rollback" {}))))))
    (t/is (empty? @events))))

(t/deftest nested-transactions-share-the-outer-commit
  (let [events (atom [])]
    (db/tx-run! th/*pool*
                (fn [cfg]
                  (db/after-commit #(swap! events conj :outer))
                  (db/tx-run! cfg
                              (fn [_]
                                (db/after-commit #(swap! events conj :inner))))))
    (t/is (= [:outer :inner] @events))))

(t/deftest after-commit-runs-immediately-outside-a-transaction
  (let [events (atom [])]
    (db/after-commit #(swap! events conj :immediate))
    (t/is (= [:immediate] @events))))

(t/deftest a-rollback-only-transaction-runs-no-callbacks
  (let [events (atom [])]
    (db/tx-run! {::db/pool th/*pool*
                 ::db/rollback true}
                (fn [_]
                  (db/after-commit #(swap! events conj :committed))))
    (t/is (= [] @events)
          "the transaction rolled back, so there is no commit to follow")))

(t/deftest an-independent-transaction-owns-its-callbacks
  (let [events (atom [])
        job-id (uuid/next)]
    (try
      (try
        (db/tx-run! th/*pool*
                    (fn [_]
                      (db/after-commit #(swap! events conj :outer))
                      ;; a different connection: its own unit of work, so
                      ;; it commits on its own and owns its own callbacks
                      (db/tx-run! th/*pool*
                                  (fn [cfg]
                                    (db/after-commit #(swap! events conj :inner))
                                    (db/exec-one! cfg ["INSERT INTO job (id, name, tenant, queue)
                                                        VALUES (?, ?, ?, ?)"
                                                       job-id "test" "acme" "default"])))
                      (throw (ex-info "rollback" {}))))
        (catch Exception _ nil))
      (t/testing "the row written by the inner transaction survives the caller rollback"
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job WHERE id = ?"
                                           job-id])))))
      (t/testing "its callback ran with its own commit, the caller one was dropped"
        (t/is (= [:inner] @events)))
      (finally
        (th/db-force-delete :job {:id job-id})))))
