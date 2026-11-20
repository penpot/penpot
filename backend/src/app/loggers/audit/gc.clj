;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.gc
  "The garbage collection of the audit log buffer.

  The producer only inserts rows and each consumer keeps its position, so
  somebody has to delete the rows every registered consumer has already
  passed. This job has two paths:

  - the full rows, the normal behavior: up to the oldest cursor of the
    consumers that read them (every id except the telemetry consumer);
  - the telemetry shadows, up to the cursor of the telemetry consumer plus
    a retention window: copies older than seven days are deleted whatever
    the cursors say, so the buffer cannot keep them forever when the
    endpoint is down or the flag is off.

  Each path has its own cursor and its own loop, so a slow telemetry pass
  every three hours does not retain the events Nexus already archived, and
  turning telemetry off does not stop the deletion of the full rows. The
  retention is the only deletion that does not wait for a consumer, and it
  can remove copies that were never sent.

  Each loop stops starting batches when its budget expires. A batch
  already in progress can still commit; remaining rows wait for the next
  cron run.

  The consumer framework (ids, flags and cursor helpers) lives in
  `app.loggers.audit.consumers`."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(def ^:private ^:dynamic batch-size
  "Rows per delete: a big buffer is cleaned in bounded statements, each
  one its own snapshot, so the job heartbeats between batches. Dynamic so a
  test can bind a smaller one."
  5000)

(def ^:private ^:dynamic run-budget
  "Wall-clock budget for starting batches in one path. The paths run in
  series, each with its own clock. Dynamic so a test can bind a different one."
  (ct/duration {:seconds 120}))

(defn- budget-spent?
  "True when the time measured since `tpoint` (a `ct/tpoint` created at the
  start of the run) has reached `run-budget`."
  [tpoint]
  (not (pos? (compare run-budget (tpoint)))))

(def ^:private retention
  "How long an unsent telemetry shadow stays in the buffer. Copies older
  than this are deleted even when no consumer has passed them."
  "7 days")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE TWO PATHS: THEIR CURSORS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:full-cursor
  "The oldest position among the cursors of the full-row consumers. The
  telemetry consumer is excluded by its id: its cursor only holds the
  anonymized copies, which have their own path."
  "SELECT xid::text AS xid_text, seq
     FROM audit_log_consumer_cursor
    WHERE consumer <> ?::smallint
    ORDER BY xid ASC, seq ASC
    LIMIT 1")

