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
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.commits :as pcm]
   [app.main.data.render-wasm :as drw]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(t/deftest reopening-restores-only-the-retained-edits-of-the-loaded-file
  (doseq [[status cause-type vern restored?]
          [[:retrying nil 0 true]
           [:saving nil 0 true]
           [:error :offline 0 true]
           [:error :validation 0 false]
           [:retrying nil 1 false]]]
    (t/testing (str status " / " cause-type " / version " vern)
      (let [file-id  (uuid/next)
            page-id  (uuid/next)
            shape-id (uuid/next)
            first-id (uuid/next)
            next-id  (uuid/next)
            other-id (uuid/next)
            shape    (cts/setup-shape {:id shape-id :type :rect :x 0 :y 0})
            file     {:id file-id
                      :vern vern
                      :data {:pages [page-id]
                             :pages-index {page-id {:id page-id :name "Page"
                                                    :objects {uuid/zero
                                                              (cts/setup-shape {:id uuid/zero :type :frame})}}}}}
            rename   (fn [name]
                       {:type :mod-obj :id shape-id :page-id page-id
                        :operations [{:type :set :attr :name :val name}]})
            pstate   {:status status
                      :error {:cause-type cause-type :commit-id first-id}
                      :queue (into #queue [] [first-id next-id other-id])
                      :index {first-id {:file-id file-id :file-vern 0
                                        :commits [{:redo-changes [{:type :add-obj :id shape-id :obj shape
                                                                   :page-id page-id :parent-id uuid/zero
                                                                   :frame-id uuid/zero}]}]}
                              next-id {:file-id file-id :file-vern 0
                                       :commits [{:redo-changes [(rename "Retained rectangle")]}]}
                              other-id {:file-id (uuid/next) :file-vern 0
                                        :commits [{:redo-changes [(rename "Another file")]}]}}}
            state    {:persistence pstate}
            loaded   (ptk/update (#'dw/bundle-fetched {:file file :file-id file-id :thumbnails {}})
                                 state)
            restored (get-in loaded [:files file-id :data :pages-index page-id :objects shape-id])]
        (t/is (= restored? (some? restored)))
        (when restored?
          (t/is (= "Retained rectangle" (:name restored))
                "queued edits apply in order and exclude other files"))
        (t/is (identical? pstate (:persistence loaded))
              "restoring the editor must preserve the save queue")))))

;; Scenario: a file loads again while an edit of it waits in the buffer.
;; Proves: buffered edits apply too, after the queued ones, and those of
;; other files do not.
(t/deftest reopening-restores-the-buffered-edits-after-the-queued-ones
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        commit-id (uuid/next)
        rename    (fn [name]
                    {:type :mod-obj :id uuid/zero :page-id page-id
                     :operations [{:type :set :attr :name :val name}]})
        file      {:id file-id
                   :vern 0
                   :data {:pages [page-id]
                          :pages-index {page-id {:id page-id :name "Page"
                                                 :objects {uuid/zero
                                                           (cts/setup-shape {:id uuid/zero :type :frame})}}}}}
        pstate    {:status :saving
                   :queue (into #queue [] [commit-id])
                   :index {commit-id {:file-id file-id :file-vern 0
                                      :commits [{:redo-changes [(rename "Queued")]}]}}
                   :buffer [{:file-id file-id :file-vern 0 :redo-changes [(rename "Buffered")]}
                            {:file-id (uuid/next) :file-vern 0 :redo-changes [(rename "Another file")]}]}
        loaded    (ptk/update (#'dw/bundle-fetched {:file file :file-id file-id :thumbnails {}})
                              {:persistence pstate})]
    (t/is (= "Buffered"
             (get-in loaded [:files file-id :data :pages-index page-id :objects uuid/zero :name])))))

;; Scenario: a queued edit no longer applies to the data the server sent, so
;; replaying it throws. Proves: the file still opens, as the server sent it,
;; and the edits stay queued.
(t/deftest reopening-with-an-edit-that-no-longer-applies-keeps-the-server-file
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        commit-id (uuid/next)
        data      {:pages [page-id]
                   :pages-index {page-id {:id page-id :name "Page"
                                          :objects {uuid/zero
                                                    (cts/setup-shape {:id uuid/zero :type :frame})}}}}
        file      {:id file-id :vern 0 :data data}
        pstate    {:status :saving
                   :queue (into #queue [] [commit-id])
                   :index {commit-id {:file-id file-id :file-vern 0
                                      :commits [{:redo-changes [{:type :mod-obj :id uuid/zero :page-id page-id
                                                                 :operations [{:type :no-longer-supported}]}]}]}}}
        loaded    (ptk/update (#'dw/bundle-fetched {:file file :file-id file-id :thumbnails {}})
                              {:persistence pstate})]
    (t/is (identical? data (get-in loaded [:files file-id :data])))
    (t/is (identical? pstate (:persistence loaded)))))

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
      {rp/cmd!          (mock/stub (fn [cmd params]
                                     (let [req (->> response (rx/take 1) (rx/observe-on :async))]
                                       (swap! requests conj {:cmd cmd :params params :req req})
                                       req)))
       st/ongoing-tasks (atom #{})}
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
                                    ((:report m)))))
       st/ongoing-tasks       (atom #{})}
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
                              (swap! active-timers dec)))))))
        st/ongoing-tasks (atom #{})}
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
;;   skipping, unsent commits are picked up, unknown outcomes are sent again
;;   under the same commit id.
;;
;; Same async pattern as the watchdog tests: triggers stay bare, every
;; assert block is preceded by `wait-for` on its leading signal (or a bare
;; `settle` tick when it asserts only absence).
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

;; Scenario: edit permission is revoked with an edit queued. The save fails
;; without sending anything, the queue keeps the edit and the user is
;; notified once, so `wait-persisted-or-error` still rejects and nothing is
;; snapshotted from unsaved state. Proves: permission loss fails safe.
(t/deftest ^:async permission-loss-retains-the-queue-and-notifies-once
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
       (t/is (= 1 (count (get-in @store [:persistence :queue])))
             "the undeliverable edit is retained, so waiters keep rejecting")
       (t/is (empty? @requests))
       (t/is (= 1 (count @failures)))))))

