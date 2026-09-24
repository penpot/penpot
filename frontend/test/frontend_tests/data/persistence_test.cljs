;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.persistence-test
  "Tests for the file persistence save pipeline (`app.main.data.persistence`):
  read-only saves, the stalled-save watchdog, the core save pipeline
  (queueing, failures, recovery), and the transient/terminal failure
  classification. Retry-episode behavior lives in
  `frontend-tests.data.persistence-retry-test`.

  The watchdog and read-only tests are fully async: `mock/with-mocks*`
  installs the doubles, `^:async` bodies `await` observable effects (never
  assert straight after a trigger), and completion propagates through
  promises — test code threads no `done`. The remaining tests still use
  `mock/with-mocks` with an explicit `done` chain."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.data.render-wasm :as drw]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
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

(defn- ^:async run-read-only-phases
  "Drives the read-only save scenario presuming asynchronous APIs: every
  phase emits its triggers, awaits the transport round-trip, and only
  then asserts the observed state."
  [store response requests file-id read-only-event errors]
  (ptk/emit! store (dps/initialize-persistence)
             (local-commit file-id)
             read-only-event
             ::dps/force-persist)
  (await (async/await-response requests response {:revn 1}))
  (t/is (= :saved (get-in @store [:persistence :status])))
  (t/is (empty? (get-in @store [:persistence :queue])))

  (ptk/emit! store (drw/context-restored)
             #(assoc-in % [:workspace-global :read-only?] false)
             (local-commit file-id)
             ::dps/force-persist)
  (await (async/await-response requests response {:revn 2}))
  (t/is (= :saved (get-in @store [:persistence :status])))
  (t/is (empty? (get-in @store [:persistence :queue])))
  (t/is (empty? @errors)))

