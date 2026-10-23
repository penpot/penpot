;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers
  "The mechanics shared by the consumers of the audit log buffer.

  Three consumers read the events of the buffer, each one from its own
  position (a cursor) over `audit_log`:

  - `:audit-log-consumer-nexus` sends them to Nexus.
  - `:audit-log-consumer-admin-console` sends the allowlisted ones to
    the admin console.
  - `:audit-log-consumer-team-storage` marks dirty the teams whose media
    storage may have changed (it lives in `app.tasks.team-storage`, its
    domain).

  Each transport lives in its own namespace (`...consumers.nexus`,
  `...consumers.admin-console`); this one holds what they share: the
  consumer ids, the catalog, the cursor (lock, read, advance), the
  garbage collection of the buffer and the metrics.

  The position is the pair `(xid, seq)`: the transaction that wrote the
  row and its insertion order. A consumer reads only rows below
  `pg_snapshot_xmin(pg_current_snapshot())` (the oldest transaction still
  in flight), so it never steps over a row whose transaction is still
  open. The producer does not know about consumers: it only inserts the
  row, and the GC deletes it once every enabled consumer has passed it."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [promesa.exec :as px]))

;; Consumer ids are fixed constants, never reuse one: a retired id keeps
;; meaning its old position in `audit_log_consumer_cursor`.
(def consumer-nexus 1)
(def consumer-admin-console 2)
(def consumer-team-storage 3)

(def ^:private consumers
  [{:id consumer-nexus         :flag :nexus         :label "nexus"}
   {:id consumer-admin-console :flag :admin-console :label "admin-console"}
   {:id consumer-team-storage  :flag :storage-usage :label "team-storage"}])

(def ^:private xf:enabled-consumers
  "Static transducer: keep the consumers whose flag is on. Allocated
  once so the drain and the GC do not rebuild it per call; the flag
  lookup itself still reads the live flags."
  (comp (filter #(contains? cf/flags (:flag %)))
        (map :id)))

(defn enabled-consumers
  "Consumers allowed to work right now, from their flags. Their cursors
  are the ones the GC waits for; a consumer that is off does not block
  the cleanup."
  []
  (into [] xf:enabled-consumers consumers))

(def ^:private consumers-by-id
  (into {} (map (juxt :id identity)) consumers))

(defn- consumer-label
  [consumer]
  (get-in consumers-by-id [consumer :label]))

(defn decode-row
  "Decode the transit-encoded columns of an audit-log row. The
  transports read the rows of their chunk, so this is shared."
  [{:keys [props ip-addr context] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))

    (db/pgobject? context)
    (assoc :context (db/decode-transit-pgobject context))

    (db/pgobject? ip-addr "inet")
    (assoc :ip-addr (db/decode-inet ip-addr))))

(def ^:private event-keys
  [:type
   :name
   :source
   :created-at
   :tracked-at
   :profile-id
   :ip-addr
   :props
   :context])

(defn row->event
  "The view of an audit-log row that travels to an archive: the decoded
  event alone, without the row id or the cursor columns."
  [row]
  (select-keys row event-keys))

(defn record-count
  "Record a counter of the consumers metrics."
  [metrics id labels amount]
  (mtx/run! metrics :id id :labels labels :inc amount))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CURSOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private chunk-size
  "Rows per chunk. Kept in a var so tests can shrink it."
  128)

