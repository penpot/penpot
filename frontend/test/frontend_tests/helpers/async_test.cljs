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
   [app.common.time :as ct]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

;; The old budget: 50 event-loop turns. A delivery after it used to be lost.
(def ^:private delivery-after-ticks 100)

(def ^:private reported
  "Messages of the failures recorded in a throwaway test environment, so the
  failure reasons can be asserted without failing the run."
  (atom []))

(defmethod t/report [::silent :fail]
  ;; The never-true case asserts that the helper records a failure: count it
  ;; in the throwaway environment and keep the message, but do not print it.
  [m]
  (swap! reported conj (:message m))
  (t/inc-report-counter! :fail))

(defn- deliver-after!
  "Calls `f` once the event loop has turned `n` times, one per macrotask."
  [n f]
  (if (pos? n)
    (js/setTimeout #(deliver-after! (dec n) f) 0)
    (f)))

(defn- turn-probe
  "Schedules one macrotask and returns `:ran?`, a reader for whether it
  already ran, and `:stop`, which cancels it. A wait that polls always
  consumes at least one macrotask, so a resolved probe still false proves the
  wait did not poll."
  []
  (let [ran (atom false)
        id  (js/setTimeout #(reset! ran true) 0)]
    {:ran? (fn [] @ran)
     :stop (fn [] (js/clearTimeout id))}))

(defn- inc-counter
  []
  (ptk/reify ::inc-counter
    ptk/UpdateEvent
    (update [_ state]
      (update state :counter inc))))

(t/deftest ^:async a-signal-resolves-a-late-delivery
  ;; The delivery lands after the old 50-turn budget. Without a signal the
  ;; wait used to give up first; with one it resolves when the event arrives.
  ;; Counting the predicate evaluations separates the two paths: a poll
  ;; evaluates it once per turn, a signal exactly twice.
  (let [ready (atom false)
        evals (atom 0)
        sig   (rx/subject)]
    (deliver-after! delivery-after-ticks
                    #(do (reset! ready true)
                         (rx/push! sig :ready)))
    (await (async/wait-for #(do (swap! evals inc) (true? @ready))
                           "the late delivery"
                           {:signal sig}))
    (t/is (= 2 @evals) "one initial check and one signal emission")
    (t/is (true? @ready))))

(t/deftest ^:async a-store-signal-resolves-after-the-update
  ;; The store notifies its subscribers after applying the update, so the
  ;; predicate is already true when the wait re-checks it.
  (let [store (ptk/store {:state {:counter 0}})
        {:keys [ran? stop]} (turn-probe)]
    (try
      (let [done (async/wait-for-store store
                                       #(= 1 (:counter @store))
                                       "the update")]
        (ptk/emit! store (inc-counter))
        (await done)
        (t/is (= 1 (:counter @store)))
        (t/is (false? (ran?)) "resolved on the event, not on a poll"))
      (finally
        (stop)
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
        started  (inst-ms (ct/now))]
    (binding [t/*current-env* (t/empty-env ::silent)]
      (await (async/wait-for (constantly false) "the impossible" {:timeout-ms 100}))
      (reset! failures (get-in (t/get-current-env) [:report-counters :fail])))
    (t/is (= 1 @failures) "one failure, not one per turn")
    ;; A safety net at 10x the 100 ms ceiling, so a hung wait fails instead of
    ;; stalling the run; it does not measure precision.
    (t/is (< (- (inst-ms (ct/now)) started) 1000) "bounded by the ceiling")))

(t/deftest ^:async an-immediate-predicate-does-not-yield
  (let [{:keys [ran? stop]} (turn-probe)]
    (try
      (await (async/wait-for (constantly true) "the immediate"))
      (t/is (false? (ran?)) "an already-true predicate does not wait")
      (finally
        (stop)))))

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

(t/deftest ^:async a-signal-that-ends-records-why
  (reset! reported [])
  (let [sig (rx/subject)]
    (binding [t/*current-env* (t/empty-env ::silent)]
      (let [done (async/wait-for (constantly false) "the closed signal" {:signal sig})]
        (rx/end! sig)
        (await done))))
  (t/is (= ["Signal ended while waiting for: the closed signal"] @reported)
        "an ended signal is not reported as a timeout"))

(t/deftest ^:async a-signal-that-errors-records-why
  (reset! reported [])
  (let [sig (rx/subject)]
    (binding [t/*current-env* (t/empty-env ::silent)]
      (let [done (async/wait-for (constantly false) "the broken signal" {:signal sig})]
        (rx/error! sig (ex-info "boom" {:where :test}))
        (await done))))
  (t/is (some #(str/includes? % "Signal failed while waiting for: the broken signal")
              @reported)
        "an errored signal is not reported as a timeout"))

(t/deftest ^:async a-signal-error-bounds-a-long-cause
  (reset! reported [])
  (let [sig   (rx/subject)
        cause (apply str (repeat 600 "x"))]
    (binding [t/*current-env* (t/empty-env ::silent)]
      (let [done (async/wait-for (constantly false) "the long cause" {:signal sig})]
        (rx/error! sig cause)
        (await done))))
  (let [text (first @reported)]
    (t/is (str/includes? text "Signal failed while waiting for: the long cause"))
    (t/is (<= (count text) 600) "the cause text is bounded")))
