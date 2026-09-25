;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs
  "Public API for the unified jobs substrate.

  Every job is defined as a job-def map returned by the `ig/init-key` of its
  module: {::name, ::schema, ::handler, ::decoder, ::validator} — decoder and
  validator are precompiled at init time. The registry of job-defs is plain
  integrant wiring (::jobs/defs); submit, dispatch and management consume it
  by reference.

  Two execution modes are provided:
  - `submit` (durable): validates + JSON-encodes params and inserts a row
    into the `job` table; the dispatcher/runner machinery does the rest.
  - `request` (ephemeral): synchronous request/response over the redis
    queues with a reply-key and a dedicated, unbounded connection pool;
    external workers answer with `reply`.

  Params payloads are stored as plain JSON (not transit) in the `params` jsonb
  column and decoded back to typed Clojure values using the job-def decoder.

  Any job that can run longer than `:jobs-lease` must call `heartbeat`
  on its loop, otherwise the dispatcher marks it orphaned while its side
  effects continue. `heartbeat` also accepts an optional `progress` report,
  which is stored as a `progress` row of `job_event`: progress is durable
  history, not a mutable column, and needs no Redis.

  Events of a job with a `profile_id` publish a `:job-event` message on the
  topic of that profile after the transaction commits, so a client can
  follow the job without polling the database.

  Reserved ledger columns (`profile_id`, `error`, `result`, `resource_id`,
  `expires_at`) are only written by `submit` (profile and resource
  references) and by the terminal writers; `submit` never infers them from
  `params`."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.generic-pool :as gpool]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs.metrics :as jobs-metrics]
   [app.metrics :as-alias mtx]
   [app.msgbus :as mbus]
   [app.redis :as rds]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER CONTEXT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:context
  "What a handler is allowed to know about the job it is running. The
  schema is closed on purpose: a handler must not be able to reach the
  whole row, and adding a key here is a decision, not an accident.

  `queue` is deliberately absent: the column stores a tenant-prefixed
  queue, which is a routing detail of the dispatcher, not a property of
  the job. `name` already says which job this is.

  The retry keys are absent too. Retry policy belongs to the runner: it
  is the one comparing `retry-num` with `max-retries` and deciding
  whether to schedule again. A handler has no use for the counter, and
  the attempt number would lie anyway: the `noop` retry strategy runs
  again without incrementing it, so a durable \"which execution is this\"
  number would need a second counter that nothing bounds."
  [:map {:closed true
         :title "job-context"}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:label [:maybe ::sm/text]]
   ;; technical reference kept for garbage collection
   [:resource-id [:maybe ::sm/uuid]]])

(def check-context
  "Validate a handler context; raises with the malli explanation when it
  does not match the closed schema."
  (sm/check-fn schema:context))