;; Scenario: the same terminal failure, then the user opens a file again.
;; Opening a file releases the edit that can never be saved and settles the
;; editor, instead of leaving it halted until a reload. Proves: the halt does
;; not outlive the visit that caused it.
(t/deftest ^:async opening-a-file-again-releases-a-terminal-failure
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests failures store]}]
       (ptk/emit! store (local-commit file-id)
                  #(assoc-in % [:permissions :can-edit] false)
                  ::dps/force-persist)
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "the save fails terminally"))
       ;; The user acts again: a team-role change is one of the two real
       ;; recovery triggers (the other is opening a file, whose event does
       ;; real work and is not usable as a bare signal here).
       (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "re-entering the file settles the queue"))
       (t/is (nil? (get-in @store [:persistence :error]))
             "the halt is cleared once the user comes back")
       (t/is (empty? @requests)
             "the edit that cannot be saved is not replayed on re-entry")
       (t/is (= 1 (count @failures)) "the user is still notified exactly once")))))

;; Scenario: two files have pending edits and the first send fails
;; terminally. Opening a file again releases the failed file, the other file
;; still saves, and the editor settles saved instead of staying halted.
;; Proves: one file that cannot be saved does not block every other file.
(t/deftest ^:async terminal-failure-does-not-block-other-files
  (let [files (into [] (repeatedly 2 uuid/next))
        sends (atom 0)]
    (await
     (with-persistence
       (^:async fn [{:keys [requests store]}]
         (ptk/emit! store
                    #(assoc % :files (into (:files %)
                                           (map (fn [id] [id {:id id :revn 0}]))
                                           files))
                    (local-commit (nth files 0))
                    (local-commit (nth files 1))
                    ::dps/force-persist)
         (await (async/wait-for #(seq @requests) "the first file is sent"))
         (let [failed-file (get-in (second (first @requests)) [:id])]
           (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                        (not (empty? (get-in @store [:persistence :queue]))))
                                  "the terminal failure retains the queue"))
           ;; The user acts again: recovery releases the failed file and drains
           ;; the rest.
           (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
           (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                        (empty? (get-in @store [:persistence :queue])))
                                  "the released file frees the rest of the queue"))
           (t/is (= 2 (count @requests))
                 "the other file was saved after the failed one was released")
           (t/is (= 1 (count (filter #(= failed-file (get-in (second %) [:id])) @requests)))
                 "the failed file is sent once, never replayed")))
       (fn [_ _]
         ;; Fail the first send terminally and answer the rest inline, so no
         ;; hot-subject race can swallow the answer.
         (if (= 1 (swap! sends inc))
           (rx/throw (ex-info "rejected" {:type :validation :code :invalid-data}))
           (rx/of {:revn 1})))))))

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

;; A busy backend refused or rolled back the save, so it is safe to resend:
;; a rate or concurrency limit, or a database timeout. Any other server
;; error stays terminal.
(t/deftest busy-server-classification
  (t/is (dps/transient-error? {:type :rate-limit}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :rate-limit :code :rate-limited}))
  (t/is (dps/transient-error? {:type :server-error :code :statement-timeout}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :server-error :code :statement-timeout}))
  (t/is (dps/transient-error? {:type :persistence :cause-type :server-error :code :idle-in-transaction-timeout}))
  (t/is (not (dps/transient-error? {:type :server-error})))
  (t/is (not (dps/transient-error? {:type :persistence :cause-type :server-error :code :unhandled})))
  (t/is (not (dps/transient-error? {:type :persistence :cause-type :validation :code :statement-timeout}))))

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
  (t/is (not (dps/terminal-status? {:status :saved :queue [1]})))
  (t/is (not (dps/terminal-status? {:status :saved :queue [] :buffer [{:id 1}]}))
        "commits still buffered keep the waiters waiting")
  (t/is (dps/terminal-status? {:status :error :queue [] :buffer [{:id 1}]})))

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
       (let [commit (pcm/queue-entry [@(local-commit file-id)])
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

;; Scenario: permission is lost mid-flight, so the save fails terminally.
;; Even if the permission comes back, recovery releases the undeliverable
;; edits instead of replaying them. The backend raises `:not-found` for a
;; lost edit permission, so a resend would only route the user to the
;; exception page. Proves: permission loss is treated as permanent, like
;; every other terminal failure.
(t/deftest ^:async permission-loss-is-released-not-resumed
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       (ptk/emit! store (local-commit file-id)
                  #(assoc-in % [:permissions :can-edit] false)
                  ::dps/force-persist)
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "permission loss fails the save"))
       ;; Permission comes back and a file is opened again.
       (ptk/emit! store #(assoc-in % [:permissions :can-edit] true)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                    (empty? (get-in @store [:persistence :queue])))
                              "recovery releases the undeliverable edit"))
       (t/is (empty? @requests)
             "the undeliverable edit is never replayed, not even once the
              permission is back")
       (t/is (not= :error (get-in @store [:persistence :status]))
             "the editor is not left halted")))))

;; Scenario: a terminal failure is waiting, and persistence is re-initialized.
;; Initialization must not lift the halt: only opening a file does. Otherwise
;; the edits would be released the moment the workspace view mounts, instead
;; of when the user acts on them. Proves: the init/recovery split holds.
(t/deftest ^:async initialization-does-not-lift-a-halt
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (.error response (ex-info "Validation failed" {:type :validation}))
       (await (async/wait-for #(and (= :error (get-in @store [:persistence :status]))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "the save fails terminally"))
       (ptk/emit! store (dps/initialize-persistence))
       (await (async/settle))
       (t/is (= :error (get-in @store [:persistence :status]))
             "initialization leaves the halt alone")
       (t/is (= 1 (count (get-in @store [:persistence :queue])))
             "and keeps the undeliverable edits queued until the user acts")))))

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
;; run id for it when initializing, so its outcome is unknown. It is sent
;; again under the same commit id and saves. Proves: an unknown outcome is
;; resent, since changes are idempotent.
(t/deftest ^:async recovery-sends-again-a-save-with-an-unknown-outcome
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [commit (assoc (pcm/queue-entry [@(local-commit file-id)]) ::dps/request-id (uuid/next))
             id     (:id commit)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id commit} :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= 1 (count @requests)) "the commit is sent again"))
         (t/is (= id (:commit-id (second (first @requests)))))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                      (empty? (get-in @store [:persistence :queue])))
                                "the resent commit saves"))
         (t/is (= 1 (count @requests))))))))

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

