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
  `frontend-tests.data.persistence-retry-test`, the rollback of a partly
  rejected save in `frontend-tests.data.persistence-rollback-test`.

  The watchdog and read-only tests are fully async: `mock/with-mocks*`
  installs the doubles, `^:async` bodies `await` observable effects (never
  assert straight after a trigger), and completion propagates through
  promises — test code threads no `done`. The remaining tests still use
  `mock/with-mocks` with an explicit `done` chain."
  (:require
   [app.common.files.changes :as cpc]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dcmt]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.commits :as pcm]
   [app.main.data.render-wasm :as drw]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.notifications :as dwn]
   [app.main.data.workspace.undo :as dwu]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp :refer [local-commit with-persistence]]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

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
            pstate   {:status status
                      :error {:cause-type cause-type :commit-id first-id}
                      :queue (into #queue [] [first-id next-id other-id])
                      :index {first-id {:file-id file-id :file-vern 0
                                        :commits [{:redo-changes [{:type :add-obj :id shape-id :obj shape
                                                                   :page-id page-id :parent-id uuid/zero
                                                                   :frame-id uuid/zero}]}]}
                              next-id {:file-id file-id :file-vern 0
                                       :commits [{:redo-changes [(thp/rename page-id shape-id "Retained rectangle")]}]}
                              other-id {:file-id (uuid/next) :file-vern 0
                                        :commits [{:redo-changes [(thp/rename page-id shape-id "Another file")]}]}}}
            state    {:persistence pstate}
            loaded   (ptk/update (#'dw/bundle-fetched {:file file :file-id file-id :thumbnails {}})
                                 state)
            restored (get-in loaded [:files file-id :data :pages-index page-id :objects shape-id])]
        (t/is (= restored? (some? restored)))
        (when restored?
          (t/is (= "Retained rectangle" (:name restored))
                "queued edits apply in order and exclude other files"))
        (if (zero? vern)
          (t/is (identical? pstate (:persistence loaded))
                "restoring the editor must preserve the save queue")
          (t/is (= [[] [] (get-in pstate [:index other-id :commits])]
                   (mapv #(get-in loaded [:persistence :index % :commits]) [first-id next-id other-id]))
                "the commits made on the replaced version are forgotten"))))))

;; Scenario: a file loads again while an edit of it waits in the buffer.
;; Proves: buffered edits apply too, after the queued ones, and those of
;; other files do not.
(t/deftest reopening-restores-the-buffered-edits-after-the-queued-ones
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        commit-id (uuid/next)
        file      {:id file-id
                   :vern 0
                   :data {:pages [page-id]
                          :pages-index {page-id {:id page-id :name "Page"
                                                 :objects {uuid/zero
                                                           (cts/setup-shape {:id uuid/zero :type :frame})}}}}}
        pstate    {:status :saving
                   :queue (into #queue [] [commit-id])
                   :index {commit-id {:file-id file-id :file-vern 0
                                      :commits [{:redo-changes [(thp/rename page-id uuid/zero "Queued")]}]}}
                   :buffer [{:file-id file-id :file-vern 0 :redo-changes [(thp/rename page-id uuid/zero "Buffered")]}
                            {:file-id (uuid/next) :file-vern 0 :redo-changes [(thp/rename page-id uuid/zero "Another file")]}]}
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

(defn- ^:async run-read-only-phases
  "Drives the read-only save scenario presuming asynchronous APIs: every
  phase emits its triggers, awaits the transport round-trip, and only
  then asserts the observed state."
  [store response requests file-id read-only-event errors]
  (ptk/emit! store (dps/initialize-persistence)
             (local-commit file-id)
             read-only-event
             ::dps/force-persist)
  (await (async/await-response requests response (thp/save-response 0)))
  (t/is (= :saved (get-in @store [:persistence :status])))
  (t/is (empty? (get-in @store [:persistence :queue])))

  (ptk/emit! store (drw/context-restored)
             #(assoc-in % [:workspace-global :read-only?] false)
             (local-commit file-id)
             ::dps/force-persist)
  (await (async/await-response requests response (thp/save-response 1)))
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
       (rx/push! response (thp/save-response 0))
       (await (async/wait-for-store store #(= 1 (count (get-in @store [:persistence :queue])))
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

       (rx/push! response (thp/save-response 1))
       (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
       (^:async fn [{:keys [clock ticks reports causes requests requests$ store file-id]}]
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
            (await (async/wait-for-store store #(= :retrying (get-in @store [:persistence :status]))
                                         "transient failure retries"))
            (t/is (= 1 (count @requests)))
            ;; Phase 2 — the timer fires and the resend goes out, hanging.
            (rx/push! timer-s :tick)
            (await (async/wait-for #(= 2 (count @requests)) "retry resends" {:signal requests$}))
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
;; `settle` tick when it asserts only absence). The fixture is
;; `with-persistence` (`frontend-tests.helpers.persistence`).

(t/deftest ^:async a-save-sends-forward-changes-when-there-is-no-local-inverse
  (await
   (with-persistence
     (^:async fn [{:keys [file-id session-id requests requests$ response store]}]
       (let [commit (local-commit file-id)]
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the save is sent" {:signal requests$}))
         (t/is (= [[:update-file-v2
                    {:id file-id :session-id session-id :revn 0 :vern 0 :features #{}
                     :repair-in-response true
                     :commits [{:commit-id (:id @commit)
                                :changes (:redo-changes @commit)}]}]]
                  @requests))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the save lands")))))))

(t/deftest ^:async a-queue-entry-emptied-by-a-restore-is-not-sent
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       ;; The file may no longer have a loaded version when this entry
       ;; reaches the head of the queue.
       (let [entry (pcm/queue-entry [@(local-commit file-id)])
             id    (:id entry)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id (assoc entry :commits [])}
                                            :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the empty entry is acknowledged"))
         (t/is (empty? @requests)))))))

(t/deftest ^:async a-backend-without-the-new-save-command-does-not-lose-pending-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests store]}]
       (await
        (mock/with-mocks*
          {refs/persistence (l/derived :persistence store)}
          (ptk/emit! store (local-commit file-id) ::dps/force-persist)
          (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
                                       "the missing endpoint fails the save"))
          (let [failure (atom nil)]
            (await (async/observe (dps/wait-persisted-or-error)
                                  :on-error #(reset! failure (ex-data %))))
            (t/is (= :not-found (:cause-type @failure))))
          (t/is (= 1 (count (get-in @store [:persistence :queue]))))
          (t/is (= [:update-file-v2] (mapv first @requests))
                "the save must not fall back to the old command"))))
     (fn [_ _]
       (rp/handle-response {:status 404 :body {:type :not-found}
                            :uri "https://penpot.example.com/api/main/methods/update-file-v2"})))))

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
       (await (async/wait-for-store store #(and (= :error (get-in @store [:persistence :status]))
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
       (await (async/wait-for-store store #(and (= :error (get-in @store [:persistence :status]))
                                                (= 1 (count (get-in @store [:persistence :queue]))))
                                    "the save fails terminally"))
       ;; Edit rights come back: a team-role change is one of the two real
       ;; recovery triggers (the other is opening a file, whose event does
       ;; real work and is not usable as a bare signal here).
       (ptk/emit! store
                  #(assoc-in % [:permissions :can-edit] true)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                (empty? (get-in @store [:persistence :queue])))
                                    "re-entering the file settles the queue"))
       (t/is (nil? (get-in @store [:persistence :error]))
             "the halt is cleared once the user comes back")
       (t/is (empty? @requests)
             "the edit that cannot be saved is not replayed on re-entry")
       (t/is (= 1 (count @failures)) "the user is still notified exactly once")))))

;; Scenario: the user renames a shape and is made a viewer before the save,
;; so it fails for lost permission; the role event comes again, then edit
;; rights return. Proves: the edit is kept while the user is a viewer, and
;; released with edit rights back, the file goes back to the server's data
;; and the history loses the edit, so the canvas never shows a change the
;; server does not have.
(t/deftest ^:async regaining-edit-rights-reverts-the-released-edit
  (await
   (with-persistence
     (^:async fn [{:keys [file-id requests failures store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             commit   (thp/rename-commit file-id page-id shape-id "A" "B")]
         (ptk/emit! store
                    (thp/open-file file-id page-id (thp/file-data page-id shape-id "B"))
                    #(assoc % :workspace-undo {:items [(thp/undo-entry commit)] :index 0})
                    commit
                    #(assoc-in % [:permissions :can-edit] false)
                    ::dps/force-persist)
         (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
                                      "lost permission fails the save"))

         (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
         (await (async/settle))
         (t/is (= 1 (count (get-in @store [:persistence :queue])))
               "a viewer keeps the edit")
         (t/is (= "B" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 2 (count @failures))
               "the count notice shows again over the role change notice")

         (ptk/emit! store
                    #(assoc-in % [:permissions :can-edit] true)
                    (ptk/data-event :app.main.data.common/change-team-role))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "edit rights back release the edit"))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id))
               "the file shows the server's data")
         (t/is (empty? (get-in @store [:workspace-undo :items]))
               "the released edit leaves the history")
         (t/is (empty? @requests) "the released edit is not sent"))))))

;; Answers every save with an unexpected server error, a terminal failure.
(defn- server-error
  [_ _]
  (rp/handle-response {:status 500
                       :body {:type :server-error :code :unhandled}
                       :uri "https://penpot.example.com/api/main/methods/update-file"}))

;; Scenario: the user is in the Inspect tab (read-only) when a save fails
;; terminally, then goes back to Design while the dialog is up. The
;; workspace stays read-only until the failure is released, and then takes
;; the mode the user last asked for. Proves: the lock does not lose the
;; read-only mode the workspace had.
(t/deftest ^:async a-failed-save-locks-the-workspace-until-it-recovers
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (ptk/emit! store #(assoc-in % [:workspace-global :read-only?] true)
                  (local-commit file-id)
                  ::dps/force-persist)
       (await (async/wait-for-store store #(some? (get-in @store [:workspace-global :persistence-lock]))
                                    "the failed save locks the workspace"))
       (t/is (true? (get-in @store [:workspace-global :read-only?])))
       (t/is (true? (get-in @store [:workspace-global :persistence-lock :read-only?])))

       (ptk/emit! store (dwc/set-workspace-read-only false))
       (await (async/wait-for-store store #(false? (get-in @store [:workspace-global :persistence-lock :read-only?]))
                                    "the requested mode is kept for later"))
       (t/is (true? (get-in @store [:workspace-global :read-only?]))
             "the workspace stays read-only while saves fail")

       (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :persistence-lock]))
                                    "recovery lifts the lock"))
       (t/is (false? (get-in @store [:workspace-global :read-only?]))
             "the workspace takes the mode the user asked for"))
     server-error)))

;; Scenario: the user is made a viewer while an edit waits to be saved. The
;; save fails for lost permission, but the workspace is already read-only
;; for a viewer: no lock and no dialog, only the failure toast. Proves: a
;; viewer is never blocked from looking at the file.
(t/deftest ^:async a-viewer-is-not-locked-by-a-failed-save
  (await
   (with-persistence
     (^:async fn [{:keys [file-id failures store]}]
       (ptk/emit! store (local-commit file-id)
                  #(assoc-in % [:permissions :can-edit] false)
                  ::dps/force-persist)
       (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
                                    "lost permission fails the save"))
       (await (async/settle))
       (t/is (nil? (get-in @store [:workspace-global :persistence-lock])))
       (t/is (nil? (get-in @store [:workspace-global :read-only?])))
       (t/is (= 1 (count @failures)) "the user is told by the toast")))))

;; Scenario: saves fail, then the WebGL context is lost, then saves recover
;; before the context comes back. Proves: lifting the save lock does not
;; unlock a workspace the lost context still holds read-only.
(t/deftest ^:async a-lost-render-context-outlasts-the-save-lock
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (await (async/wait-for-store store #(some? (get-in @store [:workspace-global :persistence-lock]))
                                    "the failed save locks the workspace"))
       (ptk/emit! store (drw/context-lost)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :persistence-lock]))
                                    "recovery lifts the save lock"))
       (t/is (true? (get-in @store [:workspace-global :read-only?]))
             "the lost context keeps the workspace read-only")

       (ptk/emit! store (drw/context-restored))
       (await (async/wait-for-store store #(false? (get-in @store [:workspace-global :read-only?]))
                                    "the restored context brings back the first mode")))
     server-error)))

;; Scenario: two files have pending edits and the first send fails
;; terminally. Opening a file again releases the failed file, the other file
;; still saves, and the editor settles saved instead of staying halted.
;; Proves: one file that cannot be saved does not block every other file.
(t/deftest ^:async terminal-failure-does-not-block-other-files
  (let [files (into [] (repeatedly 2 uuid/next))
        sends (atom 0)]
    (await
     (with-persistence
       (^:async fn [{:keys [requests requests$ store]}]
         (ptk/emit! store
                    #(assoc % :files (into (:files %)
                                           (map (fn [id] [id {:id id :revn 0}]))
                                           files))
                    (local-commit (nth files 0))
                    (local-commit (nth files 1))
                    ::dps/force-persist)
         (await (async/wait-for #(seq @requests) "the first file is sent" {:signal requests$}))
         (let [failed-file (get-in (second (first @requests)) [:id])]
           (await (async/wait-for-store store #(and (= :error (get-in @store [:persistence :status]))
                                                    (not (empty? (get-in @store [:persistence :queue]))))
                                        "the terminal failure retains the queue"))
           ;; The user acts again: recovery releases the failed file and drains
           ;; the rest.
           (ptk/emit! store (ptk/data-event :app.main.data.common/change-team-role))
           (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
           (rx/of (thp/save-response 0))))))))

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
            i18n/tr      (mock/stub #(str "translated:" %))
            ;; Keep the workspace error in this store instead of scheduling a global logout.
            rt/get-current-href (fn [] "/?screen=workspace")
            st/async-emit! (fn [& events] (js/queueMicrotask #(apply ptk/emit! store events)))}
           (ptk/emit! store (local-commit file-id) ::dps/force-persist)
           (.error response (ex-info "Raw transport details" {:type cause-type}))
           (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
                                        "failed request errors the save"))
           (t/is (= :error (get-in @store [:persistence :status])))
           (let [data (get-in @store [:persistence :error])]
             (ptk/handle-error (assoc data ::errors/instance (ex-info "Save failed" data))))
           (if (= cause-type :authentication)
             (do (await (async/wait-for-store store #(= :authentication (get-in @store [:exception :type]))
                                              "the workspace handles the authentication failure"))
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
         (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [commit (pcm/queue-entry [@(local-commit file-id)])
             id     (:id commit)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id commit} :run-id (uuid/next)
                                            :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= 1 (count @requests)) "dangling commit is sent" {:signal requests$}))
         (t/is (= 1 (count @requests)))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "recovered save lands"))
         (t/is (= :saved (get-in @store [:persistence :status])))
         (t/is (empty? (get-in @store [:persistence :queue]))))))))

