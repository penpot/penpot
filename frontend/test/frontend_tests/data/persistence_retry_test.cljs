;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.persistence-retry-test
  "Retry-episode tests for the file persistence save pipeline
  (`app.main.data.persistence`).

  Covers the transient-failure retry contract: transient failures resend
  the head commit with backoff, the budget bounds the episode, guards
  (in-flight, stale token, online resume) prevent double-sends, and hung
  resends across governor windows still earn their own stall report."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.persistence :as dps]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- local-commit
  "Builds a synthetic local commit event for `file-id`: a page edit as redo,
  no undo, from the `:local` source."
  [file-id]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :redo-changes [{:type :mod-page :id (uuid/next) :name "Edited"}]
                   :undo-changes []}))

(defn- with-persistence
  "Async fixture for the persistence tests: mocks the transport, flash and
  the leave-page task set (`:tasks`), and runs `f` with persistence
  initialized.

  Evaluates to a promise resolving once `f` settles and teardown completes;
  `await` it from an `^:async` test.

  By default the transport responds through the `:response` subject. Pass a
  `respond` function (`(fn [cmd params] observable)`) to drive the transport
  response directly instead. Delivery through the subject is asynchronous
  (`observe-on :async`): await each effect — via `wait-for` — before
  asserting it."
  [f & [respond]]
  (let [file-id  (uuid/next)
        response (rx/subject)
        failures (atom [])
        requests (atom [])
        tasks    (atom #{})
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
    (mock/with-mocks*
      {rp/cmd!          (mock/stub (fn [cmd params]
                                     (swap! requests conj [cmd params])
                                     (if respond
                                       (respond cmd params)
                                       (->> response (rx/take 1) (rx/observe-on :async)))))
       errors/flash     (fn [& {:keys [cause]}]
                          (swap! failures conj cause))
       st/ongoing-tasks tasks}
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (await (f {:file-id file-id :response response :failures failures
                   :requests requests :store store :tasks tasks}))
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

(defn- instant-backoff
  "Timer double: the backoff delays fire at once and the slow cycle never
  does, so a failure that outlasts the backoff settles in `:error`."
  [ms]
  (if (= ms dps/slow-retry-delay-ms)
    (rx/subject)
    (rx/of :tick)))

(defn- check-failed-save-response
  "Feeds `result` as the transport response and asserts the commit stays
  queued as a failed save instead of being treated as persisted. Retry
  timers fire instantly (stubbed `rx/timer`, slow cycle held), so a
  permanently bad answer exhausts the 3-retry budget and lands terminal: `:error` carrying
  `:invalid-save-response`, queue intact, exactly 4 sends."
  [result]
  (with-persistence
    (^:async fn [{:keys [file-id requests store]}]
      (await
       (mock/with-mocks*
         {rx/timer (mock/stub instant-backoff)}
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                      (= :invalid-save-response
                                         (get-in @store [:persistence :error :code]))
                                      (= 1 (count (get-in @store [:persistence :queue]))))
                                "invalid response fails the save"))
         (t/is (= :error (get-in @store [:persistence :status])))
         (t/is (= :invalid-save-response (get-in @store [:persistence :error :code])))
         (t/is (= 1 (count (get-in @store [:persistence :queue]))))
         (t/is (= 4 (count @requests)) "initial send plus 3 retries"))))
    (fn [_ _] result)))

;; Variant: an empty answer.
(t/deftest ^:async empty-save-response-preserves-the-queue-as-failed
  (await (check-failed-save-response (rx/empty))))

;; Variant: a nil answer.
(t/deftest ^:async nil-save-response-preserves-the-queue-as-failed
  (await (check-failed-save-response (rx/of nil))))

;; Variant: a negative revision answer.
(t/deftest ^:async invalid-revision-save-response-preserves-the-queue-as-failed
  (await (check-failed-save-response (rx/of {:revn -1}))))

;; Retry tests.
;;
;; Production contract under test: a transient save failure keeps the head
;; commit queued under `:retrying` and resends it with backoff (2s / 8s /
;; 20s, then terminal). Retry timers are stubbed — instant when the test
;; drives completion, manually fired when it scripts the race — and every
;; stub records its delays so the schedule itself is asserted.
;;
;; Same async pattern as above: triggers stay bare, every assert block is
;; preceded by `wait-for` on its leading signal.

;; Scenario: the first send fails transiently, the retry succeeds. Both
;; sends carry the same `:commit-id`; the save lands with an empty queue
;; and the episode metadata is cleared. Proves: one transient failure
;; retries the same commit instead of erroring.
(t/deftest ^:async transient-failure-retries-and-saves
  (let [calls (atom 0)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub instant-backoff)}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "retry saves the file"))
            (t/is (= :saved (get-in @store [:persistence :status])))
            (t/is (empty? (get-in @store [:persistence :queue])))
            (t/is (= 2 (count @requests)) "failed send plus one retry")
            (t/is (apply = (map (comp :commit-id second) @requests))
                  "both sends carry the same commit id")
            (t/is (nil? (get-in @store [:persistence :attempts]))
                  "the episode metadata is cleared on success"))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn 1})))))))