;; Leave-page guard tests.

;; Scenario: an edit is buffered, then sent, then saved. The leave-page
;; guard turns on with the buffered edit, stays on while the save is in
;; flight, and turns off once it lands. Proves: unsaved edits always keep
;; the browser's leave-page warning on.
(t/deftest ^:async unsaved-edits-keep-the-leave-page-guard
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store tasks]}]
       (ptk/emit! store (local-commit file-id))
       (await (async/wait-for #(contains? @tasks :persistence)
                              "a buffered edit turns the guard on"))
       (ptk/emit! store ::dps/force-persist)
       (await (async/wait-for #(seq @requests) "the edit is sent"))
       (t/is (contains? @tasks :persistence) "the guard stays on while the save is in flight")
       (rx/push! response {:revn 1})
       (await (async/wait-for #(not (contains? @tasks :persistence))
                              "the save turns the guard off"))
       (t/is (= :saved (get-in @store [:persistence :status])))))))

;; Scenario: a second edit arrives while the first is being saved, and the
;; first save lands while the second edit is still in the buffer. The queue
;; is empty at that point, yet the guard stays on until the second edit is
;; saved. Proves: edits waiting in the buffer keep the leave-page warning on.
(t/deftest ^:async edits-buffered-during-a-save-keep-the-leave-page-guard
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store tasks]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (await (async/wait-for #(= 1 (count @requests)) "the first edit is sent"))
       (ptk/emit! store (local-commit file-id))
       (rx/push! response {:revn 1})
       (await (async/wait-for #(empty? (get-in @store [:persistence :queue]))
                              "the first save lands"))
       (await (async/settle))
       (t/is (contains? @tasks :persistence) "the buffered edit keeps the guard on")
       (ptk/emit! store ::dps/force-persist)
       (await (async/wait-for #(= 2 (count @requests)) "the buffered edit is sent"))
       (rx/push! response {:revn 2})
       (await (async/wait-for #(not (contains? @tasks :persistence))
                              "the second save turns the guard off"))
       (t/is (empty? (get-in @store [:persistence :queue])))))))

;; Scenario: a save fails terminally, then recovery releases the edit. The
;; guard stays on while the failed edit is retained, and turns off once it
;; is released. Proves: a release that empties the queue also turns the
;; leave-page warning off.
(t/deftest ^:async releasing-a-failed-edit-turns-the-leave-page-guard-off
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store tasks]}]
       (ptk/emit! store (local-commit file-id)
                  #(assoc-in % [:permissions :can-edit] false)
                  ::dps/force-persist)
       (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                              "the save fails terminally"))
       (await (async/settle))
       (t/is (contains? @tasks :persistence) "the retained edit keeps the guard on")
       (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for #(empty? (get-in @store [:persistence :queue]))
                              "recovery releases the edit"))
       (await (async/wait-for #(not (contains? @tasks :persistence))
                              "the release turns the guard off"))
       (t/is (= :saved (get-in @store [:persistence :status])))))))

;; Scenario: an edit waits in the 3 s buffer and the user switches tabs or
;; windows. Proves: leaving sends the buffered edit at once, well before
;; the buffer would (the bounded `wait-for` gives up long before 3 s).
(t/deftest ^:async leaving-the-tab-sends-buffered-edits-at-once
  (let [leave-s (rx/subject)]
    (await
     (mock/with-mocks*
       {dps/leave-signals (fn [] leave-s)}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id requests store]}]
            (ptk/emit! store (local-commit file-id))
            (await (async/settle))
            (t/is (empty? @requests) "the edit waits in the buffer")
            (rx/push! leave-s :hidden)
            (await (async/wait-for #(= 1 (count @requests))
                                   "leaving sends the edit at once"))
            (t/is (= 1 (count @requests))))))))))

;; Scenario: nothing is buffered when the user leaves. Proves: a leave
;; signal alone sends nothing.
(t/deftest ^:async leaving-with-nothing-buffered-sends-nothing
  (let [leave-s (rx/subject)]
    (await
     (mock/with-mocks*
       {dps/leave-signals (fn [] leave-s)}
       (await
        (with-persistence
          (^:async fn [{:keys [requests store]}]
            (rx/push! leave-s :hidden)
            (await (async/settle))
            (t/is (empty? @requests))
            (t/is (nil? (get-in @store [:persistence :status]))))))))))

(defn- wait-files-saved
  "Emits into `store` an event that runs `force-persist-files-and-wait` for
  `file-ids` and then flips `done`."
  [store file-ids timeout-ms done]
  (ptk/emit! store
             (ptk/reify ::wait-files-saved
               ptk/WatchEvent
               (watch [_ _ _]
                 (rx/concat
                  (dps/force-persist-files-and-wait file-ids timeout-ms)
                  (rx/of (ptk/reify ::files-saved
                           ptk/EffectEvent
                           (effect [_ _ _] (reset! done true)))))))))