(defn- check-active-request-not-resent
  "Sends a commit, interrupts the active request with `interrupt` and
  reinitializes persistence: the pending request must not be sent again."
  [interrupt]
  (with-persistence
    (^:async fn [{:keys [file-id response requests requests$ store]}]
      (apply ptk/emit! store (local-commit file-id) ::dps/force-persist interrupt)
      (ptk/emit! store (dps/initialize-persistence))
      (await (async/wait-for #(= 1 (count @requests)) "only the active request is sent" {:signal requests$}))
      (t/is (= 1 (count @requests)))
      (rx/push! response (thp/save-response 0))
      (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
       (await (async/wait-for-store store #(and (= :error (get-in @store [:persistence :status]))
                                                (= 1 (count (get-in @store [:persistence :queue]))))
                                    "permission loss fails the save"))
       ;; Permission comes back and a file is opened again.
       (ptk/emit! store #(assoc-in % [:permissions :can-edit] true)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
       (await (async/wait-for-store store #(and (= :error (get-in @store [:persistence :status]))
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
       (rx/push! response (thp/save-response 0))
       (await (async/wait-for-store store #(= 1 (count (get-in @store [:persistence :queue])))
                                    "acknowledged commit stays queued"))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (ptk/emit! store (dps/initialize-persistence))
       (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [commit (assoc (pcm/queue-entry [@(local-commit file-id)]) ::dps/request-id (uuid/next))
             id     (:id commit)]
         (ptk/emit! store
                    #(assoc % :persistence {:queue (conj #queue [] id)
                                            :index {id commit} :status :saving})
                    (dps/initialize-persistence))
         (await (async/wait-for #(= 1 (count @requests)) "the commit is sent again" {:signal requests$}))
         (t/is (= [id] (mapv :commit-id (get-in @requests [0 1 :commits]))))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (ptk/emit! store (local-commit file-id)
                  (dps/initialize-persistence)
                  ::dps/force-persist)
       ;; The predicate reads both the store and the request counter, so it
       ;; passes the merged signal; the wrapper only provides the store one.
       (await (async/wait-for #(and (= 1 (count @requests))
                                    (= 1 (count (get-in @store [:persistence :queue]))))
                              "buffered edit is sent once"
                              {:signal (rx/merge requests$ (ptk/input-stream store))}))
       (t/is (= 1 (count @requests)))
       (t/is (= 1 (count (get-in @store [:persistence :queue]))))
       (rx/push! response (thp/save-response 0))
       (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
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
       (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                (empty? (get-in @store [:persistence :queue])))
                                    "synchronous save completes"))
       (t/is (= :saved (get-in @store [:persistence :status])))
       (t/is (empty? (get-in @store [:persistence :queue]))))
     (fn [_ _] (rx/of (thp/save-response 0))))))

;; Leave-page guard tests.

;; Scenario: an edit is buffered, then sent, then saved. The leave-page
;; guard turns on with the buffered edit, stays on while the save is in
;; flight, and turns off once it lands. Proves: unsaved edits always keep
;; the browser's leave-page warning on.
(t/deftest ^:async unsaved-edits-keep-the-leave-page-guard
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store tasks]}]
       (ptk/emit! store (local-commit file-id))
       (await (async/wait-for #(contains? @tasks :persistence)
                              "a buffered edit turns the guard on"))
       (ptk/emit! store ::dps/force-persist)
       (await (async/wait-for #(seq @requests) "the edit is sent" {:signal requests$}))
       (t/is (contains? @tasks :persistence) "the guard stays on while the save is in flight")
       (rx/push! response (thp/save-response 0))
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
     (^:async fn [{:keys [file-id response requests requests$ store tasks]}]
       (ptk/emit! store (local-commit file-id) ::dps/force-persist)
       (await (async/wait-for #(= 1 (count @requests)) "the first edit is sent" {:signal requests$}))
       (ptk/emit! store (local-commit file-id))
       (rx/push! response (thp/save-response 0))
       (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
                                    "the first save lands"))
       (await (async/settle))
       (t/is (contains? @tasks :persistence) "the buffered edit keeps the guard on")
       (ptk/emit! store ::dps/force-persist)
       (await (async/wait-for #(= 2 (count @requests)) "the buffered edit is sent" {:signal requests$}))
       (rx/push! response (thp/save-response 1))
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
       (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
                                    "the save fails terminally"))
       (await (async/settle))
       (t/is (contains? @tasks :persistence) "the retained edit keeps the guard on")
       (ptk/emit! store
                  #(assoc-in % [:permissions :can-edit] true)
                  (ptk/data-event :app.main.data.common/change-team-role))
       (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
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
          (^:async fn [{:keys [file-id requests requests$ store]}]
            (ptk/emit! store (local-commit file-id))
            (await (async/settle))
            (t/is (empty? @requests) "the edit waits in the buffer")
            (rx/push! leave-s :hidden)
            (await (async/wait-for #(= 1 (count @requests))
                                   "leaving sends the edit at once" {:signal requests$}))
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
       (^:async fn [{:keys [file-id response requests requests$ store]}]
         (await
          (mock/with-mocks*
            {refs/persistence (l/derived :persistence store)}
            (ptk/emit! store (local-commit file-id))
            (wait-files-saved store #{file-id} 60000 done)
            (await (async/wait-for #(= 1 (count @requests)) "the edit is sent at once" {:signal requests$}))
            (await (async/settle))
            (t/is (false? @done) "the wait holds while the save is in flight")
            (rx/push! response (thp/save-response 0))
            (await (async/wait-for #(true? @done) "the landed save ends the wait"))
            (t/is (not (dps/unsaved-changes? @store #{file-id}))))))))))

;; Scenario: a save of one file hangs while another file is exported.
;; Proves: the wait only covers the exported files.
(t/deftest ^:async waiting-for-a-file-ignores-other-files
  (let [done (atom false)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (await
          (mock/with-mocks*
            {refs/persistence (l/derived :persistence store)}
            (ptk/emit! store (local-commit file-id) ::dps/force-persist)
            (await (async/wait-for #(= 1 (count @requests)) "the other file's save hangs" {:signal requests$}))
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
         (await (async/wait-for-store store #(= :error (get-in @store [:persistence :status]))
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
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [commit (local-commit file-id)]
         (ptk/emit! store commit)
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the edit is buffered"))
         (t/is (= [(:id @commit)] (mapv :id (get-in @store [:persistence :buffer]))))
         (t/is (empty? (get-in @store [:persistence :queue])))
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(seq @requests) "the flush sends the edit" {:signal requests$}))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (= [(:id @commit)]
                  (->> (get-in @store [:persistence :queue])
                       (mapcat #(get-in @store [:persistence :index % :commits]))
                       (mapv :id))))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the save lands")))))))

;; Scenario: an edit waits in the buffer when another user's change to the
;; same attribute arrives. The server will apply the remote change first and
;; the buffered edit after it, so the local file must end the same way.
;; Proves: a remote commit is applied under buffered edits too.
(t/deftest ^:async a-remote-commit-goes-under-buffered-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             local    (thp/rename-commit file-id page-id shape-id "Original" "Local")]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Local")))
         (ptk/emit! store local)
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the local edit is buffered"))
         (ptk/emit! store (thp/remote-commit file-id 1 [(thp/rename page-id shape-id "Remote")]))
         (await (async/settle))
         (t/is (= "Local" (thp/shape-name store file-id page-id shape-id))))))))

;; Scenario: a library edit waits to be saved when a peer's change to this
;; file arrives, and both files have a shape with the same id. Proves: a
;; remote commit goes under the pending commits of its own file only.
(t/deftest ^:async a-remote-commit-ignores-the-pending-commits-of-other-files
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [lib-id   (uuid/next)
             page-id  (uuid/next)
             shape-id (uuid/next)]
         (ptk/emit! store (thp/open-file-with-library file-id lib-id page-id
                                                      (thp/file-data page-id shape-id "A")
                                                      (thp/file-data page-id shape-id "Y")))
         (ptk/emit! store (thp/rename-commit lib-id page-id shape-id "X" "Y"))
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the library edit is buffered"))
         (ptk/emit! store (thp/remote-commit file-id 1 [(thp/set-attr page-id shape-id :opacity 0.3)]))
         (await (async/settle))
         (t/is (= 0.3 (thp/shape-attr store file-id page-id shape-id :opacity)))
         (t/is (= "A" (thp/shape-name store file-id page-id shape-id))
               "the library edit is not replayed on this file"))))))

;; Changes the server stored before a save.

(def ^:private repair-session-id
  #uuid "a11c0de5-0000-4000-8000-000000000001")

(defn- add-child-commit
  "A local commit of `file-id` that adds `shape` as the last child of the
  root of `page-id`."
  [file-id page-id {:keys [id] :as shape}]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :undo-group (uuid/next)
                   :redo-changes [{:type :add-obj :id id :page-id page-id
                                   :parent-id uuid/zero :frame-id uuid/zero :obj shape}]
                   :undo-changes [{:type :del-obj :id id :page-id page-id}]}))

(defn- set-root-children
  "A change that sets the children of the root of `page-id`."
  [page-id ids]
  {:type :mod-obj :id uuid/zero :page-id page-id
   :operations [{:type :set :attr :shapes :val ids}]})

(defn- revision-row
  "A revision and its changes, as delivered by a GET or WebSocket."
  [file-id revn session-id changes]
  {:id (uuid/next) :file-id file-id :revn revn :session-id session-id :changes changes})

(defn- root-children
  [store file-id page-id]
  (thp/shape-attr store file-id page-id uuid/zero :shapes))

(defn- file-message
  [file-id peer row]
  (-> row
      (dissoc :id)
      (assoc :type :file-change :file-id file-id :vern 0
             :profile-id peer :session-id peer :revision-ids [(:id row)])))

(defn- open-catch-up-file
  ([store file-id load-id page-id data]
   (open-catch-up-file store file-id load-id page-id data 5))
  ([store file-id load-id page-id data revn]
   (let [file (with-meta {:id file-id :vern 0 :revn revn :data data}
                {::dps/fresh-file-read true})]
     (ptk/emit! store #(-> %
                           (assoc :workspace-load-id load-id :current-page-id page-id)
                           (assoc-in [:files file-id] file)
                           (dps/file-base-loaded file))))))

(t/deftest ^:async a-different-saved-edit-needs-a-fresh-file-even-without-a-gap
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             edit     (thp/rename-commit file-id page-id shape-id "Original" "Optimistic")]
         (open-catch-up-file store file-id (uuid/next) page-id (thp/file-data page-id shape-id "Original"))
         (ptk/emit! store (dch/commit @edit) ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (t/is (= "Optimistic" (thp/shape-name store file-id page-id shape-id)))
         (rx/push! response (assoc (thp/save-response 5) :edit-matches-request false))
         (await (async/wait-for #(= 2 (count @requests)) "the confirmed edit needs a fresh file"
                                {:signal requests$}))
         (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))
         (rx/push! response {:id file-id :vern 0 :revn 6 :data (thp/file-data page-id shape-id "Stored")})
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the fresh file settles the save"))
         (t/is (= "Stored" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= [:update-file-v2 :get-file] (mapv first @requests))))))))

(t/deftest ^:async reconnect-and-forward-gap-share-a-read
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [load-id (uuid/next)
             page-id (uuid/next)
             shape-id (uuid/next)
             data (thp/file-data page-id shape-id "Original")
             rename (fn [revn name]
                      (revision-row file-id revn repair-session-id [(thp/rename page-id shape-id name)]))]
         (open-catch-up-file store file-id load-id page-id data)
         (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true})
                    (dps/request-catch-up file-id load-id {:minimum 7}))
         (await (async/wait-for #(seq @requests) "the incremental read starts" {:signal requests$}))
         (rx/push! response {:vern 0 :head 7 :through 7 :rows [(rename 6 "First") (rename 7 "Last")]})
         (await (async/wait-for-store store #(= 7 (get-in @store [:files file-id :revn])) "both rows install"))
         (t/is (= "Last" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= [[:get-file-changes {:file-id file-id :vern 0 :since 5}]] @requests)))))))

(t/deftest ^:async catch-up-does-not-install-after-the-file-reopens
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [load-id (uuid/next)
             page-id (uuid/next)
             shape-id (uuid/next)]
         (open-catch-up-file store file-id load-id page-id (thp/file-data page-id shape-id "Previous opening"))
         (ptk/emit! store (dps/request-catch-up file-id load-id {:minimum 6}))
         (await (async/wait-for #(seq @requests) "the first opening's read starts" {:signal requests$}))
         (open-catch-up-file store file-id (uuid/next) page-id (thp/file-data page-id shape-id "New opening"))
         (rx/push! response {:vern 0 :head 6 :through 6
                             :rows [(revision-row file-id 6 repair-session-id
                                                  [(thp/rename page-id shape-id "Stale")])]})
         (await (async/settle))
         (t/is (= "New opening" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= [:get-file-changes] (mapv first @requests))))))))

(t/deftest ^:async a-websocket-row-applied-during-catch-up-is-not-applied-twice
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [load-id (uuid/next)
             page-id (uuid/next)
             shape-id (uuid/next)
             row-6  (revision-row file-id 6 repair-session-id [(thp/rename page-id shape-id "Six")])
             row-7  (revision-row file-id 7 repair-session-id [(thp/rename page-id shape-id "Seven")])]
         (open-catch-up-file store file-id load-id page-id (thp/file-data page-id shape-id "Five"))
         (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true}))
         (await (async/wait-for #(seq @requests) "catch-up starts" {:signal requests$}))
         (ptk/emit! store (dps/apply-remote-message (file-message file-id repair-session-id row-6) nil))
         (await (async/wait-for-store store #(= 6 (get-in @store [:files file-id :revn])) "WebSocket installs six"))
         (rx/push! response {:vern 0 :head 7 :through 7 :rows [row-6 row-7]})
         (await (async/wait-for-store store #(= 7 (get-in @store [:files file-id :revn])) "catch-up installs seven"))
         (t/is (= "Seven" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 2 (get-in @store [:persistence :remote-seq])))
         (t/is (= [:get-file-changes] (mapv first @requests))))))))

(defn- checked-rename
  [file-id page-id shape-id from to]
  (dch/commit {:file-id file-id :file-vern 0 :file-revn 5
               :redo-changes [(thp/rename page-id shape-id to)]
               :undo-changes [(update (thp/rename page-id shape-id from) :operations
                                      #(into [{:type :set-touched :touched nil}] %))]}))

(t/deftest ^:async independent-renames-only-index-the-remote-step
  (doseq [[source case] [[:websocket :independent] [:catch-up :independent] [:websocket :same-page]
                         [:websocket :component] [:websocket :unverified]]]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id store requests requests$ response]}]
         (let [load-id (uuid/next)
               page-id (uuid/next)
               peer-page (if (= case :same-page) page-id (uuid/next))
               shape-id (uuid/next)
               peer-id (uuid/next)
               current (cond-> (thp/file-data page-id shape-id "Original")
                         (= case :component)
                         (assoc-in [:pages-index page-id :objects shape-id :component-file] (uuid/next)))
               data (if (= case :same-page)
                      (thp/add-shape current page-id (get-in (thp/file-data page-id peer-id "Peer original")
                                                             [:pages-index page-id :objects peer-id]))
                      (-> current
                          (update :pages conj peer-page)
                          (update :pages-index merge (:pages-index (thp/file-data peer-page peer-id "Peer original")))))
               updated (atom [])
               local (if (= case :unverified)
                       (dch/commit {:file-id file-id :file-vern 0 :file-revn 5
                                    :redo-changes [(update (thp/rename page-id shape-id "Pending") :operations
                                                           conj {:type :set :attr :blocked
                                                                 :val (get-in current [:pages-index page-id :objects shape-id :blocked])})]
                                    :undo-changes [(thp/rename page-id shape-id "Original")]})
                       (checked-rename file-id page-id shape-id "Original" "Pending"))
               row (revision-row file-id 6 repair-session-id [(thp/rename peer-page peer-id "Peer changed")])]
           (await
            (mock/with-mocks*
              {cf/flags (if (= source :catch-up) #{:file-catch-up} #{})
               mw/ask! (mock/stub (fn [{:keys [cmd page-id]}]
                                    (when (= cmd :index/update)
                                      (swap! updated conj page-id))
                                    (rx/empty)))}
              (open-catch-up-file store file-id load-id page-id data)
              (ptk/emit! store local)
              (await (async/wait-for #(= [page-id] @updated) "the worker sees the local edit"))
              (reset! updated [])
              (if (= source :catch-up)
                (do (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true}))
                    (await (async/wait-for #(seq @requests) "the incremental read starts" {:signal requests$}))
                    (rx/push! response {:vern 0 :head 6 :through 6 :rows [row]}))
                (ptk/emit! store (dps/apply-remote-message (file-message file-id repair-session-id row) nil)))
              (await (async/wait-for-store store #(= 6 (get-in @store [:files file-id :revn])) "the remote rename installs"))
              (await (async/settle))
              (t/is (= "Pending" (thp/shape-name store file-id page-id shape-id)))
              (t/is (= "Peer changed" (thp/shape-name store file-id peer-page peer-id)))
              (t/is (= (if (= case :independent) [peer-page] [page-id peer-page page-id]) @updated))
              (ptk/emit! store ::dps/force-persist)
              (await (async/wait-for #(some (comp #{:update-file-v2} first) @requests) "the local rename is sent"
                                     {:signal requests$}))
              (rx/push! response (assoc (thp/rejection (:id @local)) :revn 6))
              (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue])) "the rejection settles"))
              (t/is (= "Original" (thp/shape-name store file-id page-id shape-id)))
              (t/is (= "Peer changed" (thp/shape-name store file-id peer-page peer-id)))))))))))

(t/deftest ^:async catch-up-keeps-a-verified-pending-rename
  (await
   (mock/with-mocks*
     {cf/flags #{:file-catch-up}}
     (await
      (with-persistence
        (^:async fn [{:keys [file-id store requests requests$ response]}]
          (let [load-id (uuid/next)
                page-id (uuid/next)
                remote (uuid/next)
                local (uuid/next)
                data (thp/add-shape (thp/file-data page-id remote "Remote original") page-id
                                    (cts/setup-shape {:id local :type :rect :name "Local original"
                                                      :parent-id uuid/zero :frame-id uuid/zero}))]
            (open-catch-up-file store file-id load-id page-id data)
            (ptk/emit! store (checked-rename file-id page-id local "Local original" "Pending"))
            (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true}))
            (await (async/wait-for #(seq @requests) "the read starts" {:signal requests$}))
            (rx/push! response {:vern 0 :head 6 :through 6
                                :rows [(revision-row file-id 6 repair-session-id
                                                     [(thp/rename page-id remote "Peer")])]})
            (await (async/wait-for-store store #(= 6 (get-in @store [:files file-id :revn])) "the remote rename installs"))
            (t/is (= "Peer" (thp/shape-name store file-id page-id remote)))
            (t/is (= "Pending" (thp/shape-name store file-id page-id local)))
            (t/is (= [:get-file-changes] (mapv first @requests))))))))))

(t/deftest ^:async catch-up-falls-back-when-a-pending-inverse-cannot-recover-the-base
  (await
   (mock/with-mocks*
     {cf/flags #{:file-catch-up}}
     (await
      (with-persistence
        (^:async fn [{:keys [file-id store requests requests$ response]}]
          (let [load-id (uuid/next)
                page-id (uuid/next)
                shape-id (uuid/next)
                data (thp/file-data page-id shape-id "Original")
                remote [(thp/rename page-id shape-id "Peer")]]
            (open-catch-up-file store file-id load-id page-id data)
            (ptk/emit! store (checked-rename file-id page-id shape-id "Not the original" "Pending"))
            (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true}))
            (await (async/wait-for #(seq @requests) "the incremental read starts" {:signal requests$}))
            (rx/push! response {:vern 0 :head 6 :through 6 :rows [(revision-row file-id 6 repair-session-id remote)]})
            (await (async/wait-for #(= 2 (count @requests)) "the read falls back" {:signal requests$}))
            (rx/push! response {:id file-id :vern 0 :revn 6 :data (thp/file-data page-id shape-id "Peer")})
            (await (async/wait-for-store store #(= 6 (get-in @store [:files file-id :revn])) "the server base installs"))
            (t/is (= "Pending" (thp/shape-name store file-id page-id shape-id)))
            (t/is (get-in @store [:persistence :buffer 0 :undo-uncertain]))
            (t/is (= [:get-file-changes :get-file] (mapv first @requests))))))))))

(t/deftest ^:async catch-up-reports-a-missed-restore-only-after-installing-it
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [load-id (uuid/next)
             page-id (uuid/next)
             shape-id (uuid/next)]
         (open-catch-up-file store file-id load-id page-id (thp/file-data page-id shape-id "Before restore"))
         (ptk/emit! store (checked-rename file-id page-id shape-id "Before restore" "Pending"))
         (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true}))
         (await (async/wait-for #(seq @requests) "the incremental read starts" {:signal requests$}))
         (rx/push! response {:vern 1 :head 6 :reload true})
         (await (async/wait-for #(= 2 (count @requests)) "a full read starts" {:signal requests$}))
         (rx/push! response {:id file-id :vern 1 :revn 6 :data (thp/file-data page-id shape-id "Restored")})
         (await (async/wait-for-store store #(= :file-restored (get-in @store [::modal/modal :type])) "the restore is announced"))
         (let [notice (::modal/modal @store)]
           (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= {:unsaved true} (:props notice)))
           (ptk/emit! store #(dps/file-base-loaded % (vary-meta (get-in % [:files file-id])
                                                                assoc ::dps/fresh-file-read true))
                      (dps/request-catch-up file-id load-id {:reconnected true}))
           (await (async/wait-for #(= 3 (count @requests)) "the restored file starts another read" {:signal requests$}))
           (ptk/emit! store (dps/reconcile-restored-file {:file-id file-id :vern 1 :label "Version"}))
           (rx/push! response {:vern 1 :head 7 :through 7
                               :rows [(revision-row file-id 7 repair-session-id
                                                    [(thp/rename page-id shape-id "After restore")])]})
           (await (async/wait-for-store store #(= 7 (get-in @store [:files file-id :revn])) "the duplicate did not cancel recovery"))
           (await (async/settle))
           (t/is (= notice (::modal/modal @store)))
           (t/is (= [:get-file-changes :get-file :get-file-changes] (mapv first @requests)))))))))

(t/deftest ^:async a-restore-recovers-newer-changes-and-unblocks-other-files
  (await
   (mock/with-mocks*
     {cf/flags #{:file-catch-up}}
     (await
      (with-persistence
        (^:async fn [{:keys [file-id store requests requests$ response]}]
          (let [load-id (uuid/next)
                page-id (uuid/next)
                shape-id (uuid/next)
                other-file-id (uuid/next)
                old-file (checked-rename file-id page-id shape-id "Before restore" "Pending")
                other-file (local-commit other-file-id)]
            (open-catch-up-file store file-id load-id page-id (thp/file-data page-id shape-id "Before restore"))
            (ptk/emit! store (dps/reconcile-restored-file {:file-id file-id :vern 1 :label "Version"}))
            (await (async/wait-for #(seq @requests) "the restore GET starts" {:signal requests$}))
            (ptk/emit! store old-file other-file ::dps/force-persist)
            (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                         "both files have edits waiting to save"))
            (ptk/emit! store
                       (dps/apply-remote-message
                        (assoc (file-message file-id repair-session-id
                                             (revision-row file-id 7 repair-session-id
                                                           [(thp/rename page-id shape-id "After restore")]))
                               :vern 1)
                        nil))
            (rx/push! response {:id file-id :vern 1 :revn 6 :data (thp/file-data page-id shape-id "Restored")})
            (await (async/wait-for #(= 2 (count @requests)) "the older GET is retried" {:signal requests$}))
            (t/is (= 0 (get-in @store [:files file-id :vern])))
            (rx/push! response {:id file-id :vern 1 :revn 7 :data (thp/file-data page-id shape-id "After restore")})
            (await (async/wait-for #(= 3 (count @requests)) "the unrelated edit can be sent" {:signal requests$}))
            (t/is (= [:get-file :get-file :update-file-v2] (mapv first @requests)))
            (t/is (= other-file-id (get-in @requests [2 1 :id])))
            (t/is (= "After restore" (thp/shape-name store file-id page-id shape-id)))
            (t/is (true? (get-in @store [::modal/modal :props :unsaved]))))))))))

(defn- ^:async check-catch-up-settles-save-in-order
  [same-page]
  (let [timer (rx/subject)
        sent 40
        until 43
        saved 44
        head 45]
    (await
     (mock/with-mocks*
       {cf/flags #{:file-catch-up}
        rx/timer (mock/stub (fn [ms]
                              (if (= ms @#'dps/unseen-changes-timeout-ms) timer (rx/subject))))}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$ response]}]
            (let [load-id (uuid/next)
                  page-id (uuid/next)
                  peer-page (if same-page page-id (uuid/next))
                  peer-id (uuid/next)
                  local-id (uuid/next)
                  local-data (thp/file-data page-id local-id "Local original")
                  peer-data (thp/file-data peer-page peer-id "Peer original")
                  data (if same-page
                         (thp/add-shape local-data page-id (get-in peer-data [:pages-index page-id :objects peer-id]))
                         (-> local-data
                             (update :pages conj peer-page)
                             (update :pages-index merge (:pages-index peer-data))))
                  commit (checked-rename file-id page-id local-id "Local original" "Saved edit")
                  prior (mapv (fn [revn]
                                (revision-row file-id revn repair-session-id
                                              [(thp/rename peer-page peer-id (str "Peer " revn))]))
                              [41 42 43])
                  answer (assoc (thp/save-response until) :recovery (thp/recovery :catch-up sent prior))
                  own (peek (:written answer))
                  applied (atom nil)
                  later (revision-row file-id head repair-session-id [(thp/rename peer-page peer-id "Later")])]
              (open-catch-up-file store file-id load-id page-id data sent)
              (ptk/emit! store commit ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @requests)) "the local rename is sent" {:signal requests$}))
              (t/is (= sent (get-in @requests [0 1 :revn])))
              (ptk/emit! store (checked-rename file-id page-id local-id "Saved edit" "Still pending")
                         (dps/request-catch-up file-id load-id {:minimum head}))
              (rx/push! response answer)
              (await (async/wait-for-store store #(get-in @store [:persistence :index (:id @commit) ::dps/save-result])
                                           "the save is waiting for its predecessor"))
              (rx/push! timer :tick)
              (await (async/wait-for #(= 2 (count @requests)) "the predecessor read starts" {:signal requests$}))
              (t/is (= [:get-file-changes {:file-id file-id :vern 0 :since sent :until until
                                           :confirm-revn saved :confirm-id (:id own)}]
                       (second @requests)))
              (->> (ptk/input-stream store)
                   (rx/filter (ptk/type? ::dch/rebase-commits))
                   (rx/map (comp :changes deref))
                   (rx/filter seq)
                   (rx/take 1)
                   (rx/subs! #(reset! applied %)))
              (rx/push! response {:vern 0 :head head :through until :rows prior
                                  :confirmed-row (select-keys own [:revn :id])})
              (await (async/wait-for #(= 3 (count @requests)) "the later demand is read separately" {:signal requests$}))
              (t/is (= [:get-file-changes {:file-id file-id :vern 0 :since saved}] (last @requests)))
              (t/is (= (if same-page
                         ["Saved edit" "Local original" "Peer 41" "Peer 42" "Peer 43" "Saved edit" "Still pending"]
                         ["Peer 41" "Peer 42" "Peer 43"])
                       (into [] (comp (mapcat :operations) (filter #(= :name (:attr %))) (map :val)) @applied)))
              (t/is (= "Peer 43" (thp/shape-name store file-id peer-page peer-id)))
              (t/is (= "Still pending" (thp/shape-name store file-id page-id local-id)))
              (rx/push! response {:vern 0 :head head :through head :rows [later]})
              (await (async/wait-for-store store #(= head (get-in @store [:files file-id :revn])) "the last revision installs"))
              (t/is (= "Later" (thp/shape-name store file-id peer-page peer-id)))
              (t/is (= "Still pending" (thp/shape-name store file-id page-id local-id)))
              (t/is (= 4 (get-in @store [:persistence :remote-seq]))
                    "only peer rows count as remote deltas")
              (t/is (= 1 (count (filter #(= :update-file-v2 (first %)) @requests))))))))))))

(t/deftest ^:async catch-up-rebases-the-save-and-the-pending-edit-over-the-same-page
  (await (check-catch-up-settles-save-in-order true)))

(t/deftest ^:async catch-up-installs-predecessors-on-an-independent-page-before-settling
  (await (check-catch-up-settles-save-in-order false)))

(defn- ^:async check-save-catch-up-demand
  [{:keys [winner reconnect-before demand-after follow-up] head :head :or {head 44}}]
  (let [timer (rx/subject)
        save-rpc (rx/subject)
        bounded-rpc (rx/subject)
        next-rpc (rx/subject)]
    (await
     (mock/with-mocks*
       {cf/flags #{:file-catch-up}
        rx/timer (mock/stub (fn [ms]
                              (if (= ms @#'dps/unseen-changes-timeout-ms) timer (rx/subject))))}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$]}]
            (let [load-id   (uuid/next)
                  page-id   (uuid/next)
                  peer-page (uuid/next)
                  local-id  (uuid/next)
                  peer-id   (uuid/next)
                  peer      (uuid/next)
                  peer-data (thp/file-data peer-page peer-id "Peer original")
                  data      (-> (thp/file-data page-id local-id "Local original")
                                (update :pages conj peer-page)
                                (update :pages-index merge (:pages-index peer-data)))
                  edit      (checked-rename file-id page-id local-id "Local original" "Saved")
                  pending   (checked-rename file-id page-id local-id "Saved" "Pending")
                  row       (fn [revn] (revision-row file-id revn peer
                                                     [(thp/rename peer-page peer-id (str "Peer " revn))]))
                  prior     (mapv row [41 42 43])
                  answer    (assoc (thp/save-response 43) :recovery (thp/recovery :catch-up 40 prior))
                  own       (select-keys (peek (:written answer)) [:revn :id])
                  bounded   {:vern 0 :head head :through 43 :rows prior :confirmed-row own}]
              (open-catch-up-file store file-id load-id page-id data 40)
              (ptk/emit! store edit ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @requests)) "the local save is sent" {:signal requests$}))
              (ptk/emit! store pending)
              (when reconnect-before
                (ptk/emit! store (dps/request-catch-up file-id load-id {:reconnected true})))
              (rx/push! save-rpc answer)
              (await (async/wait-for-store store #(get-in @store [:persistence :index (:id @edit) ::dps/save-result])
                                           "the save is waiting for its predecessors"))
              (rx/push! timer :tick)
              (await (async/wait-for #(= 2 (count @requests)) "the bounded read starts" {:signal requests$}))
              (t/is (= [:get-file-changes {:file-id file-id :vern 0 :since 40 :until 43
                                           :confirm-revn 44 :confirm-id (:id own)}]
                       (second @requests)))
              (when demand-after
                (ptk/emit! store (dps/request-catch-up file-id load-id demand-after)))

              (if (= winner :websocket)
                (doseq [delta prior]
                  (ptk/emit! store (dps/apply-remote-message (file-message file-id peer delta) nil))
                  (await (async/wait-for-store store #(>= (get-in @store [:files file-id :revn]) (:revn delta))
                                               "the exact WebSocket revision is installed")))
                (rx/push! bounded-rpc bounded))
              (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
                                           "the confirmed save leaves the queue"))
              (await (async/settle))
              (when (= winner :websocket)
                (rx/push! bounded-rpc bounded)
                (await (async/settle)))
              (t/is (= 44 (get-in @store [:files file-id :revn])))
              (t/is (= "Peer 43" (thp/shape-name store file-id peer-page peer-id)))
              (t/is (= "Pending" (thp/shape-name store file-id page-id local-id)))
              (t/is (= 3 (get-in @store [:persistence :remote-seq]))
                    "the predecessors are installed only once")

              (if follow-up
                (let [[cmd revn] follow-up
                      peer-name (str "Peer " (if (= revn 44) 43 revn))]
                  (await (async/wait-for #(= 3 (count @requests)) "the remaining demand starts a follow-up"
                                         {:signal requests$}))
                  (t/is (= [:update-file-v2 :get-file-changes cmd] (mapv first @requests)))
                  (case cmd
                    :get-file
                    (do
                      (t/is (= "no-store" (-> @requests peek second meta ::rp/cache)))
                      (rx/push! next-rpc {:id file-id :vern 0 :revn revn
                                          :data (-> data
                                                    (assoc-in [:pages-index page-id :objects local-id :name] "Saved")
                                                    (assoc-in [:pages-index peer-page :objects peer-id :name] peer-name))}))
                    :get-file-changes
                    (do
                      (t/is (= {:file-id file-id :vern 0 :since 44} (-> @requests peek second)))
                      (rx/push! next-rpc {:vern 0 :head revn :through revn
                                          :rows (if (= revn 44) [] [(row revn)])})))
                  (await (async/wait-for-store store #(and (nil? (get-in @store [:persistence :catch-up]))
                                                           (nil? (get-in @store [:persistence :reconciliations file-id])))
                                               "the follow-up satisfies the remaining demand"))
                  (t/is (= revn (get-in @store [:files file-id :revn])))
                  (t/is (= peer-name (thp/shape-name store file-id peer-page peer-id)))
                  (t/is (= "Pending" (thp/shape-name store file-id page-id local-id)))
                  (t/is (= 3 (count @requests))))
                (do
                  (t/is (= [:update-file-v2 :get-file-changes] (mapv first @requests)))
                  (t/is (nil? (get-in @store [:persistence :catch-up])))
                  (t/is (not (:undo-uncertain (first (get-in @store [:persistence :buffer])))))))))
          (fn [cmd params]
            (->> (case cmd
                   :update-file-v2 save-rpc
                   :get-file-changes (if (:until params) bounded-rpc next-rpc)
                   :get-file next-rpc
                   (rx/empty))
                 (rx/take 1)
                 (rx/observe-on :async)))))))))

(t/deftest ^:async exact-websocket-proof-retires-the-saves-incremental-read
  (await (check-save-catch-up-demand {:winner :websocket})))

(t/deftest ^:async exact-websocket-proof-keeps-a-later-revision-demand
  (await (check-save-catch-up-demand {:winner :websocket :demand-after {:minimum 45}
                                      :follow-up [:get-file 45]})))

(t/deftest ^:async exact-websocket-proof-keeps-a-reconnect-the-bounded-read-took
  (await (check-save-catch-up-demand {:winner :websocket :reconnect-before true
                                      :follow-up [:get-file 44]})))

(t/deftest ^:async exact-websocket-proof-keeps-a-reconnect-during-the-bounded-read
  (await (check-save-catch-up-demand {:winner :websocket :demand-after {:reconnected true}
                                      :follow-up [:get-file 44]})))

(t/deftest ^:async exact-websocket-proof-keeps-a-forced-full-read
  (await (check-save-catch-up-demand {:winner :websocket :demand-after {:force-reload true}
                                      :follow-up [:get-file 44]})))

(t/deftest ^:async a-completed-bounded-read-covers-the-reconnect-it-took
  (await (check-save-catch-up-demand {:winner :get :reconnect-before true})))

(t/deftest ^:async a-completed-bounded-read-keeps-a-later-reconnect
  (await (check-save-catch-up-demand {:winner :get :demand-after {:reconnected true}
                                      :follow-up [:get-file-changes 44]})))

(t/deftest ^:async a-completed-bounded-read-recovers-a-newer-head-separately
  (await (check-save-catch-up-demand {:winner :get :head 45
                                      :follow-up [:get-file-changes 45]})))

(defn- ^:async check-save-catch-up-falls-back
  [fault]
  (let [timer (rx/subject)]
    (await
     (mock/with-mocks*
       {cf/flags #{:file-catch-up}
        rx/timer (mock/stub (fn [ms]
                              (if (= ms @#'dps/unseen-changes-timeout-ms) timer (rx/subject))))}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$ response]}]
            (let [page-id (uuid/next)
                  shape-id (uuid/next)
                  peer-id (uuid/next)
                  load-id (uuid/next)
                  data (thp/add-shape (thp/file-data page-id shape-id "Original") page-id
                                      (cts/setup-shape {:id peer-id :type :rect :name "Peer original"
                                                        :parent-id uuid/zero :frame-id uuid/zero}))
                  edit (checked-rename file-id page-id shape-id
                                       (if (= fault :unsafe-inverse) "Wrong old name" "Original")
                                       "Local")
                  row (revision-row file-id 6 repair-session-id [(thp/rename page-id peer-id "Incremental")])
                  answer (assoc (thp/save-response 6) :recovery (thp/recovery :catch-up 5 [row]))
                  own (select-keys (peek (:written answer)) [:id :revn])
                  incremental {:vern 0 :head 7 :through 6 :rows [row] :confirmed-row own}]
              (open-catch-up-file store file-id load-id page-id data)
              (ptk/emit! store edit ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
              (rx/push! response answer)
              (await (async/wait-for-store store #(get-in @store [:persistence :index (:id @edit) ::dps/save-result])
                                           "the save is waiting for the peer"))
              (rx/push! timer :tick)
              (await (async/wait-for #(= 2 (count @requests)) "the incremental read starts" {:signal requests$}))
              (rx/push! response (case fault
                                   :wrong-id (assoc-in incremental [:confirmed-row :id] (uuid/next))
                                   :invalid-get (assoc incremental :rows [])
                                   :unsafe-inverse incremental))
              (await (async/wait-for #(= 3 (count @requests)) "the full read replaces the unsafe replay"
                                     {:signal requests$}))
              (t/is (= "Peer original" (thp/shape-name store file-id page-id peer-id)))
              (rx/push! response {:id file-id :vern 0 :revn 7
                                  :data (assoc-in data [:pages-index page-id :objects shape-id :name] "Stored")})
              (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                           "the fresh file settles the save"))
              (t/is (= "Stored" (thp/shape-name store file-id page-id shape-id)))
              (t/is (= [:update-file-v2 :get-file-changes :get-file] (mapv first @requests)))))))))))

(t/deftest ^:async catch-up-cannot-confirm-a-different-id-for-the-saved-edit
  (await (check-save-catch-up-falls-back :wrong-id)))

(t/deftest ^:async catch-up-cannot-settle-a-save-from-an-incomplete-get
  (await (check-save-catch-up-falls-back :invalid-get)))

(t/deftest ^:async catch-up-cannot-settle-a-save-with-an-unsafe-local-inverse
  (await (check-save-catch-up-falls-back :unsafe-inverse)))

(defn- open-linked-library
  [store file-id library]
  (ptk/emit! store #(-> %
                        (assoc-in [:files (:id library)] (assoc library :library-of file-id))
                        (dps/file-base-loaded library))))

(defn- library-reload-answer
  [library-id page-id]
  (let [peer (uuid/next)
        rows (mapv #(revision-row library-id % peer [{:type :mod-page :id page-id :name "Peer"}])
                   [41 42 43])]
    (assoc (thp/save-response 43) :recovery (thp/recovery :reload 40 rows))))

(defn- get-save-and-read-requests
  [requests]
  (filterv (comp #{:update-file-v2 :get-file :get-file-changes} first) @requests))

(defn- ^:async answer-reconciliation
  [store read result]
  (let [applied (async/observe
                 (->> (ptk/input-stream store)
                      (rx/filter (ptk/type? ::dps/reconciliation-result))
                      (rx/take 1)))]
    (if (instance? js/Error result)
      (rx/error! read result)
      (rx/push! read result))
    (await applied)))

(t/deftest ^:async a-restored-linked-library-settles-its-save-and-keeps-the-consumer-open
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (mock/with-mocks*
       {cf/flags #{}}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$]}]
            (let [library-id (uuid/next)
                  lib-page   (uuid/next)
                  lib-shape  (uuid/next)
                  load-id    (uuid/next)
                  page-id    (uuid/next)
                  shape-id   (uuid/next)
                  synced-at  (ct/now)
                  link       {:library-of file-id :synced-at synced-at :is-indirect true}
                  library    (merge link {:id library-id :vern 0 :revn 40
                                          :data (thp/file-data lib-page lib-shape "Original library")})
                  restored   {:id library-id :vern 93 :revn 45
                              :data (thp/file-data lib-page lib-shape "Restored library")}]
              (open-catch-up-file store file-id load-id page-id (thp/file-data page-id shape-id "Consumer"))
              (open-linked-library store file-id library)
              (ptk/emit! store #(assoc-in % [:workspace-local :selected] #{shape-id})
                         (checked-rename library-id lib-page lib-shape "Original library" "Confirmed")
                         ::dps/force-persist)
              (await (async/wait-for #(= 1 (count (get-save-and-read-requests requests)))
                                     "the first library edit is sent" {:signal requests$}))
              (rx/push! save (library-reload-answer library-id lib-page))
              (await (async/wait-for #(= 2 (count (get-save-and-read-requests requests)))
                                     "the confirmed write starts a full read" {:signal requests$}))
              (t/is (= "no-store" (::rp/cache (meta (get-in @requests [1 1])))))
              (ptk/emit! store (checked-rename library-id lib-page lib-shape "Confirmed" "Queued")
                         ::dps/force-persist)
              (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                           "one old-version edit waits behind the confirmed head"))
              (ptk/emit! store (checked-rename file-id page-id shape-id "Consumer" "Edited consumer")
                         ::dps/force-persist)
              (await (async/wait-for-store store #(= 3 (count (get-in @store [:persistence :queue])))
                                           "the consumer has a queued edit"))
              (ptk/emit! store (checked-rename library-id lib-page lib-shape "Queued" "Buffered"))
              (await (async/wait-for-store store #(= 1 (count (get-in @store [:persistence :buffer])))
                                           "another old-version edit is buffered"))
              (await (answer-reconciliation store read restored))
              (t/is (= "Restored library" (thp/shape-name store library-id lib-page lib-shape)))
              (t/is (= link (select-keys (get-in @store [:files library-id]) (keys link))))
              (t/is (= {:vern 93 :latest 45 :through 45}
                       (select-keys (get-in @store [:persistence :file-order library-id])
                                    [:vern :latest :through])))
              (t/is (= [file-id load-id page-id #{shape-id} "Edited consumer"]
                       [(:current-file-id @store) (:workspace-load-id @store) (:current-page-id @store)
                        (get-in @store [:workspace-local :selected])
                        (thp/shape-name store file-id page-id shape-id)]))
              (t/is (nil? (::modal/modal @store)))
              (await (async/wait-for #(= 3 (count (get-save-and-read-requests requests)))
                                     "the consumer can save after the replacement" {:signal requests$}))
              (t/is (= [[:update-file-v2 library-id] [:get-file library-id] [:update-file-v2 file-id]]
                       (mapv (fn [[cmd params]] [cmd (:id params)]) (get-save-and-read-requests requests))))
              (rx/push! save (thp/save-response (get-in (last (get-save-and-read-requests requests)) [1 :revn])))
              (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                                           "the queue is drained"))
              (t/is (empty? (get-in @store [:persistence :buffer])))
              (t/is (= 3 (count (get-save-and-read-requests requests))))))
          (fn [cmd _]
            (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
                 (rx/take 1)
                 (rx/observe-on :async)))))))))

(defn- with-confirmed-library-read
  [scenario]
  (let [save (rx/subject)
        reads (atom [])
        linked-file (atom nil)]
    (mock/with-mocks*
      {cf/flags #{}}
      (await
       (with-persistence
         (^:async fn [{:keys [file-id store requests requests$] :as fixture}]
           (let [library-id (uuid/next)
                 page-id    (uuid/next)
                 shape-id   (uuid/next)
                 edit       (checked-rename library-id page-id shape-id "Original" "Saving")
                 restored   {:id library-id :vern 20 :revn 45
                             :data (thp/file-data page-id shape-id "Restored")}]
             (open-linked-library store file-id {:id library-id :vern 0 :revn 40
                                                 :data (thp/file-data page-id shape-id "Original")})
             (ptk/emit! store #(assoc % :workspace-load-id (uuid/next))
                        edit ::dps/force-persist)
             (await (async/wait-for #(= 1 (count (get-save-and-read-requests requests)))
                                    "the library edit is sent" {:signal requests$}))
             (rx/push! save (library-reload-answer library-id page-id))
             (await (async/wait-for #(= 2 (count (get-save-and-read-requests requests)))
                                    "the confirmed edit starts a read" {:signal requests$}))
             (t/is (true? (get-in @store [:persistence :index (:id @edit) :confirmed])))
             (await (scenario (assoc fixture :library-id library-id :page-id page-id :shape-id shape-id
                                     :entry-id (:id @edit) :read (first @reads) :reads reads :save save
                                     :restored restored :linked-file linked-file)))))
         (fn [cmd params]
           (->> (case cmd
                  :update-file-v2 save
                  :get-file (if (= "no-store" (::rp/cache (meta params)))
                              (let [read (rx/subject)]
                                (swap! reads conj read)
                                read)
                              (rx/of @linked-file))
                  :unlink-file-from-library (rx/of nil)
                  :get-file-libraries (rx/of [])
                  :link-file-to-library (rx/of [])
                  :get-file-object-thumbnails (rx/of {})
                  (rx/empty))
                (rx/take 1)
                (rx/observe-on :async))))))))

(t/deftest ^:async a-library-restore-read-must-cover-the-confirmation-and-newer-revisions
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id page-id shape-id requests requests$ entry-id read reads restored]}]
       (await (answer-reconciliation store read (assoc restored :revn 43)))
       (t/is (= "Saving" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [entry-id] (vec (get-in @store [:persistence :queue]))))
       (t/is (false? (get-in @store [:persistence :reconciliations library-id :loading])))
       (ptk/emit! store (#'dps/reconcile-file library-id {:retry true}))
       (await (async/wait-for #(= 3 (count @requests)) "retry sends only a GET" {:signal requests$}))
       (ptk/emit! store (dwn/handle-library-change
                         {:type :library-change :file-id library-id :modified-at (ct/now)
                          :profile-id (uuid/next) :session-id (uuid/next) :vern 20 :revn 46 :changes []}))
       (await (async/wait-for-store store #(= 46 (get-in @store [:persistence :reconciliations library-id :minimum]))
                                    "the same read must now cover the observed revision"))
       (await (answer-reconciliation store (peek @reads) restored))
       (t/is (= "Saving" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [entry-id] (vec (get-in @store [:persistence :queue]))))
       (ptk/emit! store (#'dps/reconcile-file library-id {:retry true}))
       (await (async/wait-for #(= 4 (count @requests)) "the read retries again" {:signal requests$}))
       (rx/push! (peek @reads) (assoc restored :revn 46))
       (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                                    "a fresh enough read settles the save"))
       (t/is (= "Restored" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [:update-file-v2 :get-file :get-file :get-file]
                (mapv first (get-save-and-read-requests requests))))))))

(t/deftest ^:async a-library-restore-read-does-not-cross-workspace-openings
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id file-id page-id shape-id requests entry-id read restored]}]
       (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)))
       (open-linked-library store file-id {:id library-id :vern 0 :revn 42
                                           :data (thp/file-data page-id shape-id "Reopened")})
       (await (answer-reconciliation store read restored))
       (await (async/settle))
       (t/is (= "Reopened" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [entry-id] (vec (get-in @store [:persistence :queue]))))
       (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))))

(t/deftest ^:async a-library-restore-read-without-data-does-not-settle-the-head
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id page-id shape-id requests entry-id read restored]}]
       (await (answer-reconciliation store read (dissoc restored :data)))
       (t/is (= "Saving" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [entry-id] (vec (get-in @store [:persistence :queue]))))
       (t/is (false? (get-in @store [:persistence :reconciliations library-id :loading])))
       (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))))

(t/deftest ^:async a-library-restore-read-cannot-install-after-losing-its-head
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id page-id shape-id requests entry-id read restored]}]
       (ptk/emit! store (#'dps/commit-persisted {:id entry-id :file-id library-id}))
       (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
                                    "the queue no longer has the head the read was for"))
       (await (answer-reconciliation store read restored))
       (t/is (= "Saving" (thp/shape-name store library-id page-id shape-id)))
       (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))))

(t/deftest ^:async a-detached-library-read-settles-without-reloading-the-library
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id file-id requests requests$ read restored save]}]
       (ptk/emit! store (dwl/unlink-file-from-library file-id library-id)
                  (local-commit file-id) ::dps/force-persist)
       (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                                    "the consumer save queues behind the confirmed library edit"))
       (await (async/wait-for #(some (comp #{:get-file-libraries} first) @requests)
                              "the library is unlinked" {:signal requests$}))
       (rx/push! read restored)
       (await (async/wait-for #(= 3 (count (get-save-and-read-requests requests)))
                              "the confirmed edit no longer blocks the consumer" {:signal requests$}))
       (rx/push! save (thp/save-response 0))
       (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                                    "the consumer's save settles"))
       (t/is (not (contains? (:files @store) library-id)))
       (t/is (nil? (get-in @store [:persistence :reconciliations library-id])))
       (t/is (not-any? (comp #{:get-file-object-thumbnails} first) @requests))
       (t/is (= [[:update-file-v2 library-id] [:get-file library-id] [:update-file-v2 file-id]]
                (mapv (fn [[cmd params]] [cmd (:id params)]) (get-save-and-read-requests requests))))))))

(defn- ^:async unlink-library
  [store file-id library-id]
  (let [cleaned (async/observe
                 (->> (ptk/input-stream store)
                      (rx/filter (ptk/type? ::dwl/cleanup-unlinked-libraries))
                      (rx/take 1)))]
    (ptk/emit! store (dwl/unlink-file-from-library file-id library-id))
    (await cleaned)))

(defn- ^:async link-library
  [{:keys [store file-id library-id linked-file]} file]
  (let [linked (async/observe
                (->> (ptk/input-stream store)
                     (rx/filter (ptk/type? ::dwl/link-file-to-library-finished))
                     (rx/take 1)))]
    (reset! linked-file file)
    (ptk/emit! store (dwl/link-file-to-library file-id library-id))
    (await linked)
    (await (async/wait-for-store store #(= (:vern file) (get-in @store [:files library-id :vern]))
                                 "the linked version is loaded"))))

(defn- ^:async relink-library
  [{:keys [store file-id library-id] :as fixture} file]
  (await (unlink-library store file-id library-id))
  (await (link-library fixture file)))

(defn- ^:async get-library-retry
  [store]
  (await (async/wait-for-store store #(= :persistence-reconciliation-failed (get-in @store [:notification :tag]))
                               "the recovery notice offers Retry"))
  (get-in @store [:notification :links 0 :callback]))

(defn- ^:async invoke-library-retry
  [store callback]
  (await
   (mock/with-mocks*
     {st/emit! (mock/stub (fn [event] (ptk/emit! store event)))}
     (callback)
     (await (async/settle)))))

(defn- ^:async queue-consumer-save
  [store file-id]
  (ptk/emit! store (local-commit file-id) ::dps/force-persist)
  (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue])))
                               "the consumer save queues behind the confirmed library edit")))

(defn- ^:async settle-consumer-save
  [{:keys [store file-id requests requests$ save]}]
  (await (async/wait-for #(some (fn [[cmd params]] (and (= :update-file-v2 cmd) (= file-id (:id params))))
                                @requests)
                         "the consumer save is sent" {:signal requests$}))
  (rx/push! save (thp/save-response 0))
  (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                               "the consumer save settles")))

(t/deftest ^:async retry-after-unlink-settles-a-confirmed-library-save-without-reading
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store file-id library-id requests read] :as fixture}]
       (await (queue-consumer-save store file-id))
       (await (answer-reconciliation store read (js/Error. "Library read failed")))
       (let [retry (await (get-library-retry store))]
         (await (unlink-library store file-id library-id))
         (await (invoke-library-retry store retry))
         (t/is (nil? (get-in @store [:persistence :reconciliations library-id])))
         (t/is (nil? (:notification @store)))
         (await (settle-consumer-save fixture)))
       (t/is (not (contains? (:files @store) library-id)))
       (t/is (not-any? (comp #{:get-file-object-thumbnails} first) @requests))
       (t/is (= [[:update-file-v2 library-id] [:get-file library-id] [:update-file-v2 file-id]]
                (mapv (fn [[cmd params]] [cmd (:id params)]) (get-save-and-read-requests requests))))))))

(t/deftest ^:async an-obsolete-library-read-restarts-once-from-the-relinked-version
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store file-id library-id page-id shape-id entry-id requests requests$
                          read reads restored] :as fixture}]
       (let [path    [:persistence :reconciliations library-id]
             attempt (get-in @store path)]
         (await (queue-consumer-save store file-id))
         (await (relink-library fixture restored))
         (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
         (t/is (= 1 (count @reads)) "normal link loading does not start a recovery read")
         (await (answer-reconciliation store read (assoc restored :revn 90
                                                         :data (thp/file-data page-id shape-id "Obsolete"))))
         (t/is (= "Restored" (thp/shape-name store library-id page-id shape-id)))
         (await (async/wait-for #(= 2 (count @reads)) "the obsolete attempt starts a fresh GET"
                                {:signal requests$}))
         (let [next-attempt (get-in @store path)]
           (t/is (not= (:token attempt) (:token next-attempt)))
           (t/is (= (select-keys attempt [:entry-id :vern]) (select-keys next-attempt [:entry-id :vern])))
           (t/is (= (:revn restored) (:minimum next-attempt)))
           (ptk/emit! store (#'dps/reconciliation-result library-id attempt (assoc restored :revn 100)))
           (await (async/settle))
           (t/is (= next-attempt (get-in @store path)) "a retired token cannot settle or restart the new attempt"))
         (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
         (await (answer-reconciliation store (peek @reads) restored))
         (await (settle-consumer-save fixture))
         (t/is (nil? (get-in @store path)))
         (t/is (= [[:update-file-v2 library-id] [:get-file library-id "no-store"]
                   [:get-file library-id] [:get-file library-id "no-store"] [:update-file-v2 file-id]]
                  (mapv (fn [[cmd params]]
                          (cond-> [cmd (:id params)]
                            (::rp/cache (meta params)) (conj (::rp/cache (meta params)))))
                        (get-save-and-read-requests requests)))))))))

(t/deftest ^:async obsolete-library-retry-callbacks-do-not-change-the-active-wait
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store file-id library-id entry-id requests$ read reads]}]
       (await (answer-reconciliation store read (js/Error. "First read failed")))
       (let [first-retry (await (get-library-retry store))]
         (await (invoke-library-retry store first-retry))
         (await (async/wait-for #(= 2 (count @reads)) "the manual retry starts" {:signal requests$}))
         (await (answer-reconciliation store (peek @reads) (js/Error. "Retry failed")))
         (let [retry  (await (get-library-retry store))
               notice (:notification @store)]
           (t/is (not= first-retry retry))
           (await (invoke-library-retry store first-retry))
           (t/is (= 2 (count @reads)))
           (t/is (= notice (:notification @store)))
           (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)))
           (await (unlink-library store file-id library-id))
           (await (invoke-library-retry store retry))
           (t/is (= [entry-id] (vec (get-in @store [:persistence :queue]))))
           (t/is (= notice (:notification @store)))
           (t/is (= 2 (count @reads)))))))))

(t/deftest ^:async an-obsolete-library-error-restarts-but-a-current-error-needs-retry
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id entry-id requests$ read reads restored] :as fixture}]
       (await (relink-library fixture restored))
       (await (answer-reconciliation store read (js/Error. "Obsolete library read failed")))
       (await (async/wait-for #(= 2 (count @reads)) "the obsolete error starts a fresh GET" {:signal requests$}))
       (await (answer-reconciliation store (peek @reads) (js/Error. "The current library read failed")))
       (await (get-library-retry store))
       (await (async/settle))
       (t/is (= 2 (count @reads)))
       (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
       (t/is (false? (get-in @store [:persistence :reconciliations library-id :loading])))))))

(t/deftest ^:async library-retry-does-not-rearm-the-automatic-read
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store file-id library-id requests requests$ read reads restored] :as fixture}]
       (let [second-link (assoc restored :vern 21 :revn 46)
             third-link  (assoc restored :vern 22 :revn 47)]
         (await (queue-consumer-save store file-id))
         (await (relink-library fixture restored))
         (await (answer-reconciliation store read restored))
         (await (async/wait-for #(= 2 (count @reads)) "the automatic GET starts" {:signal requests$}))
         (await (relink-library fixture second-link))
         (await (answer-reconciliation store (peek @reads) second-link))
         (let [retry (await (get-library-retry store))]
           (t/is (= 2 (count @reads)))
           (await (invoke-library-retry store retry)))
         (await (async/wait-for #(= 3 (count @reads)) "the manual Retry starts" {:signal requests$}))
         (await (relink-library fixture third-link))
         (await (answer-reconciliation store (peek @reads) third-link))
         (let [retry (await (get-library-retry store))]
           (t/is (= 3 (count @reads)) "a second stale read does not grant another automatic GET")
           (t/is (= (:revn third-link) (get-in @store [:persistence :reconciliations library-id :minimum])))
           (await (invoke-library-retry store retry)))
         (await (async/wait-for #(= 4 (count @reads)) "Retry starts another manual read" {:signal requests$}))
         (await (answer-reconciliation store (peek @reads) third-link))
         (await (settle-consumer-save fixture))
         (t/is (= [library-id file-id]
                  (into [] (keep (fn [[cmd params]] (when (= :update-file-v2 cmd) (:id params)))) @requests))))))))

(t/deftest ^:async a-relinked-library-read-keeps-the-accumulated-minimum-and-cannot-restore-the-old-version
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store file-id library-id entry-id requests$ read reads restored] :as fixture}]
       (let [path [:persistence :reconciliations library-id]]
         (await (queue-consumer-save store file-id))
         (ptk/emit! store (dwn/handle-library-change
                           {:type :library-change :file-id library-id :modified-at (ct/now)
                            :profile-id (uuid/next) :session-id (uuid/next) :vern 20 :revn 60 :changes []}))
         (await (async/wait-for-store store #(= 60 (:minimum (get-in @store path))) "WebSocket raises the minimum"))
         (await (relink-library fixture restored))
         (await (answer-reconciliation store read restored))
         (await (async/wait-for #(= 2 (count @reads)) "the automatic GET starts" {:signal requests$}))
         (t/is (= 60 (:minimum (get-in @store path))))
         (await (answer-reconciliation store (peek @reads) (assoc restored :revn 59)))
         (let [retry (await (get-library-retry store))]
           (t/is (= (:revn restored) (get-in @store [:files library-id :revn])))
           (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
           (await (invoke-library-retry store retry)))
         (await (async/wait-for #(= 3 (count @reads)) "Retry sends a new GET" {:signal requests$}))
         (await (answer-reconciliation store (peek @reads) (assoc restored :vern 0 :revn 61)))
         (let [retry (await (get-library-retry store))]
           (t/is (= (:vern restored) (get-in @store [:files library-id :vern])))
           (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
           (await (invoke-library-retry store retry)))
         (await (async/wait-for #(= 4 (count @reads)) "Retry sends a fresh GET" {:signal requests$}))
         (await (answer-reconciliation store (peek @reads) (assoc restored :revn 60)))
         (await (settle-consumer-save fixture)))))))

(defn- ^:async edit-library
  [{:keys [store library-id page-id shape-id]} from to]
  (let [commit    (volatile! nil)
        committed (async/observe
                   (->> (ptk/input-stream store)
                        (rx/filter (ptk/type? ::dch/commit))
                        (rx/filter #(= library-id (:file-id @%)))
                        (rx/take 1))
                   :on-next #(vreset! commit @%))]
    (ptk/emit! store (dch/commit-changes {:file-id library-id
                                          :redo-changes [(thp/rename page-id shape-id to)]
                                          :undo-changes [(thp/rename page-id shape-id from)]}))
    (await committed)
    (let [{:keys [id undo-group file-vern]} @commit]
      (ptk/emit! store (dwu/append-undo (assoc (thp/undo-entry commit)
                                               :translation false :origins #{[library-id file-vern undo-group]})
                                        false))
      id)))

(defn- get-library-posts
  [requests library-id]
  (into [] (keep (fn [[cmd params]]
                   (when (and (= :update-file-v2 cmd) (= library-id (:id params)))
                     params)))
        @requests))

(t/deftest ^:async a-fresh-library-read-replays-only-edits-of-the-relinked-version
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id page-id shape-id entry-id requests requests$ save
                          read reads restored] :as fixture}]
       (await (edit-library fixture "Saving" "Old queued edit"))
       (ptk/emit! store ::dps/force-persist)
       (await (async/wait-for-store store #(= 2 (count (get-in @store [:persistence :queue]))) "the old edit queues"))
       (await (relink-library fixture restored))
       (await (answer-reconciliation store read restored))
       (await (async/wait-for #(= 2 (count @reads)) "the automatic GET starts" {:signal requests$}))
       (let [queued-id (await (edit-library fixture "Restored" "New queued edit"))
             posts     #(get-library-posts requests library-id)]
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for-store store #(= 3 (count (get-in @store [:persistence :queue]))) "the new edit queues"))
         (let [buffered-id (await (edit-library fixture "New queued edit" "New buffered edit"))]
           (await (async/wait-for-store store #(= [buffered-id] (mapv :id (get-in @store [:persistence :buffer])))
                                        "a later edit of the new version is buffered"))
           (await (answer-reconciliation store (peek @reads) restored))
           (t/is (= "New buffered edit" (thp/shape-name store library-id page-id shape-id)))
           (await (async/wait-for #(= 2 (count (posts))) "the runner skips the old entry and sends the new edit"
                                  {:signal requests$}))
           (ptk/emit! store ::dps/force-persist)
           (let [{:keys [revn vern]} (peek (posts))]
             (rx/push! save (thp/save-response revn vern)))
           (await (async/wait-for #(= 3 (count (posts))) "the buffered edit also saves" {:signal requests$}))
           (let [{:keys [revn vern]} (peek (posts))]
             (rx/push! save (thp/save-response revn vern)))
           (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store)) "the queue drains"))
           (t/is (= [[0 [entry-id]] [20 [queued-id]] [20 [buffered-id]]]
                    (mapv (fn [{:keys [vern commits]}] [vern (mapv :commit-id commits)]) (posts))))))))))

(t/deftest ^:async a-library-read-of-a-third-version-must-advance-the-revision-and-cuts-obsolete-undo
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id page-id shape-id requests requests$ read reads restored] :as fixture}]
       (await (relink-library fixture restored))
       (await (answer-reconciliation store read restored))
       (await (async/wait-for #(= 2 (count @reads)) "the automatic GET starts" {:signal requests$}))
       (await (edit-library fixture "Restored" "An edit of the linked version"))
       (t/is (= 1 (count (get-in @store [:workspace-undo :items]))))
       (let [third (assoc restored :vern 29 :data (thp/file-data page-id shape-id "Third version"))]
         (await (answer-reconciliation store (peek @reads) third))
         (let [retry (await (get-library-retry store))]
           (t/is (= "An edit of the linked version" (thp/shape-name store library-id page-id shape-id)))
           (await (invoke-library-retry store retry)))
         (await (async/wait-for #(= 3 (count @reads)) "Retry checks the third version again" {:signal requests$}))
         (await (answer-reconciliation store (peek @reads) (update third :revn inc)))
         (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                                      "the replacement retires the old library edits"))
         (t/is (= "Third version" (thp/shape-name store library-id page-id shape-id)))
         (t/is (= 29 (get-in @store [:files library-id :vern])))
         (t/is (empty? (get-in @store [:workspace-undo :items])))
         (t/is (empty? (get-in @store [:persistence :buffer])))
         (t/is (= 1 (count (get-library-posts requests library-id)))))))))

(t/deftest ^:async a-failed-library-replay-keeps-the-visible-edits-and-offers-retry
  (await
   (with-confirmed-library-read
     (^:async fn [{:keys [store library-id entry-id requests requests$ read reads restored] :as fixture}]
       (await (relink-library fixture restored))
       (await (answer-reconciliation store read restored))
       (await (async/wait-for #(= 2 (count @reads)) "the automatic GET starts" {:signal requests$}))
       (let [buffered-id (await (edit-library fixture "Restored" "Unsaved rename"))]
         (await (async/wait-for-store store #(= [buffered-id] (mapv :id (get-in @store [:persistence :buffer])))
                                      "the new edit is buffered"))
         (let [before (select-keys @store [:files :workspace-undo])
               queue  (select-keys (:persistence @store) [:index :queue :buffer])]
           (await
            (mock/with-mocks*
              {cpc/process-changes (mock/stub (fn [_ _ _] (throw (js/Error. "Unable to replay the local edit"))))}
              (await (answer-reconciliation store (peek @reads) restored))
              (await (get-library-retry store))))
           (t/is (= before (select-keys @store [:files :workspace-undo])))
           (t/is (= queue (select-keys (:persistence @store) [:index :queue :buffer])))
           (t/is (= entry-id (peek (get-in @store [:persistence :queue]))))
           (t/is (= 1 (count (get-library-posts requests library-id))))))))))

(defn- ^:async flush-and-settle
  [store]
  (ptk/emit! store ::dps/force-persist)
  (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store)) "pending edits are saved")))

(t/deftest ^:async a-library-restore-cuts-a-saved-component-action-and-every-later-undo-entry
  (let [read       (rx/subject)
        reload     (atom nil)
        init-event (fn [type] (mock/stub (fn [& _] (ptk/data-event type {}))))]
    (await
     (mock/with-mocks*
       {cf/flags                     #{}
        dw/initialize-file           (init-event ::file-requested)
        dcmt/retrieve-comment-threads (init-event ::threads-requested)
        dcmt/fetch-profiles          (init-event ::profiles-requested)
        df/fetch-fonts               (init-event ::fonts-requested)}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$]}]
            (let [library    (-> (cthf/sample-file :library)
                                 (ctho/add-simple-component :component :main :main-child)
                                 (assoc :vern 0 :revn 39))
                  consumer   (-> (cthf/sample-file nil :id file-id)
                                 (cthc/instantiate-component :component :copy :library library
                                                             :children-labels [:copy-child])
                                 (cths/update-shape :copy-child :opacity 0.6)
                                 (cths/add-sample-shape :unrelated :name "Unrelated"))
                  library-id (:id library)
                  lib-page   (cthf/current-page-id library)
                  main-child (cthi/id :main-child)
                  copy-id    (cthi/id :copy)
                  copy-child (cthi/id :copy-child)
                  page-id    (cthf/current-page-id consumer)
                  shape-id   (cthi/id :unrelated)
                  group      (uuid/next)
                  rename     (fn [from to]
                               (dch/commit-changes {:redo-changes [(thp/rename page-id shape-id to)]
                                                    :undo-changes [(thp/rename page-id shape-id from)]}))
                  get-name   #(thp/shape-name store file-id page-id shape-id)
                  undo       #(:workspace-undo @store)]
              (ptk/emit! store (dw/initialize-workspace (uuid/next) file-id))
              (open-catch-up-file store file-id (:workspace-load-id @store) page-id (:data consumer) 0)
              (open-linked-library store file-id library)
              (ptk/emit! store #(assoc-in % [:workspace-local :selected] #{copy-id})
                         (rename "Unrelated" "Earlier"))
              (await (async/wait-for-store store #(= 1 (count (:items (undo)))) "the first undo entry is recorded"))
              (await (flush-and-settle store))
              (ptk/emit! store (dwl/update-component copy-id group))
              (await (async/wait-for-store store #(= 3 (count (:items (undo))))
                                           "updating the component records both commits"))
              (await (flush-and-settle store))
              (t/is (= [group group] (mapv :undo-group (rest (:items (undo))))))
              (t/is (= 0.6 (thp/shape-attr store library-id lib-page main-child :opacity)))
              (t/is (nil? (thp/shape-attr store file-id page-id copy-child :touched)))
              (t/is (= #{[file-id 2] [library-id 40]}
                       (into #{} (map (fn [[_ {:keys [id revn]}]] [id (inc revn)]))
                             (drop 1 (get-save-and-read-requests requests))))
                    "both sides of the component action are already saved")
              (ptk/emit! store (rename "Earlier" "Later"))
              (await (async/wait-for-store store #(= 4 (count (:items (undo)))) "the later edit is recorded"))
              (await (flush-and-settle store))
              (let [consumer-before (get-in @store [:files file-id :data])
                    prefix          (first (:items (undo)))
                    n               (count (get-save-and-read-requests requests))]
                (reset! reload (library-reload-answer library-id lib-page))
                (ptk/emit! store (dch/commit-changes {:file-id library-id :save-undo? false
                                                      :redo-changes [(thp/rename lib-page main-child "Saved")]
                                                      :undo-changes []})
                           ::dps/force-persist)
                (await (async/wait-for #(= (+ n 2) (count (get-save-and-read-requests requests)))
                                       "another confirmed library save starts a read" {:signal requests$}))
                (reset! reload nil)
                (rx/push! read (assoc library :vern 99 :revn 45))
                (await (async/wait-for-store store #(dps/terminal-status? (:persistence @store))
                                             "the restore settles the library save"))
                (t/is (= [prefix] (:items (undo))) "the consumer entry of the group is also cut")
                (t/is (= 0 (:index (undo))))
                (t/is (= consumer-before (get-in @store [:files file-id :data])))
                (t/is (= #{copy-id} (get-in @store [:workspace-local :selected])))
                (t/is (empty? (get-in @store [:persistence :buffer])) "the cut makes no compensating edits")
                (t/is (= (+ n 2) (count (get-save-and-read-requests requests))))
                (ptk/emit! store dwu/undo)
                (await (async/wait-for-store store #(= "Unrelated" (get-name)) "undo can still run the earlier edit"))
                (t/is (= -1 (:index (undo))))
                (ptk/emit! store dwu/redo)
                (await (async/wait-for-store store #(= "Earlier" (get-name)) "redo can still run the earlier edit"))
                (ptk/emit! store dwu/redo)
                (await (async/settle))
                (t/is (= "Earlier" (get-name)) "the later edit and the component action cannot be redone")
                (t/is (= [prefix] (:items (undo))))
                (t/is (nil? (thp/shape-attr store file-id page-id copy-child :touched)))
                (await (flush-and-settle store))
                (t/is (= (+ n 3) (count (get-save-and-read-requests requests)))))))
          (fn [cmd {:keys [revn vern]}]
            (->> (case cmd
                   :update-file-v2 (rx/of (or @reload (thp/save-response revn vern)))
                   :get-file (rx/take 1 read)
                   (rx/empty))
                 (rx/observe-on :async)))))))))

(t/deftest ^:async a-reload-hint-does-not-read-when-all-old-predecessors-already-applied
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store requests requests$ response]}]
       (let [page-id (uuid/next)
             shape-id (uuid/next)
             peer (uuid/next)
             edit (checked-rename file-id page-id shape-id "Original" "Saved")
             prior (mapv (fn [revn]
                           (revision-row file-id revn peer [{:type :mod-page :id page-id :name (str "Page " revn)}]))
                         [41 42 43])]
         (open-catch-up-file store file-id (uuid/next) page-id (thp/file-data page-id shape-id "Original") 40)
         (ptk/emit! store edit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (doseq [row prior]
           (ptk/emit! store (dps/apply-remote-message (file-message file-id peer row) nil))
           (await (async/wait-for-store store #(= (:revn row) (get-in @store [:files file-id :revn]))
                                        "the exact predecessor is applied")))
         (rx/push! response (assoc (thp/save-response 43) :recovery (thp/recovery :reload 40 prior)))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the proven base settles without a read"))
         (t/is (= [44 "Page 43" "Saved"]
                  [(get-in @store [:files file-id :revn])
                   (get-in @store [:files file-id :data :pages-index page-id :name])
                   (thp/shape-name store file-id page-id shape-id)]))
         (t/is (= [:update-file-v2] (mapv first @requests))))))))

(t/deftest ^:async a-reload-hint-goes-straight-to-uncached-reading-and-never-reposts
  (let [timers (atom [])
        save (rx/subject)
        failed-read (rx/subject)
        fresh-read (rx/subject)
        reads (atom 0)]
    (await
     (mock/with-mocks*
       {cf/flags #{}
        rx/timer (mock/stub (fn [ms] (swap! timers conj ms) (rx/subject)))}
       (await
        (with-persistence
          (^:async fn [{:keys [file-id store requests requests$]}]
            (let [page-id (uuid/next)
                  shape-id (uuid/next)
                  peer (uuid/next)
                  edit (checked-rename file-id page-id shape-id "Original" "Saved")
                  prior (mapv (fn [revn]
                                (revision-row file-id revn peer [{:type :mod-page :id page-id :name (str "Page " revn)}]))
                              [41 42 43])]
              (open-catch-up-file store file-id (uuid/next) page-id (thp/file-data page-id shape-id "Original") 40)
              (ptk/emit! store edit ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
              (rx/push! save (assoc (thp/save-response 43) :recovery (thp/recovery :reload 40 prior)))
              (await (async/wait-for #(= 2 (count @requests)) "the full read does not await WebSocket"
                                     {:signal requests$}))
              (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))
              (t/is (not-any? #{@#'dps/unseen-changes-timeout-ms} @timers))
              (t/is (true? (get-in @store [:persistence :index (:id @edit) :confirmed])))
              (rx/push! failed-read nil)
              (await (async/wait-for-store store
                                           #(false? (get-in @store [:persistence :reconciliations file-id :loading]))
                                           "the failed read preserves the confirmed head"))
              (t/is (= [(:id @edit)] (vec (get-in @store [:persistence :queue]))))
              (ptk/emit! store (#'dps/reconcile-file file-id {:retry true}))
              (await (async/wait-for #(= 3 (count @requests)) "only the full read is retried" {:signal requests$}))
              (t/is (= ["no-store" "no-store"] (mapv (comp ::rp/cache meta second) (rest @requests))))
              (rx/push! fresh-read {:id file-id :vern 0 :revn 44 :data (thp/file-data page-id shape-id "Stored")})
              (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                           "a fresh copy settles the head"))
              (t/is (= "Stored" (thp/shape-name store file-id page-id shape-id)))
              (t/is (= [:update-file-v2 :get-file :get-file] (mapv first @requests)))))
          (fn [cmd _]
            (->> (case cmd
                   :update-file-v2 save
                   :get-file (if (= 1 (swap! reads inc)) failed-read fresh-read)
                   (rx/empty))
                 (rx/take 1)
                 (rx/observe-on :async)))))))))

(defn- repair-answer
  [revn repair prior]
  (let [row {:id (uuid/next) :revn (+ 2 revn) :changes repair}]
    {:revn revn
     :vern 0
     :saved-revn (+ 2 revn)
     :repair row
     :written [{:id (uuid/next) :revn (inc revn) :kind :edit}
               (thp/written-row :repair row)]
     :recovery (if (seq prior) (thp/recovery :wait 0 prior) {:mode :none})}))

(defn- repair-before-edit-answer
  [repair]
  {:revn 0
   :vern 0
   :saved-revn 2
   :written [(thp/written-row :repair repair)
             {:id (uuid/next) :revn 2 :kind :edit}]
   :recovery {:mode :none}})

(t/deftest ^:async a-fallback-save-with-relinked-media-reads-the-final-image-id
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id    (uuid/next)
               shape-id   (uuid/next)
               source-id  (uuid/next)
               final-id   (uuid/next)
               fills      (fn [id] [{:fill-image {:id id :width 10 :height 10 :mtype "image/png"}}])
               data       #(assoc-in (thp/file-data page-id shape-id %1)
                                     [:pages-index page-id :objects shape-id :fills] (fills %2))
               edit       (thp/attr-commit file-id page-id shape-id :fills [] (fills source-id))
               buffered   (thp/rename-commit file-id page-id shape-id "Original" "Buffered")
               repair-row (revision-row file-id 1 repair-session-id [(thp/set-attr page-id shape-id :opacity 0.7)])
               repair-msg (assoc (file-message file-id repair-session-id repair-row) :repaired true)]
           (ptk/emit! store (thp/open-file file-id page-id (data "Original" source-id))
                      edit ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
           (ptk/emit! store #(assoc-in % [:files file-id :data :pages-index page-id :objects shape-id :name] "Buffered")
                      buffered)
           (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer])) "the next edit is buffered"))
           (rx/push! save (assoc (repair-before-edit-answer repair-row) :media-relinked true))
           (await (async/wait-for #(= :get-file (first (peek @requests))) "the accepted edit needs a read"
                                  {:signal requests$}))
           (rx/push! read {:id file-id :vern 0 :revn 2
                           :data (assoc-in (data "Original" final-id)
                                           [:pages-index page-id :objects shape-id :opacity] 0.7)})
           (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
                                        "the read settles the confirmed save"))
           (t/is (= (fills final-id) (thp/shape-attr store file-id page-id shape-id :fills)))
           (t/is (= "Buffered" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= dps/file-repaired-tag (get-in @store [:notification :tag]))
                 "the HTTP fallback announces the repair")
           (ptk/emit! store (ntf/hide :tag dps/file-repaired-tag) (dwn/handle-file-change repair-msg))
           (await (async/settle))
           (t/is (nil? (:notification @store)) "the later WebSocket row does not announce it again")
           (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(defn- open-with-new-child
  "Opens a page whose root holds `shape-id` and the new child `child`, as
  the local data does once the user adds it."
  [store file-id session-id page-id shape-id child]
  (ptk/emit! store
             (thp/open-file file-id page-id
                            (thp/add-shape (thp/file-data page-id shape-id "S") page-id child))
             #(assoc % :session-id session-id)))

(t/deftest ^:async a-repair-in-the-save-answer-goes-after-the-save-and-before-buffered-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             local    (thp/rename-commit file-id page-id shape-id "Original" "Saved")
             buffered (thp/rename-commit file-id page-id shape-id "Saved" "Buffered")
             repair   [(thp/rename page-id shape-id "Repaired")
                       (thp/set-attr page-id shape-id :opacity 0.7)]]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                    local ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Buffered"))
                    buffered)
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the second edit is still buffered"))
         (rx/push! response (repair-answer 0 repair []))
         (await (async/wait-for-store store #(empty? (get-in @store [:persistence :queue]))
                                      "the repair needs no WebSocket echo"))
         (t/is (= :update-file-v2 (ffirst @requests)))
         (t/is (true? (get-in @requests [0 1 :repair-in-response])))
         (t/is (= "Buffered" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 0.7 (thp/shape-attr store file-id page-id shape-id :opacity))
               "the repair changed the view without entering the save queue"))))))

(t/deftest ^:async a-file-change-older-than-an-applied-aggregate-does-not-regress-the-view
  (await
   (with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             peer     (uuid/next)
             edit-id  (uuid/next)
             msg      {:type :file-change :file-id file-id :vern 0
                       :profile-id (uuid/next) :session-id peer}]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Original")))
         (ptk/emit! store
                    (dwn/handle-file-change
                     (assoc msg :revn 2 :repair-base-revn 0 :repaired true
                            :revision-ids [edit-id (uuid/next)]
                            :changes [(thp/rename page-id shape-id "Repaired")])))
         (await (async/wait-for-store store #(= "Repaired" (thp/shape-name store file-id page-id shape-id))
                                      "the two revisions are applied as one"))
         (ptk/emit! store
                    (dwn/handle-file-change
                     (assoc msg :revn 1 :revision-ids [edit-id]
                            :changes [(thp/rename page-id shape-id "Old")])))
         (await (async/settle))
         (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id))))))))