(defn- check-gateway-response-retries
  "Answers the first send with a proxy error page for `status`, built by the
  real `rp/handle-response`, and the retry with a valid revision. The save
  must take the retry path and land, never halt in `:error`."
  [status]
  (let [calls (atom 0)]
    (with-persistence
      (^:async fn [{:keys [file-id failures requests store]}]
        (await
         (mock/with-mocks*
           {rx/timer (mock/stub instant-backoff)}
           (ptk/emit! store (local-commit file-id) ::dps/force-persist)
           (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                        (empty? (get-in @store [:persistence :queue])))
                                  (str "a " status " is retried and the save lands")))
           (t/is (= 2 (count @requests)) "the proxy error plus one retry")
           (t/is (empty? @failures) "a retried proxy error never reaches the failure toast"))))
      (fn [_ _]
        (if (= 1 (swap! calls inc))
          (rp/handle-response {:status status
                               :headers {"content-type" "text/html"}
                               :body "<html><body>Gateway Timeout</body></html>"
                               :uri "https://penpot.example.com/api/main/methods/update-file"})
          (rx/of {:revn 1}))))))

;; Variant: the proxy in front of the backend timed out.
(t/deftest ^:async gateway-timeout-save-is-retried
  (await (check-gateway-response-retries 504)))

;; Variant: Cloudflare gave up waiting for the origin.
(t/deftest ^:async cloudflare-origin-timeout-save-is-retried
  (await (check-gateway-response-retries 524)))

(defn- check-busy-server-response-retries
  "Answers the first send with `response`, a refusal the backend wrote
  because it was busy, built by the real `rp/handle-response`, and the retry
  with a valid revision. The save was never applied, so it must take the
  retry path and land, never halt in `:error`."
  [response]
  (let [calls (atom 0)]
    (with-persistence
      (^:async fn [{:keys [file-id failures requests store]}]
        (await
         (mock/with-mocks*
           {rx/timer (mock/stub instant-backoff)}
           (ptk/emit! store (local-commit file-id) ::dps/force-persist)
           (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                        (empty? (get-in @store [:persistence :queue])))
                                  (str "a busy " (:status response) " is retried and the save lands")))
           (t/is (= 2 (count @requests)) "the busy answer plus one retry")
           (t/is (empty? @failures) "a retried busy answer never reaches the failure toast"))))
      (fn [_ _]
        (if (= 1 (swap! calls inc))
          (rp/handle-response (assoc response :uri "https://penpot.example.com/api/main/methods/update-file"))
          (rx/of {:revn 1}))))))

;; Variant: the save waited too long for a concurrency slot (no body).
(t/deftest ^:async concurrency-limit-save-is-retried
  (await (check-busy-server-response-retries {:status 429 :body nil})))

;; Variant: the database cancelled the save while it waited for the file lock.
(t/deftest ^:async statement-timeout-save-is-retried
  (await (check-busy-server-response-retries
          {:status 504
           :body {:type :server-error
                  :code :statement-timeout
                  :hint "The operation took too long and was cancelled"}})))

;; Variant: the database ended the save's transaction after it sat idle.
(t/deftest ^:async idle-transaction-timeout-save-is-retried
  (await (check-busy-server-response-retries
          {:status 504
           :body {:type :server-error
                  :code :idle-in-transaction-timeout
                  :hint "The transaction was idle too long and was cancelled"}})))