;; Scenario: an edit waits in the buffer when an export asks for its file
;; to be saved. Proves: the wait sends the edit and only ends once the save
;; lands.
(t/deftest ^:async waiting-for-a-file-sends-and-waits-for-its-edits
  (let [done (atom false)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id response requests store]}]
         (await
          (mock/with-mocks*
            {refs/persistence (l/derived :persistence store)}
            (ptk/emit! store (local-commit file-id))
            (wait-files-saved store #{file-id} 60000 done)
            (await (async/wait-for #(= 1 (count @requests)) "the edit is sent at once"))
            (await (async/settle))
            (t/is (false? @done) "the wait holds while the save is in flight")
            (rx/push! response {:revn 1})
            (await (async/wait-for #(true? @done) "the landed save ends the wait"))
            (t/is (not (dps/unsaved-changes? @store #{file-id}))))))))))

;; Scenario: a save of one file hangs while another file is exported.
;; Proves: the wait only covers the exported files.
(t/deftest ^:async waiting-for-a-file-ignores-other-files
  (let [done (atom false)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {refs/persistence (l/derived :persistence store)}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the other file's save hangs"))
            (wait-files-saved store #{(uuid/next)} 60000 done)
            (await (async/wait-for #(true? @done) "the wait ends at once")))))))))

;; Scenario: the file's save hangs past the wait. Proves: the wait gives up
;; without raising, and the file still reports unsaved changes.
(t/deftest ^:async waiting-for-a-file-gives-up-after-the-timeout
  (let [done (atom false)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests store]}]
         (await
          (mock/with-mocks*
            {refs/persistence (l/derived :persistence store)}
            (ptk/emit! store (local-commit file-id))
            (wait-files-saved store #{file-id} 1 done)
            (await (async/wait-for #(true? @done) "the wait times out"))
            (t/is (= 1 (count @requests)))
            (t/is (dps/unsaved-changes? @store #{file-id})))))))))

(defn- save-failed-events
  "Starts collecting the `save-failed` audit events that `store` processes."
  [store]
  (let [events (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter #(= ::ev/event (ptk/type %)))
         (rx/map deref)
         (rx/filter #(= "save-failed" (::ev/name %)))
         (rx/subs! #(swap! events conj %)))
    events))

;; Scenario: the backend rejects a save as invalid data. Proves: the user's
;; failure toast comes with one `save-failed` audit event that records why.
(t/deftest ^:async a-failed-save-emits-one-save-failed-event
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [events (save-failed-events store)]
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(= :error (get-in @store [:persistence :status]))
                                "the save fails"))
         (await (async/wait-for #(seq @events) "the event is emitted"))
         (await (async/settle))
         (t/is (= 1 (count @events)))
         (t/is (= {:cause-type :validation
                   :code :referential-integrity
                   :retrying false
                   :queued-commits 1}
                  (select-keys (first @events)
                               [:cause-type :code :retrying :queued-commits])))))
     (fn [_ _]
       (rx/throw (ex-info "invalid" {:type :validation :code :referential-integrity}))))))

;; Buffered commits.

;; Scenario: an edit waits in the buffer for the next flush, then a flush
;; moves it to the queue. Proves: buffered edits are visible in the store
;; until they are queued, so a rollback can reach them.
(t/deftest ^:async buffered-commits-stay-in-the-store-until-queued
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [commit (local-commit file-id)]
         (ptk/emit! store commit)
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "the edit is buffered"))
         (t/is (= [(:id @commit)] (mapv :id (get-in @store [:persistence :buffer]))))
         (t/is (empty? (get-in @store [:persistence :queue])))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(seq @requests) "the flush sends the edit"))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (= [(:id @commit)]
                  (->> (get-in @store [:persistence :queue])
                       (mapcat #(get-in @store [:persistence :index % :commits]))
                       (mapv :id))))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the save lands")))))))

(defn- rename
  [page-id shape-id name]
  {:type :mod-obj :id shape-id :page-id page-id
   :operations [{:type :set :attr :name :val name}]})

(defn- rename-commit
  "A local commit of `file-id` that renames the shape from `from` to `to`,
  with its own undo group."
  [file-id page-id shape-id from to]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :undo-group (uuid/next)
                   :redo-changes [(rename page-id shape-id to)]
                   :undo-changes [(rename page-id shape-id from)]}))

(defn- file-data
  "File data with one page holding one rectangle named `name`."
  [page-id shape-id name]
  {:pages [page-id]
   :pages-index {page-id {:id page-id
                          :name "Page"
                          :objects {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :shapes [shape-id]})
                                    shape-id (cts/setup-shape {:id shape-id :type :rect :name name
                                                               :parent-id uuid/zero :frame-id uuid/zero})}}}})

(defn- shape-name
  [store file-id page-id shape-id]
  (get-in @store [:files file-id :data :pages-index page-id :objects shape-id :name]))

;; Scenario: an edit waits in the buffer when another user's change to the
;; same attribute arrives. The server will apply the remote change first and
;; the buffered edit after it, so the local file must end the same way.
;; Proves: a remote commit is applied under buffered edits too.
(t/deftest ^:async a-remote-commit-goes-under-buffered-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                local    (rename-commit file-id page-id shape-id "Original" "Local")]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data] (file-data page-id shape-id "Local"))))
            (ptk/emit! store local)
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "the local edit is buffered"))
            (ptk/emit! store (dch/commit {:file-id file-id
                                          :file-revn 1
                                          :file-vern 0
                                          :source :remote
                                          :save-undo? false
                                          :redo-changes [(rename page-id shape-id "Remote")]
                                          :undo-changes []}))
            (await (async/settle))
            (t/is (= "Local" (shape-name store file-id page-id shape-id))))))))))

;; Commit boundaries.

;; Scenario: two edits are buffered and saved together. The request carries
;; one group per edit, each with its commit id and its own changes, so the
;; backend can accept or reject them one by one. Proves: merging a buffer
;; for one request keeps the boundaries between commits.
(t/deftest ^:async a-save-sends-each-commit-as-its-own-group
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [commit1 (local-commit file-id)
             commit2 (local-commit file-id)]
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "both edits go in one save"))
         (let [[_ params] (first @requests)]
           (t/is (not (contains? params :changes)))
           (t/is (= [{:commit-id (:id @commit1) :changes (:redo-changes @commit1)}
                     {:commit-id (:id @commit2) :changes (:redo-changes @commit2)}]
                    (:changes-with-metadata params))))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                "the save lands")))))))

