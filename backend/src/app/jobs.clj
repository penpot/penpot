;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs
  "Public API for the unified jobs substrate.

  Every job is defined as a job-def map returned by the `ig/init-key` of its
  module: {::name, ::schema, ::handler, ::decoder, ::validator} — decoder and
  validator are precompiled at init time. A def whose result needs its types
  back declares the optional ::result-schema: the substrate compiles the
  decoder from that schema and checks a result against it before storing it,
  while a def without one answers with the JSON the row holds. A job-def may
  also carry the optional metadata {::family, ::resource-role}, which the
  consumers of the job (quotes, storage helpers) read from the registry: the
  substrate never reads them. The registry of job-defs is plain integrant
  wiring (::jobs/defs); submit, dispatch and management consume it by
  reference.

  Two execution modes are provided:
  - `submit` (durable): validates + JSON-encodes params and inserts a row
    into the `job` table; the dispatcher/runner machinery does the rest.
  - `request` (ephemeral): synchronous request/response over the redis
    queues with a reply-key and a dedicated, unbounded connection pool;
    external workers answer with `reply`.

  Params payloads are stored as plain JSON (not transit) in the `params` jsonb
  column and decoded back to typed Clojure values using the job-def decoder.
  Results are plain JSON in the `result` jsonb column: they are decoded with the
  decoder the substrate compiled from the schema the def declares, and a result
  that does not conform to that schema is refused before it is stored. Without a
  schema a uuid comes back as the text it is on disk and a uuid used as a map
  key as a keyword, so a def that keys a result map by uuid must declare it.

  Any job that can run longer than `:jobs-lease` must call `heartbeat`
  on its loop, otherwise the dispatcher marks it `aborted` (a system-side
  terminal state, like `cancelled` but set by the dispatcher instead of
  a user: never retried, kept for triage and reported with an error
  log) while its side effects continue. A beat that finds the job no
  longer active raises an `:interrupt`, an internal signal that the task
  should stop the work in course, never an error. `heartbeat` also accepts
  an optional `progress` report, which is stored as a `progress` row of
  `job_event`: progress is durable history, not a mutable column, and
  needs no Redis.

  Events of a job with a `profile_id` publish a `:job-event` message on the
  topic of that profile after the transaction commits, so a client can
  follow the job without polling the database.

  Reserved ledger columns (`profile_id`, `error`, `result`, `resource_id`,
  `expires_at`) are only written by `submit` (profile, resource and expiry
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
   [app.jobs.metrics :as metrics]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.redis :as rds]
   [app.worker :as wrk]
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

  `queue` is deliberately absent: it is a routing detail of the
  dispatcher, not a property of the job. `name` already says which job
  this is, and a handler has no use for the queue it happened to be
  scheduled on.

  The retry keys are absent too. Retry policy belongs to the runner: it
  is the one comparing `retry-num` with `max-retries` and deciding
  whether to schedule again. A handler has no use for the counter, and
  the attempt number would lie anyway: the `noop` retry strategy runs
  again without incrementing it, so a durable \"which execution is this\"
  number would need a second counter that nothing bounds.

  `profile-id` is the owner of a user-facing job and nil for an internal
  one. It travels here so a handler can revalidate permissions and check
  who owns the resource it is working on without a second query."
  [:map {:closed true
         :title "job-context"}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:label [:maybe ::sm/text]]
   ;; technical reference kept for garbage collection
   [:resource-id [:maybe ::sm/uuid]]
   ;; owner of a user-facing job; nil for an internal job
   [:profile-id [:maybe ::sm/uuid]]])

(def check-context
  "Validate a handler context; raises with the malli explanation when it
  does not match the closed schema."
  (sm/check-fn schema:context))