(t/deftest ^:async a-repair-waits-for-the-websocket-row-it-actually-depends-on
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             peer     (uuid/next)
             edit     (thp/rename-commit file-id page-id shape-id "Original" "Saving")
             prior    (revision-row file-id 1 peer [(thp/rename page-id shape-id "Peer")])]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saving"))
                    edit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (rx/push! response (repair-answer 1
                                           [(thp/rename page-id shape-id "Repaired")] [prior]))
         (await (async/wait-for-store store #(some? (get-in @store [:persistence :index (:id @edit) ::dps/save-result]))
                                      "The answer is awaiting its predecessor"))
         (t/is (= "Saving" (thp/shape-name store file-id page-id shape-id)))
         (ptk/emit! store (dwn/handle-file-change (file-message file-id peer prior)))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "The predecessor makes it safe to apply the repair"))
         (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= [:update-file-v2] (mapv first @requests))))))))

(t/deftest ^:async an-opt-in-fallback-keeps-the-edit-over-the-websocket-repair
  (await
   (with-persistence
     (^:async fn [{:keys [file-id session-id response requests requests$ store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             child    (cts/setup-shape {:id (uuid/next) :type :rect :name "New"
                                        :parent-id uuid/zero :frame-id uuid/zero})
             edit     (add-child-commit file-id page-id child)
             repair   (revision-row file-id 1 repair-session-id [(set-root-children page-id [shape-id])])]
         (open-with-new-child store file-id session-id page-id shape-id child)
         (ptk/emit! store edit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
         (rx/push! response (repair-before-edit-answer repair))
         (await (async/wait-for-store store #(some? (get-in @store [:persistence :index (:id @edit) ::dps/save-result]))
                                      "the fallback awaits the repair"))
         (ptk/emit! store (dwn/handle-file-change (assoc (file-message file-id repair-session-id repair)
                                                         :repaired true)))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the fallback is settled"))
         (t/is (= [shape-id (:id child)] (root-children store file-id page-id)))
         (t/is (= [:update-file-v2] (mapv first @requests))))))))

(t/deftest ^:async the-same-revision-number-cannot-stand-in-for-the-required-row
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               peer     (uuid/next)
               edit     (thp/rename-commit file-id page-id shape-id "Original" "Saving")
               prior    (revision-row file-id 1 peer [(thp/rename page-id shape-id "Peer")])
               wrong    (assoc prior :id (uuid/next))]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saving"))
                      edit ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
           (ptk/emit! store (dwn/handle-file-change (file-message file-id peer wrong)))
           (rx/push! save (repair-answer 1
                                         [(thp/rename page-id shape-id "Repaired")] [prior]))
           (await (async/wait-for #(some (comp #{:get-file} first) @requests)
                                  "the unrelated row cannot authorize the rebase" {:signal requests$}))
           (t/is (= "Saving" (thp/shape-name store file-id page-id shape-id)))
           (rx/push! read {:id file-id :vern 0 :revn 4
                           :data (thp/file-data page-id shape-id "Newest")})
           (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                        "the server's view settles the save"))
           (t/is (= "Newest" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/observe-on :async)))))))