(defn- ^:async check-read-only-save
  "Saves a queued edit while the file is read-only and verifies that the edit
  is persisted once the restriction is lifted.

  Scenario: with persistence initialized, queue an edit, switch the file to
  read-only (`read-only-event`), and force persistence — the edit goes out
  and is acknowledged. Then restore an editable context, queue another edit
  and persist again. Both saves land in order with no reported errors.
  Proves: read-only defers writes without dropping them, and resumption
  picks up where it left off."
  [read-only-event]
  (let [file-id  (uuid/next)
        response (rx/subject)
        requests (atom [])
        errors   (atom [])
        store    (ptk/store {:state {:permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(swap! errors conj %)})]

    (mock/with-mocks*
      {rp/cmd! (mock/stub (fn [cmd params]
                            (let [req (->> response (rx/take 1) (rx/observe-on :async))]
                              (swap! requests conj {:cmd cmd :params params :req req})
                              req)))}
      (try
        (await (run-read-only-phases store response requests file-id read-only-event errors))
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

;; Variant: the file goes read-only through render context loss.
(t/deftest ^:async queued-edits-save-after-context-loss
  (await (check-read-only-save (drw/context-lost))))

;; Variant: the file goes read-only through the workspace flag.
(t/deftest ^:async queued-edits-save-in-read-only-mode
  (await (check-read-only-save #(assoc-in % [:workspace-global :read-only?] true))))

;; Variant: the file goes read-only through preview mode.
(t/deftest ^:async queued-edits-save-in-read-only-preview-mode
  (await (check-read-only-save #(assoc % :workspace-global {:read-only? true
                                                            :preview-id (uuid/next)}))))

;; Scenario: the open file is a historical preview (read-only with a preview
;; id). Attempting a commit there must produce nothing observable: the gate
;; answers refusals with an empty stream (never nil), so the test observes
;; termination instead of branching on nil. Previews cannot create local
;; commits. Proves: the read-only preview gate holds at the watch level.
(t/deftest ^:async historical-preview-cannot-create-local-commits
  (let [file-id (uuid/next)
        output  (atom [])
        state   {:current-file-id file-id
                 :permissions {:can-edit true}
                 :files {file-id {:id file-id :revn 0 :vern 0}}
                 :workspace-global {:read-only? true :preview-id (uuid/next)}}
        event   (dch/commit-changes {:redo-changes [] :undo-changes []})]
    (await (async/observe (ptk/watch event state (rx/empty))
                          :on-next #(swap! output conj %)))
    (t/is (empty? @output))))

;; Watchdog: stalled-save detection.
;;
;; Production contract under test (`app.main.data.persistence`,
;; `saving-stall-timeout-ms` = 5 minutes):
;; - A request unanswered past the deadline reports once as
;;   `:saving-stalled` (a `handled-exception` audit event) and never
;;   repeats for the same stall (`:stall-reported`).
;; - Any completed save resets the clock: a later stall reports again.
;; - Edits queued behind a stall are preserved; an already failed save is
;;   not re-reported as a stall.
;;
;; Emulation model — the fixture freezes the three inputs the watchdog
;; reads, so each test scripts time by hand:
;; - clock (`ct/now`): an atom. The test sets the time; time never flows.
;; - ticks (`rx/interval`): a subject. Each push runs one watchdog pass.
;; - network (`rp/cmd!`): answered when the test pushes into `:response`
;;   (scenario timing stays in test hands); delivery is asynchronous, so
;;   every phase awaits its effects before asserting them.
;; - reports (`errors/generate-report`/`submit-report`): recorded into
;;   atoms for assertions; nothing leaves the test. Thunk reports are
;;   evaluated through the `generate-report` double, mirroring production
;;   (granted reports build, suppressed ones never exist here).
;;
;; Reading the tests below: every assert block is preceded by quiescence —
;; `wait-for` on its presence-conditions, or a bare `settle` tick when it
;; asserts only absence.
(defn- with-watchdog
  "Async fixture for the persistence watchdog tests: mocks the clock, timers
  and RPC transport, and runs `f` with persistence initialized.

  Evaluates to a promise resolving once `f` settles and teardown completes;
  `await` it from an `^:async` test. The transport answers when the test
  pushes into `:response` (scenario timing stays in test hands); delivery
  is asynchronous, so await each effect — via `wait-for` — before asserting
  it. `f` is awaited (usually an `^:async` fn)."
  [f]
  (let [clock    (atom 0)
        ticks    (rx/subject)
        response (rx/subject)
        requests (atom [])
        reports  (atom [])
        causes   (atom [])
        file-id  (uuid/next)
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
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
       errors/submit-report   (fn [& params]
                                ;; Mirrors production: a granted report builds
                                ;; its payload through `generate-report`, so a
                                ;; thunk report is evaluated here while a
                                ;; string report is recorded as it arrives.
                                (let [m (apply hash-map params)]
                                  (swap! reports conj m)
                                  (when (fn? (:report m))
                                    ((:report m)))))}
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (await (f {:clock clock :ticks ticks :response response :requests requests
                   :causes causes :reports reports :store store :file-id file-id}))
        (finally
          (rx/dispose! store)
          (rx/end! ticks)
          (rx/end! response))))))

;; Scenario: persistence sits in a non-terminal status with an empty
;; queue past the deadline (a status transition racing the queue drain).
;; The watchdog must stay silent: with nothing queued there is nothing
;; stalled to report. Proves: no false-positive stall reports on an
;; empty queue.
(t/deftest ^:async empty-queue-never-reports-a-stall
  (await
   (with-watchdog
     (^:async fn [{:keys [clock ticks reports store]}]
       (ptk/emit! store (#'dps/update-status :pending))
       (reset! clock 300001)
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (empty? @reports) "an empty queue is never a stall")))))

;; Scenario: the network hangs. One save goes out and is never answered.
;; Five minutes pass with no report (the deadline is exclusive); one second
;; later, with more edits piled behind the stalled request, the tick reports
;; the stall exactly once — as `handled-exception`, carrying the file id and
;; the lost render context — and later ticks repeat nothing while both edits
;; stay queued. Proves: one report per stall, no lost edits.
(t/deftest ^:async stalled-request-is-reported-once-without-discarding-edits
  (await
   (with-watchdog
     (^:async fn [{:keys [clock ticks reports causes store file-id]}]
       ;; Phase 1 — at exactly five minutes the deadline has not elapsed: silence.
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (reset! clock 300000)
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (empty? @reports) "Five minutes must elapse before reporting")

       ;; Phase 2 — one second past the deadline the stall reports exactly once.
       ;; More local edits must not reset the stalled request's clock.
       (reset! clock 300001)
       (ptk/emit! store (drw/context-lost)
                  (local-commit file-id) ::dps/force-persist)
       (rx/push! ticks :tick)
       (await (async/wait-for #(= 1 (count @reports)) "stall report"))
       (t/is (= 1 (count @reports)))
       (t/is (= "handled-exception" (:event-name (first @reports))))
       (let [data (ex-data (first @causes))]
         (t/is (= :saving-stalled (:code data)))
         (t/is (= file-id (:file-id data)))
         (t/is (true? (:render-context-lost? data))))

       ;; Phase 3 — long after: no repeat, both edits still queued.
       (reset! clock 900000)
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (= 1 (count @reports)) "Do not repeat a report for the same stall")
       (t/is (= :saving (get-in @store [:persistence :status])))
       (t/is (= 2 (count (get-in @store [:persistence :queue]))))))))

;; Scenario: answers arrive, then stop. Two commits share the first request;
;; awaiting its landing advances the queue and starts the second request, so
;; the tick finds progress and stays silent. Left hanging past a fresh
;; deadline it reports once; answering it saves the file and silences the
;; watchdog; a later hang reports again. Proves: progress resets the clock,
;; and every new stall earns its own report.
;;
;; Note the awaits: a push only schedules delivery, so each tick must
;; observe the completed save — awaiting after the push is what separates
;; "answered" from "completed".
(t/deftest ^:async successful-saves-reset-the-stall-clock-and-allow-a-new-report
  (await
   (with-watchdog
     (^:async fn [{:keys [clock ticks reports response store file-id]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist
                  (local-commit file-id) ::dps/force-persist)
       ;; Phase 1 — two commits share the first request; its landing starts the second.
       (reset! clock 290000)
       (rx/push! response {:revn 1})
       (await (async/wait-for #(= 1 (count (get-in @store [:persistence :queue])))
                              "first save lands, second request starts"))
       (reset! clock 300001)
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (empty? @reports) "The queue is making progress")

       ;; Phase 2 — the second request hangs past its own fresh deadline.
       (reset! clock 590001)
       (rx/push! ticks :tick)
       (await (async/wait-for #(= 1 (count @reports)) "second request stalls"))
       (t/is (= 1 (count @reports)) "The second request has now stalled")

       (rx/push! response {:revn 2})
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "second save lands"))
       (t/is (= :saved (get-in @store [:persistence :status])))
       ;; Phase 3 — answering saves the file: the tick stays silent.
       (reset! clock 1000000)
       (rx/push! ticks :tick)
       (t/is (= 1 (count @reports)) "A saved file must not be reported")

       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       ;; Phase 4 — a later hang is a new stall with its own report.
       (reset! clock 1300001)
       (rx/push! ticks :tick)
       (await (async/wait-for #(= 2 (count @reports)) "later stall reports again"))
       (t/is (= 2 (count @reports)) "A later stall gets its own report")))))

;; Scenario: no network request exists at all — an edit sits `:pending`
;; locally without being sent. The watchdog still tracks it against the
;; original deadline (re-marking `:pending` does not push it): an idle
;; file never reports, an aged pending edit reports once, and once the
;; save fails outright the watchdog stands down — failures belong to the
;; save path, never twice. Proves: pending edits are watched without
;; extending deadlines, and failures are not re-reported as stalls.
(t/deftest ^:async pending-edits-are-monitored-without-extending-the-deadline
  (await
   (with-watchdog
     (^:async fn [{:keys [clock ticks reports store file-id]}]
       ;; Phase 1 — idle: nothing queued, nothing reported.
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (empty? @reports) "An idle file must not be reported")
       ;; Phase 2 — a queued (yet unsent) edit ages into one report, and
       ;; re-marking pending does not extend its deadline.
       (let [id (uuid/next)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id {:id id :file-id file-id}}})))
       (ptk/emit! store (#'dps/update-status :pending))
       (reset! clock 300001)
       (ptk/emit! store (#'dps/update-status :pending))
       (rx/push! ticks :tick)
       (await (async/wait-for #(= 1 (count @reports)) "pending edit ages into a report"))
       (t/is (= 1 (count @reports)))
       ;; Phase 3 — an outright failure is not a stall: no second report.
       (ptk/emit! store (#'dps/update-status :error))
       (reset! clock 900000)
       (rx/push! ticks :tick)
       (await (async/settle))
       (t/is (= 1 (count @reports)) "Do not report an already failed save")))))

;; Scenario: initializing twice must replace the watchdog instead of stacking
;; it — a single active timer at all times, zero after teardown. Proves:
;; reinitialization swaps the timer subscription instead of leaking it.
;; (Fully synchronous bodies need no awaits; the promise shell still
;; guarantees restore and completion.)
(t/deftest ^:async reinitializing-persistence-replaces-the-watchdog
  (await
   (let [active-timers (atom 0)
         ticks         (rx/subject)
         store         (ptk/store {:state {} :on-error #(t/is false (str %))})]
     (mock/with-mocks*
       {rx/interval (mock/stub
                     (fn [_]
                       (rx/create
                        (fn [subscriber]
                          (swap! active-timers inc)
                          (let [subscription (.subscribe ticks subscriber)]
                            (fn []
                              (rx/dispose! subscription)
                              (swap! active-timers dec)))))))}
       (try
         (ptk/emit! store (dps/initialize-persistence))
         (t/is (= 1 @active-timers))
         (ptk/emit! store (dps/initialize-persistence))
         (t/is (= 1 @active-timers))
         (finally
           (rx/dispose! store)
           (rx/end! ticks)
           (t/is (zero? @active-timers))))))))

;; Scenario: the first send fails transiently and the retry resend hangs
;; forever inside the `:retrying` episode. Past the 5-minute deadline the
;; watchdog must still report the stall exactly once — and never repeat
;; it — while the queued edit is preserved. Proves: the watchdog sees
;; hung resends; a `:retrying` episode cannot deadlock silently.
(t/deftest ^:async hung-retry-resend-is-reported-as-a-stall
  (let [calls   (atom 0)
        timer-s (rx/subject)]
    (await
     (with-watchdog
       (^:async fn [{:keys [clock ticks reports causes requests store file-id]}]
         (await
          (mock/with-mocks*
            {rx/timer (mock/stub (fn [_] timer-s))
             rp/cmd!  (mock/stub (fn [cmd params]
                                   (swap! requests conj {:cmd cmd :params params})
                                   (if (= 1 (swap! calls inc))
                                     (rx/throw (ex-info "offline" {:type :offline}))
                                     ;; The resend hangs forever: the subject
                                     ;; is never answered nor failed.
                                     (rx/subject))))}
            ;; Phase 1 — first send fails transiently; the episode parks
            ;; on the hand-fired timer.
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= :retrying (get-in @store [:persistence :status]))
                                   "transient failure retries"))
            (t/is (= 1 (count @requests)))
            ;; Phase 2 — the timer fires and the resend goes out, hanging.
            (rx/push! timer-s :tick)
            (await (async/wait-for #(= 2 (count @requests)) "retry resends"))
            (t/is (= :retrying (get-in @store [:persistence :status])))
            ;; Phase 3 — past the deadline the watchdog reports the stall
            ;; once, keeping the queued edit.
            (reset! clock 300001)
            (rx/push! ticks :tick)
            (await (async/wait-for #(= 1 (count @reports)) "hung resend stalls"))
            (t/is (= 1 (count @reports)))
            (t/is (= "handled-exception" (:event-name (first @reports))))
            (t/is (= :saving-stalled (:code (ex-data (first @causes)))))
            (t/is (= :retrying (get-in @store [:persistence :status])))
            (t/is (= 1 (count (get-in @store [:persistence :queue]))))
            ;; Phase 4 — later ticks repeat nothing for the same stall.
            (reset! clock 900000)
            (rx/push! ticks :tick)
            (await (async/settle))
            (t/is (= 1 (count @reports)) "Do not repeat a report for the same stall")
            (rx/end! timer-s))))))))

;; Save pipeline tests.
;;
;; Production contract under test (`app.main.data.persistence`):
;; - Commits queue locally and flush in order; every request carries the
;;   queued changes exactly once (no resends of active requests, no drops).
;; - Without edit permission nothing is sent and the failure flashes.
;; - Transport failures and malformed answers (`nil`, empty, bad revision)
;;   fail the save but preserve the queue for retry.
;; - Initialization recovers gracefully: dangling runners error instead of
;;   skipping, unsent commits are picked up, unknown outcomes are reported
;;   without replaying.
;;
;; Same async pattern as the watchdog tests: triggers stay bare, every
;; assert block is preceded by `wait-for` on its leading signal (or a bare
;; `settle` tick when it asserts only absence).
(defn- with-persistence
  "Async fixture for the persistence tests: mocks the transport and flash, and
  runs `f` with persistence initialized.

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
        store    (ptk/store {:state {:current-file-id file-id
                                     :permissions {:can-edit true}
                                     :files {file-id {:id file-id :revn 0}}}
                             :on-error #(t/is false (str %))})]
    (mock/with-mocks*
      {rp/cmd!      (mock/stub (fn [cmd params]
                                 (swap! requests conj [cmd params])
                                 (if respond
                                   (respond cmd params)
                                   (->> response (rx/take 1) (rx/observe-on :async)))))
       errors/flash (fn [& {:keys [cause]}]
                      (swap! failures conj cause))}
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (await (f {:file-id file-id :response response :failures failures
                   :requests requests :store store}))
        (finally
          (rx/dispose! store)
          (rx/end! response))))))

;; Scenario: edit permission is revoked with an edit queued. The save fails
;; without sending anything, the queue keeps the edit, and the user is
;; notified once; further edits keep queueing behind the failure instead of
;; replacing it. Proves: permission loss fails safe — no send, no discard,
;; one flash.
(t/deftest ^:async permission-loss-fails-without-discarding-queued-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests failures store]}]
       (ptk/emit! store (local-commit file-id)
                  #(assoc-in % [:permissions :can-edit] false)
                  ::dps/force-persist)
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "permission loss fails the save"))
       (t/is (= :error (get-in @store [:persistence :status])))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (t/is (empty? @requests))
       (t/is (= 1 (count @failures)))
       (ptk/emit! store (local-commit file-id) ::dps/force-persist
                  (#'dps/update-status :pending))
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 2 (count (get-in @store [:persistence :queue]))))
                              "second edit queues behind the failure"))
       (t/is (= :error (get-in @store [:persistence :status])))
       (t/is (= 2 (count (get-in @store [:persistence :queue]))))))))

;; Scenario: a request with two queued commits fails terminally at the
;; transport. Both edits stay queued as failed; reinitializing persistence
;; must not replay the dead request. Proves: failed requests preserve the
;; queue and are never retried on initialization.
(t/deftest ^:async failed-request-retains-the-queue-and-is-not-retried-on-initialization
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist
                  (local-commit file-id) ::dps/force-persist)
       (.error response (ex-info "Validation failed" {:type :validation}))
       (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                              "failed request errors the save"))
       (ptk/emit! store (dps/initialize-persistence))
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 2 (count (get-in @store [:persistence :queue]))))
                              "initialization preserves the failed queue"))
       (t/is (= :error (get-in @store [:persistence :status])))
       (t/is (= 2 (count (get-in @store [:persistence :queue]))))
       (t/is (= 1 (count @requests)))))))

