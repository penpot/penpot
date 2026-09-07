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
  - `submit!` (durable): validates + JSON-encodes params and inserts a row
    into the `job` table; the dispatcher/runner machinery does the rest.
  - `request!` (ephemeral): synchronous request/response over the redis
    queues with a reply-key and a dedicated, unbounded connection pool;
    external workers answer with `reply!`.

  Params payloads are stored as plain JSON (not transit) in the `props` jsonb
  column and decoded back to typed Clojure values using the job-def decoder."
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
   [app.metrics :as-alias mtx]
   [app.redis :as rds]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB DEFINITIONS (registry)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:job-def
  [:map {:title "job-def"}
   [::name [:or ::sm/text :keyword]]
   [::schema any?]
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
  [cfg defs]
  (or defs (get cfg ::defs) @defs-registry))

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

(defn get-job-def
  "Resolve the job-def for the provided job name; raises if missing."
  [defs name]
  (or (get defs (keyword name))
      (ex/raise :type :not-found
                :hint "no job definition found"
                :code :no-job-definition
                :name (d/name name))))

(defn decode-params
  "Decode the raw JSON props (pgobject or decoded map) into typed params
  using the precompiled decoder of the job-def."
  [job-def props]
  (-> (cond-> props
        (db/pgobject? props)
        db/decode-json-pgobject)
      ((::decoder job-def))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:insert-new-job
  "insert into job (id, name, props, queue, label, priority, max_retries,
                    created_at, modified_at, scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
   [::dedupe {:optional true} ::sm/boolean]])

(def check-options!
  (sm/check-fn schema:options))

(defn- validate-params!
  "Validate the params with the precompiled validator of the job-def; on
  failure raises with the malli explanation."
  [job-def params]
  (when-not ^boolean ((::validator job-def) params)
    (sm/check (::schema job-def) params))
  params)