(t/deftest ^:async a-reconciliation-read-behind-a-newer-observed-change-is-not-installed
  (let [read (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               peer     (uuid/next)
               row      #(file-message file-id peer (revision-row file-id %1 peer
                                                                  [(thp/rename page-id shape-id %2)]))]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Original"))
                      (dwn/handle-file-change (row 3 "After repair")))
           (await (async/wait-for #(= :get-file (ffirst @requests))
                                  "the gap starts a read" {:signal requests$}))
           (ptk/emit! store
                      (dwn/handle-file-change
                       (assoc (row 2 "Repair") :repair-base-revn 0 :repaired true
                              :revision-ids [(uuid/next) (uuid/next)])))
           (ptk/emit! store (dwn/handle-file-change (row 4 "Most recent")))
           (await (async/wait-for-store store #(= 4 (get-in @store [:persistence :reconciliations file-id :minimum]))
                                        "the read now needs to cover the newer revision"))
           (rx/push! read {:id file-id :vern 0 :revn 3 :data (thp/file-data page-id shape-id "Too old")})
           (await (async/wait-for-store store #(= :persistence-reconciliation-failed
                                                  (get-in @store [:notification :tag]))
                                        "the view is preserved when the read is too old"))
           (t/is (= "Original" (thp/shape-name store file-id page-id shape-id)))
           (t/is (not (dps/terminal-status? (:persistence @store))))
           (t/is (= [:get-file] (mapv first @requests)))))
       (fn [cmd _] (if (= cmd :get-file) (->> read (rx/observe-on :async)) (rx/empty)))))))