(def ^:private sql:lock-cursor
  "Lock the position of one consumer for the duration of the chunk.
  The lock is on the cursor row, which takes part in no foreign key:
  two runs of the same consumer serialize here, and no row lock on
  `audit_log` is needed (the GC never locks the cursor). `xid::text`
  keeps the value portable: it goes back to SQL as `?::xid8`."
  "SELECT xid::text AS xid, seq
     FROM audit_log_consumer_cursor
    WHERE consumer = ?::smallint
      FOR UPDATE")

(def ^:private sql:get-cursor
  "SELECT xid::text AS xid, seq
     FROM audit_log_consumer_cursor
    WHERE consumer = ?::smallint")

(def ^:private sql:ensure-cursor
  "A consumer born after the migration starts at the current end of the
  buffer (it does not process the history), or at zero when the buffer
  is empty. ON CONFLICT keeps an existing position untouched."
  "INSERT INTO audit_log_consumer_cursor (consumer, xid, seq)
   SELECT ?::smallint,
          COALESCE((SELECT xid FROM audit_log ORDER BY xid DESC, seq DESC LIMIT 1),
                   '0'::xid8),
          COALESCE((SELECT seq FROM audit_log ORDER BY xid DESC, seq DESC LIMIT 1),
                   0)
   ON CONFLICT (consumer) DO NOTHING")

(defn ensure-cursor
  "Create the cursor of a consumer when it does not exist yet: at the
  end of the buffer, or at zero when the buffer is empty. Only the
  drain calls this; the GC treats a missing cursor as position 0 and
  waits for the consumer to create it."
  [cfg consumer]
  (db/exec-one! cfg [sql:ensure-cursor consumer]))

(def ^:private sql:chunk
  "The next chunk of events for one consumer.

  The tuple comparison walks the `(xid, seq)` index from the cursor;
  the snapshot cut leaves out every row whose transaction is still in
  flight. The order is the insertion order that commits respect: a
  transaction with a lower `xid` committed (or aborted) before a higher
  one can be visible, and `seq` unties the rows of a single transaction.

  No `SKIP LOCKED` and no row lock: the cursor lock already serializes
  the runs of this consumer, and the rows are only read."
  "SELECT a.id,
          a.name,
          a.type,
          a.source,
          a.created_at,
          a.tracked_at,
          a.profile_id,
          a.ip_addr,
          a.props,
          a.context,
          a.seq,
          a.xid::text AS xid_text
     FROM audit_log a
    WHERE (a.xid, a.seq) > (?::xid8, ?::bigint)
      AND a.xid <= pg_snapshot_xmin(pg_current_snapshot())
    ORDER BY a.xid ASC, a.seq ASC
    LIMIT ?")

(def ^:private sql:advance-cursor
  "UPDATE audit_log_consumer_cursor
      SET xid = ?::xid8, seq = ?::bigint, updated_at = now()
    WHERE consumer = ?::smallint")

(defn- lock-cursor
  [cfg consumer]
  (db/exec-one! cfg [sql:lock-cursor consumer]))

(defn- get-chunk-rows
  [cfg cursor]
  (->> (db/exec! cfg [sql:chunk (:xid cursor) (:seq cursor) chunk-size])
       (not-empty)))

(defn- advance-cursor
  [{:keys [::db/conn]} consumer row]
  (db/exec-one! conn [sql:advance-cursor (:xid-text row) (:seq row) consumer]))

(defn- drain-chunks
  "Drain one consumer cursor chunk by chunk, one transaction per chunk.
  The processor receives the rows of each chunk and returns
  {:processed n :skipped n}; the cursor advances past every row of the
  chunk in the same transaction, so a refusal (or a throw) rolls the
  advance back and the chunk is retried on the next tick. Returns
  totals; the committed chunks stay processed."
  [cfg consumer process-chunk]
  (loop [processed 0
         skipped   0
         chunks    0]
    ;; One transaction per chunk: fetch, process and advance commit (or
    ;; roll back) together, and the next chunk sees a fresh snapshot.
    (if-let [res (db/tx-run! cfg
                             (fn [cfg]
                               (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
                               (let [consumer-id (:id consumer)
                                     cursor      (or (lock-cursor cfg consumer-id)
                                                     (do (ensure-cursor cfg consumer-id)
                                                         (lock-cursor cfg consumer-id)))]
                                 (when-let [rows (get-chunk-rows cfg cursor)]
                                   (let [res (process-chunk cfg rows)]
                                     (advance-cursor cfg consumer-id (peek rows))
                                     res)))))]
      (do
        (jobs/heartbeat cfg)
        (px/sleep 100)
        (recur (+ processed ^long (:processed res))
               (+ skipped ^long (:skipped res 0))
               (inc chunks)))
      {:processed processed :skipped skipped :chunks chunks})))

(def ^:private sql:lag
  "Events confirmed in the buffer that this consumer has not processed
  yet. It rises when the stream falls behind (a slow destination, or a
  big transaction that just committed); while a transaction is open its
  rows are invisible to everybody, so they cannot be counted here."
  "SELECT count(*) AS lag
     FROM audit_log a
    WHERE (a.xid, a.seq) > (?::xid8, ?::bigint)")

(defn- record-lag
  [cfg consumer]
  (when-let [cursor (db/exec-one! cfg [sql:get-cursor (:id consumer)])]
    (let [lag (:lag (db/exec-one! cfg [sql:lag (:xid cursor) (:seq cursor)]))]
      (mtx/run! (mtx/instance cfg)
                :id :audit-consumer-lag
                :labels [(consumer-label (:id consumer))]
                :val lag))))

(defn drain-consumer
  "Run one consumer: drain its cursor chunk by chunk (one transaction
  per chunk) and record the summary. The processor receives the rows of
  each chunk and returns {:processed n :skipped n}; the cursor advances
  past every row of the chunk, so a selective consumer still advances
  over the events it does not take (they are counted as skipped, not
  lost: the next chunk continues from there).

  A refused chunk (or a throw) aborts the job: the committed chunks stay
  processed, the rest waits for the next tick, and the cron retries. The
  metrics land here, outside every chunk transaction."
  [cfg consumer-id process-chunk]
  (let [consumer   (or (get consumers-by-id consumer-id)
                       (ex/raise :type :internal
                                 :code :unknown-audit-consumer
                                 :hint "unknown audit-log consumer"
                                 :consumer consumer-id))
        label      (consumer-label consumer-id)
        metrics    (mtx/instance cfg)
        tpoint     (ct/tpoint)
        ;; The failed count comes from the error data: the send fns
        ;; carry the attempted events as :events. A local DB failure
        ;; carries none and is not counted as a destination failure.
        drained (try
                  (drain-chunks cfg consumer process-chunk)
                  (catch Throwable cause
                    (let [n (:events (ex-data cause))]
                      (when (and (integer? n) (pos? n))
                        (record-count metrics :audit-consumer-events [label "failed"] n)))
                    (throw cause)))
        _       (let [{:keys [processed skipped]} drained]
                  (when (pos? processed)
                    (record-count metrics :audit-consumer-events [label "processed"] processed))
                  (when (pos? skipped)
                    (record-count metrics :audit-consumer-events [label "skipped"] skipped)))
        _       (record-lag cfg consumer)
        processed (:processed drained)
        skipped   (:skipped drained)
        chunks    (:chunks drained)
        ;; Strict binding on purpose: log props evaluate lazily in
        ;; the logger thread, so the elapsed must be frozen here.
        elapsed   (ct/format-duration (tpoint))]
    (if (and (zero? processed)
             (zero? skipped))
      (l/dbg :hint "no new audit events"
             :consumer label)
      (l/dbg :hint "audit events processed"
             :consumer label
             :elapsed elapsed
             :processed processed
             :skipped skipped
             :chunks chunks))))
