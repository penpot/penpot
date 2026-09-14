;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.logging-test
  (:require
   [app.common.logging :as l]
   [clojure.test :as t]))

(defn- throws?
  [thunk]
  #?(:clj  (try (thunk) false (catch clojure.lang.ExceptionInfo _ true))
     :cljs (try (thunk) false (catch :default _ true))))

(t/deftest level->int-test
  (t/is (= 10 (l/level->int :trace)))
  (t/is (= 20 (l/level->int :debug)))
  (t/is (= 30 (l/level->int :info)))
  (t/is (= 40 (l/level->int :warn)))
  (t/is (= 50 (l/level->int :error)))
  (t/is (= 50 (l/level->int :fatal)))
  (t/is (throws? #(l/level->int nil)))
  (t/is (throws? #(l/level->int :bogus))))

#?(:cljs
   (t/deftest browser-boundaries-test
     (t/testing "unknown levels never throw and disable logging"
       (t/is (false? (l/enabled? "logging-test-probe-xyz" nil)))
       (t/is (false? (l/enabled? "logging-test-probe-xyz" :bogus))))
     (t/testing "fatal filters exactly like error"
       (t/is (= (l/enabled? "logging-test-probe-xyz" :fatal)
                (l/enabled? "logging-test-probe-xyz" :error))))
     (t/testing "setup! skips invalid entries and installs valid ones"
       (t/is (do (l/setup! {"logging-test-setup-xyz" :error
                            "logging-test-bad-xyz"   nil})
                 true))
       (t/is (true? (l/enabled? "logging-test-setup-xyz" :error)))
       (t/is (false? (l/enabled? "logging-test-setup-xyz" :bogus))))
     (t/testing "console handler survives a nil-level record"
       (t/is (nil? (l/console-log-handler
                    nil nil nil
                    {::l/logger  "logging-test-probe-xyz"
                     ::l/level   nil
                     ::l/message (delay "hi")
                     ::l/props   {}}))))))

#?(:clj
   (t/deftest backend-strict-test
     (t/testing "JVM branch still rejects invalid levels loudly"
       (t/is (try (l/enabled? "app" nil) false
                  (catch IllegalArgumentException _ true))))))