;; Scenario: the backend fails the save for any other reason. Proves: only
;; the busy-server codes retry; a server defect stops saving at once.
(t/deftest ^:async other-server-errors-stay-terminal
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       (await
        (mock/with-mocks*
          {rx/timer (mock/stub instant-backoff)}
          (ptk/emit! store (local-commit file-id) ::dps/force-persist)
          (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                 "the save fails"))
          (t/is (= :server-error (get-in @store [:persistence :error :cause-type])))
          (t/is (= 1 (count @requests)) "no retry"))))
     (fn [_ _]
       (rp/handle-response {:status 500
                            :body {:type :server-error :code :unhandled}
                            :uri "https://penpot.example.com/api/main/methods/update-file"})))))

;; Scenario: every send fails transiently. The 2s / 8s / 20s retries fire
;; and then the failure falls through to the exact terminal path: `:error`
;; carrying the cause, queue intact, one flash. Proves: the budget bounds
;; the episode (4 sends) and exhaustion is today's terminal behavior.
(t/deftest ^:async retry-exhaustion-goes-terminal
  (let [delays (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id failures requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [ms] (swap! delays conj ms) (instant-backoff ms)))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                         (= 4 (count @requests)))
                                   "retries exhaust into terminal error"))
            (t/is (= :error (get-in @store [:persistence :status])))
            (t/is (= :save-failed (get-in @store [:persistence :error :code])))
            (t/is (= 1 (count (get-in @store [:persistence :queue]))))
            (t/is (= [2000 8000 20000 dps/slow-retry-delay-ms] @delays)
                  "the backoff schedule fires in order, then the slow cycle is armed")
            (t/is (= 1 (count @failures)) "exhaustion flashes exactly once"))))
       (fn [_ _] (rx/throw (ex-info "offline" {:type :offline})))))))

;; Scenario: a retry timer fires while the replacement request is still in
;; flight. The timer is driven by hand: fail the first send, let the
;; online signal resend the head (hanging), then fire the pending timer.
;; Proves: the firing is skipped instead of double-sending the same commit.
(t/deftest ^:async retry-skips-resend-while-previous-request-is-in-flight
  (let [calls   (atom 0)
        delays  (atom [])
        timer-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [ms] (swap! delays conj ms) timer-s))}
            ;; Phase 1 — first send fails transiently; the retry pends on
            ;; the hand-fired timer.
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "transient failure retries"))
            (t/is (= 1 (count @requests)))
            ;; Phase 2 — the online signal resends the head under the live
            ;; episode (status stays :retrying), hanging in flight.
            (ptk/emit! store (#'dps/resume-on-online))
            (await (async/wait-for #(= 2 (count @requests)) "online resends"))
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (t/is (= 1 (get-in @store [:persistence :attempts])))
            ;; Phase 3 — the pending timer fires into the in-flight request:
            ;; skipped, never a third send.
            (rx/push! timer-s :tick)
            (await (async/settle))
            (t/is (= 2 (count @requests)) "no double-send while in flight")
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (t/is (= 1 (count (get-in @store [:persistence :queue]))))
            (rx/end! timer-s))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/subject)))))))

;; Scenario: a persist-commit arrives with a superseded episode token after
;; a transient failure. Without the token guard it would send outside the
;; backoff schedule; with it, nothing happens. Proves: stale retry timers
;; stay silent.
(t/deftest ^:async stale-retry-token-stays-silent
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       (await
        (mock/with-mocks*
          {rx/timer (mock/stub (fn [_] (rx/subject)))}
          (ptk/emit! store (local-commit file-id) ::dps/force-persist)
          (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                 "transient failure retries"))
          (t/is (= 1 (count @requests)))
          (ptk/emit! store (#'dps/persist-commit
                            (peek (get-in @store [:persistence :queue]))
                            {:token (uuid/next)}))
          (await (async/settle))
          (t/is (= 1 (count @requests)) "stale token sends nothing")
          (t/is (= :retrying (get-in @store [:persistence :status])))
          (t/is (nil? (get-in @store [:persistence :error]))))))
     (fn [_ _] (rx/throw (ex-info "offline" {:type :offline}))))))