(t/deftest ^:async reopening-with-the-same-vern-does-not-install-a-read-from-the-previous-opening
  (let [answer (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [page-id (uuid/next)
               shape-id (uuid/next)
               peer (uuid/next)
               initial (assoc (file-message file-id peer
                                            (revision-row file-id 3 peer [(thp/rename page-id shape-id "Peer")]))
                              :repair-base-revn 1 :revision-ids [(uuid/next) (uuid/next)])]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Original"))
                      #(assoc % :workspace-load-id (uuid/next))
                      (dwn/handle-file-change initial))
           (await (async/wait-for #(= :get-file (ffirst @requests))
                                  "the gap launches a read" {:signal requests$}))
           ;; Install the next opening without starting its unrelated shell requests.
           (ptk/emit! store #(-> %
                                 (assoc :workspace-load-id (uuid/next))
                                 (assoc-in [:files file-id] {:id file-id :vern 0 :revn 2
                                                             :data (thp/file-data page-id shape-id "Reopened")})))
           (rx/push! answer {:id file-id :vern 0 :revn 3 :data (thp/file-data page-id shape-id "Old opening")})
           (await (async/settle))
           (t/is (= "Reopened" (thp/shape-name store file-id page-id shape-id)))))
       (fn [cmd _] (if (= cmd :get-file) (->> answer (rx/observe-on :async)) (rx/empty)))))))

