;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.gc
  "The garbage collection of the audit log buffer.

  The producer only inserts rows and each consumer keeps its position,
  so somebody has to delete the rows every enabled consumer has already
  passed. This job does it in batches below the oldest enabled cursor,
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
   [clojure.string :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(def ^:private gc-batch-size
  "Rows per delete: a big buffer is cleaned in bounded statements, each
  one its own snapshot, so the job heartbeats between batches."
  5000)

(defn- get-min-cursor
  "The oldest position among the enabled consumers, or nil when some of
  them has no cursor yet.

  A missing cursor means that consumer has not run for the first time:
  the GC treats it as position 0 and deletes nothing. The consumer
  creates its cursor at the end of the buffer on its first drain, so
  waiting keeps the buffer intact instead of deleting rows a consumer
  that is about to start could still read."
  [cfg consumer-ids]
  (when (seq consumer-ids)
    (let [ids   (str/join "," consumer-ids)
          total (count consumer-ids)
          found (:cnt (db/exec-one! cfg
                                    [(str "SELECT count(*) AS cnt"
                                          "  FROM audit_log_consumer_cursor"
                                          " WHERE consumer IN (" ids ")")]))]
      (when (= found total)
        (db/exec-one! cfg
                      [(str "SELECT xid::text AS xid, seq"
                            "  FROM audit_log_consumer_cursor"
                            " WHERE consumer IN (" ids ")"
                            " ORDER BY xid ASC, seq ASC"
                            " LIMIT 1")])))))

(def ^:private max-xid
  "Any real row sorts before it: used when no consumer is enabled, so
  nothing will ever read the buffer and the GC can clean it whole."
  "18446744073709551615")

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

(defn- delete-batched
  "Run a batched DELETE until it removes nothing. Returns the total."
  [cfg xid seq]
  (loop [total 0]
    (let [n (db/get-update-count (db/exec-one! cfg [sql:delete-batch xid seq gc-batch-size]))]
      (if (pos? ^long (or n 0))
        (do
          (jobs/heartbeat cfg)
          (px/sleep 100)
          (recur (+ total ^long n)))
        total))))

(defn gc-audit-log
  "Delete the rows every enabled consumer has already passed. The
  position is the oldest enabled cursor, inclusive (the cursor points at
  the last row the consumer processed); a consumer that is off does not
  hold the buffer (its cursor freezes and rejoins from wherever it is
  when it is enabled again: rows the GC removed under it are simply
  skipped).

  If an enabled consumer has no cursor yet, nothing is deleted for that
  run: a missing position is treated as 0 until the consumer creates it
  on its first drain (at the end of the buffer). With no consumer
  enabled, nothing will read the buffer, so it is cleaned whole
  (telemetry shadows excluded).

  Returns the number of discarded rows; they count in the `discarded`
  metric with reason `gc`."
  [cfg]
  (let [consumer-ids (consumers/enabled-consumers)
        cursor       (if (seq consumer-ids)
                       (get-min-cursor cfg consumer-ids)
                       {:xid max-xid :seq Long/MAX_VALUE})]
    (if-let [cursor cursor]
      (let [total (delete-batched cfg (:xid cursor) (:seq cursor))]
        (when (pos? total)
          (consumers/record-count (mtx/instance cfg) :audit-consumer-discarded ["gc"] total))
        total)
      0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:audit-log-gc-params
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
   ::jobs/schema    schema:audit-log-gc-params
   ::jobs/handler
   (fn [_context _params]
     (let [total (gc-audit-log cfg)]
       (l/dbg :hint "audit log buffer cleaned" :discarded total)
       total))
   ::jobs/decoder   (sm/decoder schema:audit-log-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-gc-params)})
