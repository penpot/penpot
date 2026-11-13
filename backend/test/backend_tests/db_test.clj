;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.db-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   java.sql.Connection))

(t/use-fixtures :once th/state-init)

(defn- get-consistent-pool-stats
  "Read pool stats until total equals active + idle, or attempts run out.
  Hikari exposes each counter through a separate MXBean call, so a borrow
  or return between the reads can leave a single snapshot inconsistent
  (total off by one). Retrying until the pool is quiescent keeps the test
  deterministic without weakening the invariant."
  [pool]
  (loop [attempt 20]
    (let [stats  (db/pool-stats pool)
          total  (:total-connections stats)
          active (:active-connections stats)
          idle   (:idle-connections stats)]
      (if (or (= total (+ active idle))
              (zero? attempt))
        stats
        (do (Thread/sleep 10)
            (recur (dec attempt)))))))

(t/deftest pool-requires-metrics
  ;; Metrics is required by the pool schema: a pool wired without it
  ;; fails at the boundary (AssertionError when asserts are enabled) or
  ;; when the prometheus tracker is wired, instead of silently skipping
  ;; the instrumentation.
  (t/is (thrown? Throwable
                 (ig/init-key :app.db/pool
                              {::db/uri (java.net.URI. "postgresql://localhost/test")}))))