(t/deftest ^:async rejecting-the-rest-of-a-repaired-group-keeps-the-saved-prefix
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store failures]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               group    (uuid/next)
               edit     (fn [from to]
                          (let [data @(thp/rename-commit file-id page-id shape-id from to)]
                            (ptk/data-event ::dch/commit (assoc data :undo-group group))))
               prefix   (edit "Original" "Saved")
               later    (edit "Saved" "Rejected")]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                      prefix ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the prefix is sent" {:signal requests$}))
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Rejected"))
                      later ::dps/force-persist)
           (rx/push! save (repair-answer 0
                                         [(thp/rename page-id shape-id "Repaired")] []))
           (await (async/wait-for #(= 2 (count @requests)) "the rest is sent" {:signal requests$}))
           (t/is (= [(:id @prefix)] (mapv :id (get-in @store [:persistence :saved]))))
           (rx/push! save (assoc (thp/rejection (:id @later)) :revn 2))
           (await (async/wait-for #(some (comp #{:get-file} first) @requests)
                                  "the rollback reads instead of undoing the saved prefix" {:signal requests$}))
           (rx/push! read {:id file-id :vern 0 :revn 2 :data (thp/file-data page-id shape-id "Repaired")})
           (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                        "the failed suffix settles against the repaired file"))
           (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= 1 (:kept-saved (ex-data (first @failures)))))
           (t/is (= [:update-file-v2 :update-file-v2 :get-file] (mapv first @requests)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/observe-on :async)))))))