(defn- check-save-failure-warning
  "Fails a queued save with `cause-type` and asserts the hint shown to the
  user; authentication failures have their own UI and must stay silent."
  [cause-type]
  (with-persistence
    (^:async fn [{:keys [file-id response store]}]
      (let [notifications (atom [])]
        (await
         (mock/with-mocks*
           {errors/flash (fn [& params]
                           (swap! notifications conj (apply hash-map params)))
            i18n/tr      (mock/stub #(str "translated:" %))}
           (ptk/emit! store (local-commit file-id) ::dps/force-persist)
           (.error response (ex-info "Raw transport details" {:type cause-type}))
           (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                  "failed request errors the save"))
           (t/is (= :error (get-in @store [:persistence :status])))
           (let [data (get-in @store [:persistence :error])]
             (ptk/handle-error (assoc data ::errors/instance (ex-info "Save failed" data))))
           (if (= cause-type :authentication)
             (do (await (async/settle))
                 (t/is (empty? @notifications)))
             (do (await (async/wait-for #(seq @notifications) "save-failure toast"))
                 (t/is (= ["translated:errors.save-failed"]
                          (mapv :hint @notifications)))))))))))

;; Variant: an authentication failure stays silent (own UI flow).
(t/deftest ^:async authentication-save-failure-shows-no-warning
  (await (check-save-failure-warning :authentication)))

;; NOTE: the `:network` and `:offline` warning variants lived here while
;; transport failures went straight to `:error`. Transient failures now
;; retry instead, so their coverage moved to the retry tests below (and to
;; the exhaustion test for the terminal toast).

;; The transient/terminal classification is pure: transport failures and
;; unusable save responses retry with backoff, everything else stays
;; terminal. Note the wrapped shape: `persistence-failed` records the
;; original cause under `:cause-type` with `:type :persistence`.
(t/deftest transient-error-classification
  (t/is (dps/transient-error? {:type :network}))
  (t/is (dps/transient-error? {:type :offline}))
  (t/is (dps/transient-error? {:type :bad-gateway}))
  (t/is (dps/transient-error? {:type :service-unavailable}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :network}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :offline}))
  (t/is (dps/transient-error? {:type :internal :cause-type :bad-gateway}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :service-unavailable}))
  ;; The wrapped shape stays transient with an explicit non-retry code too:
  ;; the cause-type carries the transport verdict, not the wrapper's code.
  (t/is (dps/transient-error? {:type :persistence :cause-type :service-unavailable :code :save-failed}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :bad-gateway :code :save-failed}))
  (t/is (dps/transient-error? {:type :persistence :code :invalid-save-response}))
  (t/is (not (dps/transient-error? {:type :authentication})))
  (t/is (not (dps/transient-error? {:type :validation})))
  (t/is (not (dps/transient-error? {:type :internal})))
  (t/is (not (dps/transient-error? {:type :persistence :cause-type :authentication})))
  (t/is (not (dps/transient-error? {:type :persistence :code :missing-commit})))
  (t/is (not (dps/transient-error? {:type :persistence :code :save-permission-denied})))
  (t/is (not (dps/transient-error? {}))))