;; Scenario: a new edit lands while the episode waits on its backoff timer.
;; The edit only joins the queue: nothing is sent and no attempt is spent
;; until the timer fires. The retry then saves the head and the runner
;; sends the new edit after it. Proves: edits during an episode never
;; bypass the backoff nor consume the retry budget.
(t/deftest ^:async new-edit-during-retry-waits-for-the-backoff
  (let [calls   (atom 0)
        delays  (atom [])
        timer-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [ms] (swap! delays conj ms) timer-s))}
            ;; Phase 1 — first send fails transiently; the retry pends on
            ;; the hand-fired timer.
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "transient failure retries"))
            (t/is (= 1 (count @requests)))
            ;; Phase 2 — a new edit joins the queue without resending.
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 2 (count (get-in @store [:persistence :queue])))
                                   "second edit is queued"))
            (await (async/settle))
            (t/is (= 1 (count @requests)) "the edit does not resend the head")
            (t/is (= 1 (get-in @store [:persistence :attempts])) "no attempt is spent")
            (t/is (= [2000] @delays) "the pending backoff is kept")
            (t/is (= :retrying (get-in @store [:persistence :status])))
            ;; Phase 3 — the timer fires: the head saves, then the new edit.
            (rx/push! timer-s :tick)
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "both edits save"))
            (t/is (= 3 (count @requests)) "failed send, retry, then the new edit")
            (let [[first-id retry-id edit-id] (map (comp :commit-id second) @requests)]
              (t/is (= first-id retry-id) "the retry resends the head")
              (t/is (not= retry-id edit-id) "the new edit is sent after the head"))
            (rx/end! timer-s))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn @calls})))))))

;; Scenario: a transient failure raises the reconnect notice; the retry
;; then succeeds. The timer is driven by hand so the test observes the
;; episode mid-flight: one visible notice (the store holds a single toast,
;; so episodes never stack), gone once the save lands. Proves: exactly one
;; notice per episode, hidden on recovery.
(t/deftest ^:async retry-shows-a-single-reconnect-notice-until-saved
  (let [calls   (atom 0)
        timer-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [_] timer-s))
             i18n/tr  (mock/stub #(str "translated:" %))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (get-in @store [:persistence :attempts]))
                                   "first attempt fails into retrying"))
            (let [notice (get @store :notification)]
              (t/is (map? notice) "a single notice is visible, never stacked"))
            (t/is (= :persistence-reconnecting (get-in @store [:notification :tag])))
            (t/is (= "translated:errors.save-retrying"
                     (get-in @store [:notification :content])))
            (rx/push! timer-s :tick)
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (nil? (get @store :notification)))
                                   "save hides the notice"))
            (t/is (= :saved (get-in @store [:persistence :status])))
            (t/is (nil? (get @store :notification)) "recovery is silent")
            (rx/end! timer-s))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn 1})))))))

;; Scenario: every send fails transiently with instant timers. Exhaustion
;; takes the terminal path, which hides the reconnect notice explicitly
;; before flashing. Proves: no stale notice survives a terminal failure.
(t/deftest ^:async retry-exhaustion-hides-the-reconnect-notice
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (await
        (mock/with-mocks*
          {rx/timer (mock/stub instant-backoff)}
          (ptk/emit! store (local-commit file-id) ::dps/force-persist)
          (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                 "retries exhaust into terminal error"))
          (t/is (= :error (get-in @store [:persistence :status])))
          (t/is (nil? (get @store :notification))
                "the terminal path hides the reconnect notice"))))
     (fn [_ _] (rx/throw (ex-info "offline" {:type :offline}))))))

;; Scenario: a retrying episode waits on its backoff timer when the browser
;; reports connectivity back. The pending timer never fires, so only the
;; online signal can resume: the head resends under the live episode and,
;; once answered, the file saves. A second online signal with an empty
;; queue sends nothing. Proves: reconnect resumes promptly without
;; terminal failures staying terminal.
(t/deftest ^:async online-event-resumes-a-retrying-episode
  (let [calls    (atom 0)
        timer-s  (rx/subject)
        second-s (atom nil)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [_] timer-s))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "transient failure retries"))
            (t/is (= 1 (count @requests)))
            (ptk/emit! store (#'dps/resume-on-online))
            (await (async/wait-for #(= 2 (count @requests)) "online resends"))
            (t/is (= 2 (count @requests)))
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (t/is (= 1 (get-in @store [:persistence :attempts])))
            (rx/push! @second-s {:revn 1})
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "resumed save lands"))
            (t/is (= :saved (get-in @store [:persistence :status])))
            (ptk/emit! store (#'dps/resume-on-online))
            (await (async/settle))
            (t/is (= 2 (count @requests)) "online with an empty queue sends nothing")
            (rx/end! timer-s))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (let [s (rx/subject)] (reset! second-s s) s)))))))

