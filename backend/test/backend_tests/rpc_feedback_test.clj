;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-feedback-test
  (:require
   [app.common.schema :as sm]
   [app.rpc.commands.feedback :as feedback]
   [clojure.test :as t]))

(t/deftest send-user-feedback-schema-validation
  (let [schema feedback/schema:send-user-feedback]

    (t/testing "accepts valid feedback with all fields"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :type "bug"
                    :error-href "https://example.com/error"
                    :error-report "Error details here"}]
        (t/is (sm/valid? schema params))))

    (t/testing "accepts feedback without optional fields"
      (let [params {:subject "Test subject"
                    :content "Test content"}]
        (t/is (sm/valid? schema params))))

    (t/testing "accepts error-report up to 1MiB"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :error-report (apply str (repeat 1048576 "x"))}]
        (t/is (sm/valid? schema params))))

    (t/testing "rejects error-report exceeding 1MiB"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :error-report (apply str (repeat 1048577 "x"))}]
        (t/is (not (sm/valid? schema params)))))))
