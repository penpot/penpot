;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-errors-test
  "Focused tests for the HTTP error mapping in `app.http.errors`."
  (:require
   [app.http :as-alias http]
   [app.http.errors :as http-errors]
   [clojure.test :as t]
   [yetti.response :as yres]))

(t/deftest account-lockout-returns-429-with-retry-after
  (let [cause    (ex-info "account locked"
                          {:type :rate-limit
                           :code :account-locked
                           :hint "account locked due to too many failed login attempts"
                           :ttl  900})
        response (http-errors/handle cause {})
        headers  (::yres/headers response)
        body     (::yres/body response)]
    (t/is (= 429 (::yres/status response)))
    (t/is (= "900" (get headers "retry-after")))
    (t/is (= :rate-limit (:type body)))
    (t/is (= :account-locked (:code body)))
    (t/is (= 900 (:ttl body)))))

(t/deftest rpc-rate-limit-preserves-headers
  (let [cause    (ex-info "rate limit reached"
                          {:type :rate-limit
                           :code :request-blocked
                           :hint "rate limit reached"
                           ::http/headers {"x-rate-limit-remaining" "3"
                                           "x-rate-limit-reset" "60"}})
        response (http-errors/handle cause {})
        headers  (::yres/headers response)
        body     (::yres/body response)]
    (t/is (= 429 (::yres/status response)))
    (t/is (= "3" (get headers "x-rate-limit-remaining")))
    (t/is (= "60" (get headers "x-rate-limit-reset")))
    (t/is (nil? (get headers "retry-after")))
    (t/is (= :rate-limit (:type body)))
    (t/is (= :request-blocked (:code body)))))

(t/deftest rate-limit-with-ttl-keeps-headers-and-adds-retry-after
  (let [cause    (ex-info "rate limit reached"
                          {:type :rate-limit
                           :code :request-blocked
                           :hint "rate limit reached"
                           :ttl  120
                           ::http/headers {"x-rate-limit-remaining" "0"
                                           "x-rate-limit-reset" "120"}})
        response (http-errors/handle cause {})
        headers  (::yres/headers response)]
    (t/is (= 429 (::yres/status response)))
    (t/is (= "0" (get headers "x-rate-limit-remaining")))
    (t/is (= "120" (get headers "x-rate-limit-reset")))
    (t/is (= "120" (get headers "retry-after")))))

(t/deftest rate-limit-without-ttl-has-no-retry-after
  (let [cause    (ex-info "rate limit reached"
                          {:type :rate-limit
                           :code :request-blocked
                           :hint "rate limit reached"})
        response (http-errors/handle cause {})
        headers  (::yres/headers response)]
    (t/is (= 429 (::yres/status response)))
    (t/is (nil? (get headers "retry-after")))))

(defn- psql-exception
  [state]
  (org.postgresql.util.PSQLException. "database failure" state))

(t/deftest transient-database-failures-answer-503
  (doseq [state [org.postgresql.util.PSQLState/CONNECTION_FAILURE
                 org.postgresql.util.PSQLState/CONNECTION_UNABLE_TO_CONNECT
                 org.postgresql.util.PSQLState/SERIALIZATION_FAILURE
                 org.postgresql.util.PSQLState/DEADLOCK_DETECTED]]
    (let [response (http-errors/handle (psql-exception state) {})]
      (t/is (= 503 (::yres/status response)) (str state))
      (t/is (= :server-error (-> response ::yres/body :type))))))

(t/deftest other-database-failures-answer-500
  (let [response (http-errors/handle (psql-exception org.postgresql.util.PSQLState/UNIQUE_VIOLATION) {})]
    (t/is (= 500 (::yres/status response)))
    (t/is (= :database-error (-> response ::yres/body :code)))))

(t/deftest database-timeouts-keep-answering-504
  (let [response (http-errors/handle (psql-exception org.postgresql.util.PSQLState/QUERY_CANCELED) {})]
    (t/is (= 504 (::yres/status response)))
    (t/is (= :statement-timeout (-> response ::yres/body :code)))))

(t/deftest transient-connection-failures-answer-503
  (doseq [cause [(java.sql.SQLTransientConnectionException. "pool exhausted")
                 (java.net.SocketException. "broken pipe")
                 (java.net.ConnectException. "connection refused")
                 (java.net.SocketTimeoutException. "read timed out")
                 (java.net.UnknownHostException. "storage.example.com")
                 (InterruptedException. "shutting down")]]
    (let [response (http-errors/handle cause {})]
      (t/is (= 503 (::yres/status response)) (str (class cause)))
      (t/is (= :server-error (-> response ::yres/body :type))))))

(t/deftest permanent-io-failures-answer-500
  (doseq [cause [(java.io.IOException. "generic")
                 (java.io.EOFException. "truncated blob")
                 (java.nio.file.NoSuchFileException. "/data/missing")
                 (com.fasterxml.jackson.core.JsonParseException. nil "bad json")]]
    (let [response (http-errors/handle cause {})]
      (t/is (= 500 (::yres/status response)) (str (class cause)))
      (t/is (= :io-exception (-> response ::yres/body :code))))))