(defn- audit-events
  "The audit events (report emissions) out of everything collected through
  the `st/emit!` double. Discriminates by `ptk/type`, which is total (never
  throws)."
  [events]
  (filter #(= ::ev/event (ptk/type %)) events))

;; Scenario: the `online` event arrives while a retry request is already in
;; flight. The first send fails transiently, the retry timer resends
;; (hanging), then connectivity reports back mid-flight. Proves: the online
;; entry point honors the in-flight guard instead of double-sending — the
;; same guard as the retry-timer path, through `resume-on-online` ->
;; `run-persistence-task`.
(t/deftest ^:async online-event-does-not-resend-an-in-flight-request
  (let [calls   (atom 0)
        timer-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [_] timer-s))}
            ;; Phase 1 — first send fails transiently; the retry pends on
            ;; the hand-fired timer.
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "transient failure retries"))
            (t/is (= 1 (count @requests)))
            ;; Phase 2 — the retry timer fires and resends the head,
            ;; hanging in flight.
            (rx/push! timer-s :tick)
            (await (async/wait-for #(= 2 (count @requests)) "retry resends"))
            (t/is (= :retrying (get-in @store [:persistence :status])))
            ;; Phase 3 — online arrives mid-flight: silent, never a third send.
            (ptk/emit! store (#'dps/resume-on-online))
            (await (async/settle))
            (t/is (= 2 (count @requests)) "online sends nothing while in flight")
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (rx/end! timer-s))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/subject)))))))

;; Scenario: the same file stalls twice, more than a governor window apart
;; (scripted clock). Each stall emits its own report: the second is a new
;; episode, not a suppressed duplicate of the first. Proves: identical
;; stall causes coalesce only inside the window.
;;
;; Note: unlike `with-watchdog`, the real `submit-report` runs here (only
;; `generate-report` stays doubled), so the test exercises the governor,
;; the thunk contract and the emit path end to end.
(t/deftest ^:async repeated-stalls-across-windows-emit-each-report
  (let [clock    (atom 0)
        ticks    (rx/subject)
        response (rx/subject)
        requests (atom [])
        causes   (atom [])
        events   (atom [])
        file-id  (uuid/next)
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
    (errors/reset-report-governor!)
    (await
     (mock/with-mocks*
       {ct/now                 (mock/stub #(ct/inst @clock))
        rx/interval            (mock/stub (fn [_] ticks))
        rp/cmd!                (mock/stub (fn [cmd params]
                                            (swap! requests conj {:cmd cmd :params params})
                                            (->> response (rx/take 1) (rx/observe-on :async))))
        st/state               store
        errors/generate-report (fn [cause & _]
                                 (swap! causes conj cause)
                                 "report")
        st/emit!               (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href    (constantly "https://penpot.example.com/#/workspace")
        st/ongoing-tasks       (atom #{})}
       (try
         (ptk/emit! store (dps/initialize-persistence))
         ;; Phase 1 — the first stall reports once.
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (reset! clock 300001)
         (rx/push! ticks :tick)
         (await (async/wait-for #(= 1 (count (audit-events @events)))
                                "first stall reports"))
         (t/is (= 1 (count @requests)))
         ;; Phase 2 — the save lands, resetting the stall clock; a new
         ;; edit stalls again, over a governor window later.
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "first save lands"))
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "second edit is sent"))
         (reset! clock (+ 300001 errors/report-window-ms 300001))
         (rx/push! ticks :tick)
         (await (async/wait-for #(= 2 (count (audit-events @events)))
                                "second stall reports"))
         (let [audits (audit-events @events)]
           (t/is (= 2 (count audits)))
           (t/is (= 2 (count @causes)) "each granted stall builds its report")
           (t/is (every? #(= 1 (:occurrences (deref %))) audits)
                 "each stall is a fresh emission, never a coalesced repeat"))
         (finally
           (rx/dispose! store)
           (rx/end! ticks)
           (rx/end! response)))))))

;; Scenario: the first send never answers. The request timeout fails it as
;; a transient error, and the retry resends the same commit and saves. The
;; timeout is shortened by wrapping the real operator. Proves: a hung save
;; does not block the queue.
(t/deftest ^:async hung-save-request-times-out-and-retries
  (let [calls    (atom 0)
        timeouts (atom [])
        timeout  rx/timeout]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer   (mock/stub instant-backoff)
             rx/timeout (mock/stub (fn [ms with ob]
                                     (swap! timeouts conj ms)
                                     (timeout 0 with ob)))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "the retry saves the file"))
            (t/is (= 2 (count @requests)) "hung send plus one retry")
            (t/is (apply = (map (comp :commit-id second) @requests))
                  "both sends carry the same commit id")
            (t/is (= [420000 420000] @timeouts) "every send gets the 7 minute timeout"))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/subject)
           (rx/of {:revn 1})))))))

