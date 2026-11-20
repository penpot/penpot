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

(t/deftest pool-passes-prepared-statement-cache-properties
  ;; The driver reads dataSourceProperties through Properties.getProperty,
  ;; which keeps only String values, so the wiring must set them as strings.
  ;; Comparing the literals alone cannot catch a property the driver ignores,
  ;; so the threshold cases also read it back from a real connection.
  (let [metrics (ig/init-key :app.metrics/metrics {:default {}})
        init-pool (fn [opts]
                    (ig/init-key :app.db/pool
                                 (merge {:app.db/uri (java.net.URI. "postgresql://localhost/test")
                                         :app.metrics/metrics metrics
                                         :app.db/name :test-prepared-statement-cache}
                                        opts)))
        with-connection (fn [opts f]
                          (let [ds (init-pool (merge {:app.db/uri (:database-uri th/config)
                                                      :app.db/username (:database-username th/config)
                                                      :app.db/password (:database-password th/config)}
                                                     opts))]
                            (try
                              (with-open [conn (.getConnection ^HikariDataSource ds)]
                                (f conn))
                              (finally
                                (.close ^HikariDataSource ds)))))
        prepare-threshold (fn [conn]
                            (.getPrepareThreshold
                             ^org.postgresql.PGConnection
                             (.unwrap conn org.postgresql.PGConnection)))]
    (t/testing "absent keys stay absent"
      (let [ds (init-pool {})]
        (t/is (empty? (.getDataSourceProperties ^HikariDataSource ds)))
        (.close ^HikariDataSource ds)))
    (t/testing "the driver defaults apply when no option is set"
      (with-connection {} (fn [conn] (t/is (= 5 (prepare-threshold conn))))))
    (t/testing "configured keys reach the driver as strings"
      (let [ds (init-pool {:app.db/prepared-statement-cache-threshold 1
                           :app.db/prepared-statement-cache-queries 512
                           :app.db/prepared-statement-cache-size 10485760})
            props (.getDataSourceProperties ^HikariDataSource ds)]
        (t/is (= "1" (.getProperty props "prepareThreshold")))
        (t/is (= "512" (.getProperty props "preparedStatementCacheQueries")))
        (t/is (= "10" (.getProperty props "preparedStatementCacheSizeMiB")))
        (.close ^HikariDataSource ds)))
    (t/testing "a configured threshold reaches the driver"
      (with-connection {:app.db/prepared-statement-cache-threshold 1}
        (fn [conn] (t/is (= 1 (prepare-threshold conn)))))
      (with-connection {:app.db/prepared-statement-cache-threshold 0}
        (fn [conn] (t/is (= 0 (prepare-threshold conn))))))
    (t/testing "a -1 threshold reaches the driver (forceBinary)"
      (let [ds (init-pool {:app.db/prepared-statement-cache-threshold -1})]
        (t/is (= "-1" (.getProperty (.getDataSourceProperties ^HikariDataSource ds)
                                    "prepareThreshold")))
        (.close ^HikariDataSource ds)))
    (t/testing "a zero threshold reaches the driver (server-side statements off)"
      (let [ds (init-pool {:app.db/prepared-statement-cache-threshold 0})]
        (t/is (= "0" (.getProperty (.getDataSourceProperties ^HikariDataSource ds)
                                   "prepareThreshold")))
        (.close ^HikariDataSource ds)))
    (t/testing "a positive sub-MiB size rounds up so the cache stays enabled"
      (let [ds (init-pool {:app.db/prepared-statement-cache-size 1})]
        (t/is (= "1" (.getProperty (.getDataSourceProperties ^HikariDataSource ds)
                                   "preparedStatementCacheSizeMiB")))
        (.close ^HikariDataSource ds)))
    (t/testing "the MiB ceiling rounds a byte over the boundary up"
      (let [ds (init-pool {:app.db/prepared-statement-cache-size 1048577})]
        (t/is (= "2" (.getProperty (.getDataSourceProperties ^HikariDataSource ds)
                                   "preparedStatementCacheSizeMiB")))
        (.close ^HikariDataSource ds)))
    (t/testing "the cache limits hold a closed statement in the server"
      ;; A closed statement stays prepared in the server only while the
      ;; driver's cache holds it, so counting it in pg_prepared_statements is
      ;; what tells an accepted property from an effective one.
      (let [cached? (fn [opts]
                      (with-connection
                        (merge {:app.db/prepared-statement-cache-threshold 1} opts)
                        (fn [conn]
                          (dotimes [_ 2]
                            (with-open [stmt (.prepareStatement ^Connection conn "SELECT ?::int AS cache_probe")]
                              (.setInt stmt 1 1)
                              (with-open [rs (.executeQuery stmt)] (.next rs))))
                          (-> (db/exec-one! conn ["SELECT count(*) AS n FROM pg_prepared_statements
                                                   WHERE statement LIKE '%AS cache_probe'"])
                              :n
                              pos?))))]
        (t/testing "a positive sub-MiB size keeps the cache enabled"
          (t/is (cached? {:app.db/prepared-statement-cache-size 1})))
        (t/testing "zero size disables the cache"
          (t/is (not (cached? {:app.db/prepared-statement-cache-size 0}))))
        (t/testing "zero queries disables the cache"
          (t/is (not (cached? {:app.db/prepared-statement-cache-queries 0}))))))))

(t/deftest pool-rejects-a-non-minus-one-negative-threshold
  ;; The pool boundary mirrors app.config: -1 prepares from the first
  ;; execution, 0 stops server-side prepared statements, and any other
  ;; negative is a typo the driver would silently clamp to -1.
  (let [options {:app.db/uri (:database-uri th/config)
                 :app.db/username (:database-username th/config)
                 :app.db/password (:database-password th/config)
                 :app.metrics/metrics (ig/init-key :app.metrics/metrics {:default {}})}]
    (t/testing "a -2 threshold fails the pool schema"
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (ig/assert-key :app.db/pool
                                    (assoc options :app.db/prepared-statement-cache-threshold -2)))))
    (t/testing "-1 and 0 pass the pool schema"
      (t/is (nil? (ig/assert-key :app.db/pool
                                 (assoc options :app.db/prepared-statement-cache-threshold -1))))
      (t/is (nil? (ig/assert-key :app.db/pool
                                 (assoc options :app.db/prepared-statement-cache-threshold 0)))))))

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