;; Scenario: a save is in flight while a later edit waits in the buffer, and
;; something waits for the edits to be saved (as a library sync does before
;; marking the library synced). The save lands and the queue empties, so the
;; status turns :saved. Proves: the waiter is released only once the
;; buffered edit is saved too.
(t/deftest ^:async waiting-for-the-save-covers-the-buffered-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (let [released? #(dps/terminal-status? (:persistence @store))]
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first edit is sent"))
         (ptk/emit! store (local-commit file-id))
         (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                "the second edit waits in the buffer"))
         (rx/push! response {:revn 1})
         (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                      (empty? (get-in @store [:persistence :queue])))
                                "the first save lands"))
         (t/is (seq (get-in @store [:persistence :buffer])))
         (t/is (not (released?)) "the buffered edit is not saved yet")
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the buffered edit is sent"))
         (rx/push! response {:revn 2})
         (await (async/wait-for released? "the waiters are released")))))))

;; Rollback of rejected commits.

;; Scenario: the first save carries two commits; while it is in flight a
;; third one is queued, an edit of another file is queued too, and a fourth
;; one waits in the buffer. The server keeps the first commit and rejects
;; the second. The file goes back to the first commit: the rejected one and
;; every later one of that file are undone and dropped from the queue, the
;; buffer and the undo history, while the other file's edit is still sent.
;; Proves: a rejection leaves the file as the server holds it and the queue
;; keeps going.
(t/deftest ^:async a-rejected-save-undoes-the-rejected-commit-and-every-later-one
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store failures]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                other-id (uuid/next)
                commit1  (rename-commit file-id page-id shape-id "A" "B")
                commit2  (rename-commit file-id page-id shape-id "B" "C")
                commit3  (rename-commit file-id page-id shape-id "C" "D")
                commit4  (rename-commit file-id page-id shape-id "D" "E")
                other    (local-commit other-id)
                entry    (fn [commit] {:undo-group (:undo-group @commit)
                                       :undo-changes (:undo-changes @commit)
                                       :redo-changes (:redo-changes @commit)
                                       :tags #{}})]
            ;; The local data already holds the four renames
            (ptk/emit! store
                       #(-> %
                            (assoc :current-page-id page-id)
                            (assoc-in [:files file-id :data] (file-data page-id shape-id "E"))
                            (assoc-in [:files other-id] {:id other-id :revn 0})
                            (assoc :workspace-undo {:items (mapv entry [commit1 commit2 commit3 commit4])
                                                    :index 3})
                            (assoc-in [:workspace-local :selected] #{shape-id})))
            (ptk/emit! store commit1 commit2 ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the first two commits are sent"))
            (ptk/emit! store commit3 other ::dps/force-persist)
            (await (async/wait-for #(= 3 (count (get-in @store [:persistence :queue])))
                                   "the third commit and the other file's edit are queued"))
            (ptk/emit! store commit4)
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "the fourth commit is buffered"))

            (rx/push! response {:revn 0
                                :rejected {:commit-ids [(:id @commit2)]
                                           :failed-commit-id (:id @commit2)
                                           :error {:type :validation :code :referential-integrity}}})
            (await (async/wait-for #(= 2 (count @requests)) "the other file's edit is sent next"))

            (t/is (= "B" (shape-name store file-id page-id shape-id))
                  "the file holds the commits the server kept")
            (t/is (= [other-id] (mapv #(get-in @store [:persistence :index % :file-id])
                                      (get-in @store [:persistence :queue])))
                  "only the other file's edit is still queued")
            (t/is (empty? (get-in @store [:persistence :buffer])))
            (t/is (= [(:undo-group @commit1)]
                     (mapv :undo-group (get-in @store [:workspace-undo :items]))))
            (t/is (= 0 (get-in @store [:workspace-undo :index])))
            (t/is (= 1 (count @failures)) "the user is told once")
            (t/is (= other-id (get-in (second @requests) [1 :id])))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: an edit is buffered while a save is in flight and the save comes
;; back rejected, so the rollback drops the edit; the 200 ms pending notice
;; of that edit fires afterwards. Proves: with nothing left to save the
;; notice is ignored and the status stays :saved, so waiters such as "Pin
;; this version" are not left hanging.
(t/deftest ^:async a-pending-notice-after-a-rollback-leaves-the-status-saved
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                commit1  (rename-commit file-id page-id shape-id "A" "B")
                commit2  (rename-commit file-id page-id shape-id "B" "C")]
            (ptk/emit! store
                       #(-> %
                            (assoc :current-page-id page-id)
                            (assoc-in [:files file-id :data] (file-data page-id shape-id "C"))))
            (ptk/emit! store commit1 ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the first commit is sent"))
            (ptk/emit! store commit2)
            (rx/push! response {:revn 0
                                :rejected {:commit-ids [(:id @commit1)]
                                           :failed-commit-id (:id @commit1)
                                           :error {:type :validation :code :referential-integrity}}})
            (await (async/wait-for #(and (= :saved (get-in @store [:persistence :status]))
                                         (empty? (get-in @store [:persistence :buffer])))
                                   "the rollback drops both commits"))
            ;; The notice the dropped edit scheduled
            (ptk/emit! store (#'dps/update-status :pending))
            (await (async/settle))
            (t/is (= :saved (get-in @store [:persistence :status]))))))))))

(defn- set-attr
  [page-id shape-id attr val]
  {:type :mod-obj :id shape-id :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn- attr-commit
  "A local commit of `file-id` that sets `attr` from `from` to `to`."
  [file-id page-id shape-id attr from to]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :undo-group (uuid/next)
                   :redo-changes [(set-attr page-id shape-id attr to)]
                   :undo-changes [(set-attr page-id shape-id attr from)]}))

(defn- remote-commit
  [file-id revn changes]
  (dch/commit {:file-id file-id
               :file-revn revn
               :file-vern 0
               :source :remote
               :save-undo? false
               :redo-changes changes
               :undo-changes []}))

(defn- shape-attr
  [store file-id page-id shape-id attr]
  (get-in @store [:files file-id :data :pages-index page-id :objects shape-id attr]))

(def ^:private rejected-second
  (fn [commit]
    {:revn 0
     :rejected {:commit-ids [(:id @commit)]
                :failed-commit-id (:id @commit)
                :error {:type :validation :code :referential-integrity}}}))

