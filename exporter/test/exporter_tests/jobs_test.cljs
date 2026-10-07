;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.jobs-test
  "The local arm of the claimed job: the flag and the pieces a hard
  cancel needs. The row of the job is the backend's business; nothing
  here touches it."
  (:require
   [app.common.uuid :as uuid]
   [app.jobs :as jobs]
   [cljs.test :as t :include-macros true]))

(defn- register!
  []
  (let [job-id (uuid/next)]
    (jobs/register! job-id)
    job-id))

(t/deftest mark-cancelled-runs-the-cancel-pieces-once
  (t/testing "the signal arms, the callbacks run, and a second mark is a no-op"
    (t/async done
      (let [job-id (register!)
            seen   (atom [])]
        (jobs/on-cancel job-id (fn [] (swap! seen conj :first)))
        (jobs/on-cancel job-id (fn [] (swap! seen conj :second)))

        (t/is (true? (jobs/mark-cancelled job-id)))
        (t/is (true? (jobs/cancelled? job-id)))
        (t/is (some? (jobs/cancel-signal job-id)))
        ;; the signal is armed: a render thread reading it stops
        (let [^js signal (jobs/cancel-signal job-id)]
          (t/is (= 1 (js/Atomics.load signal 0))))

        (t/is (= [:first :second] @seen))

        ;; a settled job is a no-op: nothing marked, nobody new called
        (t/is (nil? (jobs/mark-cancelled job-id)))
        (t/is (= [:first :second] @seen))
        (done)))))

(t/deftest the-callbacks-see-the-cancelled-flag
  ;; a callback that stops the in-flight render reads the flag first: the
  ;; terminate of the worker is what a hard-cancel is for, and the pieces
  ;; below it must not race the writer
  (t/async done
    (let [job-id (register!)
          seen   (atom ::not-called)]
      (jobs/on-cancel job-id (fn [] (reset! seen (jobs/cancelled? job-id))))
      (jobs/mark-cancelled job-id)
      (t/is (true? @seen))
      (done))))

(t/deftest a-signal-created-after-the-mark-is-armed
  (t/testing "a job cancelled before its render leased a worker is
              already stoppable for the render that comes next"
    (t/async done
      (let [job-id (register!)]
        (jobs/mark-cancelled job-id)
        (let [^js signal (jobs/cancel-signal job-id)]
          (t/is (some? signal))
          (t/is (= 1 (js/Atomics.load signal 0))))
        (done)))))

(t/deftest writes-stop-once-the-job-is-released
  (t/testing "a late cancel for a settled job changes nothing, and
              release drops every piece"
    (t/async done
      (let [job-id (register!)
            seen   (atom ::not-called)]
        (jobs/on-cancel job-id (fn [] (reset! seen :callback-ran)))
        (jobs/release! job-id)

        (t/is (nil? (jobs/mark-cancelled job-id)))
        (t/is (= ::not-called @seen))
        (t/is (false? (jobs/cancelled? job-id)))
        (t/is (nil? (jobs/cancel-signal job-id)))
        (done)))))

(t/deftest an-unowned-job-is-a-no-op
  (t/testing "mark and pieces for a job this process never registered"
    (t/async done
      (let [job-id (uuid/next)]
        (t/is (nil? (jobs/mark-cancelled job-id)))
        (t/is (false? (jobs/cancelled? job-id)))
        (t/is (nil? (jobs/cancel-signal job-id)))
        (done)))))
