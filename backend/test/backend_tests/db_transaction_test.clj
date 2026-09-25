;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.db-transaction-test
  (:require
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(t/deftest after-commit-runs-after-a-successful-transaction
  (let [events (atom [])]
    (db/tx-run! th/*pool*
                (fn [_]
                  (db/after-commit! #(swap! events conj :committed))))
    (t/is (= [:committed] @events))))

(t/deftest after-commit-is-discarded-on-rollback
  (let [events (atom [])]
    (t/is (thrown? Exception
                   (db/tx-run! th/*pool*
                               (fn [_]
                                 (db/after-commit! #(swap! events conj :committed))
                                 (throw (ex-info "rollback" {}))))))
    (t/is (empty? @events))))

(t/deftest nested-transactions-share-the-outer-commit
  (let [events (atom [])]
    (db/tx-run! th/*pool*
                (fn [cfg]
                  (db/after-commit! #(swap! events conj :outer))
                  (db/tx-run! cfg
                              (fn [_]
                                (db/after-commit! #(swap! events conj :inner))))))
    (t/is (= [:outer :inner] @events))))

(t/deftest after-commit-runs-immediately-outside-a-transaction
  (let [events (atom [])]
    (db/after-commit! #(swap! events conj :immediate))
    (t/is (= [:immediate] @events))))