;; Scenario: two edits wait in the buffer, the second one changing the
;; opacity, when a peer's opacity change arrives. The server keeps the first
;; edit and rejects the second. The server holds the peer's opacity, so the
;; local file must too, not the opacity from before the rejected edit.
;; Proves: the rollback applies the peer's changes again over the undone
;; commits.
(t/deftest ^:async a-rollback-keeps-a-peer-change-to-the-same-attribute
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                commit1  (rename-commit file-id page-id shape-id "A" "B")
                commit2  (attr-commit file-id page-id shape-id :opacity 0.5 0.9)]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data]
                                            (-> (file-data page-id shape-id "B")
                                                (assoc-in [:pages-index page-id :objects shape-id :opacity] 0.9)))))
            (ptk/emit! store commit1 commit2)
            (await (async/wait-for #(= 2 (count (get-in @store [:persistence :buffer])))
                                   "both edits are buffered"))
            (ptk/emit! store (remote-commit file-id 1 [(set-attr page-id shape-id :opacity 0.3)]))
            (await (async/settle))
            (t/is (= 0.9 (shape-attr store file-id page-id shape-id :opacity))
                  "the local edit stays on top of the peer's while pending")
            (ptk/emit! store ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the edits are sent"))
            (rx/push! response (rejected-second commit2))
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the rollback settles"))
            (t/is (= 0.3 (shape-attr store file-id page-id shape-id :opacity))
                  "the peer's opacity, as on the server")
            (t/is (= "B" (shape-name store file-id page-id shape-id))
                  "the kept edit stays"))))))))

;; Scenario: a peer's opacity change arrives while an opacity edit is
;; pending; that edit is saved (on the server it lands after the peer's).
;; Later two new edits are sent and the second is rejected. The rollback
;; must not apply the old peer change again, which would undo the saved
;; edit. Proves: remote changes older than the oldest pending commit are
;; forgotten once the commits pending before them are saved.
(t/deftest ^:async a-rollback-ignores-peer-changes-older-than-the-pending-commits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                commit0  (attr-commit file-id page-id shape-id :opacity 0.5 0.7)
                commit1  (rename-commit file-id page-id shape-id "A" "B")
                commit2  (rename-commit file-id page-id shape-id "B" "C")]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data]
                                            (-> (file-data page-id shape-id "A")
                                                (assoc-in [:pages-index page-id :objects shape-id :opacity] 0.7)))))
            (ptk/emit! store commit0)
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer])) "the edit is buffered"))
            (ptk/emit! store (remote-commit file-id 1 [(set-attr page-id shape-id :opacity 0.3)]))
            (ptk/emit! store ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the opacity edit is sent"))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the opacity edit is saved"))
            (t/is (empty? (get-in @store [:persistence :remote-changes file-id]))
                  "nothing pending, nothing kept")
            (ptk/emit! store
                       #(assoc-in % [:files file-id :data :pages-index page-id :objects shape-id :name] "C")
                       commit1 commit2 ::dps/force-persist)
            (await (async/wait-for #(= 2 (count @requests)) "the renames are sent"))
            (rx/push! response (rejected-second commit2))
            (await (async/wait-for #(= "B" (shape-name store file-id page-id shape-id))
                                   "the rejected rename is undone"))
            (t/is (= 0.7 (shape-attr store file-id page-id shape-id :opacity))
                  "the saved opacity edit stays"))))))))

(defn- grouped
  "`commit` with its undo group set to `group`: one action that makes
  several commits gives them the same undo group."
  [commit group]
  (ptk/data-event ::dch/commit (assoc @commit :undo-group group)))

(defn- with-library
  "Store state holding this file's data and a library file `lib-id` with
  one rectangle each, named `name` and `lib-name`."
  [file-id lib-id page-id shape-id name lshape-id lib-name]
  #(-> %
       (assoc :current-page-id page-id)
       (assoc-in [:files file-id :data] (file-data page-id shape-id name))
       (assoc-in [:files lib-id] {:id lib-id :revn 0 :data (file-data page-id lshape-id lib-name)})))

(defn- sent-changes
  "The changes of each group of the `n`th save request."
  [requests n]
  (mapv :changes (get-in (nth @requests n) [1 :changes-with-metadata])))

