;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.db-test
  (:require
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   java.sql.Connection))

(t/use-fixtures :once th/state-init)

(t/deftest pool-stats-returns-expected-keys
  (let [stats (db/pool-stats th/*pool*)]
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
