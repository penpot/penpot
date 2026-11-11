;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.async-test
  "Contract of the wait primitives: a wait with a signal resolves on the
  event that changes the state, not on an event-loop budget, and the
  ceiling only bounds the failure."
  (:require
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

;; The old budget: 50 event-loop turns. A delivery after it used to be lost.
(def ^:private delivery-after-ticks 100)

(defmethod t/report [::silent :fail]
  ;; The never-true case asserts that the helper records a failure: count it
  ;; in the throwaway environment, but do not print it.
  [_]
  (t/inc-report-counter! :fail))

(defn- deliver-after!
  "Calls `f` once the event loop has turned `n` times, one per macrotask."
  [n f]
  (if (pos? n)
    (js/setTimeout #(deliver-after! (dec n) f) 0)
    (f)))

(defn- turn-probe
  "Schedules one macrotask and returns a reader for whether it already ran.
  A wait that polls always consumes at least one macrotask, so a resolved
  probe still false proves the wait did not poll."
  []
  (let [ran (atom false)]
    (js/setTimeout #(reset! ran true) 0)
    (fn [] @ran)))

(defn- inc-counter
  []
  (ptk/reify ::inc-counter
    ptk/UpdateEvent
    (update [_ state]
      (update state :counter inc))))

(t/deftest ^:async a-signal-resolves-a-late-delivery
  ;; The delivery lands after the old 50-turn budget. Without a signal the
  ;; wait used to give up first; with one it resolves when the event arrives.
  (let [ready (atom false)
        sig   (rx/subject)]
    (deliver-after! delivery-after-ticks
                    #(do (reset! ready true)
                         (rx/push! sig :ready)))
    (await (async/wait-for #(true? @ready) "the late delivery" {:signal sig}))
    (t/is (true? @ready))))

(t/deftest ^:async a-store-signal-resolves-after-the-update
  ;; The store notifies its subscribers after applying the update, so the
  ;; predicate is already true when the wait re-checks it.
  (let [store (ptk/store {:state {:counter 0}})]
    (try
      (let [ran? (turn-probe)
            done (async/wait-for-store store
                                       #(= 1 (:counter @store))
                                       "the update")]
        (ptk/emit! store (inc-counter))
        (await done)
        (t/is (= 1 (:counter @store)))
        (t/is (false? (ran?)) "resolved on the event, not on a poll"))
      (finally
        (rx/dispose! store)))))

(t/deftest ^:async a-late-predicate-without-signal
  ;; Same late delivery, with nothing to listen to: the ceiling has to be
  ;; time, not turns, for the wait to survive it.
  (let [ready (atom false)]
    (deliver-after! delivery-after-ticks #(reset! ready true))
    (await (async/wait-for #(true? @ready) "the late delivery"))
    (t/is (true? @ready))))

(t/deftest ^:async a-never-true-predicate-records-one-failure
  (let [failures (atom nil)
        started  (js/Date.now)]
    (binding [t/*current-env* (t/empty-env ::silent)]
      (await (async/wait-for (constantly false) "the impossible" {:timeout-ms 100}))
      (reset! failures (get-in (t/get-current-env) [:report-counters :fail])))
    (t/is (= 1 @failures) "one failure, not one per turn")
    (t/is (< (- (js/Date.now) started) 1000) "bounded by the ceiling")))

(t/deftest ^:async an-immediate-predicate-does-not-yield
  (let [ran? (turn-probe)]
    (await (async/wait-for (constantly true) "the immediate"))
    (t/is (false? (ran?)) "an already-true predicate does not wait")))

(t/deftest ^:async a-resolved-wait-disposes-its-signal
  (let [evals (atom 0)
        sig   (rx/subject)
        pred  #(do (swap! evals inc) (>= @evals 2))
        done  (async/wait-for pred "the second check" {:signal sig})]
    (rx/push! sig :first)
    (await done)
    (let [before @evals]
      (rx/push! sig :second)
      (t/is (= before @evals) "a resolved wait stops listening"))))
