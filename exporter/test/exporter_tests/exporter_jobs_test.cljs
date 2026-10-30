;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-jobs-test
  "The local cancel arm of the new tree: one flag per claimed job,
  and the zero-arg check the renderers run."
  (:require
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [exporter.jobs :as jobs]))

(t/deftest register-mark-and-release
  (let [job-id (uuid/next)]
    (t/testing "a fresh job is not cancelled"
      (jobs/register job-id)
      (t/is (false? (jobs/cancelled? job-id))))
    (t/testing "the first mark flips the flag and reports it"
      (t/is (true? (jobs/mark-cancelled job-id)))
      (t/is (true? (jobs/cancelled? job-id))))
    (t/testing "a released job is gone, not cancelled"
      (jobs/release job-id)
      (t/is (false? (jobs/cancelled? job-id)))
      (t/is (nil? (jobs/mark-cancelled job-id))))))

(t/deftest check-cancelled-raises-the-cooperative-code
  (let [job-id (uuid/next)
        check  (jobs/check-cancelled job-id)]
    (jobs/register job-id)
    (t/testing "a live job passes the check silently"
      (t/is (nil? (check))))
    (t/testing "a marked job raises what the runner settles as cancel"
      (jobs/mark-cancelled job-id)
      (try
        (check)
        (t/is false "the check should have raised")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code))))))
    (jobs/release job-id)))