(defn submit!
  "Submit a durable job: validates the params with the job-def validator,
  encodes them as plain JSON and inserts a row into the `job` table.
  Fire-and-forget: returns the job id immediately.

  NOTE: the dedupe semantics match the legacy `wrk/submit!`: a non-atomic
  DELETE of not-yet-due 'new' rows with the same name/queue/label followed by
  the INSERT. Concurrent cross-backend submissions can, in rare race
  conditions, produce duplicated 'new' rows (accepted risk, see
  prod-infra documentation)."
  [cfg {:keys [::params ::name ::delay ::queue ::priority ::max-retries
               ::dedupe ::label]
        :or   {delay 0 queue :default priority 100 max-retries 3 label ""}
        :as   options}]

  (check-options! options)

  (let [job-def      (get-job-def (get-defs cfg nil) name)
        params       (validate-params! job-def params)
        delay        (ct/duration delay)
        now          (ct/now)
        scheduled-at (-> (ct/plus now delay)
                         (ct/truncate :millisecond))
        props        (db/json params)
        id           (uuid/next)
        tenant       (cf/get :tenant)
        job-name     (d/name name)
        queue        (str/ffmt "%:%" tenant (d/name queue))
        conn         (db/get-connectable cfg)
        ;; Dedupe is non-atomic: we delete not-started jobs with the same
        ;; name/queue/label, then insert. A race between backends could create
        ;; duplicates, but this is acceptable: cross-backend races are rare,
        ;; jobs are idempotent, and dedupe is best-effort.
        deleted      (when dedupe
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

    (db/exec-one! conn [sql:insert-new-job id job-name props queue
                        label priority max-retries
                        now now scheduled-at])

    id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:cancel-job
  "UPDATE job
      SET status='cancelled', modified_at=?
    WHERE id=?
      AND status = ANY(?)")

(defn- decode-json-col
  [row key]
  (cond-> row
    (db/pgobject? (get row key))
    (assoc key (db/decode-json-pgobject (get row key)))))

(defn- decode-row
  [row]
  (-> row
      (decode-json-col :props)
      (decode-json-col :progress)))

(defn get-job
  "Retrieve the job row (with raw JSON props decoded to a plain map)."
  [cfg job-id]
  (some-> (db/get* cfg :job {:id job-id})
          (decode-row)))

(defn cancel!
  "Cancel a pending job (new/scheduled/retry). Returns the number of
  affected rows; jobs already running or in a terminal state are left
  untouched (the conditional claim in the runner/management API will skip
  them)."
  [cfg job-id]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (-> (db/exec-one! conn [sql:cancel-job (ct/now) job-id
                                        (db/create-array conn "text" ["new" "scheduled" "retry"])])
                    (db/get-update-count)))))

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
;; HEARTBEAT / PROGRESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private heartbeat-interval (ct/duration {:seconds 60}))
(def ^:private progress-interval (ct/duration {:millis 250}))

(def ^:private prune-threshold 10000)
(def ^:private prune-window (ct/duration {:hours 1}))

;; Throttle state atoms (public for testing)
(def heartbeats (atom {}))
(def progresses (atom {}))

(def ^:dynamic *job-id*
  "Job id of the job being executed on the current thread. The runner
  binds it around handler invocations; handlers call `heartbeat!`/`progress!`
  without knowing the id. Nil means no job context (in-process `invoke!`
  without a row), in which case the throttled writes become no-ops."
  nil)

(def ^:private sql:persist-progress
  "UPDATE job
      SET progress=?, modified_at=?
    WHERE id=?
      AND status = ANY(?)")

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

(defn heartbeat!
  "Touch `modified_at` on the running job (throttled: does not write when
  the last beat is more recent than ~60s). Handlers call it on every
  iteration without thinking. The job id comes from the `::job-id` key on
  the cfg, the thread-bound `*job-id*` (set by the runner), or can be
  passed explicitly. No-op when there is no job context."
  ([cfg]
   (let [job-id (or (get cfg ::job-id) *job-id*)]
     (when (uuid? job-id)
       (heartbeat! cfg job-id))))
  ([cfg job-id]
   (when (uuid? job-id)
     (when (should-write? heartbeats job-id (ct/now) heartbeat-interval)
       (db/update! cfg :job
                   {:modified-at (ct/now)}
                   {:id job-id}
                   {::db/return-keys false})
       nil))))

(defn progress!
  "Persist the `progress` payload and touch `modified_at` (throttled at
  ~250ms; only writes on non-terminal job states). The job id comes from
  the `::job-id` key on the cfg, the thread-bound `*job-id*` (set by the
  runner), or can be passed explicitly. No-op when there is no job
  context."
  ([cfg progress]
   (let [job-id (or (get cfg ::job-id) *job-id*)]
     (when (uuid? job-id)
       (progress! cfg job-id progress))))
  ([cfg job-id progress]
   (when (uuid? job-id)
     (when (should-write? progresses job-id (ct/now) progress-interval)
       (db/tx-run! cfg
                   (fn [{:keys [::db/conn]}]
                     (let [now (ct/now)]
                       (db/exec-one! conn [sql:persist-progress (db/json progress) now job-id
                                           (db/create-array conn "text" ["new" "scheduled" "running" "retry"])]))))
       nil))))

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

(defn claim!
  "Claim a job on behalf of an external worker: only transitions a
  pending row (new/scheduled/retry) to `running` and requires an exact
  `scheduled-at` match with the value advertised in the queue payload, so
  a stale payload (row rescheduled or claimed in the meantime) affects 0
  rows and must be skipped. Returns the number of affected rows."
  [cfg job-id scheduled-at]
  (-> (db/exec-one! (db/get-connectable cfg)
                    [sql:claim-external-job job-id scheduled-at])
      (db/get-update-count)))

