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
   (t/use-fixtures
     :each
     (fn [f]
       (f)
       (doseq [k ["logging-test-probe-xyz"
                  "logging-test-fatal-xyz"
                  "logging-test-setup-xyz"
                  "logging-test-bad-xyz"
                  "logging-test-key-xyz"]]
         (.delete l/loggers k)))))

#?(:cljs
   (t/deftest browser-boundaries-test
     (t/testing "unknown levels never throw and disable logging"
       (t/is (false? (l/enabled? "logging-test-probe-xyz" nil)))
       (t/is (false? (l/enabled? "logging-test-probe-xyz" :bogus))))
     (t/testing "invalid loggers never throw and disable logging"
       (t/is (false? (l/enabled? nil :error)))
       (t/is (false? (l/enabled? "" :error)))
       (t/is (false? (l/enabled? 123 :error))))
     (t/testing "unknown logger defaults to disabled"
       (t/is (false? (l/enabled? "logging-test-probe-xyz" :fatal)))
       (t/is (false? (l/enabled? "logging-test-probe-xyz" :error))))
     (t/testing "fatal filters exactly like error when enabled"
       (l/setup! {"logging-test-fatal-xyz" :debug})
       (t/is (true? (l/enabled? "logging-test-fatal-xyz" :fatal)))
       (t/is (= (l/enabled? "logging-test-fatal-xyz" :fatal)
                (l/enabled? "logging-test-fatal-xyz" :error)))
       (l/setup! {"logging-test-fatal-xyz" :error})
       (t/is (true? (l/enabled? "logging-test-fatal-xyz" :fatal)))
       (t/is (false? (l/enabled? "logging-test-fatal-xyz" :debug)))
       (t/is (false? (l/enabled? "logging-test-fatal-xyz" :trace))))
     (t/testing "setup! skips invalid entries and installs valid ones"
       (t/is (do (l/setup! {"logging-test-setup-xyz" :error
                            "logging-test-bad-xyz"   nil})
                 true))
       (t/is (true? (l/enabled? "logging-test-setup-xyz" :error)))
       (t/is (false? (l/enabled? "logging-test-setup-xyz" :bogus)))
       (t/is (false? (l/enabled? "logging-test-bad-xyz" :error))))
     (t/testing "setup! skips invalid logger keys and installs valid ones"
       (t/is (do (l/setup! {"logging-test-key-xyz" :error
                            ""                     :error
                            nil                    :error})
                 true))
       (t/is (true? (l/enabled? "logging-test-key-xyz" :error))))
     (t/testing "safe fallbacks never throw"
       (t/is (= "#969896" (l/level->color-safe nil)))
       (t/is (= "UNK" (l/level->name-safe nil)))
       (t/is (= "#c82829" (l/level->color-safe :fatal)))
       (t/is (= "ERR" (l/level->name-safe :fatal))))
     (t/testing "console handler survives a nil-level record"
       (t/is (nil? (l/console-log-handler
                    nil nil nil
                    {::l/logger  "logging-test-probe-xyz"
                     ::l/level   nil
                     ::l/message (delay "hi")
                     ::l/props   {}}))))
     (t/testing "console handler skips invalid-logger records"
       (t/is (nil? (l/console-log-handler
                    nil nil nil
                    {::l/logger  nil
                     ::l/level   nil
                     ::l/message (delay "hi")
                     ::l/props   {}}))))))

#?(:clj
   (t/deftest backend-strict-test
     (t/testing "fatal does not throw"
       (t/is (do (l/enabled? "app" :fatal) true)))
     (t/testing "JVM branch still rejects invalid levels loudly"
       (t/is (try (l/enabled? "app" nil) false
                  (catch IllegalArgumentException _ true)))
       (t/is (try (l/enabled? "app" :bogus) false
                  (catch IllegalArgumentException _ true))))))
