;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.job-test
  "The queue payload both processes share: a two-element array of the
  job id and the instant it was pushed, and nothing else."
  (:require
   [app.common.types.job :as ctj]
   [clojure.test :as t]))

(t/deftest a-payload-is-two-strings
  (t/testing "the dispatcher shape validates"
    (t/is (ctj/valid-queue-payload?
           ["03b7300f-11e6-8131-8008-e4b09b780a0e" "2026-10-07T09:00:00Z"])))
  (t/testing "the decoder keeps it a vector of two strings"
    (t/is (= ["03b7300f-11e6-8131-8008-e4b09b780a0e" "2026-10-07T09:00:00Z"]
             (ctj/decode-queue-payload
              ["03b7300f-11e6-8131-8008-e4b09b780a0e" "2026-10-07T09:00:00Z"])))))

(t/deftest anything-else-is-not-a-payload
  (t/testing "an empty object is not the array the dispatcher pushes"
    (t/is (not (ctj/valid-queue-payload? {}))))
  (t/testing "nil is not a payload"
    (t/is (not (ctj/valid-queue-payload? nil))))
  (t/testing "an empty array has no job id"
    (t/is (not (ctj/valid-queue-payload? []))))
  (t/testing "two items of another type are not a payload"
    (t/is (not (ctj/valid-queue-payload? [1 2]))))
  (t/testing "a third item is not part of the contract"
    (t/is (not (ctj/valid-queue-payload? ["a" "b" "c"])))))