;; The transient set is owned by `repo/retryable-types`: every transport
;; type retryable at the HTTP layer must also be transient for the save
;; pipeline, in both the direct and the wrapped (`persistence-failed`)
;; positions. Proves: the two classifications cannot drift apart.
(t/deftest transient-classification-follows-repo-retryable-types
  (doseq [transport-type rp/retryable-types]
    (t/testing (str "transport type " transport-type " is transient in both positions")
      (t/is (dps/transient-error? {:type transport-type}))
      (t/is (dps/transient-error? {:type :persistence :cause-type transport-type})))))

;; Scenario: a save fails transiently and the episode parks on its backoff
;; timer. A waiter started mid-episode must not resolve on the `:retrying`
;; transition — only the terminal save releases it. Proves: `:retrying` is
;; non-terminal for waiters.
(t/deftest waiter-release-rule
  ;; The waiter release rule is pure: only a failed save or a settled
  ;; (`nil` / `:saved`) empty queue releases waiters. In particular, the
  ;; `:retrying` episode — queue non-empty, status non-terminal — never
  ;; does. Proves: `:retrying` is non-terminal for waiters, a document of
  ;; intent over the `wait-persisted-or-error` filter.
  ;;
  ;; Note: this intentionally avoids scripting the global store atom — it
  ;; is shared with the whole suite, so any test reading it observes
  ;; other namespaces' leftovers depending on run order.
  (t/is (dps/terminal-status? {:status :error :queue [1]}))
  (t/is (dps/terminal-status? {:status :saved :queue []}))
  (t/is (dps/terminal-status? {:status nil :queue []}))
  (t/is (dps/terminal-status? {:status nil :queue nil}))
  (t/is (not (dps/terminal-status? {:status :retrying :queue [1]})))
  (t/is (not (dps/terminal-status? {:status :saving :queue [1]})))
  (t/is (not (dps/terminal-status? {:status :pending :queue [1]})))
  (t/is (not (dps/terminal-status? {:status :saved :queue [1]}))))

