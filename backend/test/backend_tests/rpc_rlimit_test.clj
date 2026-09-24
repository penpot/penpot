;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-rlimit-test
  (:require
   [app.common.time :as ct]
   [app.loggers.database]
   [app.loggers.mattermost]
   [app.redis :as rds]
   [app.rpc.rlimit :as rlimit]
   [clojure.test :as t]))

(t/deftest bucket-reset-supports-fractional-milliseconds
  (let [now   (ct/inst 0)
        limit {::rlimit/name     :test
               ::rlimit/strategy :bucket
               ::rlimit/key      "test"
               ::rlimit/method   "main.test"
               ::rlimit/capacity 5
               ::rlimit/rate     3
               ::rlimit/interval (ct/duration 1000)
               ::rlimit/params   [1 3 5]
               ::rlimit/opts     "5/3/1s"}]
    (with-redefs [rds/eval (fn [_ _] [true 4])]
      (let [result (rlimit/process-limit nil "profile" now limit)]
        (t/is (= (ct/inst 334)
                 (:app.rpc.rlimit.result/reset result)))))))

(defn- test-limit
  []
  {::rlimit/name     :test
   ::rlimit/strategy :bucket
   ::rlimit/key      "test"
   ::rlimit/method   "main.test"
   ::rlimit/capacity 5
   ::rlimit/rate     3
   ::rlimit/interval (ct/duration 1000)
   ::rlimit/params   [1 3 5]
   ::rlimit/opts     "5/3/1s"})

(t/deftest rejected-rate-limit-emits-retry-after-header
  ;; When a limit rejects the request the 429 headers must carry a
  ;; Retry-After with the seconds until the client can retry.
  (with-redefs [rds/eval (fn [_ _] [false 2])
                app.loggers.mattermost/emit (fn [_ _] nil)
                app.loggers.database/emit (fn [_ _] nil)]
    (let [result  (#'rlimit/process-limits {::rds/conn nil} "profile" [(test-limit)] (ct/inst 0))
          headers (::rlimit/headers result)]
      (t/is (false? (::rlimit/allowed result)))
      (t/is (= "1" (get headers "retry-after")))
      (t/is (some? (get headers "x-rate-limit-remaining")))
      (t/is (some? (get headers "x-rate-limit-reset"))))))

(t/deftest allowed-rate-limit-omits-retry-after-header
  ;; Retry-After is only meaningful on a rejected 429; allowed responses
  ;; must not carry it.
  (with-redefs [rds/eval (fn [_ _] [true 4])
                app.loggers.mattermost/emit (fn [_ _] nil)
                app.loggers.database/emit (fn [_ _] nil)]
    (let [result  (#'rlimit/process-limits {::rds/conn nil} "profile" [(test-limit)] (ct/inst 0))
          headers (::rlimit/headers result)]
      (t/is (true? (::rlimit/allowed result)))
      (t/is (nil? (get headers "retry-after"))))))
