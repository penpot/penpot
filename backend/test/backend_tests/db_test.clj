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