(defn- ^:async leave-before-applying-a-repair
  [already-answered?]
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store failures]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)
             group    (uuid/next)
             edit     (fn [from to]
                        (ptk/data-event ::dch/commit
                                        (assoc @(thp/rename-commit file-id page-id shape-id from to)
                                               :undo-group group)))
             prefix   (edit "Original" "Saved")
             later    (edit "Saved" "Rejected")
             prior    (revision-row file-id 1 (uuid/next) [])
             answer   (repair-answer 1
                                     [(thp/rename page-id shape-id "Repaired")] [prior])]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                    prefix ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the prefix is sent" {:signal requests$}))
         (ptk/emit! store later ::dps/force-persist)
         (when already-answered?
           (rx/push! response answer)
           (await (async/wait-for-store store
                                        #(get-in @store [:persistence :index (:id @prefix) ::dps/save-result])
                                        "the repair is waiting for a predecessor")))
         (ptk/emit! store #(-> %
                               (dissoc :current-file-id)
                               (update-in [:files file-id] dissoc :data))
                    (ptk/data-event ::dw/finalize-workspace))
         (when-not already-answered?
           (rx/push! response answer))
         (await (async/wait-for #(= 2 (count @requests)) "the prefix settles on exit" {:signal requests$}))
         (rx/push! response (assoc (thp/rejection (:id @later)) :revn 3))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the remaining rejected edit settles without compensation"))
         (t/is (= 1 (:kept-saved (ex-data (first @failures)))))
         (t/is (= [:update-file-v2 :update-file-v2] (mapv first @requests))))))))