;; ---------------------------------------------------------------------------
;; slow cycle
;; ---------------------------------------------------------------------------

(defn- slow-cycle-timer
  "Timer double: the backoff delays fire at once, and every slow cycle waits
  on a subject collected in `cycles`, so the test fires them one by one."
  [cycles]
  (fn [ms]
    (if (= ms dps/slow-retry-delay-ms)
      (let [cycle-s (rx/subject)]
        (swap! cycles conj cycle-s)
        cycle-s)
      (rx/of :tick))))

;; Scenario: the backoff is spent while the network is down. The queue keeps
;; trying on the slow cycle, one send per cycle, without warning the user
;; again, and saves once the network is back. Proves: a session outlasting
;; the backoff still recovers on its own.
(t/deftest ^:async a-spent-backoff-keeps-trying-on-a-slow-cycle
  (let [calls  (atom 0)
        cycles (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id failures requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (slow-cycle-timer cycles))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                         (= 1 (count @cycles)))
                                   "the spent backoff arms the slow cycle"))
            (t/is (= 4 (count @requests)))
            (t/is (= 1 (count @failures)))

            (rx/push! (last @cycles) :tick)
            (await (async/wait-for #(= 2 (count @cycles))
                                   "a failed slow attempt arms the next cycle"))
            (t/is (= 5 (count @requests)) "a slow cycle sends once")
            (t/is (= :error (get-in @store [:persistence :status])))
            (t/is (= 1 (count @failures)) "the user is not warned again")

            ;; The warning the first failure left on screen.
            (ptk/emit! store #(assoc % :notification {:tag errors/persistence-failed-tag
                                                      :level :error}))
            (rx/push! (last @cycles) :tick)
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "the slow attempt saves"))
            (t/is (= 6 (count @requests)))
            (t/is (apply = (map (comp :commit-id second) @requests))
                  "every attempt carries the same commit id")
            (t/is (nil? (get-in @store [:persistence :recovering])))
            (t/is (nil? (get-in @store [:persistence :failing-since])))
            (t/is (nil? (get @store :notification))
                  "the failure warning goes away once the save lands"))))
       (fn [_ _]
         (if (< (swap! calls inc) 6)
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn 1})))))))

;; Scenario: a gateway error outlasts the backoff, then the proxy recovers.
;; Proves: each gateway status takes the slow cycle and saves the held edit.
(t/deftest ^:async a-gateway-error-arms-the-slow-cycle
  (doseq [status [504 520 521 522 523 524]]
    (let [calls  (atom 0)
          cycles (atom [])]
      (await
       (with-persistence
         (^:async fn [{:keys [file-id requests store]}]
           (await
            (mock/with-mocks*
              {rx/timer (mock/stub (slow-cycle-timer cycles))}
              (ptk/emit! store (local-commit file-id) ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @cycles))
                                     (str status " arms the slow cycle")))
              (t/is (= 4 (count @requests)))
              (t/is (= :gateway-error (get-in @store [:persistence :error :cause-type])))
              (t/is (= 1 (count (get-in @store [:persistence :queue]))))

              (rx/push! (last @cycles) :tick)
              (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                           (empty? (get-in @store [:persistence :queue])))
                                     (str status " recovers on the slow cycle")))
              (t/is (= 5 (count @requests)))
              (t/is (apply = (map (comp :commit-id second) @requests))
                    "the slow cycle saves the held commit"))))
         (fn [_ _]
           (if (<= (swap! calls inc) 4)
             (rp/handle-response {:status status :body "<html>Gateway error</html>" :uri "/api"})
             (rx/of {:revn 1}))))))))