;; Scenario: one action makes a commit in this file and one in a library
;; (as updating a main component from a copy does). The library also has an
;; unrelated edit before the action and another after it. This file's save
;; comes back with the action's commit rejected while the library's commits
;; are still queued. Proves: the rejection reverts the whole action, so the
;; library drops its commit of the action and every later one, and keeps
;; the edit made before it.
(t/deftest ^:async a-rejection-reverts-the-pending-commits-of-the-same-action-in-other-files
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [lib-id    (uuid/next)
                page-id   (uuid/next)
                shape-id  (uuid/next)
                lshape-id (uuid/next)
                group     (uuid/next)
                commit1   (rename-commit file-id page-id shape-id "A" "B")
                commit2   (grouped (rename-commit file-id page-id shape-id "B" "C") group)
                before    (rename-commit lib-id page-id lshape-id "X" "Y")
                linked    (grouped (rename-commit lib-id page-id lshape-id "Y" "Z") group)
                after     (rename-commit lib-id page-id lshape-id "Z" "W")]
            (ptk/emit! store (with-library file-id lib-id page-id shape-id "C" lshape-id "W"))
            (ptk/emit! store commit1 commit2 before linked after ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "this file's save is sent"))
            (rx/push! response (rejected-second commit2))
            (await (async/wait-for #(= 2 (count @requests)) "the library's save is sent next"))
            (t/is (= "B" (shape-name store file-id page-id shape-id)))
            (t/is (= "Y" (shape-name store lib-id page-id lshape-id))
                  "the library goes back to the edit made before the action")
            (t/is (= lib-id (get-in (second @requests) [1 :id])))
            (t/is (= [[(rename page-id lshape-id "Y")]] (sent-changes requests 1))
                  "only the library's edit made before the action is saved")
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: one action makes a commit in a library and one in this file.
;; The library's is saved first; then this file's save comes back with the
;; action's commit rejected. The library's commit can no longer be dropped.
;; Proves: a new commit undoes the saved part of the rejected action.
(t/deftest ^:async a-rejection-undoes-the-saved-commits-of-the-same-action-with-a-new-commit
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [lib-id    (uuid/next)
                page-id   (uuid/next)
                shape-id  (uuid/next)
                lshape-id (uuid/next)
                group     (uuid/next)
                linked    (grouped (rename-commit lib-id page-id lshape-id "X" "Y") group)
                commit    (grouped (rename-commit file-id page-id shape-id "A" "B") group)]
            (ptk/emit! store (with-library file-id lib-id page-id shape-id "B" lshape-id "Y"))
            (ptk/emit! store linked commit ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
            (t/is (= lib-id (get-in (first @requests) [1 :id])))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
            (rx/push! response (rejected-second commit))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "a new commit undoes the library's"))
            (ptk/emit! store ::dps/force-persist)
            (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent"))
            (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
            (t/is (= [[(rename page-id lshape-id "X")]] (sent-changes requests 2)))
            (t/is (= "A" (shape-name store file-id page-id shape-id)))
            (t/is (= "X" (shape-name store lib-id page-id lshape-id)))
            (rx/push! response {:revn 2})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: as above, while the user has an undo transaction open. Proves:
;; the undo commit gets its own group, not the transaction's, so a later
;; rejection in the transaction does not revert it.
(t/deftest ^:async the-undo-of-a-saved-commit-stays-out-of-an-open-transaction
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [lib-id    (uuid/next)
                page-id   (uuid/next)
                shape-id  (uuid/next)
                lshape-id (uuid/next)
                group     (uuid/next)
                linked    (grouped (rename-commit lib-id page-id lshape-id "X" "Y") group)
                commit    (grouped (rename-commit file-id page-id shape-id "A" "B") group)]
            (ptk/emit! store (with-library file-id lib-id page-id shape-id "B" lshape-id "Y"))
            (ptk/emit! store linked commit ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
            (ptk/emit! store (dwu/start-undo-transaction (js/Symbol) :timeout 0))
            (let [tx-group (get-in @store [:workspace-undo :transaction :commit-group])]
              (rx/push! response (rejected-second commit))
              (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                     "a new commit undoes the library's"))
              (t/is (some? tx-group))
              (t/is (not= tx-group (get-in @store [:persistence :buffer 0 :undo-group])))
              (ptk/emit! store ::dps/force-persist)
              (await (async/wait-for #(= 3 (count @requests)) "the new commit is sent"))
              (rx/push! response {:revn 2})
              (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                     "the queue drains"))))))))))

;; Scenario: as above, but the library is no longer loaded when this file's
;; save comes back. Proves: the undo of the library's saved commit is still
;; sent, without touching local data the library no longer has.
(t/deftest ^:async a-rejection-queues-the-undo-of-a-saved-commit-of-a-file-not-loaded
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [lib-id    (uuid/next)
                page-id   (uuid/next)
                shape-id  (uuid/next)
                lshape-id (uuid/next)
                group     (uuid/next)
                linked    (grouped (rename-commit lib-id page-id lshape-id "X" "Y") group)
                commit    (grouped (rename-commit file-id page-id shape-id "A" "B") group)]
            (ptk/emit! store (with-library file-id lib-id page-id shape-id "B" lshape-id "Y"))
            (ptk/emit! store linked commit ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the library's save is sent first"))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= 2 (count @requests)) "this file's save is sent"))
            (ptk/emit! store #(update % :files dissoc lib-id))
            (rx/push! response (rejected-second commit))
            (await (async/wait-for #(= 3 (count @requests)) "the undo is queued and sent"))
            (t/is (= lib-id (get-in (nth @requests 2) [1 :id])))
            (t/is (= [[(rename page-id lshape-id "X")]] (sent-changes requests 2)))
            (t/is (empty? (get-in @store [:persistence :buffer])))
            (t/is (nil? (get-in @store [:files lib-id])) "no local data is made up")
            (t/is (= "A" (shape-name store file-id page-id shape-id)))
            (rx/push! response {:revn 2})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: a library edit waits to be saved when a peer's change to this
;; file arrives, and both files have a shape with the same id. Proves: a
;; remote commit goes under the pending commits of its own file only.
(t/deftest ^:async a-remote-commit-ignores-the-pending-commits-of-other-files
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [lib-id   (uuid/next)
                page-id  (uuid/next)
                shape-id (uuid/next)]
            (ptk/emit! store (with-library file-id lib-id page-id shape-id "A" shape-id "Y"))
            (ptk/emit! store (rename-commit lib-id page-id shape-id "X" "Y"))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "the library edit is buffered"))
            (ptk/emit! store (remote-commit file-id 1 [(set-attr page-id shape-id :opacity 0.3)]))
            (await (async/settle))
            (t/is (= 0.3 (shape-attr store file-id page-id shape-id :opacity)))
            (t/is (= "A" (shape-name store file-id page-id shape-id))
                  "the library edit is not replayed on this file"))))))))

