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