;; Scenario: a save fails for a reason no attempt can get past. Proves: only
;; transport failures arm the slow cycle.
(t/deftest ^:async a-terminal-failure-does-not-arm-the-slow-cycle
  (let [cycles (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (slow-cycle-timer cycles))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                   "the save fails"))
            (await (async/settle))
            (t/is (= 1 (count @requests)))
            (t/is (empty? @cycles)))))
       (fn [_ _] (rx/throw (ex-info "Validation failed" {:type :validation})))))))

;; Scenario: a gateway answers 504 only after its own timeout, while the
;; backend may still be applying the save. Proves: slow failures below the
;; limit still arm the slow cycle; at the limit the save stops being sent,
;; and opening a file releases it instead of sending it again.
(t/deftest ^:async slow-failures-stop-the-slow-cycle-at-the-limit
  (doseq [[slow-count armed?] [[(dec dps/max-slow-failures) true]
                               [dps/max-slow-failures false]]]
    (let [clock  (atom 0)
          calls  (atom 0)
          cycles (atom [])]
      (await
       (with-persistence
         (^:async fn [{:keys [file-id requests store]}]
           (await
            (mock/with-mocks*
              {rx/timer (mock/stub (slow-cycle-timer cycles))
               ct/now   (mock/stub #(ct/inst @clock))}
              (ptk/emit! store (local-commit file-id) ::dps/force-persist)
              (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                     (str slow-count " slow failures spend the backoff")))
              (await (async/settle))
              (t/is (= 4 (count @requests)))
              (t/is (= slow-count (get-in @store [:persistence :slow-failures])))
              (t/is (= (if armed? 1 0) (count @cycles))
                    (str slow-count " slow failures " (if armed? "arm" "do not arm") " the slow cycle"))

              (when-not armed?
                (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
                (await (async/wait-for #(empty? (get-in @store [:persistence :queue]))
                                       "recovery releases the held edit"))
                (t/is (= 4 (count @requests)) "the released edit is not sent again")))))
         (fn [_ _]
           (when (<= (swap! calls inc) slow-count)
             (swap! clock + dps/slow-failure-ms))
           (rp/handle-response {:status 504 :body "<html>Gateway timeout</html>" :uri "/api"})))))))

;; Scenario: the slow cycle is still failing when the retry window closes.
;; Proves: the cycle gives up, keeping the edits queued.
(t/deftest ^:async the-slow-cycle-stops-when-the-retry-window-closes
  (let [clock  (atom 0)
        cycles (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (slow-cycle-timer cycles))
             ct/now   (mock/stub #(ct/inst @clock))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @cycles)) "the slow cycle is armed"))

            (reset! clock (+ dps/retry-give-up-ms 1000))
            (rx/push! (last @cycles) :tick)
            (await (async/wait-for #(= 5 (count @requests)) "the last slow attempt"))
            (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                   "the attempt fails"))
            (await (async/settle))
            (t/is (= 1 (count @cycles)) "no further cycle is armed")
            (t/is (= 1 (count (get-in @store [:persistence :queue])))))))
       (fn [_ _] (rx/throw (ex-info "offline" {:type :offline})))))))

;; Scenario: the backoff is spent and the user leaves the workspace, which
;; stops the slow cycle, then opens a file again. Recovery sends the queue
;; once more instead of releasing it, and it saves. Proves: edits held by a
;; transport failure are never dropped by recovery.
(t/deftest ^:async recovery-resends-a-transport-failure-instead-of-releasing-it
  (let [calls  (atom 0)
        cycles (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (slow-cycle-timer cycles))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @cycles)) "the slow cycle is armed"))
            (ptk/emit! store (ptk/data-event :app.main.data.workspace/finalize-workspace))
            (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "recovery saves the held edit"))
            (t/is (= 5 (count @requests)))
            (t/is (apply = (map (comp :commit-id second) @requests))
                  "recovery sends the same commit, not a released queue"))))
       (fn [_ _]
         (if (< (swap! calls inc) 5)
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn 1})))))))

