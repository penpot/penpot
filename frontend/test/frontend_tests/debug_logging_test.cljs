;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.debug-logging-test
  (:require
   [app.common.logging :as l]
   [cljs.test :as t :include-macros true]
   [debug :as dbg]))

(t/deftest set-logging-test
  (t/testing "invalid inputs warn and do not throw"
    (t/is (nil? (dbg/set-logging nil)))
    (t/is (nil? (dbg/set-logging "bogus")))
    (t/is (nil? (dbg/set-logging 123)))
    (t/is (nil? (dbg/set-logging nil nil)))
    (t/is (nil? (dbg/set-logging "app" "bogus")))
    (t/is (nil? (dbg/set-logging "app" nil)))
    (t/is (nil? (dbg/set-logging "" "debug"))))
  (t/testing "valid inputs install a string logger key"
    (dbg/set-logging "app" "debug")
    (t/is (true? (l/enabled? "app" :debug)))
    (dbg/set-logging "app" "error")
    (t/is (true? (l/enabled? "app" :error)))
    (t/is (false? (l/enabled? "app" :debug)))
    (.delete l/loggers "app")))