(t/deftest pool-stats-returns-expected-keys
  (let [stats (get-consistent-pool-stats th/*pool*)]
    (t/testing "all expected keys are present"
      (t/is (contains? stats :active-connections))
      (t/is (contains? stats :idle-connections))
      (t/is (contains? stats :threads-awaiting-connection))
      (t/is (contains? stats :total-connections))
      (t/is (contains? stats :maximum-pool-size))
      (t/is (contains? stats :minimum-idle)))

    (t/testing "values are non-negative integers"
      (t/is (>= (:active-connections stats) 0))
      (t/is (>= (:idle-connections stats) 0))
      (t/is (>= (:threads-awaiting-connection stats) 0))
      (t/is (>= (:total-connections stats) 0))
      (t/is (>= (:maximum-pool-size stats) 0))
      (t/is (>= (:minimum-idle stats) 0)))

    (t/testing "total connections equals active + idle"
      (t/is (= (:total-connections stats)
               (+ (:active-connections stats)
                  (:idle-connections stats)))))

    (t/testing "maximum pool size is reasonable"
      (t/is (pos? (:maximum-pool-size stats))))))

(t/deftest uuid->hash-code-is-deterministic
  (t/is (= (db/uuid->hash-code uuid/zero)
           (db/uuid->hash-code uuid/zero))))

(t/deftest uuid->hash-code-returns-long
  (t/is (instance? Long (db/uuid->hash-code uuid/zero))))

(t/deftest uuid->hash-code-stable-for-zero-uuid
  (t/is (= 3659997967308761462 (db/uuid->hash-code uuid/zero))))

(t/deftest pool-enables-the-partitionwise-join
  ;; The pool sets the GUC on every connection, so the planner can resolve a
  ;; join between two partitioned tables pairwise, partition by partition.
  (let [row (db/exec-one! th/*pool*
                          ["SELECT current_setting('enable_partitionwise_join') AS partitionwise_join"])]

    (t/is (= "on" (:partitionwise-join row)))))

(t/deftest session-locks-are-held-per-connection
  (let [classid 730000
        objid   1]
    (with-open [a (db/open th/*pool*)
                b (db/open th/*pool*)]
      (t/testing "the first connection takes it, the second cannot"
        (t/is (true? (db/session-try-lock a classid objid)))
        (t/is (false? (db/session-try-lock b classid objid))))
      (t/testing "releasing it lets the other take it"
        (t/is (true? (db/session-unlock a classid objid)))
        (t/is (true? (db/session-try-lock b classid objid)))
        (t/is (true? (db/session-unlock b classid objid)))))))

(t/deftest session-locks-keep-the-two-key-space-apart
  ;; PostgreSQL documents the split: a bigint key is shown with its
  ;; high-order half in classid, its low-order half in objid and
  ;; objsubid 1, while a two-int key keeps both numbers and objsubid 2.
  ;; The two forms are therefore different locks even with the same
  ;; numbers, which is what the consumers rely on to never block a file
  ;; update. The test proves each step instead of assuming it.
  (let [classid    730000
        objid      2
        bigint-key (+ (* (long classid) 4294967296) objid)]
    (with-open [a (db/open th/*pool*)
                b (db/open th/*pool*)
                c (db/open th/*pool*)]
      (t/testing "the one-key form really holds that identity"
        (t/is (true? (:lock (db/exec-one! a ["select pg_try_advisory_lock(?::bigint) as lock"
                                             bigint-key]))))
        (t/is (false? (:lock (db/exec-one! b ["select pg_try_advisory_lock(?::bigint) as lock"
                                              bigint-key])))))

      (t/testing "PostgreSQL displays that bigint as the (classid, objid) pair"
        (let [{:keys [classid objid objsubid]}
              (db/exec-one! a ["select classid, objid, objsubid from pg_locks
                                 where pid = pg_backend_pid()
                                   and locktype = 'advisory'
                                   and objsubid = 1
                                   and granted"])]
          (t/is (= [730000 2 1] [classid objid objsubid]))
          ;; and the displayed halves reassemble to the same bigint
          (t/is (= bigint-key (+ (* (long classid) 4294967296) objid)))))

      (t/testing "the same numbers in the two-key form are another lock"
        (t/is (true? (db/session-try-lock c classid objid)))
        (t/is (true? (db/session-unlock c classid objid))))

      (t/is (true? (:lock (db/exec-one! a ["select pg_advisory_unlock(?::bigint) as lock"
                                           bigint-key])))))))

(t/deftest session-locks-reject-a-key-that-is-not-int4
  (with-open [a (db/open th/*pool*)]
    (t/is (thrown? IllegalArgumentException
                   (db/session-try-lock a uuid/zero 1)))
    (t/is (thrown? IllegalArgumentException
                   (db/session-unlock a 730000 uuid/zero)))
    (t/is (thrown? IllegalArgumentException
                   (db/session-try-lock a 730000 (inc Integer/MAX_VALUE))))))

(t/deftest session-unlock-all-releases-every-lock-of-the-connection
  (let [classid 730000]
    (with-open [a (db/open th/*pool*)
                b (db/open th/*pool*)]
      (t/is (true? (db/session-try-lock a classid 1)))
      (t/is (true? (db/session-try-lock a classid 2)))
      (t/is (false? (db/session-try-lock b classid 1)))
      (db/session-unlock-all a)
      (t/is (true? (db/session-try-lock b classid 1)))
      (t/is (true? (db/session-try-lock b classid 2)))
      (db/session-unlock-all b))))

(t/deftest session-unlock-answers-false-for-a-lock-not-held
  (with-open [a (db/open th/*pool*)]
    (t/is (false? (db/session-unlock a 730000 3)))))

(t/deftest own-transaction-drops-the-connection-and-the-read-only-flag
  ;; The contract has two halves: the write must not join the caller's
  ;; connection, and it must be writable even when the caller is
  ;; read-only (`::db/read-only` dropped).
  (let [caller-conn (Object.)
        cfg         {::db/pool th/*pool*
                     ::db/conn caller-conn
                     ::db/read-only true}]
    (t/testing "the connection and the read-only flag are gone"
      (t/is (= {::db/pool th/*pool*} (db/own-transaction cfg))))
    (t/testing "so the write opens its own transaction on another connection"
      (db/tx-run! (db/own-transaction cfg)
                  (fn [{:keys [::db/conn]}]
                    (t/is (not (identical? conn caller-conn))))))))
