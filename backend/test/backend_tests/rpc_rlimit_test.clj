;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-rlimit-test
  (:require
   [app.common.time :as ct]
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