(defn complete!
  "Mark a running job as completed with the (JSON-encodable) result.
  Conditional on the non-terminal running/retry states (first-terminal
  wins: a row already marked failed/cancelled — e.g. an orphan detected
  by the dispatcher — is never overwritten). Returns the number of
  affected rows."
  ([cfg job-id]
   (complete! cfg job-id nil))
  ([cfg job-id result]
   (-> (db/exec-one! (db/get-connectable cfg)
                     [sql:complete-job (ct/now) (ct/now)
                      (when (some? result) (db/json result)) job-id])
       (db/get-update-count))))

(defn fail!
  "Mark a running job as failed with the error payload (a JSON object
  with at least a :code). Conditional on the non-terminal running/retry
  states (first-terminal wins). Returns the number of affected rows."
  [cfg job-id error]
  (-> (db/exec-one! (db/get-connectable cfg)
                    [sql:fail-job (ct/now) (db/json error) job-id])
      (db/get-update-count)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REQUEST (ephemeral request/response, no row, no dispatcher)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private request-command-timeout-margin (ct/duration {:seconds 30}))
(def ^:private reply-expire-seconds 60)

(def reply-key-prefix "penpot.worker.reply")

(defn get-request-pool
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
  (rds/pool {::rds/client client
             ::mtx/metrics metrics}
            {:timeout command-timeout}))

(def ^:private schema:request-options
  [:map {:title "request-options"}
   [::queue [:or ::sm/text :keyword]]
   [::cmd [:or ::sm/text :keyword]]
   [::params any?]
   [::timeout {:optional true} [:or ::sm/int ::ct/duration]]])

(def check-request-options!
  (sm/check-fn schema:request-options))

(defn reply!
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

(defn request!
  "Ephemeral request/response (no job row, no dispatcher): pushes a JSON
  payload [request-id, reply-key, cmd, params] to the target queue and
  blocks on the reply-key with a per-call timeout (defaults to
  :jobs-request-timeout; a per-call override must stay below the pooled
  connection command timeout, which is raised for the duration of the
  call and restored by the pool dispose-fn on return).

  On success returns the decoded `:ok` payload; an `:error` reply
  propagates as an exception; on timeout raises `:request-timeout` and
  the reply-key is deleted (in finally, also on error). The connection
  is always returned to the pool."
  [cfg
   {:keys [::queue ::cmd ::params ::timeout] :as options}]

  (check-request-options! options)

  (let [pool       (get-request-pool cfg)
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
      (let [conn @pooled]
        (try
          ;; raise the connection command timeout above the per-call
          ;; blpop timeout; the pool dispose-fn restores the default on
          ;; return.
          (rds/set-timeout conn (ct/plus timeout request-command-timeout-margin))

          (rds/rpush conn queue-key [payload])

          (let [[_ reply] (rds/blpop conn [reply-key] timeout)]
            (if (nil? reply)
              (ex/raise :type :timeout
                        :code :request-timeout
                        :hint "timeout waiting for the job reply"
                        :queue queue
                        :timeout timeout)
              (let [response (json/decode reply :key-fn keyword)]
                (if-let [error (:error response)]
                  (ex/raise :type :internal
                            :code (get error :code)
                            :hint (or (get error :hint) "request failed")
                            :response response)
                  (get response :ok)))))

          (finally
            (rds/del conn reply-key)
            (rds/reset-timeout conn)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IN-PROCESS INVOCATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke!
  "Execute a job handler in-process (no row, no dispatch): decodes the
  params with the job-def decoder and invokes the handler bound to the
  `*job-id*` dynamic (or the provided ::job-id, which makes the
  throttled heartbeat/progress writes work against the row). Options:

  {::name    :delete-object
   ::params  {...}     ;; raw (JSON-shaped) params
   ::defs    {...}     ;; the ::jobs/defs registry
   ::job-id  <uuid>}   ;; optional, only when the row already exists

  Returns the handler result."
  [cfg]
  (let [job-def (get-job-def (get-defs cfg nil) (get cfg ::name))
        decoded (decode-params job-def (get cfg ::params))]
    (binding [*job-id* (get cfg ::job-id)]
      ((::handler job-def) decoded))))