;; Scenario: the queue references a commit id with no matching commit
;; (plus a dangling run id) when persistence initializes. It must error
;; instead of silently skipping the unknown entry, keeping it queued and
;; sending nothing. Proves: dangling queue entries fail loudly, never skip.
(t/deftest ^:async missing-commit-is-an-error-instead-of-skipping-changes
  (await
   (with-persistence
     (^:async fn [{:keys [requests store]}]
       (let [id (uuid/next)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {} :run-id (uuid/next)
                                            :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                "dangling commit errors instead of skipping"))
         (t/is (= :error (get-in @store [:persistence :status])))
         (t/is (= [id] (vec (get-in @store [:persistence :queue]))))
         (t/is (empty? @requests)))))))

;; Scenario: a valid but unsent commit sits queued with a dangling run id
;; when persistence initializes. It is sent exactly once and, once answered,
;; the file saves with an empty queue. Proves: unsent commits survive a
;; stale runner — neither stuck nor duplicated.
(t/deftest ^:async initialization-recovers-an-unsent-commit-with-a-dangling-run-id
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [commit (assoc @(local-commit file-id) :changes [])
             id     (:id commit)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id commit} :run-id (uuid/next)
                                            :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= 1 (count @requests)) "dangling commit is sent"))
         (t/is (= 1 (count @requests)))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                      (empty? (get-in @store [:persistence :queue])))
                                "recovered save lands"))
         (t/is (= :saved (get-in @store [:persistence :status])))
         (t/is (empty? (get-in @store [:persistence :queue]))))))))

