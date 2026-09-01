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
  - `request!` (ephemeral): implemented in app.jobs.request.

  Params payloads are stored as plain JSON (not transit) in the `props` jsonb
  column and decoded back to typed Clojure values using the job-def decoder."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

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

(defmethod ig/assert-key ::defs
  [_ defs]
  (sm/check schema:job-defs defs)
  (doseq [[name job-def] defs]
    (when-not (= (d/name name) (d/name (::name job-def)))
      (ex/raise :type :assertion
                :code :job-def-name-mismatch
                :hint "job-def name mismatch"
                :job (d/name name)
                :expected (d/name (::name job-def))))))

(defmethod ig/init-key ::defs
  [_ defs]
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

  (let [job-def      (get-job-def (get cfg ::defs) name)
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

(def ^:private heartbeats (atom {}))
(def ^:private progresses (atom {}))

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
  registry of the last write time per job."
  [state-ref job-id now interval]
  (let [decision (volatile! false)]
    (swap! state-ref
           (fn [m]
             (let [m (if (> (count m) prune-threshold)
                       (into {}
                             (filter (fn [[_ last-inst]]
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
                                           (db/create-array conn "text" ["new" "scheduled" "running" "retry"])])))))
     nil)))
