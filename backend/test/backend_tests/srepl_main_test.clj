;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.srepl-main-test
  (:require
   [app.srepl.main :as srepl]
   [clojure.test :as t]))

(t/deftest parse-emails
  (t/is (= ["some@example.com"]
           (srepl/parse-emails "some@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails "some@example.com,other@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails " some@example.com , other@example.com ,")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails ["some@example.com" "other@example.com"])))

  (t/is (= [] (srepl/parse-emails ",")))

  (t/is (thrown? clojure.lang.ExceptionInfo
                 (srepl/parse-emails 42))))