;; Scenario: one action makes two commits in this file and the save keeps
;; the first and rejects the second. Proves: the kept commit of the action
;; is undone too, with a new commit, so no action is left half done.
(t/deftest ^:async a-rejection-undoes-the-kept-commits-of-the-same-action
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                group    (uuid/next)
                commit1  (grouped (rename-commit file-id page-id shape-id "A" "B") group)
                commit2  (grouped (attr-commit file-id page-id shape-id :opacity 1 0.5) group)]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data]
                                            (-> (file-data page-id shape-id "B")
                                                (assoc-in [:pages-index page-id :objects shape-id :opacity] 0.5)))))
            (ptk/emit! store commit1 commit2 ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the action is sent"))
            (rx/push! response (rejected-second commit2))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "a new commit undoes the kept one"))
            (ptk/emit! store ::dps/force-persist)
            (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent"))
            (t/is (= [[(rename page-id shape-id "A")]] (sent-changes requests 1)))
            (t/is (= "A" (shape-name store file-id page-id shape-id)))
            (t/is (= 1 (shape-attr store file-id page-id shape-id :opacity)))
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: applying a token to five shapes makes three commits inside one
;; undo transaction, all sent in one save; the server keeps the first two
;; and rejects the third. Proves: the whole action is reverted, the kept
;; commits by a new commit, so opacity and applied tokens are back to their
;; starting values on every shape.
(t/deftest ^:async a-rejection-reverts-every-commit-of-an-undo-transaction
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id   (uuid/next)
                shape-ids (into [] (repeatedly 5 uuid/next))
                shapes    (map #(cts/setup-shape {:id % :type :rect :name "R" :opacity 1
                                                  :parent-id uuid/zero :frame-id uuid/zero})
                               shape-ids)
                apply-to  (fn [ids]
                            (dch/commit-changes
                             {:redo-changes (into []
                                                  (mapcat (fn [id]
                                                            [(set-attr page-id id :opacity 0.5)
                                                             (set-attr page-id id :applied-tokens {:opacity "half"})]))
                                                  ids)
                              :undo-changes (into []
                                                  (mapcat (fn [id]
                                                            [(set-attr page-id id :opacity 1)
                                                             (set-attr page-id id :applied-tokens nil)]))
                                                  ids)}))
                tx-id     (js/Symbol)]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data]
                                            {:pages [page-id]
                                             :pages-index {page-id {:id page-id
                                                                    :name "Page"
                                                                    :objects (into {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame
                                                                                                                :shapes shape-ids})}
                                                                                   (map (juxt :id identity))
                                                                                   shapes)}}})))
            (ptk/emit! store
                       (dwu/start-undo-transaction tx-id :timeout 0)
                       (apply-to (subvec shape-ids 0 2))
                       (apply-to (subvec shape-ids 2 4))
                       (apply-to (subvec shape-ids 4 5))
                       (dwu/commit-undo-transaction tx-id)
                       ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the action is sent"))
            (let [groups (get-in (first @requests) [1 :changes-with-metadata])
                  third  (:commit-id (nth groups 2))]
              (t/is (= 3 (count groups)) "the save carries the three commits")
              (rx/push! response {:revn 0
                                  :rejected {:commit-ids [third]
                                             :failed-commit-id third
                                             :error {:type :validation :code :referential-integrity}}}))
            (await (async/wait-for #(seq (get-in @store [:persistence :buffer]))
                                   "a new commit undoes the kept ones"))
            (ptk/emit! store ::dps/force-persist)
            (await (async/wait-for #(= 2 (count @requests)) "the new commit is sent"))
            (doseq [id shape-ids]
              (t/is (= 1 (shape-attr store file-id page-id id :opacity)))
              (t/is (nil? (shape-attr store file-id page-id id :applied-tokens))))
            (t/is (= #{1}
                     (into #{}
                           (comp (mapcat :changes)
                                 (mapcat :operations)
                                 (filter #(= :opacity (:attr %)))
                                 (map :val))
                           (get-in (second @requests) [1 :changes-with-metadata])))
                  "the new commit puts the kept opacity back on the server")
            (rx/push! response {:revn 1})
            (await (async/wait-for #(= :saved (get-in @store [:persistence :status]))
                                   "the queue drains")))))))))

;; Scenario: the rejected commit added the text being edited. Proves: the
;; rollback leaves edition mode, so the editor does not keep pointing at a
;; shape that no longer exists.
(t/deftest ^:async a-rollback-leaves-the-edition-of-a-removed-shape
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests store]}]
       (await
        (mock/with-mocks*
          {mw/ask! (mock/stub (fn [_] (rx/empty)))}
          (let [page-id  (uuid/next)
                shape-id (uuid/next)
                text-id  (uuid/next)
                text     (cts/setup-shape {:id text-id :type :text :parent-id uuid/zero :frame-id uuid/zero})
                commit   (ptk/data-event ::dch/commit
                                         {:id (uuid/next)
                                          :file-id file-id
                                          :file-revn 0
                                          :file-vern 0
                                          :source :local
                                          :features #{}
                                          :undo-group (uuid/next)
                                          :redo-changes [{:type :add-obj :id text-id :page-id page-id
                                                          :parent-id uuid/zero :frame-id uuid/zero :obj text}]
                                          :undo-changes [{:type :del-obj :id text-id :page-id page-id}]})]
            (ptk/emit! store #(-> %
                                  (assoc :current-page-id page-id)
                                  (assoc-in [:files file-id :data]
                                            (-> (file-data page-id shape-id "A")
                                                (assoc-in [:pages-index page-id :objects text-id] text)
                                                (update-in [:pages-index page-id :objects uuid/zero :shapes] conj text-id)))
                                  (assoc-in [:workspace-local :edition] text-id)))
            (ptk/emit! store commit ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the new text is sent"))
            (rx/push! response (rejected-second commit))
            (await (async/wait-for #(nil? (get-in @store [:workspace-local :edition]))
                                   "the rollback leaves edition mode"))
            (t/is (nil? (get-in @store [:files file-id :data :pages-index page-id :objects text-id]))))))))))

(t/deftest forgetting-undo-groups-cuts-the-history-at-the-first-match
  (let [[g1 g2 g3] (repeatedly 3 uuid/next)
        state      {:workspace-undo {:items [{:undo-group g1} {:undo-group g2} {:undo-group g3}]
                                     :index 2
                                     :transaction {:undo-group g3 :undo-changes [{:type :mod-page}]}}}
        undo       (:workspace-undo (ptk/update (dwu/forget-undo-groups #{g2 g3}) state))]
    (t/is (= [g1] (mapv :undo-group (:items undo))))
    (t/is (= 0 (:index undo)))
    (t/is (empty? (get-in undo [:transaction :undo-changes])) "the open transaction is emptied")
    (t/is (= g3 (get-in undo [:transaction :commit-group]))
          "its later commits keep the group of its earlier ones")))

(t/deftest forgetting-undo-groups-clears-the-history-without-a-match
  (let [state {:workspace-undo {:items [{:undo-group (uuid/next)}] :index 0}}
        undo  (:workspace-undo (ptk/update (dwu/forget-undo-groups #{(uuid/next)}) state))]
    (t/is (empty? (:items undo)))
    (t/is (nil? (:index undo)))))