(t/deftest ^:async closing-while-a-repair-awaits-a-predecessor-protects-saved-undo
  (await (leave-before-applying-a-repair true)))

(t/deftest ^:async a-repair-answer-for-an-unloaded-file-protects-saved-undo
  (await (leave-before-applying-a-repair false)))

(t/deftest ^:async a-new-vern-releases-a-head-confirmed-during-the-restore
  (let [save (rx/subject)
        read (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id requests requests$ store]}]
         (let [other-id (uuid/next)
               page-id  (uuid/next)
               shape-id (uuid/next)
               edit     (thp/rename-commit file-id page-id shape-id "Original" "Saved")
               next     (local-commit other-id)
               restored (thp/file-data page-id shape-id "Restored")]
           (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                      #(assoc % :workspace-load-id (uuid/next))
                      edit next ::dps/force-persist)
           (await (async/wait-for #(= 1 (count @requests)) "the original edit is sent" {:signal requests$}))
           ;; The new opening has started, but still holds the old data until
           ;; its file bundle arrives.
           (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)))
           (rx/push! save (repair-answer 0
                                         [(thp/rename page-id shape-id "Repaired")] []))
           (await (async/wait-for #(some (comp #{:get-file} first) @requests)
                                  "the late save starts a read against the old vern" {:signal requests$}))
           (ptk/emit! store (#'dw/bundle-fetched {:file-id file-id :thumbnails {}
                                                  :file {:id file-id :revn 3 :vern 1 :data restored}}))
           (await (async/wait-for #(= other-id (get-in @requests [2 1 :id]))
                                  "the next file can save without waiting for the obsolete read"
                                  {:signal requests$}))
           (rx/push! read {:id file-id :vern 0 :revn 2 :data (thp/file-data page-id shape-id "Old version")})
           (rx/push! save (thp/save-response 0))
           (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                        "the queue drains after the restore"))
           (t/is (= "Restored" (thp/shape-name store file-id page-id shape-id)))
           (t/is (empty? (get-in @store [:persistence :reconciliations])))
           (t/is (= [:update-file-v2 :get-file :update-file-v2] (mapv first @requests)))))
       (fn [cmd _]
         (->> (case cmd :update-file-v2 save :get-file read (rx/empty))
              (rx/observe-on :async)))))))

;; Scenario: the user adds a child to a page with old damage. The server
;; repairs the page first, storing a repair that sets the root's children
;; without the new child, then stores the user's change, which adds it. The
;; save answer reaches the tab before the repair's message. Proves: the
;; saved commit stays pending until the repair arrives, so the repair goes
;; under it and the tab ends as the server does.
(t/deftest ^:async a-repair-answered-before-its-message-goes-under-the-saved-commit
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [session-id (uuid/next)
             page-id    (uuid/next)
             shape-id   (uuid/next)
             child      (cts/setup-shape {:id (uuid/next) :type :rect :name "Rectangle"
                                          :parent-id uuid/zero :frame-id uuid/zero})
             commit     (add-child-commit file-id page-id child)
             repair     (revision-row file-id 1 repair-session-id [(set-root-children page-id [shape-id])])]
         (open-with-new-child store file-id session-id page-id shape-id child)
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the new child is sent" {:signal requests$}))
         (rx/push! response (repair-before-edit-answer repair))
         (await (async/wait-for-store store #(get-in @store [:persistence :index (:id @commit) ::dps/save-result])
                                      "the saved commit waits for the repair"))
         (t/is (= [(:id @commit)] (vec (get-in @store [:persistence :queue]))))

         (ptk/emit! store (dwn/handle-file-change (assoc (file-message file-id repair-session-id repair) :repaired true)))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the saved commit is acknowledged once the repair arrives"))
         (t/is (= [shape-id (:id child)] (root-children store file-id page-id))
               "the repair goes under the new child, as on the server"))))))

;; Scenario: the repair's message reaches the tab before the save answer.
;; Proves: the answer does not wait for a change the tab already applied.
(t/deftest ^:async a-save-answer-does-not-wait-for-changes-already-received
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [session-id (uuid/next)
             page-id    (uuid/next)
             shape-id   (uuid/next)
             child      (cts/setup-shape {:id (uuid/next) :type :rect :name "Rectangle"
                                          :parent-id uuid/zero :frame-id uuid/zero})
             commit     (add-child-commit file-id page-id child)
             repair     (revision-row file-id 1 repair-session-id [(set-root-children page-id [shape-id])])]
         (open-with-new-child store file-id session-id page-id shape-id child)
         (ptk/emit! store commit ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the new child is sent" {:signal requests$}))
         (ptk/emit! store (dwn/handle-file-change (assoc (file-message file-id repair-session-id repair) :repaired true)))
         (await (async/wait-for-store store #(= 1 (get-in @store [:files file-id :revn])) "the repair is applied"))
         (rx/push! response (repair-before-edit-answer repair))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the save lands at once"))
         (t/is (= [shape-id (:id child)] (root-children store file-id page-id)))
         (t/is (= [:update-file-v2] (mapv first @requests))))))))

;; The save names a predecessor whose WebSocket message never arrives.
;; With incremental recovery disabled, the wait expires and a full read
;; settles the confirmed save.
(t/deftest ^:async a-change-that-never-arrives-reloads-the-file
  (let [timeout-s (rx/subject)]
    (await
     (with-persistence
       (^:async fn [{:keys [file-id response requests requests$ store]}]
         (await
          (mock/with-mocks*
            {cf/flags #{}
             rx/timer (mock/stub (fn [ms]
                                   (if (= ms @#'dps/unseen-changes-timeout-ms)
                                     timeout-s
                                     (rx/subject))))}
            (let [page-id  (uuid/next)
                  shape-id (uuid/next)
                  peer     (uuid/next)
                  commit   (thp/rename-commit file-id page-id shape-id "Original" "Saved")
                  prior    (revision-row file-id 1 peer [(thp/set-attr page-id shape-id :opacity 0.7)])]
              (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Saved"))
                         commit ::dps/force-persist)
              (await (async/wait-for #(= 1 (count @requests)) "the edit is sent" {:signal requests$}))
              (rx/push! response (assoc (thp/save-response 1) :recovery (thp/recovery :wait 0 [prior])))
              (await (async/wait-for-store store #(get-in @store [:persistence :index (:id @commit) ::dps/save-result])
                                           "the saved commit waits for the peer"))
              (t/is (= [:update-file-v2] (mapv first @requests)))
              (rx/push! timeout-s :tick)
              (await (async/wait-for #(= 2 (count @requests)) "the file reloads" {:signal requests$}))
              (rx/push! response {:id file-id :vern 0 :revn 2 :data (thp/file-data page-id shape-id "From server")})
              (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                           "the fresh file settles the save"))
              (t/is (= "From server" (thp/shape-name store file-id page-id shape-id)))
              (t/is (= [:update-file-v2 :get-file] (mapv first @requests)))))))))))

;; Commit boundaries.

(t/deftest ^:async a-save-preserves-commit-boundaries-and-the-order-of-forward-changes
  (await
   (with-persistence
     (^:async fn [{:keys [file-id session-id response requests requests$ store]}]
       (let [page-id (uuid/next)
             rename  (fn [name] {:type :mod-page :id page-id :name name})
             commit1 (local-commit file-id {:redo-changes [(rename "B") (rename "C")]
                                            :undo-changes [(rename "B") (rename "A")]})
             commit2 (local-commit file-id {:redo-changes [(rename "D")]
                                            :undo-changes [(rename "C")]})]
         (ptk/emit! store commit1 commit2 ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "both edits go in one save" {:signal requests$}))
         (t/is (= [[:update-file-v2
                    {:id file-id :session-id session-id :revn 0 :vern 0 :features #{}
                     :repair-in-response true
                     :commits [{:commit-id (:id @commit1)
                                :changes [(rename "B") (rename "C")]}
                               {:commit-id (:id @commit2)
                                :changes [(rename "D")]}]}]]
                  @requests))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                      "the save lands")))))))

;; Scenario: a save is in flight while a later edit waits in the buffer, and
;; something waits for the edits to be saved (as a library sync does before
;; marking the library synced). The save lands and the queue empties, so the
;; status turns :saved. Proves: the waiter is released only once the
;; buffered edit is saved too.
(t/deftest ^:async waiting-for-the-save-covers-the-buffered-edits
  (await
   (with-persistence
     (^:async fn [{:keys [file-id response requests requests$ store]}]
       (let [released? #(dps/terminal-status? (:persistence @store))]
         (ptk/emit! store (local-commit file-id) ::dps/force-persist)
         (await (async/wait-for #(= 1 (count @requests)) "the first edit is sent" {:signal requests$}))
         (ptk/emit! store (local-commit file-id))
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the second edit waits in the buffer"))
         (rx/push! response (thp/save-response 0))
         (await (async/wait-for-store store #(and (= :saved (get-in @store [:persistence :status]))
                                                  (empty? (get-in @store [:persistence :queue])))
                                      "the first save lands"))
         (t/is (seq (get-in @store [:persistence :buffer])))
         (t/is (not (released?)) "the buffered edit is not saved yet")
         (ptk/emit! store ::dps/force-persist)
         (await (async/wait-for #(= 2 (count @requests)) "the buffered edit is sent" {:signal requests$}))
         (rx/push! response (thp/save-response 1))
         (await (async/wait-for released? "the waiters are released")))))))