(defn make-context
  "Build the handler context from a job row: exactly the five keys a
  handler may see, and nothing else. The result is a plain map, not the
  database row, and it is not modified afterwards.

  The empty label is the `submit` default for \"no label\", so it becomes
  nil here: a handler sees either a label or nothing."
  [job]
  (check-context {:id          (:id job)
                  :name        (:name job)
                  :label       (if (str/blank? (:label job)) nil (:label job))
                  :resource-id (:resource-id job)
                  :profile-id  (:profile-id job)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB DEFINITIONS (registry)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:job-name
  "Canonical job name: a keyword, and only a keyword. Callers pass
  `:sendmail`, never \"sendmail\": `submit` and `invoke` reject anything
  else. The database still stores a string (`d/name` at the submit
  boundary), so `get-job-def` coerces back at that boundary."
  :keyword)

;; Durable (`submit-and-wait`) and ephemeral (`request`) replies share the
;; key namespace; a job id and a request id are both uuids, so they cannot
;; collide. The TTL is a safety net for a late reply nobody reads.
(def reply-key-prefix "penpot.worker.reply")
(def ^:private reply-expire-seconds 60)

(def ^:private schema:job-def
  [:map {:title "job-def"}
   [::name schema:job-name]
   ;; Optional metadata of the job family, read from the registry by the
   ;; consumers that need it (quotes, storage helpers, a future job
   ;; listing). `app.jobs` itself never reads them: they are not part of
   ;; the execution contract.
   [::family {:optional true} ::sm/keyword]
   [::resource-role {:optional true} ::sm/keyword]
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
   [::validator ::sm/fn]
   ;; Optional domain effect of a terminal transition, called by the
   ;; writers (`complete`, `fail`, `cancel`) inside the transaction that
   ;; changes the status. It receives the transaction cfg (with
   ;; `::db/conn`), the decoded row, the params decoded with the def's own
   ;; decoder, and an outcome map: `{:outcome :completed :result ...}`,
   ;; `{:outcome :failed :error ...}` or `{:outcome :cancelled}`. A throw
   ;; rolls the status change back, so nothing is applied halfway.
   [::on-terminal {:optional true} ::sm/fn]
   ;; Publish the terminal outcome on `penpot.worker.reply:<tenant>:<job-id>`
   ;; so a synchronous caller (`submit-and-wait`) wakes up. Only the job-def
   ;; decides: a def without the flag writes no reply key, and a retry or a
   ;; skip never notifies.
   [::notifies? {:optional true} ::sm/boolean]
   ;; A result crosses a plain JSON column on its way out of the job, so a
   ;; uuid comes back as the text it is on disk and a uuid used as a map
   ;; key comes back as a keyword. A def that declares the schema of its
   ;; result gets its types back when the row is read (the substrate
   ;; compiles the decoder from the schema), and its result is checked
   ;; against that schema before it is stored.
   [::result-schema {:optional true} any?]])

(def ^:private schema:job-defs
  [:map-of :keyword schema:job-def])

;; The worker components name this registry as a bare `::defs` entry in
;; their integrant params schemas, and a bare entry is a registry lookup:
;; without this the backend crashes at boot with
;; `:malli.core/invalid-schema` instead of asserting the config.
(sm/register! ::defs schema:job-defs)

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

(defn- compile-result-schema
  "Compile the decoder of a job-def from the result schema it declares, so the
  pair cannot drift from the schema and a def without one keeps working
  untouched. The write path checks the result against the schema itself."
  [job-def]
  (if-let [schema (::result-schema job-def)]
    (assoc job-def ::result-decoder (sm/decoder schema sm/json-transformer))
    job-def))

(defmethod ig/init-key ::defs
  [_ defs]
  (let [defs (reduce-kv (fn [acc name job-def]
                          (assoc acc name (compile-result-schema job-def)))
                        {}
                        defs)]
    (reset! defs-registry defs)
    (l/inf :hint "job definitions initialized" :jobs (count defs))
    defs))

(defmethod ig/halt-key! ::defs
  [_ defs]
  (reset! defs-registry {})
  (l/inf :hint "job definitions halted" :jobs (count defs)))

(defn get-job-def
  "Resolve the job-def for the provided job name; raises if missing.

  Accepts a string as well as a keyword, but only because the runner
  resolves from the database row (whose `name` column is text): every
  other caller passes the canonical keyword."
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

(defn decode-result
  "Decode the raw JSON result (pgobject or decoded map) with the decoder the
  substrate compiled from the result schema of the job-def. A def without one
  answers with the JSON the row holds: every key keywordized and every uuid as
  the text it is on disk."
  [job-def result]
  (when (some? result)
    (let [value (cond-> result
                  (db/pgobject? result)
                  db/decode-json-pgobject)]
      (if-let [decoder (::result-decoder job-def)]
        (decoder value)
        value))))

(defn- check-result
  "Validate the result of a job against the schema its job-def declares, with
  the malli explanation on failure. A job-def without a result schema has
  nothing to check, and so has a job whose definition is gone."
  [job-def result]
  (when-let [schema (::result-schema job-def)]
    (sm/check schema result
              :hint "invalid result of a job"
              :type :validation
              :code :invalid-result))
  result)

(defn- with-redis-conn
  "Run `f` with a redis connection: the one the runner already holds, then
  the shared pool, and only when the cfg carries a bare client (in-process
  callers, tests) a connection opened and closed for the call."
  [cfg f]
  (cond
    (::rds/conn cfg)
    (f (::rds/conn cfg))

    (::rds/pool cfg)
    (rds/run! cfg (fn [{:keys [::rds/conn]}] (f conn)))

    :else
    (with-open [conn ^AutoCloseable (rds/connect cfg)]
      (f conn))))

(defn- push-queue!
  "Push the payload of a job to its queue. Must run after the commit that
  made the row durable, so a lost push is a `scheduled` row for
  `reschedule-lost-jobs`, never a job claimed before it exists."
  [cfg job]
  (let [key   (wrk/queue-key (:tenant job) (:queue job))
        value (json/encode [(str (:id job))
                            (ct/format-inst (:scheduled-at job))])]
    (with-redis-conn cfg
      (fn [conn] (rds/rpush conn key [value])))))

(defn- push-reply!
  "Publish a terminal response on a reply key and set its TTL. The TTL is a
  safety net for a reply nobody read (a caller that already timed out)."
  [cfg key response]
  (let [value (json/encode response)]
    (with-redis-conn cfg
      (fn [conn]
        (rds/rpush conn key [value])
        (rds/expire conn key reply-expire-seconds)))))

(defn- run-on-terminal
  "Run the `::on-terminal` hook of the job's def, if it has one.

  Called by the terminal writers inside the transaction that changed the
  status, so a hook that throws rolls the change back (no event, no
  metric, no half-applied effect). A job-def without a hook, or a row
  whose definition is gone, is a no-op."
  [tx-cfg job outcome]
  (when-let [job-def (get (get-defs tx-cfg) (keyword (:name job)))]
    (when-let [hook (::on-terminal job-def)]
      (hook tx-cfg job (decode-params job-def (:params job)) outcome))))

(defn- notify-terminal!
  "Publish the terminal outcome of a notifying job on its reply key, after
  the commit that made it terminal.

  Only a job-def with `::notifies?` has a waiter; a job without it writes
  no key. A failed push is a `reply-failed` warning and never changes the
  job state: the waiter times out and the row keeps its real outcome."
  [tx-cfg job response]
  (when-let [job-def (get (get-defs tx-cfg) (keyword (:name job)))]
    (when (::notifies? job-def)
      (let [key (str/ffmt "%:%:%" reply-key-prefix (:tenant job) (:id job))]
        (db/after-commit
         #(try
            (push-reply! tx-cfg key response)
            (catch Throwable cause
              (l/wrn :hint "reply-failed"
                     :job-id (str (:id job))
                     :tenant (:tenant job)
                     :cause cause))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:insert-new-job
  "insert into job (id, name, tenant, status, params, queue, label, priority,
                    max_retries, profile_id, resource_id, expires_at,
                    created_at, modified_at, scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
   returning id")

;; `tenant` is part of the match, not an extra: without it this DELETE
;; reaches the not-started jobs of every other instance sharing the
;; database.
(def ^:private sql:remove-not-started-jobs
  "DELETE FROM job
    WHERE name=?
      AND tenant=?
      AND queue=?
      AND label=?
      AND status = 'new'
      AND scheduled_at > ?")

;; The cron no-overlap check and the skip-if-active submit option share
;; this shape: both look for jobs of one tenant with the same name, queue
;; and label that have not reached a terminal state yet. The
;; `job__name_label__idx` partial index serves both queries.
(def ^:private sql:count-active-jobs
  "SELECT count(*) AS n
     FROM job
    WHERE name = ?
      AND tenant = ?
      AND queue = ?
      AND label = ?
      AND status IN ('new', 'scheduled', 'running', 'retry')")

(def ^:private schema:options
  [:map {:title "submit-options"}
   [::name schema:job-name]
   [::label {:optional true} ::sm/text]
   [::delay {:optional true}
    [:or ::sm/int ::ct/duration]]
   [::queue {:optional true} [:or ::sm/text :keyword]]
   [::priority {:optional true} ::sm/int]
   [::max-retries {:optional true} ::sm/int]
   [::dedupe {:optional true} ::sm/boolean]
   ;; Skip the insert when a live job already covers the same
   ;; (tenant, name, queue, label). Requires an explicit label: the
   ;; default empty one would block every flagged submission at once.
   [::skip-if-active {:optional true} ::sm/boolean]
   ;; Owner of a user-facing job. Its events are published on the msgbus
   ;; topic of this profile; internal jobs omit it.
   [::profile-id {:optional true} ::sm/uuid]
   ;; Technical reference (storage object) kept for garbage collection. It
   ;; is a column, never part of `params`, and it is never inferred from it.
   [::resource-id {:optional true} ::sm/uuid]
   ;; Retention of the job: when it is set, the jobs GC deletes the row
   ;; (and reclaims its resource) once the instant has passed. User-facing
   ;; jobs set it at creation; internal jobs leave it nil.
   [::expires-at {:optional true} ::ct/inst]
   ;; The row is born `scheduled` and pushed to its queue after the commit,
   ;; so a synchronous caller does not wait for the dispatcher turn. A
   ;; failed push leaves the row for `reschedule-lost-jobs`.
   [::immediate? {:optional true} ::sm/boolean]])

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

  The optional ledger columns (`::profile-id`, `::resource-id` and
  `::expires-at`) are validated and stored as columns; nothing is inferred
  from `params`.

  With `::skip-if-active`, the insert is skipped (and `nil` returned)
  when a live job (`new`, `scheduled`, `running` or `retry`) already
  exists for the same tenant, name, queue and label. The check and the
  insert share one transaction. A skip deletes nothing and inserts
  nothing; it records a skipped metric instead of a submitted one, so
  dashboards can tell withheld work apart from new work. The option
  needs an explicit non-blank `::label`, otherwise it raises.

  NOTE: the dedupe DELETE and the INSERT run atomically: joined to
  the caller's transaction when the cfg provides `::db/conn`, wrapped
  in their own transaction otherwise. Concurrent cross-backend
  submissions can, in rare race conditions, produce duplicated 'new'
  rows (accepted risk, see prod-infra documentation). The same caveat
  covers the skip-if-active check: a race between backends can slip
  two inserts past the count."
  [cfg {:keys [::params ::name ::delay ::queue ::priority ::max-retries
               ::dedupe ::skip-if-active ::label ::immediate?
               ::profile-id ::resource-id ::expires-at]
        :or   {delay 0 queue :default priority 100 max-retries 3 label ""}
        :as   options}]

  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  ;; Before the schema check so the failure names the option, not the
  ;; shape: the schema would reject a blank label with a generic
  ;; "check error" either way.
  (when (and skip-if-active (or (not (string? label)) (str/blank? label)))
    (ex/raise :type :validation
              :code :skip-if-active-requires-label
              :hint "skip-if-active requires an explicit non-blank label"
              :name (d/name name)))
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
                                                  (inst-ms %)
                                                  %))))
        id           (uuid/next)
        tenant       (cf/get :tenant)
        job-name     (d/name name)
        queue        (d/name queue)
        status       (if immediate? "scheduled" "new")
        ;; Dedupe is best-effort: we delete not-started jobs of this
        ;; tenant with the same name/queue/label, then insert. A race
        ;; between backends could create duplicates, but this is
        ;; acceptable: cross-backend races are rare, jobs are idempotent,
        ;; and dedupe is best-effort.
        ;; Skip-if-active is best-effort like dedupe: the count and the
        ;; insert share this transaction, but a race between backends
        ;; can still slip two inserts past the count.
        active?      (fn [conn]
                       (when skip-if-active
                         (pos? (or (:n (db/exec-one! conn [sql:count-active-jobs
                                                           job-name tenant queue label]))
                                   0))))
        insert!      (fn [conn]
                       (let [deleted (when dedupe
                                       ;; the DELETE below is intentional:
                                       ;; disable the `job` deletion-protection
                                       ;; guard inside this transaction
                                       (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                                       (-> (db/exec-one! conn [sql:remove-not-started-jobs
                                                               job-name tenant queue label now])
                                           (db/get-update-count)))]
                         (l/trc :hint "submit"
                                :name job-name
                                :job-id (str id)
                                :tenant tenant
                                :queue queue
                                :label label
                                :dedupe (boolean dedupe)
                                :delay (ct/format-duration delay)
                                :replace (or deleted 0))
                         (db/exec-one! conn [sql:insert-new-job id job-name tenant status payload queue
                                             label priority max-retries
                                             profile-id resource-id expires-at
                                             now now scheduled-at])))]
    ;; Both statements always run inside db/tx-run!: joined to the
    ;; caller's transaction when the cfg provides a connection,
    ;; wrapped in their own otherwise (a failed INSERT can never
    ;; orphan a committed DELETE, even on an autocommit caller conn).
    ;; A skip returns nil: no DELETE, no INSERT, no submitted metric.
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (if (active? conn)
                    (do (db/after-commit
                         #(metrics/record-skipped cfg job-name queue))
                        (l/trc :hint "submit skipped, active job exists"
                               :name job-name
                               :tenant tenant
                               :queue queue
                               :label label
                               :skipped true))
                    (let [result (insert! conn)]
                      (db/after-commit
                       #(metrics/record-submitted cfg job-name queue))
                      (when immediate?
                        (db/after-commit
                         #(try
                            (push-queue! cfg {:id id
                                              :tenant tenant
                                              :queue queue
                                              :scheduled-at scheduled-at})
                            (catch Throwable cause
                              (l/wrn :hint "dispatch-failed"
                                     :job-id (str id)
                                     :tenant tenant
                                     :queue queue
                                     :scheduled-at (ct/format-inst scheduled-at)
                                     :cause cause)))))
                      result
                      id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- decode-json-col
  [row key]
  (cond-> row
    (db/pgobject? (get row key))
    (assoc key (db/decode-json-pgobject (get row key)))))

(defn- decode-row
  [row]
  (-> row
      (decode-json-col :params)
      (decode-json-col :result)
      (decode-json-col :error)))

(defn- decode-result-of
  "Decode the result of a row with the decoder of the job-def that owns it.
  A kind the registry no longer knows (a retired job-def) answers with the
  row untouched instead of raising."
  [cfg row]
  (if-let [job-def (get (get-defs cfg) (keyword (:name row)))]
    (update row :result #(decode-result job-def %))
    row))

(defn get-job
  "Retrieve the job row, with params and result decoded to plain maps (the
  result only when its job-def declares a result decoder)."
  [cfg job-id]
  (when-let [row (db/get* cfg :job {:id job-id})]
    (decode-result-of cfg (decode-row row))))

(defn get-user-status
  "Map the internal job status to the user-facing status."
  [status]
  (let [status (d/name status)]
    (case status
      ("new" "scheduled" "retry") "pending"
      "running"                   "running"
      "completed"                 "completed"
      ("failed" "cancelled" "aborted") "failed")))

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
  ;; mirrors the `job_event` end outcomes. `aborted` is only ever written
  ;; by the dispatcher orphan sweep (bulk insert in the same transaction,
  ;; see `app.worker.dispatcher/mark-orphan-jobs`): no `app.jobs` writer
  ;; emits it.
  #{"completed" "failed" "cancelled" "aborted"})

(def ^:private known-retry-reasons
  #{"backoff" "noop"})

(def ^:private max-progress-key-length
  "Longest accepted `:stage` or counter key. They name a vocabulary, they
  are not a place for data, so the cap only keeps a runaway key out of the
  log."
  64)

(def schema:progress-counter
  "Counter of one scope of a job: `current` is how many units are done and
  `total` how many there are, when the worker knows it."
  [:map {:closed true
         :title "job-progress-counter"}
   [:current ::sm/int]
   [:total {:optional true} ::sm/int]])

(def schema:progress
  "Progress milestone of a job: the activity in course and the counters of
  the scopes around it, each one under its own key.

  `:stage` is a stable keyword the client resolves (a translation, an
  icon...), never a display string, and `:counters` is open on purpose: a
  worker reports the scopes it knows and names the counter of the activity
  after its own stage, so a client can render `counters[stage]` without a
  table of equivalences. Neither is a place for names, params or exception
  text: the payload is keywords and integers only."
  [:map {:closed true
         :title "job-progress"}
   [:stage    ::sm/keyword]
   [:counters {:optional true} [:map-of ::sm/keyword schema:progress-counter]]])

(def check-progress
  "Validate the shape of a progress milestone."
  (sm/check-fn schema:progress))

(defn- check-counter
  "Check the range rules of one counter: a non-negative `current` and a
  positive `total` never lower than its `current`."
  [key counter]
  (when (neg? (:current counter))
    (ex/raise :type :validation
              :code :invalid-progress
              :hint "progress counter current must not be negative"
              :counter key
              :progress counter))
  (when-let [total (:total counter)]
    (when (or (not (pos? total))
              (> (:current counter) total))
      (ex/raise :type :validation
                :code :invalid-progress
                :hint "progress counter total must be positive and not lower than current"
                :counter key
                :progress counter))))

(defn validate-progress
  "Check what the schema cannot express: non-negative counters, a positive
  total never lower than its `current`, and stage and counter keys short
  enough to be a name and not a payload."
  [progress]
  (let [progress (check-progress progress)]
    (doseq [key (cons (:stage progress) (keys (:counters progress)))]
      (when (> (count (name key)) max-progress-key-length)
        (ex/raise :type :validation
                  :code :invalid-progress
                  :hint (str "progress key must not be longer than "
                             max-progress-key-length " characters")
                  :key key
                  :progress progress)))
    (doseq [[key counter] (:counters progress)]
      (check-counter key counter))
    progress))

(defn decode-progress
  "Read back a stored progress payload as a Clojure map: `:stage` and the
  counter keys come back as the strings the database holds, so they are
  keywordized here, and a payload that is already a map is returned as is."
  [progress]
  (when (some? progress)
    (-> (cond-> progress
          (db/pgobject? progress)
          (db/decode-json-pgobject))
        (d/update-when :stage keyword)
        (d/update-when :counters
                       (fn [counters]
                         (into {} (map (fn [[k v]] [(keyword k) v])) counters))))))

(def ^:private event-payload-keys
  {"start"    #{:attempt}
   "progress" #{:stage :counters}
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
      "start" (do (when-not (nat-int? (:attempt payload)) (reject-attempt))
                  payload)
      "retry" (do (when-not (nat-int? (:attempt payload)) (reject-attempt))
                  (when-not (contains? known-retry-reasons (:reason payload))
                    (reject "unknown job event retry reason" (:reason payload)))
                  payload)
      "end"   (do (when-not (contains? known-outcomes (:outcome payload))
                    (reject "unknown job event outcome" (:outcome payload)))
                  payload)
      "progress" (validate-progress payload))))

(def ^:private sql:insert-job-event
  "INSERT INTO job_event (job_id, kind, payload)
   VALUES (?, ?, ?)
   RETURNING id, created_at")

(def ^:private sql:job-event-owner
  "SELECT profile_id FROM job WHERE id = ?")

(defn- notify-event
  "Publish a `:job-event` message on the topic of the job profile. Runs
  after the commit that inserted the event: a listener that is not
  connected never rolls back durable history. Takes the cfg and resolves
  the msgbus from it, like `record-terminal` does with the metrics."
  [{:keys [::mbus/msgbus]}
   {:keys [profile-id job-id event-id kind payload created-at]}]
  (mbus/pub! msgbus
             :topic profile-id
             :message {:type       :job-event
                       :profile-id profile-id
                       :job-id     job-id
                       :event-id   event-id
                       :kind       kind
                       :payload    payload
                       :created-at created-at}))

(defn- insert-event
  "Insert a `job_event` row, then count it and notify after the commit.

  Must be called inside a transaction that owns the job row. When the job
  has a `profile_id` the cfg must carry a msgbus: the check happens
  before the insert, so a profile job never loses its notification
  silently. Jobs without profile store the event and publish nothing.

  The counter also runs after the commit, so a rolled back transaction
  never inflates it."
  [{:keys [::mbus/msgbus] :as cfg} job-id kind payload]
  (assert (db/connection-map? cfg)
          "expected cfg with valid connection: insert-event must run inside a transaction")
  (assert (some? db/*after-commit-context*)
          "expected transaction context: insert-event must run inside a transaction")
  (let [payload     (validate-event-payload kind payload)
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
      (db/after-commit #(metrics/record-event cfg))
      (when profile-id
        (db/after-commit
         #(notify-event cfg {:profile-id profile-id
                             :job-id     job-id
                             :event-id   id
                             :kind       kind
                             :payload    payload
                             :created-at created-at})))
      {:event-id id :created-at created-at :kind kind :payload payload})))

(defn- own-transaction
  "A cfg for a job write that must own its transaction.

  A lifecycle write reports on work that already happened elsewhere, so
  it never joins the transaction of whoever called it: the `::db/conn` is
  dropped and `db/tx-run!` opens its own transaction on its own
  connection. Because that transaction joins no other, it owns its
  after-commit context too, so the write and its metric commit or vanish
  together and a rollback of the surrounding work can undo neither.

  `submit` is the deliberate exception: creating a job is part of the
  business unit of work of whoever asks for it."
  [cfg]
  (dissoc cfg ::db/conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HEARTBEAT / PROGRESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private heartbeat-interval
  ;; one second, down from a minute: a heavy job that was cancelled must
  ;; be seen as gone at its next beat. The price is one `modified_at`
  ;; touch per beating job per second (60x the old rate): cheap while the
  ;; only heavy beater runs alone on its queue, revisit if concurrent
  ;; beating jobs ever grow.
  (ct/duration {:seconds 1}))
(def ^:private progress-interval (ct/duration {:millis 200}))

(def ^:private prune-threshold 10000)
(def ^:private prune-window (ct/duration {:hours 1}))

;; Throttle state atoms (public for testing)
(def heartbeats (atom {}))
(def progresses (atom {}))

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

(def ^:private active-statuses
  "The statuses of a job that is still on its way. It mirrors the filter
  of the heartbeat and progress writes below: a row outside this set is no
  longer active and nothing can report to it."
  #{"new" "scheduled" "running" "retry"})

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
  Returns 1 when the event was stored.

  Like every lifecycle write it owns its transaction (see `own-transaction`),
  so a beat survives the rollback of the work around it."
  [cfg job-id progress]
  (db/tx-run! (own-transaction cfg)
              (fn [{:keys [::db/conn] :as tx-cfg}]
                (if (db/exec-one! conn [sql:lock-active-job job-id])
                  (do
                    (insert-event tx-cfg job-id "progress" progress)
                    1)
                  0))))

(defn check-active
  "Raise an `:interrupt` when the job is no longer active.

  A job is active while its status is one of `active-statuses`; a row
  that already reached a terminal state, or that no longer exists, is
  not. The status is read from the row, because a write can only tell
  that it matched no row, not why.

  The interrupt is an internal signal, not an error: the caller is a task
  that must stop the work in course. `heartbeat` calls this when one of
  its writes finds the job gone, and a caller can also ask explicitly,
  which is what the binfile adapter does before and after its run.
  Answers with the cfg, so a caller can thread the check into its run."
  [cfg job-id]
  (let [job (get-job cfg job-id)]
    (when-not (and (some? job) (contains? active-statuses (:status job)))
      (ex/raise :type :interrupt
                :code :job-interrupted
                :hint "the job is no longer active"
                :job-id job-id
                :status (or (:status job) "gone")))
    cfg))

(defn heartbeat
  "Keep a running job alive and, optionally, report its progress.

  Named options:

  - `:job-id`   explicit job id; defaults to `::jobs/job-id` on the cfg
                and then to the runner-bound `*job-id*`.
  - `:progress` optional progress report (see `schema:progress`).
  - `::force?`  internal: skips only the progress throttle.

  Returns the number of durable writes performed, so 0 means there was
  nothing to write: no job context, or the throttle did not allow it. A
  write that finds the job no longer active raises an `:interrupt` (see
  `check-active`) instead of counting zero, so a task in course stops at
  its next beat.

  Both writes go through the connection pool (`::db/pool` on the cfg),
  never the caller transaction, so a beat survives a rollback of the
  surrounding work: the `modified_at` touch is throttled to ~1s while
  progress events go out at up to ~200ms, so the user sees movement
  without hammering the row. External workers report progress as sparse
  milestones, while handlers may beat on every iteration.

  Like every other function here the cfg is a cfg: a bare pool or
  connection is a caller bug and is not accommodated.

  A progress report never touches a terminal row (beating one would
  silently extend its retention window). The payload itself is validated
  before any write, on every route: a malformed report is a caller bug,
  not a transient failure, so it always raises. Only the insert itself is
  forgiving on the handler path, where it is logged and ignored; the
  forced path used by the management API propagates it so the external
  worker can retry."
  [cfg & {:keys [job-id progress] :as options}]
  (let [job-id   (or job-id (get cfg ::job-id) *job-id*)
        ;; a malformed report is a caller bug: never swallow it
        progress (some-> progress validate-progress)]
    (when (uuid? job-id)
      (let [connectable (::db/pool cfg)
            now         (ct/now)
            force?      (boolean (::force? options))
            writes      (volatile! 0)]
        (when (should-write? heartbeats job-id now heartbeat-interval)
          (let [n (touch-job connectable job-id now)]
            ;; the touch is conditional on the row still being active: a
            ;; write that matched no row is what raises the interrupt
            (when (zero? n)
              (check-active cfg job-id))
            (vswap! writes + n)))
        (when (and (some? progress)
                   (or force?
                       (should-write? progresses job-id now progress-interval)))
          (let [store #(report-progress cfg job-id progress)
                n     (if force?
                        (store)
                        (try
                          (store)
                          (catch Throwable cause
                            (l/err :hint "unable to persist job progress"
                                   :tenant (cf/get :tenant)
                                   :job-id (str job-id)
                                   :cause cause)
                            0)))]
            ;; the raise stays outside the catch above: an interrupt is
            ;; not a transient failure and must not be swallowed with it
            (when (zero? n)
              (check-active cfg job-id))
            (vswap! writes + n)))
        (int @writes)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB WRITERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The only path that changes the status of a job. Every writer changes
;; the row and stores its event in the same transaction, so the durable
;; history can never contradict the row: if the event cannot be stored,
;; the transition is rolled back. A writer that affects no row writes no
;; event and no metric, which is what makes concurrent transitions safe:
;; the first one wins and the losers see zero.
;;
;; Every metric is registered with `db/after-commit` from inside the
;; transaction, next to the event, so it lands on the outermost commit
;; whatever that is: a rolled back transition is never counted. Each
;; writer owns that transaction (see `own-transaction`), so the commit
;; in question is always its own.
;;
;; That callback swallows exceptions, which is why every writer below
;; opens with `(assert (mtx/instance cfg) ...)`. It reads like a
;; redundant assertion because the instance is not used here, but it is
;; the only thing that makes a miswired cfg fail loudly: resolve it
;; inside the callback instead and the row is persisted while the count
;; disappears into a log line.

(defn encode-result
  "Serialize a job result to JSON, dropping unserializable values to nil
  with a warning instead of throwing (a throw here would leave the row
  stuck in `running` until the orphan lease fires)."
  [job-name result]
  (try
    (db/json result)
    (catch Throwable cause
      (l/err :hint "unable to serialize job result to JSON"
             :job-name (some-> job-name str)
             :cause cause)
      nil)))

(defn- record-terminal
  "Record the total time of a terminal outcome. Runs after the commit
  that made the job terminal, so a rolled back transition is never
  counted. The histogram `_count` is the terminal total per outcome:
  no separate counter. Takes the cfg and resolves the metrics
  instance from it, so the callback is not tied to an instance read
  at registration time."
  [cfg job outcome]
  (when job
    (when (ct/inst? (:created-at job))
      (metrics/record-total
       cfg
       (:name job)
       (:queue job)
       outcome
       (- (inst-ms (ct/now)) (inst-ms (:created-at job)))))))

(def schema:job-error
  "Structured error of a job that did not complete. `type` and `code` are
  keywords so they decode back from the stored JSON, `hint` is the human
  message. The map stays open: these three keys are the contract, and
  anything else is a detail the failing component adds for triage."
  [:map {:title "job-error"}
   [:type ::sm/keyword]
   [:code ::sm/keyword]
   [:hint ::sm/text]])

(def check-job-error
  "Validate a job error against its schema; raises with the malli
  explanation when it does not match."
  (sm/check-fn schema:job-error))

(def orphan-error
  "Error of a job whose execution process died or stopped reporting.
  Nothing is alive to report it, so the dispatcher stores this and marks
  the job `aborted` (system-side terminal, never retried) with its `end`
  event in the same transaction: the durable state of the job is the
  whole story."
  (check-job-error {:type :internal
                    :code :orphan
                    :hint "job execution lease expired"}))

(def ^:private decode-job-error-value
  (sm/decoder schema:job-error sm/json-transformer))

(defn decode-job-error
  "Read back a stored `job.error` as a Clojure map, applying the
  transformation of `schema:job-error` so `type` and `code` come back as
  keywords instead of the strings the database holds."
  [error]
  (when (some? error)
    (decode-job-error-value (cond-> error
                              (db/pgobject? error)
                              (db/decode-json-pgobject)))))

;; --- CLAIM

(def ^:private sql:claim-job
  "UPDATE job
      SET status='running', started_at=now(), modified_at=now()
    WHERE id=?
      AND scheduled_at=?
      AND status IN ('new','scheduled','retry')
    RETURNING retry_num")

(defn claim
  "Claim a job on behalf of a worker, internal or external, and record the
  `start` event.

  Only transitions a pending row (new/scheduled/retry) to `running`, and
  requires an exact `scheduled-at` match with the value advertised in the
  queue payload, so a stale payload (row rescheduled or claimed in the
  meantime) affects 0 rows and must be skipped. Returns the number of
  affected rows.

  The event carries `attempt` = `retry_num + 1`: the number of the
  attempt this run consumes from the retry budget."
  [cfg job-id scheduled-at]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (db/tx-run! (own-transaction cfg)
              (fn [{:keys [::db/conn] :as tx-cfg}]
                (if-let [{:keys [retry-num]}
                         (db/exec-one! conn [sql:claim-job job-id scheduled-at])]
                  (do
                    (insert-event tx-cfg
                                  job-id "start" {:attempt (inc (long retry-num))})
                    1)
                  0))))

;; --- RETRY

(def ^:private retriable-error-codes
  "Errors that no retry can heal: invalid params and a job-def missing on
  this backend are a contract or deployment problem, not a transient one."
  #{:data-validation :no-job-definition})

(defn retry-delay
  "Milliseconds to wait before the next attempt: the first retry waits
  `base-ms` and every further consumed attempt doubles the wait. Shared by
  the internal runner and the external worker so both apply the same
  backoff."
  [next-retry-num & {:keys [base-ms] :or {base-ms 1000}}]
  (->> (iterate #(* 2 %) base-ms)
       (take (max 1 next-retry-num))
       (last)))

(defn retriable-error?
  "True when a failure may consume another attempt: the job is alive, it
  has retry budget left and the error code is not one that no retry can
  heal. The caller still decides whether it wants a retry at all."
  [job error]
  (and (some? job)
       (< (:retry-num job) (:max-retries job))
       (not (contains? retriable-error-codes (:code error)))))

(def ^:private sql:retry-job
  "UPDATE job
      SET status='retry', modified_at=?, scheduled_at=?, retry_num=?, error=?
    WHERE id=?
      AND status IN ('running','retry')
    RETURNING retry_num")

(defn retry-job
  "Schedule the next attempt of a running job and record the `retry` event.

  `next-retry-num` is the counter after the transition and `reason` says
  how we got there, one of `:backoff` or `:noop`. A backoff increments the
  counter, a noop keeps it, so the event always reports the attempt that
  counts against `max-retries`: a noop retry re-reports the same number,
  because it consumed no budget. Returns the number of affected rows; a
  terminal job is left untouched and writes no event."
  [cfg job-id next-retry-num scheduled-at error reason]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (let [job     (get-job cfg job-id)
        encoded (db/json (check-job-error error))]
    (db/tx-run! (own-transaction cfg)
                (fn [{:keys [::db/conn] :as tx-cfg}]
                  (if-let [{:keys [retry-num]}
                           (db/exec-one! conn [sql:retry-job (ct/now) scheduled-at
                                               next-retry-num encoded job-id])]
                    (do
                      (insert-event tx-cfg
                                    job-id "retry" {:attempt (inc (long retry-num))
                                                    :reason   (name reason)})
                      (db/after-commit
                       #(metrics/record-retry tx-cfg
                                              (:name job)
                                              (:queue job)
                                              reason))
                      1)
                    0)))))

;; --- COMPLETE

(def ^:private sql:complete-job
  "UPDATE job
      SET status='completed', completed_at=?, modified_at=?, result=?,
          error=NULL, resource_id=coalesce(resource_id, ?)
    WHERE id=?
      AND status IN ('running','retry')
    RETURNING id")

(def ^:private sql:lock-job
  "SELECT * FROM job
    WHERE id = ?
    FOR UPDATE")

;; `complete` answers a result whose object is already gone by failing the
;; job, and `fail` is defined below with the other terminal writers.
(declare fail)

(defn- check-resource-id
  "Validate a `resource-id` on its own, before any database work."
  [resource-id]
  (when (some? resource-id)
    (when-not (uuid? resource-id)
      (ex/raise :type :validation
                :code :invalid-resource-id
                :hint "resource-id must be a uuid"
                :resource-id resource-id))))

(defn complete
  "Mark a running job as completed and record the `end` event.

  Named options:

  - `:job-id`      the job to complete.
  - `:result`      the JSON-encodable result, nil when the job returns
                   nothing; a result that does not conform to the schema
                   its job-def declares is refused.
  - `:resource-id` optional storage object to associate with the job. A
                   resource is only ever set, never replaced: passing one
                   for a job that already has a resource is a validation
                   error, and a nil value leaves the column untouched.

  The result, the resource, the status and the event share one
  transaction, so a job never ends up completed with a resource that was
  not stored or an event that is missing.

  Conditional on the non-terminal running/retry states (first-terminal
  wins: a row already marked failed/cancelled/aborted — e.g. an orphan
  aborted by the dispatcher — is never overwritten) and writes no event. Returns
  the number of affected rows.

  The row is read locked inside that same transaction, so the rules are
  applied in a fixed order against the row and not against the clock: a
  job that is no longer completable is skipped, only a job that is still
  completable can be refused for its resource, and the result is decoded
  with the codec its job-def compiles and then checked against the schema
  that def declares (a def without one has nothing to check). Decoding is
  not optional: a JSON caller sends uuids as text, and the hook and the
  row both need the typed value. A concurrent completion waits for the
  lock and then sees the outcome."
  [cfg & {:keys [job-id result resource-id]}]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (check-resource-id resource-id)
  (let [n (try
            (db/tx-run! (own-transaction cfg)
                        (fn [{:keys [::db/conn] :as tx-cfg}]
                          (let [job     (some-> (db/exec-one! conn [sql:lock-job job-id])
                                                decode-row)
                                job-def (get (get-defs tx-cfg) (keyword (:name job)))
                                ;; The result crosses a wire (a JSON caller
                                ;; sends uuids as text): decode it with the
                                ;; job-def codec before validating, storing or
                                ;; handing it on. Idempotent for a caller that
                                ;; already passes a typed value.
                                result  (decode-result job-def result)
                                encoded (when (some? result)
                                          (encode-result (:name job) result))]
                            (if-not (contains? #{"running" "retry"} (:status job))
                              0
                              (do
                                (when (and (some? resource-id)
                                           (:resource-id job))
                                  (ex/raise :type :validation
                                            :code :resource-already-associated
                                            :hint "the job already has a resource, it cannot be replaced"
                                            :job-id job-id
                                            :resource-id (:resource-id job)))
                                (check-result job-def result)
                                (if (db/exec-one! conn [sql:complete-job (ct/now) (ct/now)
                                                        encoded resource-id job-id])
                                  (do
                                    (run-on-terminal tx-cfg job {:outcome :completed :result result})
                                    (insert-event tx-cfg
                                                  job-id "end" {:outcome "completed"})
                                    (notify-terminal! tx-cfg job {:ok result})
                                    (db/after-commit
                                     #(record-terminal tx-cfg job :completed))
                                    1)
                                  0))))))
            (catch Throwable cause
              ;; The result object may be gone between the handler and this
              ;; writer. Retrying cannot help, so turn the hook's signal into
              ;; a terminal failure instead of leaving the job running until
              ;; the lease fires.
              (if (= :media-result-expired (:type (ex-data cause)))
                (do
                  (fail cfg job-id {:type :internal
                                    :code :media-result-expired
                                    :hint (or (:hint (ex-data cause))
                                              "the job result is no longer available")})
                  0)
                (throw cause))))]
    (cleanup-throttle job-id)
    n))

;; --- FAIL

(def ^:private sql:fail-job
  "UPDATE job
      SET status='failed', modified_at=?, error=?
    WHERE id=?
      AND status IN ('running','retry')
    RETURNING id")

(defn fail
  "Mark a running job as failed and record the `end` event.

  The error must conform to `schema:job-error`; it is validated here, not
  only at the RPC boundary, so every path writes the same shape. The
  error never reaches the event: `job.error` is where the whole thing
  lives, and the event only says the job failed.

  Conditional on the non-terminal running/retry states (first-terminal
  wins) and writes no event when it affects no row. Returns the number of
  affected rows."
  [cfg job-id error]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (let [job     (get-job cfg job-id)
        encoded (db/json (check-job-error error))
        n       (db/tx-run! (own-transaction cfg)
                            (fn [{:keys [::db/conn] :as tx-cfg}]
                              (if (db/exec-one! conn [sql:fail-job (ct/now) encoded job-id])
                                (do
                                  (run-on-terminal tx-cfg job {:outcome :failed :error error})
                                  (insert-event tx-cfg
                                                job-id "end" {:outcome "failed"})
                                  (notify-terminal! tx-cfg job {:error error})
                                  (db/after-commit
                                   #(record-terminal tx-cfg job :failed))
                                  1)
                                0)))]
    (cleanup-throttle job-id)
    n))

;; --- CANCEL

(def ^:private sql:cancel-job
  "UPDATE job
      SET status='cancelled', modified_at=?
    WHERE id=?
      AND status IN ('new','scheduled','retry','running')
    RETURNING id")

(def ^:private sql:select-active-jobs-by-labels
  "SELECT id, label FROM job
    WHERE name = ?
      AND tenant = ?
      AND label = ANY(?::text[])
      AND status IN ('new','scheduled','retry','running')
    ORDER BY scheduled_at DESC, id DESC")

(defn cancel
  "Cancel a pending or running job (new/scheduled/retry/running) and
  record the `end` event.

  A running job stops at its next heartbeat: the beat finds no active
  row and raises an `:interrupt`, which the runner treats as a normal
  stop while the handler rolls back. A terminal job is left untouched
  and writes no event. Returns the number of affected rows."
  [cfg job-id]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (let [job     (get-job cfg job-id)
        n       (db/tx-run! (own-transaction cfg)
                            (fn [{:keys [::db/conn] :as tx-cfg}]
                              (if (db/exec-one! conn [sql:cancel-job (ct/now) job-id])
                                (do
                                  (run-on-terminal tx-cfg job {:outcome :cancelled})
                                  (insert-event tx-cfg
                                                job-id "end" {:outcome "cancelled"})
                                  (notify-terminal! tx-cfg job
                                                    {:error {:type :internal
                                                             :code :cancelled
                                                             :hint "the job was cancelled"}})
                                  (db/after-commit
                                   #(record-terminal tx-cfg job :cancelled))
                                  1)
                                0)))]
    (when (pos? n)
      (cleanup-throttle job-id))
    n))

(defn active-jobs-by-label
  "Active jobs (`new`, `scheduled`, `retry` or `running`) of `name` for the
  current tenant, indexed by label. When a label has more than one active
  row, the most recently scheduled one wins."
  [cfg name labels]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (let [tenant (cf/get :tenant)
        labels (vec (distinct labels))]
    (if (empty? labels)
      {}
      (db/run! cfg
               (fn [{:keys [::db/conn]}]
                 (let [arr (db/create-array conn "text" labels)]
                   (->> (db/exec! conn [sql:select-active-jobs-by-labels (d/name name) tenant arr])
                        (reduce (fn [result row]
                                  (assoc result (:label row) (:id row)))
                                {}))))))))

(defn cancel-by-label
  "Cancel every active job of this tenant with the given `name` and `label`,
  and answer how many were actually cancelled.

  Used to stop work whose resource is gone or replaced without knowing the
  job id: deleting a font variant or uploading a new profile photo. Each
  cancellation runs `cancel`, so every one owns its transaction and writes
  its `end` event with the `cancelled` outcome.

  Active means `new`, `scheduled`, `retry` or `running`. A terminal row is
  never touched, and one that turns terminal between the read and the
  cancel simply is not counted. The label must be explicit and non-blank:
  a blank one would cancel every unlabeled job of that name."
  [cfg name label]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (when (or (not (string? label)) (str/blank? label))
    (ex/raise :type :validation
              :code :cancel-by-label-requires-label
              :hint "cancel-by-label requires an explicit non-blank label"
              :name (d/name name)))
  (let [ids (vals (active-jobs-by-label cfg name [label]))]
    (reduce (fn [total id] (+ total (cancel cfg id))) 0 ids)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REQUEST (ephemeral request/response, no row, no dispatcher)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NOTE: the media worker was the first producer of this path (2026-10-30)
;; and no longer is (2026-11-04): media is durable jobs now. The path stays
;; as substrate API without a producer. The reply's `:type` is part of the
;; contract, so a validation or restriction error from the worker does not
;; become an internal error in the backend.

(def ^:private request-command-timeout-margin (ct/duration {:seconds 30}))

(def ^:private request-error-types
  #{:validation :restriction :authorization :internal})

(defn- request-error-type
  "The type of a reply error, coerced to a keyword and narrowed to the
  known set. A missing or unknown type stays `:internal`: a worker that
  does not name it must not change the caller's behavior."
  [error]
  (let [value (get error :type)
        type  (cond
                (keyword? value) value
                (string? value)  (keyword value)
                :else            nil)]
    (if (contains? request-error-types type)
      type
      :internal)))

(defn- request-error-code
  "The code of a reply error, coerced to a keyword: JSON carries it as a
  string and the backend exception vocabulary is keywords."
  [error]
  (let [value (get error :code)]
    (cond
      (keyword? value) value
      (string? value)  (keyword value)
      :else            value)))

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
        tenant     (cf/get :tenant)
        timeout    (or timeout (cf/get-jobs-request-timeout))
        request-id (uuid/next)
        reply-key  (str/ffmt "%:%:%" reply-key-prefix tenant request-id)
        queue-key  (wrk/queue-key tenant queue)
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
                          :tenant tenant
                          :queue queue
                          :timeout timeout))
              (let [response (json/decode reply :key-fn keyword)]
                (if-let [error (:error response)]
                  (do
                    (vreset! outcome :error)
                    (ex/raise :type (request-error-type error)
                              :code (request-error-code error)
                              :hint (or (get error :hint) "request failed")
                              :tenant tenant
                              :queue queue
                              :response response))
                  (do
                    (vreset! outcome :replied)
                    (get response :ok))))))

          (finally
            ;; the context, not `cfg`: the caller's cfg only carries the
            ;; request pool, and the metrics instance comes with it
            (metrics/record-request context
                                    @outcome
                                    (inst-ms (tpoint)))
            (l/trc :hint "request"
                   :tenant tenant
                   :queue (d/name queue)
                   :cmd (d/name cmd)
                   :outcome @outcome
                   :elapsed (inst-ms (tpoint)))
            (rds/del conn reply-key)
            (rds/reset-timeout conn)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT-AND-WAIT (durable job, synchronous caller)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wait-reply
  "BLPOP on `key` with `timeout`, answering the decoded reply or nil. The
  connection timeout is raised for the call, like `request` does, and the
  key is deleted on every path."
  [cfg key timeout]
  (rds/run! cfg
            (fn [{:keys [::rds/conn]}]
              (rds/set-timeout conn (ct/plus timeout request-command-timeout-margin))
              (try
                (let [[_ reply] (rds/blpop conn [key] timeout)]
                  (when reply
                    (json/decode reply :key-fn keyword)))
                (finally
                  (rds/del conn key)
                  (rds/reset-timeout conn))))))

(defn submit-and-wait
  "Submit a job with immediate dispatch and block until it reaches a
  terminal state, answering its result.

  The job-def must declare `::notifies?`: the terminal writers publish
  `{ok result}` or `{error ...}` on
  `penpot.worker.reply:<tenant>:<job-id>` after their commit. The answer is
  decoded with the result codec of the job-def, the same one the row read
  applies, so the caller sees one type whether the reply arrived or the row
  had to be re-read. The job is
  committed before the wait (the submission owns its transaction), so the
  worker can claim it while the caller blocks. The wait uses
  `:media-wait-deadline` (60 s by default, under the 300 s gateway
  timeout).

  On timeout the job is cancelled and `:request-timeout` is raised. An
  error reply is raised with its type, code and hint. The reply key is
  deleted on every path, so it cannot be read twice."
  [cfg options]
  (assert (mtx/instance cfg) "missing ::mtx/metrics on the jobs cfg")
  (let [name    (::name options)
        job-def (get-job-def (get-defs cfg) name)]
    (when-not (::notifies? job-def)
      (ex/raise :type :assertion
                :code :job-does-not-notify
                :hint "submit-and-wait requires a job-def with ::notifies?"
                :name (d/name name)))

    (let [job-id   (submit (own-transaction cfg) (assoc options ::immediate? true))
          tenant   (cf/get :tenant)
          timeout  (cf/get-media-wait-deadline)
          reply    (when (uuid? job-id)
                     (wait-reply cfg
                                 (str/ffmt "%:%:%" reply-key-prefix tenant job-id)
                                 timeout))]

      (when-not (uuid? job-id)
        (ex/raise :type :internal
                  :code :job-not-submitted
                  :hint "submit-and-wait requires a job that is actually submitted"
                  :name (d/name name)))

      (cond
        (nil? reply)
        ;; The reply can be lost while the row is already terminal (the
        ;; writer committed just after the BLPOP timed out). The row is the
        ;; source of truth: answer its outcome instead of a false timeout.
        (let [job (get-job cfg job-id)]
          (cond
            (= "completed" (:status job))
            (:result job)

            (= "failed" (:status job))
            (let [error (:error job)]
              (ex/raise :type (request-error-type error)
                        :code (request-error-code error)
                        :hint (or (get error :hint) "job failed")
                        :job-id (str job-id)
                        :response {:error error}))

            :else
            (do
              (cancel cfg job-id)
              (ex/raise :type :timeout
                        :code :request-timeout
                        :hint "timeout waiting for the job result"
                        :tenant tenant
                        :job-id (str job-id)
                        :timeout timeout))))

        (contains? reply :error)
        (let [error (:error reply)]
          (ex/raise :type (request-error-type error)
                    :code (request-error-code error)
                    :hint (or (get error :hint) "job failed")
                    :job-id (str job-id)
                    :response reply))

        :else
        ;; The reply travels as JSON: restore the typed value with the same
        ;; codec the row read applies, so a caller sees one shape whichever
        ;; path settled the job.
        (decode-result job-def (:ok reply))))))

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
  (let [name (get cfg ::name)]
    (when-not (keyword? name)
      (ex/raise :type :validation
                :code :invalid-job-name
                :hint "job name must be a keyword"
                :name name))
    (let [job-def (get-job-def (get-defs cfg) name)
          context (some-> (get cfg ::context) check-context)
          decoded (decode-params job-def (get cfg ::params))]
      (binding [*job-id* (get cfg ::job-id)]
        ((::handler job-def) context decoded)))))
