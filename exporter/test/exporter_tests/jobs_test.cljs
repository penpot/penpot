;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.jobs-test
  "Job state machine. Runs without redis: a store write with no connection is
  reported and swallowed, so only the in-process record is exercised."
  (:require
   [app.common.uuid :as uuid]
   [app.jobs :as jobs]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(defn- create!
  []
  (jobs/create! {:profile-id (uuid/next)
                 :cmd :export-shapes
                 :backend "wasm"
                 :total 10
                 :name "test"
                 :resource-id (uuid/next)}
                (constantly (p/resolved nil))))

(t/deftest progress-does-not-resurrect-a-finished-job
  (t/testing "a render reporting after the export failed cannot undo the failure"
    (t/async done
      (p/let [job (create!)
              _   (jobs/start! job)
              _   (jobs/fail! (jobs/lookup (:id job)) (ex-info "boom" {}))
              ;; `job` is the snapshot handed to the work when it started, which
              ;; is what a straggling render still holds.
              _   (jobs/progress! job 7)]
        (let [current (jobs/lookup (:id job))]
          (t/is (= "error" (:state current)))
          (t/is (= "boom" (:error current)))
          (t/is (not= 7 (:done current))))
        (jobs/release! (:id job))
        (done)))))

(t/deftest first-terminal-state-wins
  (t/testing "a failure arriving after a cancellation leaves the job cancelled"
    (t/async done
      (p/let [job (create!)
              _   (jobs/start! job)
              _   (jobs/cancel! (:id job))
              _   (jobs/fail! job (ex-info "too late" {}))]
        (let [current (jobs/lookup (:id job))]
          (t/is (= "cancelled" (:state current)))
          (t/is (nil? (:error current))))
        (jobs/release! (:id job))
        (done)))))

(t/deftest cancel-is-recorded-before-the-callbacks-run
  (t/testing "a queued job dropped by its own cancel callback still ends cancelled"
    (t/async done
      (let [seen (atom ::not-called)]
        (p/let [job (create!)
                ;; What `scheduler/drop-queued!` does: it takes the job off the
                ;; queue and releases it. Anything the lifecycle wrote after
                ;; the callbacks ran would be dropped on the floor, so by the
                ;; time one is called the record has to be terminal already.
                _   (jobs/on-cancel (:id job)
                                    (fn []
                                      (reset! seen (:state (jobs/lookup (:id job))))
                                      (jobs/release! (:id job))))
                _   (jobs/cancel! (:id job))]
          (t/is (= "cancelled" @seen))
          (t/is (nil? (jobs/lookup (:id job))))
          (done))))))

(t/deftest writes-stop-once-the-job-is-released
  (t/testing "a late write for a job the scheduler already settled is dropped"
    (t/async done
      (p/let [job (create!)
              _   (jobs/start! job)
              _   (jobs/complete! (jobs/lookup (:id job)) {:uri "http://example/x"
                                                           :filename "x.zip"
                                                           :mtype "application/zip"})
              ended (jobs/lookup (:id job))
              _   (jobs/release! (:id job))
              _   (jobs/progress! job 3)]
        (t/is (= "ended" (:state ended)))
        (t/is (nil? (jobs/lookup (:id job))))
        (done)))))
