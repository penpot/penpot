;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.scheduler-test
  "Admission control. A headless job leases one render worker for its whole run,
  so no more of them may start than there are workers."
  (:require
   [app.common.uuid :as uuid]
   [app.jobs :as jobs]
   [app.jobs.scheduler :as scheduler]
   [app.wasm.pool :as pool]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(defn- pin-capacity!
  "Fixes the worker count for the test. Returns the thunk that puts it back;
  `with-redefs` cannot be used here, since the scheduler keeps admitting jobs
  after the body of the test has returned."
  [n]
  (let [original pool/capacity]
    (set! pool/capacity (constantly n))
    (fn [] (set! pool/capacity original))))

(defn- create!
  [backend run-fn]
  (jobs/create! {:profile-id (uuid/next)
                 :cmd :export-shapes
                 :backend backend
                 :total 1
                 :name "test"
                 :resource-id (uuid/next)}
                run-fn))

(defn- gate
  "A promise and the fn that settles it, standing in for a render in flight."
  []
  (let [resolve* (volatile! nil)
        pending  (p/create (fn [resolve _] (vreset! resolve* resolve)))]
    [pending (fn [] (@resolve* nil))]))

(t/deftest headless-jobs-wait-for-a-render-worker
  (t/testing "with one worker, the second headless job stays queued until the first ends"
    (t/async done
      (let [restore!     (pin-capacity! 1)
            [render open] (gate)
            started      (atom [])]
        (p/let [job1 (create! "wasm" (fn [_] (swap! started conj :one) render))
                job2 (create! "wasm" (fn [_] (swap! started conj :two) (p/resolved nil)))]
          (let [p1 (scheduler/submit! job1)
                p2 (scheduler/submit! job2)]
            (p/do
              (p/delay 10)
              (t/is (= [:one] @started))
              (t/is (= "running" (:state (jobs/lookup (:id job1)))))
              (t/is (= "queued" (:state (jobs/lookup (:id job2)))))
              (open)
              (p/all [p1 p2])
              (t/is (= [:one :two] @started))
              (restore!)
              (done))))))))

(t/deftest a-browser-job-is-not-held-back-by-the-worker-pool
  (t/testing "the headless cap applies to headless jobs only"
    (t/async done
      (let [restore!      (pin-capacity! 1)
            [render open] (gate)
            started       (atom [])]
        (p/let [job1 (create! "wasm" (fn [_] (swap! started conj :wasm) render))
                job2 (create! "browser" (fn [_] (swap! started conj :browser) (p/resolved nil)))]
          (let [p1 (scheduler/submit! job1)
                p2 (scheduler/submit! job2)]
            (p/do
              (p/delay 10)
              (t/is (= [:wasm :browser] @started))
              (open)
              (p/all [p1 p2])
              (restore!)
              (done))))))))