;; Leaving the workspace tests.

;; Scenario: a save fails on a transport error and the user leaves the
;; workspace before the backoff fires. The pending retry stays silent, and
;; opening a file sends the held edit again. Proves: the backoff does not
;; run outside the workspace and resumes once a file is opened.
(t/deftest ^:async leaving-the-workspace-stops-the-backoff
  (let [calls  (atom 0)
        timers (atom [])]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [_]
                                   (let [timer-s (rx/subject)]
                                     (swap! timers conj timer-s)
                                     timer-s)))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @timers)) "the failure schedules a retry"))

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/finalize-workspace))
            (await (async/wait-for #(get-in @store [:persistence :suspended])
                                   "leaving the workspace suspends retries"))
            (rx/push! (first @timers) :tick)
            (await (async/settle))
            (t/is (= 1 (count @requests)) "the pending retry stays silent")
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (t/is (= 1 (count (get-in @store [:persistence :queue]))))

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/workspace-initialized))
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "opening a file saves the held edit"))
            (t/is (= 2 (count @requests)))
            (t/is (apply = (map (comp :commit-id second) @requests))
                  "the same commit is sent again")
            (t/is (nil? (get-in @store [:persistence :suspended]))))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (rx/throw (ex-info "offline" {:type :offline}))
           (rx/of {:revn 1})))))))

;; Scenario: the user leaves the workspace while a save is in flight, and the
;; save then fails on a transport error. No backoff is scheduled, the online
;; event does not resend, and opening a file does. Proves: a failure that
;; lands outside the workspace waits for the user instead of retrying there.
(t/deftest ^:async a-failure-after-leaving-the-workspace-arms-no-retry
  (let [calls   (atom 0)
        timers  (atom [])
        first-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [ms] (swap! timers conj ms) (rx/subject)))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the edit is sent"))

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/finalize-workspace))
            (await (async/wait-for #(get-in @store [:persistence :suspended])
                                   "leaving the workspace suspends retries"))
            (rx/error! first-s (ex-info "offline" {:type :offline}))
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "the failure keeps the edit queued"))
            (await (async/settle))
            (t/is (empty? @timers) "no backoff is scheduled")

            (ptk/emit! store (#'dps/resume-on-online))
            (await (async/settle))
            (t/is (= 1 (count @requests)) "the online event does not resend")

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/workspace-initialized))
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "opening a file saves the held edit"))
            (t/is (= 2 (count @requests))))))
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           first-s
           (rx/of {:revn 1})))))))

;; Scenario: the last backoff attempt is in flight when the user leaves the
;; workspace, and it fails. The queue halts without arming the slow cycle,
;; and opening a file sends it once more. Proves: the slow cycle never
;; starts outside the workspace.
(t/deftest ^:async a-spent-backoff-outside-the-workspace-arms-no-slow-cycle
  (let [calls  (atom 0)
        cycles (atom [])
        last-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (slow-cycle-timer cycles))}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 4 (count @requests)) "the last retry is sent"))

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/finalize-workspace))
            (await (async/wait-for #(get-in @store [:persistence :suspended])
                                   "leaving the workspace suspends retries"))
            (rx/error! last-s (ex-info "offline" {:type :offline}))
            (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                   "the spent backoff halts the queue"))
            (await (async/settle))
            (t/is (empty? @cycles) "no slow cycle is armed")
            (t/is (= 1 (count (get-in @store [:persistence :queue]))))

            (ptk/emit! store (ptk/data-event :app.main.data.workspace/workspace-initialized))
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :queue])))
                                   "opening a file saves the held edit"))
            (t/is (= 5 (count @requests))))))
       (fn [_ _]
         (let [n (swap! calls inc)]
           (cond
             (< n 4) (rx/throw (ex-info "offline" {:type :offline}))
             (= n 4) last-s
             :else   (rx/of {:revn 1}))))))))