(defn- check-active-request-not-resent
  "Sends a commit, interrupts the active request with `interrupt` and
  reinitializes persistence: the pending request must not be sent again."
  [interrupt]
  (with-persistence
    (^:async fn [{:keys [file-id response requests store]}]
      (apply ptk/emit! store (local-commit file-id) ::dps/force-persist interrupt)
      (ptk/emit! store (dps/initialize-persistence))
      (await (async/wait-for #(= 1 (count @requests)) "only the active request is sent"))
      (t/is (= 1 (count @requests)))
      (rx/push! response {:revn 1})
      (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                   (empty? (get-in @store [:persistence :queue])))
                             "active request completes"))
      (t/is (= :saved (get-in @store [:persistence :status])))
      (t/is (empty? (get-in @store [:persistence :queue]))))))

;; Variant: the interrupt is a reinitialization.
(t/deftest ^:async reinitializing-does-not-resend-an-active-request
  (await (check-active-request-not-resent [(dps/initialize-persistence)])))

;; Variant: the interrupt is a save error event.
(t/deftest ^:async save-error-does-not-resend-an-active-request
  (await (check-active-request-not-resent [(ptk/data-event ::dps/error)])))

;; Scenario: two commits go out, permission is lost mid-flight (first request
;; fails, one edit stays queued as failed), then permission is restored with
;; a team-role change. Only the unsent edit is resent; once answered, the
;; file saves with an empty queue. Proves: restoration resumes exactly the
;; unsent edits.
(t/deftest ^:async permission-restoration-resumes-only-unsent-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist
                  (local-commit file-id) ::dps/force-persist
                  #(assoc-in % [:permissions :can-edit] false))
       (rx/push! response {:revn 1})
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "permission loss fails the save"))
       (t/is (= :error (get-in @store [:persistence :status])))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (ptk/emit! store
                  #(assoc-in % [:permissions :can-edit] true)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for #(= 2 (count @requests)) "restoration resends"))
       (t/is (= 2 (count @requests)))
       (rx/push! response {:revn 2})
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "resumed save lands"))
       (t/is (= :saved (get-in @store [:persistence :status])))
       (t/is (empty? (get-in @store [:persistence :queue])))))))