(defn make-context
  "Build the handler context from a job row: exactly the four keys a
  handler may see, and nothing else. The result is a plain map, not the
  database row, and it is not modified afterwards.

  The empty label is the `submit` default for \"no label\", so it becomes
  nil here: a handler sees either a label or nothing."
  [job]
  (check-context {:id          (:id job)
                  :name        (:name job)
                  :label       (if (str/blank? (:label job)) nil (:label job))
                  :resource-id (:resource-id job)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB DEFINITIONS (registry)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:job-def
  [:map {:title "job-def"}
   [::name [:or ::sm/text :keyword]]
   [::schema any?]
   ;; every handler is [context params]. A job-def already closes over its
   ;; own dependencies, so nothing else is handed to it: a handler cannot
   ;; reach the runner cfg, the pool or any other component.
   ;;
   ;; A wrapper forwards the context to its implementation only when that
   ;; implementation takes it. Most jobs have no use for it, so most
   ;; wrappers are (fn [_context params] (execute-X cfg params)) and their
   ;; implementation stays [cfg params].
   [::handler ::sm/fn]
   [::decoder ::sm/fn]
   [::validator ::sm/fn]])

(def ^:private schema:job-defs
  [:map-of :keyword schema:job-def])

;; Module-level registry of the job-defs, populated by the ::jobs/defs
;; ig component on init. It exists for the submit call-sites that run
;; inside components that cannot reference `::jobs/defs` by ig/ref
;; (the job-def components themselves are part of the registry; an ig
;; ref would create a wiring cycle). Call-sites that can provide the
;; registry via cfg take precedence over this global one.
(def ^:private defs-registry (atom {}))

(defn get-defs
  "The registry for submit/lookup: the one provided on the cfg has
  precedence over the module-level one (used by tests)."
  [cfg]
  (or (get cfg ::defs) @defs-registry))

(def ^:private definitions-validator (sm/validator schema:job-defs))

(defmethod ig/assert-key ::defs
  [_ defs]
  (assert (definitions-validator defs) "expected valid job-defs map")
  (doseq [[name job-def] defs]
    (when-not (= (d/name name) (d/name (::name job-def)))
      (ex/raise :type :assertion
                :code :job-def-name-mismatch
                :hint "job-def name mismatch"
                :job (d/name name)
                :expected (d/name (::name job-def))))))

(defmethod ig/init-key ::defs
  [_ defs]
  (reset! defs-registry defs)
  (l/inf :hint "job definitions initialized" :jobs (count defs))
  defs)

(defmethod ig/halt-key! ::defs
  [_ defs]
  (reset! defs-registry {})
  (l/inf :hint "job definitions halted" :jobs (count defs)))

(defn- require-metrics
  [cfg]
  (or (::mtx/metrics cfg)
      (ex/raise :type :assertion
                :code :missing-metrics
                :hint "missing ::mtx/metrics on jobs cfg")))

(defn get-job-def
  "Resolve the job-def for the provided job name; raises if missing."
  [defs name]
  (or (get defs (keyword name))
      (ex/raise :type :not-found
                :hint "no job definition found"
                :code :no-job-definition
                :name (d/name name))))

(defn decode-params
  "Decode the raw JSON params (pgobject or decoded map) into typed params
  using the precompiled decoder of the job-def."
  [job-def params]
  (-> (cond-> params
        (db/pgobject? params)
        db/decode-json-pgobject)
      ((::decoder job-def))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:insert-new-job
  "insert into job (id, name, params, queue, label, priority, max_retries,
                    profile_id, resource_id, created_at, modified_at,
                    scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
   returning id")

(def ^:private sql:remove-not-started-jobs
  "DELETE FROM job
    WHERE name=?
      AND queue=?
      AND label=?
      AND status = 'new'
      AND scheduled_at > ?")

(def ^:private schema:options
  [:map {:title "submit-options"}
   [::name [:or ::sm/text :keyword]]
   [::label {:optional true} ::sm/text]
   [::delay {:optional true}
    [:or ::sm/int ::ct/duration]]
   [::queue {:optional true} [:or ::sm/text :keyword]]
   [::priority {:optional true} ::sm/int]
   [::max-retries {:optional true} ::sm/int]
   [::dedupe {:optional true} ::sm/boolean]
   ;; Owner of a user-facing job. Its events are published on the msgbus
   ;; topic of this profile; internal jobs omit it.
   [::profile-id {:optional true} ::sm/uuid]
   ;; Technical reference (storage object) kept for garbage collection. It
   ;; is a column, never part of `params`, and it is never inferred from it.
   [::resource-id {:optional true} ::sm/uuid]])

(def check-options
  (sm/check-fn schema:options))

(defn validate-params
  "Validate the params with the precompiled validator of the job-def; on
  failure raises with the malli explanation. Shared by `submit` (raw
  params) and the runner (decoded params). `invoke` deliberately stays
  lenient: it is the in-process escape hatch used by tests (legacy task
  params via `run-task!`) and the REPL."
  [job-def params]
  (when-not ^boolean ((::validator job-def) params)
    (sm/check (::schema job-def) params))
  params)

(defn submit
  "Submit a durable job: validates the params with the job-def validator,
  encodes them as plain JSON and inserts a row into the `job` table.
  Fire-and-forget: returns the job id immediately.

  NOTE: the dedupe DELETE and the INSERT run atomically: joined to
  the caller's transaction when the cfg provides `::db/conn`, wrapped
  in their own transaction otherwise. Concurrent cross-backend
  submissions can, in rare race conditions, produce duplicated 'new'
  rows (accepted risk, see prod-infra documentation)."
  [cfg {:keys [::params ::name ::delay ::queue ::priority ::max-retries
               ::dedupe ::label ::profile-id ::resource-id]
        :or   {delay 0 queue :default priority 100 max-retries 3 label ""}
        :as   options}]

  (let [metrics (require-metrics cfg)]
    (check-options options)

    (let [job-def      (get-job-def (get-defs cfg) name)
          params       (validate-params job-def params)
          delay        (ct/duration delay)
          now          (ct/now)
          scheduled-at (-> (ct/plus now delay)
                           (ct/truncate :millisecond))
          ;; The :rollback? testing escape hatch must never persist on a
          ;; durable row (the runner would roll everything back yet mark
          ;; the job completed); it stays available in-process via
          ;; invoke/run-task!. Validation above is untouched.
          ;; Duration values are normalized to millis: a Duration object
          ;; does not survive JSON encoding (schemas still accept it for
          ;; in-process callers).
          payload      (db/json (-> (dissoc params :rollback?)
                                    (update-vals #(if (ct/duration? %)
                                                    (.toMillis ^java.time.Duration %)
                                                    %))))
          id           (uuid/next)
          tenant       (cf/get :tenant)
          job-name     (d/name name)
          queue        (str/ffmt "%:%" tenant (d/name queue))
          ;; Dedupe is best-effort: we delete not-started jobs with the
          ;; same name/queue/label, then insert. A race between backends
          ;; could create duplicates, but this is acceptable:
          ;; cross-backend races are rare, jobs are idempotent, and
          ;; dedupe is best-effort.
          insert!      (fn [conn]
                         (let [deleted (when dedupe
                                         (-> (db/exec-one! conn [sql:remove-not-started-jobs
                                                                 job-name queue label now])
                                             (db/get-update-count)))]
                           (l/trc :hint "submit job"
                                  :name job-name
                                  :job-id (str id)
                                  :queue queue
                                  :label label
                                  :dedupe (boolean dedupe)
                                  :delay (ct/format-duration delay)
                                  :replace (or deleted 0))
                           (db/exec-one! conn [sql:insert-new-job id job-name payload queue
                                               label priority max-retries
                                               profile-id resource-id
                                               now now scheduled-at])))]
      ;; Both statements always run inside db/tx-run!: joined to the
      ;; caller's transaction when the cfg provides a connection,
      ;; wrapped in their own otherwise (a failed INSERT can never
      ;; orphan a committed DELETE, even on an autocommit caller conn).
      (db/tx-run! cfg
                  (fn [{:keys [::db/conn]}]
                    (let [result (insert! conn)]
                      (db/after-commit!
                       #(jobs-metrics/record-submitted metrics job-name queue))
                      result)))
      id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:cancel-job
  "UPDATE job
      SET status='cancelled', modified_at=?
    WHERE id=?
      AND status IN ('new','scheduled','retry')")

(defn- decode-json-col
  [row key]
  (cond-> row
    (db/pgobject? (get row key))
    (assoc key (db/decode-json-pgobject (get row key)))))

(defn- decode-row
  [row]
  (decode-json-col row :params))

(defn get-job
  "Retrieve the job row (with the raw JSON params decoded to a plain map)."
  [cfg job-id]
  (some-> (db/get* cfg :job {:id job-id})
          (decode-row)))

(defn cancel
  "Cancel a pending job (new/scheduled/retry). Returns the number of
  affected rows; jobs already running or in a terminal state are left
  untouched (the conditional claim in the runner/management API will skip
  them)."
  [cfg job-id]
  (let [metrics (require-metrics cfg)
        job     (get-job cfg job-id)
        n       (-> (db/exec-one! (db/get-connectable cfg)
                                  [sql:cancel-job (ct/now) job-id])
                    (db/get-update-count))]
    (when (pos? n)
      (db/after-commit!
       #(jobs-metrics/record-outcome metrics
                                     (:name job)
                                     (:queue job)
                                     :cancelled)))
    n))

(defn get-user-status
  "Map the internal job status to the user-facing status."
  [status]
  (let [status (d/name status)]
    (case status
      ("new" "scheduled" "retry") "pending"
      "running"                   "running"
      "completed"                 "completed"
      ("failed" "cancelled")      "failed")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; `job_event` is an append-only log: it is the only durable place for
;; progress, and the place where the lifecycle of a job is recorded. There
;; is no Redis key and no mutable column to keep in sync.

;; mirrors the `job_event.kind` CHECK constraint of migration 0154
(def ^:private known-event-kinds
  #{"start" "progress" "retry" "end"})

(def ^:private known-outcomes
  #{"completed" "failed" "cancelled"})

(def ^:private known-retry-reasons
  #{"backoff" "noop"})

(def ^:private max-stage-length 250)

(def schema:progress
  "Progress report of a job: `current` is mandatory, `total` and `stage` are
  optional, and no other key is accepted. `stage` is a short human label,
  not a place for exception messages or params."
  [:map {:closed true
         :title "job-progress"}
   [:current ::sm/int]
   [:total {:optional true} ::sm/int]
   [:stage {:optional true} ::sm/text]])

(def check-progress
  "Validate a progress report against its schema."
  (sm/check-fn schema:progress))

(defn validate-progress
  "Check the range rules a schema cannot express: non-negative `current`,
  positive `total`, `current` never greater than `total` and a short
  `stage`."
  [progress]
  (let [progress (check-progress progress)]
    (when (neg? (:current progress))
      (ex/raise :type :validation
                :code :invalid-progress
                :hint "progress current must not be negative"
                :progress progress))
    (when-let [total (:total progress)]
      (when (or (not (pos? total))
                (> (:current progress) total))
        (ex/raise :type :validation
                  :code :invalid-progress
                  :hint "progress total must be positive and not lower than current"
                  :progress progress)))
    (when (and (:stage progress)
               (> (count (:stage progress)) max-stage-length))
      (ex/raise :type :validation
                :code :invalid-progress
                :hint (str "progress stage must not be longer than "
                           max-stage-length " characters")
                :progress progress))
    progress))

(def ^:private event-payload-keys
  {"start"    #{:attempt}
   "progress" #{:current :total :stage}
   "retry"    #{:attempt :reason}
   "end"      #{:outcome}})

(defn- validate-event-payload
  "Reject anything the durable log must never store: unknown kinds, unknown
  outcomes or retry reasons, extra keys, params or exception text."
  [kind payload]
  (let [reject (fn [hint details]
                 (ex/raise :type :validation
                           :code :invalid-job-event
                           :hint hint
                           :kind kind
                           :details details))
        reject-attempt #(reject "job event attempt must be a non negative integer"
                                payload)
        allowed (get event-payload-keys kind)]
    (when-not (contains? known-event-kinds kind)
      (reject "unknown job event kind" kind))
    (when (seq (remove allowed (keys payload)))
      (reject "job event payload has unexpected keys" (keys payload)))
    (case kind
      "start" (when-not (nat-int? (:attempt payload)) (reject-attempt))
      "retry" (do (when-not (nat-int? (:attempt payload)) (reject-attempt))
                  (when-not (contains? known-retry-reasons (:reason payload))
                    (reject "unknown job event retry reason" (:reason payload))))
      "end"   (when-not (contains? known-outcomes (:outcome payload))
                (reject "unknown job event outcome" (:outcome payload)))
      "progress" (validate-progress payload))
    payload))

(def ^:private sql:insert-job-event
  "INSERT INTO job_event (job_id, kind, payload)
   VALUES (?, ?, ?)
   RETURNING id, created_at")

(def ^:private sql:job-event-owner
  "SELECT profile_id FROM job WHERE id = ?")

(defn- notify-event
  "Publish a `:job-event` message on the topic of the job profile. Runs
  after the commit that inserted the event: a listener that is not
  connected never rolls back durable history."
  [msgbus {:keys [profile-id job-id event-id kind payload created-at]}]
  (mbus/pub! msgbus
             :topic profile-id
             :message {:type       :job-event
                       :profile-id profile-id
                       :job-id     job-id
                       :event-id   event-id
                       :kind       kind
                       :payload    payload
                       :created-at created-at}))

(defn insert-event
  "Insert a `job_event` row and schedule its post-commit notification.

  Must be called inside a transaction that owns the job row. When the job
  has a `profile_id` the cfg must carry a msgbus: the check happens
  before the insert, so a profile job never loses its notification
  silently. Jobs without profile store the event and publish nothing."
  [{:keys [::mbus/msgbus] :as cfg} job-id kind payload]
  (let [payload    (validate-event-payload kind payload)
        connectable (db/get-connectable cfg)
        profile-id (:profile-id (db/exec-one! connectable [sql:job-event-owner job-id]))]
    (when (and profile-id
               (not (mbus/msgbus? msgbus)))
      (ex/raise :type :assertion
                :code :missing-msgbus
                :hint "job events of a profile job require ::mbus/msgbus on the cfg"
                :job-id job-id
                :profile-id profile-id))
    (let [{:keys [id created-at]}
          (db/exec-one! connectable [sql:insert-job-event job-id kind (db/json payload)])]
      (when profile-id
        (db/after-commit!
         #(notify-event msgbus {:profile-id profile-id
                                :job-id     job-id
                                :event-id   id
                                :kind       kind
                                :payload    payload
                                :created-at created-at})))
      {:event-id id :created-at created-at :kind kind :payload payload})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HEARTBEAT / PROGRESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private heartbeat-interval (ct/duration {:seconds 60}))
(def ^:private progress-interval (ct/duration {:seconds 1}))

(def ^:private prune-threshold 10000)
(def ^:private prune-window (ct/duration {:hours 1}))

;; Throttle state atoms (public for testing)
(def heartbeats (atom {}))
(def progresses (atom {}))

(defn- assert-connectable
  "Heartbeat writes always go through the connection pool, so the cfg must
  carry ::db/pool (or be a pool/connection itself). Fails fast with a clear
  error instead of the opaque deep failure inside app.db."
  [cfg]
  (let [connectable (if (map? cfg) (::db/pool cfg) cfg)]
    (when-not (db/connectable? connectable)
      (ex/raise :type :validation
                :code :missing-pool
                :hint "heartbeat requires ::db/pool on the cfg (or a pool/connection directly)"))))

(def ^:dynamic *job-id*
  "Job id of the job being executed on the current thread. The runner
  binds it around handler invocations; handlers call `heartbeat` without
  knowing the id. Nil means no job context (in-process `invoke` without a
  row), in which case the throttled writes become no-ops."
  nil)

(defn- should-write?
  "Throttle gate: true when the last recorded write for `job-id` is older
  than `interval` (or when there is none). Maintains a bounded in-memory
  registry of the last write time per job. Uses a volatile inside swap! to
  communicate the decision — the volatile is dereferenced after the swap
  completes, which is safe."
  [state-ref job-id now interval]
  (let [decision (volatile! false)]
    (swap! state-ref
           (fn [m]
             (let [m (if (> (count m) prune-threshold)
                       ;; Remove stale entries (older than prune-window)
                       (into {}
                             (remove (fn [[_ last-inst]]
                                       (> (- (inst-ms now) (inst-ms last-inst))
                                          (inst-ms prune-window))))
                             m)
                       m)
                   last-inst (get m job-id)]
               (if (and last-inst
                        (< (- (inst-ms now) (inst-ms last-inst))
                           (inst-ms interval)))
                 m
                 (do
                   (vreset! decision true)
                   (assoc m job-id now))))))
    @decision))

(defn cleanup-throttle
  "Remove `job-id` from the heartbeat and progress throttle atoms.
  Called by the runner after a job completes (success or failure) to
  prevent completed job IDs from accumulating in memory. The prune
  fallback in `should-write?` remains as defense-in-depth."
  [job-id]
  (swap! heartbeats dissoc job-id)
  (swap! progresses dissoc job-id))

(def ^:private sql:touch-heartbeat
  "UPDATE job
      SET modified_at = ?
    WHERE id = ?
      AND status IN ('new', 'scheduled', 'running', 'retry')")

(def ^:private sql:lock-active-job
  "SELECT id FROM job
    WHERE id = ?
      AND status IN ('new', 'scheduled', 'running', 'retry')
    FOR UPDATE")

(defn- touch-job
  [connectable job-id now]
  (-> (db/exec-one! connectable [sql:touch-heartbeat now job-id])
      (db/get-update-count)))

(defn- report-progress
  "Insert a progress event on an active job.

  The row is locked first: a concurrent complete, fail or cancel blocks
  here, so a progress event can never land on an already terminal job.
  Returns 1 when the event was stored."
  [cfg job-id progress]
  (db/tx-run! (or (::db/pool cfg) cfg)
              (fn [{:keys [::db/conn]}]
                (if (db/exec-one! conn [sql:lock-active-job job-id])
                  (do
                    (insert-event (assoc cfg ::db/conn conn) job-id "progress" progress)
                    1)
                  0))))

(defn heartbeat
  "Keep a running job alive and, optionally, report its progress.

  Named options:

  - `:job-id`   explicit job id; defaults to `::jobs/job-id` on the cfg
                and then to the runner-bound `*job-id*`.
  - `:progress` optional progress report (see `schema:progress`).
  - `:force?`   internal: skips only the progress throttle.

  Returns the number of durable writes performed, so 0 means the job is
  terminal, has no job context, or the throttle did not allow a write.

  The `modified_at` touch is throttled to ~60s and the progress event to
  ~1s: external workers report progress as sparse milestones, while
  handlers may beat on every iteration. Both writes go through the
  connection pool (`::db/pool` on the cfg), never the caller transaction,
  so a beat survives a rollback of the surrounding work.

  A progress report never touches a terminal row (beating one would
  silently extend its retention window). The payload itself is validated
  before any write, on every route: a malformed report is a caller bug,
  not a transient failure, so it always raises. Only the insert itself is
  forgiving on the handler path, where it is logged and ignored; the
  forced path used by the management API propagates it so the external
  worker can retry."
  [cfg & {:keys [job-id progress] :as options}]
  (let [job-id  (or job-id (get cfg ::job-id) *job-id*)
        ;; a malformed report is a caller bug: never swallow it
        progress (some-> progress validate-progress)]
    (when (uuid? job-id)
      (assert-connectable cfg)
      (let [connectable (or (::db/pool cfg) cfg)
            now         (ct/now)
            force?      (boolean (::force? options))
            writes      (volatile! 0)]
        (when (should-write? heartbeats job-id now heartbeat-interval)
          (vswap! writes + (touch-job connectable job-id now)))
        (when (and (some? progress)
                   (or force?
                       (should-write? progresses job-id now progress-interval)))
          (let [store #(report-progress cfg job-id progress)]
            (if force?
              (vswap! writes + (store))
              (try
                (vswap! writes + (store))
                (catch Throwable cause
                  (l/err :hint "unable to persist job progress"
                         :job-id (str job-id)
                         :cause cause))))))
        (int @writes)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MANAGEMENT API SUPPORT (external workers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:claim-external-job
  "UPDATE job
      SET status='running', started_at=now(), modified_at=now()
    WHERE id=?
      AND scheduled_at=?
      AND status IN ('new','scheduled','retry')")

(def sql:complete-job
  "UPDATE job
      SET status='completed', completed_at=?, modified_at=?, result=?, error=NULL
    WHERE id=?
      AND status IN ('running','retry')")

(def sql:fail-job
  "UPDATE job
      SET status='failed', modified_at=?, error=?
    WHERE id=?
      AND status IN ('running','retry')")

(defn claim
  "Claim a job on behalf of an external worker: only transitions a
  pending row (new/scheduled/retry) to `running` and requires an exact
  `scheduled-at` match with the value advertised in the queue payload, so
  a stale payload (row rescheduled or claimed in the meantime) affects 0
  rows and must be skipped. Returns the number of affected rows."
  [cfg job-id scheduled-at]
  (-> (db/exec-one! (db/get-connectable cfg)
                    [sql:claim-external-job job-id scheduled-at])
      (db/get-update-count)))

(defn encode-result
  "Serialize a job result to JSON, dropping unserializable values to nil
  with a warning instead of throwing (a throw here would leave the row
  stuck in `running` until the orphan lease fires). Shared by the runner
  and the management API."
  [job-name result]
  (try
    (db/json result)
    (catch Throwable cause
      (l/err :hint "unable to serialize job result to JSON"
             :job-name (some-> job-name str)
             :cause cause)
      nil)))

(defn record-terminal
  [metrics job outcome]
  (when job
    (jobs-metrics/record-outcome metrics (:name job) (:queue job) outcome)
    (when (ct/inst? (:created-at job))
      (jobs-metrics/record-total
       metrics
       (:name job)
       (:queue job)
       outcome
       (- (inst-ms (ct/now)) (inst-ms (:created-at job)))))))

(defn complete
  "Mark a running job as completed with the (JSON-encodable) result.
  Conditional on the non-terminal running/retry states (first-terminal
  wins: a row already marked failed/cancelled — e.g. an orphan detected
  by the dispatcher — is never overwritten). Returns the number of
  affected rows."
  ([cfg job-id]
   (complete cfg job-id nil))
  ([cfg job-id result]
   (let [metrics  (require-metrics cfg)
         job      (get-job cfg job-id)
         job-name (:name job)
         n        (-> (db/exec-one! (db/get-connectable cfg)
                                    [sql:complete-job (ct/now) (ct/now)
                                     (when (some? result) (encode-result job-name result)) job-id])
                      (db/get-update-count))]
     (when (pos? n)
       (db/after-commit! #(record-terminal metrics job :completed)))
     (cleanup-throttle job-id)
     n)))

(defn fail
  "Mark a running job as failed with the error payload (a JSON object
  with at least a :code). Conditional on the non-terminal running/retry
  states (first-terminal wins). Returns the number of affected rows."
  [cfg job-id error]
  (let [metrics (require-metrics cfg)
        job     (get-job cfg job-id)
        n       (-> (db/exec-one! (db/get-connectable cfg)
                                  [sql:fail-job (ct/now)
                                   (if (string? error) error (db/json error)) job-id])
                    (db/get-update-count))]
    (when (pos? n)
      (db/after-commit! #(record-terminal metrics job :failed)))
    (cleanup-throttle job-id)
    n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REQUEST (ephemeral request/response, no row, no dispatcher)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NOTE (2026-09-17): this path intentionally has no producer yet; it
;; is kept for an upcoming phase. Do not re-raise as dead code until
;; that phase wires a consumer.

(def ^:private request-command-timeout-margin (ct/duration {:seconds 30}))
(def ^:private reply-expire-seconds 60)

(def reply-key-prefix "penpot.worker.reply")

(defn get-request-context
  [cfg]
  (or (::request-pool cfg)
      (ex/raise :type :assertion
                :code :missing-request-pool
                :hint "missing ::jobs/request-pool on provided cfg")))

(defmethod ig/expand-key ::request-pool
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::command-timeout
                (ct/plus (cf/get-jobs-request-timeout)
                         request-command-timeout-margin)))})

(def ^:private schema:request-pool
  [:map
   [::command-timeout ::ct/duration]
   ::rds/client
   ::mtx/metrics])

(defmethod ig/assert-key ::request-pool
  [_ cfg]
  (sm/check schema:request-pool cfg))

(defmethod ig/init-key ::request-pool
  [_ {::rds/keys [client] ::mtx/keys [metrics] ::keys [command-timeout]}]
  ;; pool without a max size: gpool/get creates a connection when no
  ;; idle one is available and never blocks; the in-flight concurrency
  ;; is bounded upstream by the RPC concurrency limits. Connections are
  ;; created with a command timeout above the per-call request timeout;
  ;; the dispose-fn restores it on return to the pool.
  {::pool     (rds/pool {::rds/client client
                         ::mtx/metrics metrics}
                        {:timeout command-timeout})
   ::mtx/metrics metrics})

(def ^:private schema:request-options
  [:map {:title "request-options"}
   [::queue [:or ::sm/text :keyword]]
   [::cmd [:or ::sm/text :keyword]]
   [::params any?]
   [::timeout {:optional true} [:or ::sm/int ::ct/duration]]])

(def check-request-options
  (sm/check-fn schema:request-options))

(defn reply
  "Respond to an ephemeral request: push the JSON response to the
  reply-key and set a short TTL as a safety net for late replies (a
  reply pushed after the caller timeout would otherwise live forever).
  Response shape: `{:ok ...}` or `{:error {...}}`. Accepts a connectable
  cfg (a redis pool under ::rds/pool)."
  [cfg reply-key response]
  (rds/run! cfg
            (fn [{:keys [::rds/conn]}]
              (rds/rpush conn reply-key [(json/encode response)])
              (rds/expire conn reply-key reply-expire-seconds))))

(defn request
  "Ephemeral request/response (no job row, no dispatcher): pushes a JSON
  payload [request-id, reply-key, cmd, params] to the target queue and
  blocks on the reply-key with a per-call timeout (defaults to
  :jobs-request-timeout). Any per-call override is applied by raising
  the pooled connection command timeout for the duration of the call,
  which the pool dispose-fn restores on return.

  On success returns the decoded `:ok` payload; an `:error` reply
  propagates as an exception; on timeout raises `:request-timeout` and
  the reply-key is deleted (in finally, also on error). The connection
  is always returned to the pool."
  [cfg
   {:keys [::queue ::cmd ::params ::timeout] :as options}]

  (check-request-options options)

  (let [context    (get-request-context cfg)
        pool       (::pool context)
        metrics    (::mtx/metrics context)
        tenant     (cf/get :tenant)
        timeout    (or timeout (cf/get-jobs-request-timeout))
        request-id (uuid/next)
        reply-key  (str/ffmt "%:%:%" reply-key-prefix tenant request-id)
        queue-key  (str/ffmt "penpot.worker.queue:%:%" tenant (d/name queue))
        payload    (json/encode [(str request-id)
                                 reply-key
                                 (d/name cmd)
                                 params])]

    (with-open [^AutoCloseable pooled (gpool/get pool)]
      (let [conn    @pooled
            tpoint  (ct/tpoint)
            outcome (volatile! :error)]
        (try
          ;; raise the connection command timeout above the per-call
          ;; blpop timeout; the pool dispose-fn restores the default on
          ;; return.
          (rds/set-timeout conn (ct/plus timeout request-command-timeout-margin))

          (rds/rpush conn queue-key [payload])

          (let [[_ reply] (rds/blpop conn [reply-key] timeout)]
            (if (nil? reply)
              (do
                (vreset! outcome :timeout)
                (ex/raise :type :timeout
                          :code :request-timeout
                          :hint "timeout waiting for the job reply"
                          :queue queue
                          :timeout timeout))
              (let [response (json/decode reply :key-fn keyword)]
                (if-let [error (:error response)]
                  (do
                    (vreset! outcome :error)
                    (ex/raise :type :internal
                              :code (get error :code)
                              :hint (or (get error :hint) "request failed")
                              :response response))
                  (do
                    (vreset! outcome :replied)
                    (get response :ok))))))

          (finally
            (jobs-metrics/record-request metrics
                                         @outcome
                                         (inst-ms (tpoint)))
            (rds/del conn reply-key)
            (rds/reset-timeout conn)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IN-PROCESS INVOCATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke
  "Execute a job handler in-process (no row, no dispatch): decodes the
  params with the job-def decoder and invokes the handler bound to the
  `*job-id*` dynamic (or the provided ::job-id, which makes the
  throttled heartbeat/progress writes work against the row). Options:

  {::name    :delete-object
   ::params  {...}     ;; raw (JSON-shaped) params
   ::defs    {...}     ;; the ::jobs/defs registry
   ::context {...}}    ;; optional, validated and delivered as is
   ::job-id  <uuid>}   ;; optional, only when the row already exists

  Without ::context the handler receives a nil context: there is no row
  to describe. ::context and ::job-id are independent, and providing a
  context does not turn this into a durable execution: only the job-id
  makes heartbeat and progress reach a row.

  Returns the handler result."
  [cfg]
  (let [job-def (get-job-def (get-defs cfg) (get cfg ::name))
        context (some-> (get cfg ::context) check-context)
        decoded (decode-params job-def (get cfg ::params))]
    (binding [*job-id* (get cfg ::job-id)]
      ((::handler job-def) context decoded))))
