;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.gc
  "The garbage collection of the audit log buffer.

  The producer only inserts rows and each consumer keeps its position,
  so somebody has to delete the rows every registered consumer has
  already passed. This job does it in batches below the oldest cursor,
  never touching the telemetry shadows (the telemetry task owns them).

  The consumer framework (ids, flags and cursor helpers) lives in
  `app.loggers.audit.consumers`."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(def ^:private batch-size
  "Rows per delete: a big buffer is cleaned in bounded statements, each
  one its own snapshot, so the job heartbeats between batches."
  5000)

(defn- get-oldest-cursor
  "The oldest position among every cursor in the table, or nil when there
  is none.

  The set is not derived from the flags of the process that runs the job:
  the cursor table is shared by every instance, so a consumer disabled on
  this worker still holds the position it reached and keeps its unread
  rows alive. A consumer registers its (0, 0) cursor at boot, before its
  first pass, so the first row it has to read is never deleted under it.
  With no cursor at all the GC deletes nothing: it cannot know whether a
  consumer is about to start."
  [cfg]
  (when-let [row (first (db/exec! cfg
                                  ["SELECT xid::text AS xid_text, seq
                                      FROM audit_log_consumer_cursor
                                     ORDER BY xid ASC, seq ASC
                                     LIMIT 1"]))]
    (select-keys row [:xid-text :seq])))

(def ^:private sql:delete-batch
  "Delete one batch of rows up to and including a position, telemetry
  shadows excluded: the cursor points at the last row a consumer
  processed, so that row is done too. The telemetry task owns the
  shadows (it deletes them after sending and purges the ones older than
  its retention window), so deleting them here would lose events before
  they are shipped.

  The inner select walks the `(xid, seq)` index and skips locked rows:
  a producer never holds an old row, and two GC runs must not wait for
  each other."
  "DELETE FROM audit_log a
    WHERE a.id IN (SELECT a2.id
                     FROM audit_log a2
                    WHERE (a2.xid, a2.seq) <= (?::xid8, ?::bigint)
                      AND a2.source NOT LIKE 'telemetry:%'
                    ORDER BY a2.xid ASC, a2.seq ASC
                    LIMIT ?
                      FOR UPDATE SKIP LOCKED)")

(defn- delete-one-batch
  "Read the oldest cursor and delete one batch under the registry lock, in
  one transaction. The read is inside the batch, so a cursor registered
  between two batches is seen before the next delete. Answers
  `{:deleted n :found? bool}`."
  [cfg]
  (db/tx-run!
   cfg
   (fn [cfg]
     (consumers/lock-registry (::db/conn cfg))
     (if-let [cursor (get-oldest-cursor cfg)]
       {:deleted (or (db/get-update-count
                      (db/exec-one! cfg [sql:delete-batch
                                         (:xid-text cursor) (:seq cursor)
                                         batch-size]))
                     0)
        :found?  true}
       {:deleted 0 :found? false}))))

(defn- delete-batched
  "Run a batched DELETE until it removes nothing, re-reading the oldest
  cursor on every batch. Answers `{:total n :found? bool}`."
  [cfg]
  (loop [total 0 found? false]
    (let [{:keys [deleted] :as batch} (delete-one-batch cfg)]
      (if (pos? (long deleted))
        (do
          (jobs/heartbeat cfg)
          (px/sleep 100)
          (recur (+ total (long deleted)) (or found? (:found? batch))))
        {:total total :found? (or found? (:found? batch))}))))

(defn delete-consumed
  "Delete the rows every registered consumer has already passed. The
  position is the oldest cursor, inclusive (the cursor points at the last
  row the consumer processed). The set is the cursor table, not the flags
  of this worker: a consumer that is off still holds the buffer (its
  cursor freezes and rejoins from wherever it is when it is enabled
  again), so a temporary disable never deletes events it has not read.

  A consumer registers its (0, 0) cursor when its component starts, so a
  consumer that is enabled but has not drained yet still holds the buffer
  at position 0. With no cursor at all nothing is deleted: the
  `:audit-log-gc` flag is the deployment switch, and the GC waits for the
  consumers to register. Deleting the buffer behind the back of a
  consumer that may start later loses events; the disk risk is the
  operator's, not a reason to drain.

  Returns the number of discarded rows; they count in the `discarded`
  metric with reason `gc`."
  [cfg]
  (let [{:keys [total found?]} (delete-batched cfg)]
    (if-not found?
      (do
        (l/wrn :hint "audit log GC is on with no consumer cursor; the buffer will not be cleaned")
        0)
      (do
        (when (pos? total)
          (consumers/record-count (mtx/instance cfg) :audit-consumer-discarded ["gc"] total))
        total))))

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
     ;; The no-cursor branch deletes nothing on purpose: deleting would lose
     ;; events for a consumer that may start later. Without the warning the
     ;; operator only sees `discarded 0` at `dbg` and the buffer grows.
     (let [total (delete-consumed cfg)]
       (l/dbg :hint "audit log buffer cleaned" :discarded total)
       total))
   ::jobs/decoder   (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator (sm/validator schema:params)})