(def ^:private sql:telemetry-cursor
  "SELECT xid::text AS xid_text, seq
     FROM audit_log_consumer_cursor
    WHERE consumer = ?::smallint")

(defn- get-oldest-full-cursor
  "The position of the full-row path, or nil when no consumer of that path
  has registered a cursor yet.

  The set is not derived from the flags of the process that runs the job:
  the cursor table is shared by every instance, so a consumer disabled on
  this worker still holds the position it reached and keeps its unread rows
  alive."
  [cfg]
  (when-let [row (first (db/exec! cfg [sql:full-cursor consumers/telemetry]))]
    (select-keys row [:xid-text :seq])))

(defn- get-telemetry-cursor
  "The position of the telemetry path, or nil when its consumer has not
  registered yet. A missing cursor means its first pass has not run, so the
  cursor-based deletion of this path is idle (the retention still applies)."
  [cfg]
  (when-let [row (db/exec-one! cfg [sql:telemetry-cursor consumers/telemetry])]
    (select-keys row [:xid-text :seq])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE TWO PATHS: THEIR DELETES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:delete-full-batch
  "Delete one batch of full rows up to and including a position. The cursor
  points at the last row a consumer processed, so that row is done too.

  Do not replace the materialized CTE with `id IN (SELECT ... LIMIT ?
  FOR UPDATE SKIP LOCKED)`: PostgreSQL can plan that form as a nested-loop
  semi join and rescan its locking subquery for each target row. As each
  rescan observes the rows locked or deleted by the preceding one, `LIMIT`
  becomes a per-rescan bound and one statement can delete more rows than its
  batch size.

  The materialized CTE chooses the bounded set before the delete joins it,
  so the statement cannot delete more rows than its limit. Its query walks
  the `(xid, seq)` index and skips locked rows: a producer never holds an old
  row, and two GC runs must not wait for each other."
  "WITH doomed AS MATERIALIZED
       (SELECT a2.id
          FROM audit_log a2
         WHERE (a2.xid, a2.seq) <= (?::xid8, ?::bigint)
           AND a2.source NOT LIKE 'telemetry:%'
         ORDER BY a2.xid ASC, a2.seq ASC
         LIMIT ?
           FOR UPDATE SKIP LOCKED)
   DELETE FROM audit_log a
        USING doomed
        WHERE a.id = doomed.id")

(def ^:private sql:delete-shadow-batch
  "Delete one batch of telemetry shadows up to and including the position of
  the telemetry consumer. Same shape as the full-row statement, with the
  predicate reversed."
  "WITH doomed AS MATERIALIZED
       (SELECT a2.id
          FROM audit_log a2
         WHERE (a2.xid, a2.seq) <= (?::xid8, ?::bigint)
           AND a2.source LIKE 'telemetry:%'
         ORDER BY a2.xid ASC, a2.seq ASC
         LIMIT ?
           FOR UPDATE SKIP LOCKED)
   DELETE FROM audit_log a
        USING doomed
        WHERE a.id = doomed.id")

(def ^:private sql:delete-expired-shadows
  "Delete one batch of telemetry shadows past the retention window. It does
  not read a cursor: the window is the guarantee, whatever the consumers have
  passed, so the buffer stays bounded with the endpoint down or the flag off.
  Oldest first, so the batch always makes progress."
  (str "WITH doomed AS MATERIALIZED
             (SELECT a2.id
                FROM audit_log a2
               WHERE a2.source LIKE 'telemetry:%'
                 AND a2.created_at < now() - interval '" retention "'
               ORDER BY a2.created_at ASC
               LIMIT ?
                 FOR UPDATE SKIP LOCKED)
         DELETE FROM audit_log a
              USING doomed
              WHERE a.id = doomed.id"))

(defn- delete-batch
  "One batch of one path, in one transaction: read the position again (a
  cursor registered between two batches must be seen before the next delete)
  and delete up to it. Answers the number of deleted rows, 0 when the path has
  no cursor."
  [cfg get-cursor sql]
  (db/tx-run! cfg (fn [cfg]
                    (if-let [cursor (get-cursor cfg)]
                      (or (db/get-update-count
                           (db/exec-one! cfg [sql (:xid-text cursor) (:seq cursor) batch-size]))
                          0)
                      0))))

(defn- check-interrupted
  []
  (when (Thread/interrupted)
    (throw (InterruptedException.))))

(defn- run-path
  "Drain one path on the runner thread. Return the total committed before
  the path ran out of work or time, or met an ordinary failure. Propagate
  interruptions so the runner does not start another path."
  [cfg path batch]
  (let [tpoint (ct/tpoint)
        total  (volatile! 0)]
    (try
      (loop []
        (check-interrupted)
        (when-not (budget-spent? tpoint)
          (let [deleted (long (batch))]
            (when (pos? deleted)
              (vswap! total + deleted)
              (consumers/record-count (mtx/instance cfg) :audit-consumer-discarded ["gc"] deleted))
            (check-interrupted)
            (when (pos? deleted)
              (jobs/heartbeat cfg)
              (Thread/sleep 100)
              (recur)))))
      (catch InterruptedException cause
        (throw cause))
      (catch Exception cause
        (when (= :interrupt (:type (ex-data cause)))
          (throw cause))
        (check-interrupted)
        (l/err :hint "audit log GC path failed" :path path :cause cause)))
    @total))

(defn- delete-full-rows
  "The normal path: the full rows every one of their consumers has passed."
  [cfg]
  (run-path cfg "full" #(delete-batch cfg get-oldest-full-cursor sql:delete-full-batch)))

(defn- delete-shadow-rows
  "The telemetry path: the shadows the telemetry consumer has passed."
  [cfg]
  (run-path cfg "shadow" #(delete-batch cfg get-telemetry-cursor sql:delete-shadow-batch)))

(defn- delete-expired-shadows
  "The telemetry retention window: shadows older than seven days, in
  batches, whatever the cursors say. Runs after the cursor paths with its
  own budget and answers the number of deleted rows."
  [cfg]
  (let [total (run-path cfg "shadow-expired"
                        #(or (db/get-update-count
                              (db/exec-one! cfg [sql:delete-expired-shadows batch-size]))
                             0))]
    (when (pos? total)
      (l/warn :hint "purged stale telemetry events" :count total))
    total))

(defn delete-consumed
  "Delete the rows of the two paths, each one independently:

  - the full rows, up to the oldest cursor of the consumers that read them;
  - the telemetry shadows, up to the cursor of consumer 0.

  Then apply the retention window of the shadows.

  The set is the cursor table, not the flags of this worker: a consumer that
  is off still holds its path (its cursor freezes and rejoins from wherever
  it is when it is enabled again), so a temporary disable never deletes
  events it has not read. A consumer registers its (0, 0) cursor when its
  component starts, so a consumer that is enabled but has not drained yet
  still holds its path at position 0.

  The three paths run in order on the runner thread. Each one owns its
  wall-clock budget for starting batches, not for finishing them. An ordinary
  failure is logged and ends only that path, preserving its committed count.
  An interruption stops the whole pass.

  Retention is the explicit exception: copies older than seven days are
  deleted even if no cursor passed them. They still count as removed by the
  GC. The warning counts rows deleted by age, whether sent or not.

  Returns the total number of rows deleted. Each committed batch contributes
  to the `discarded` metric with reason `gc` before its heartbeat."
  [cfg]
  (let [cfg (db/own-transaction cfg)]
    (+ (delete-full-rows cfg)
       (delete-shadow-rows cfg)
       (delete-expired-shadows cfg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:params
  "Params of the GC job: none. Cron submits {}; system deps (pool,
  metrics) live on the job-def cfg closed over by the handler."
  [:map {:closed true}])

(defmethod ig/assert-key ::job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  (l/dbg :hint "audit log GC job initialized")
  {::jobs/name      :audit-log-gc
   ::jobs/schema    schema:params
   ::jobs/handler
   (fn [_context _params]
     ;; A path with no cursor deletes nothing on purpose: deleting would lose
     ;; events for a consumer that may start later. Retention still runs,
     ;; because it is bounded by age, not by a position.
     (let [total (delete-consumed cfg)]
       (l/dbg :hint "audit log buffer cleaned" :discarded total)
       total))
   ::jobs/decoder   (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator (sm/validator schema:params)})