;; Scenario: the answer arrives but no runner is tracking the request
;; anymore. The acknowledged commit stays queued instead of being dropped;
;; on initialization it is settled as saved without resending. Proves: late
;; acknowledgments are neither lost nor replayed.
(t/deftest ^:async recovery-keeps-an-acknowledgment-received-without-a-runner
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist
                  (ptk/data-event ::dps/error))
       (rx/push! response {:revn 1})
       (await (async/wait-for #(= 1 (count (get-in @store [:persistence :queue])))
                              "acknowledged commit stays queued"))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (ptk/emit! store (dps/initialize-persistence))
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "recovery completes without resending"))
       (t/is (= 1 (count @requests)) "The acknowledged changes must not be sent again")
       (t/is (= :saved (get-in @store [:persistence :status])))
       (t/is (empty? (get-in @store [:persistence :queue])))))))

;; Scenario: a queued commit carries a request id but persistence has no
;; run id for it when initializing — its outcome is unknowable. It errors
;; as `:save-outcome-unknown`, stays queued, and nothing is sent.
;; Proves: unknown outcomes report without replaying (never assume persisted,
;; never resend blindly).
(t/deftest ^:async recovery-reports-an-unknown-request-outcome-without-replaying-it
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       (let [commit (assoc @(local-commit file-id) ::dps/request-id (uuid/next))
             id     (:id commit)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id commit} :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= :save-outcome-unknown
                                    (get-in @store [:persistence :error :code]))
                                "unknown outcome errors"))
         (t/is (= :error (get-in @store [:persistence :status])))
         (t/is (= :save-outcome-unknown (get-in @store [:persistence :error :code])))
         (t/is (= [id] (vec (get-in @store [:persistence :queue]))))
         (t/is (empty? @requests)))))))

;; Scenario: an edit is queued before persistence even initializes. On
;; initialization plus force-persist it is sent exactly once (still queued
;; until answered); once answered, the file saves. Proves: pre-init buffered
;; edits flush exactly once, without duplicating.
(t/deftest ^:async initialization-flushes-buffered-edits-without-duplicating-them
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (ptk/emit! store (local-commit file-id)
                  (dps/initialize-persistence)
                  ::dps/force-persist)
       (await (async/wait-for #(and (= 1 (count @requests))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "buffered edit is sent once"))
       (t/is (= 1 (count @requests)))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (rx/push! response {:revn 1})
       (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                              "buffered edit saves"))
       (t/is (= :saved (get-in @store [:persistence :status])))))))

;; Scenario: the transport answers synchronously (immediate observable).
;; The save completes, the queue empties, and no runner is left dangling.
;; Proves: synchronous transport responses settle cleanly.
(t/deftest ^:async synchronous-save-results-do-not-leave-a-dangling-runner
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "synchronous save completes"))
       (t/is (= :saved (get-in @store [:persistence :status])))
       (t/is (empty? (get-in @store [:persistence :queue]))))
     (fn [_ _] (rx/of {:revn 1})))))
