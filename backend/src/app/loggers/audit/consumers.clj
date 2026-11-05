;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers
  "The mechanics shared by the consumers of the audit log buffer.

  Two consumers read the events of the buffer, each one from its own
  position (a cursor) over `audit_log`:

  - the nexus consumer sends them to Nexus.
  - the admin-console consumer sends the allowlisted ones to the admin
    console.

  Each transport lives in its own namespace (`...consumers.nexus`,
  `...consumers.admin-console`); this one
  holds what they share: the consumer ids, the catalog, the cursor
  (lock, read, advance), the tick mutex, the loop and the metrics. The
  garbage collection of the buffer lives in `app.loggers.audit.gc`.

  A consumer is a loop in a thread, not a job: the pass takes the tick
  lock, drains up to the frontier it found at its start and sleeps. The
  position is the pair `(xid, seq)`: the transaction that wrote the row
  and its insertion order. A consumer reads only rows below
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
   [app.metrics :as mtx]
   [promesa.exec :as px]))

;; Consumer ids are fixed constants, never reuse one: a retired id keeps
;; meaning its old position in `audit_log_consumer_cursor`.
(def nexus 1)
(def admin-console 2)

(def ^:private consumers
  [{:id nexus         :flag :nexus         :label "nexus"}
   {:id admin-console :flag :admin-console :label "admin-console"}])

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

(defn enabled?
  "True when the flag of a consumer is on. The transport components use it
  to decide at boot whether to start the loop; the GC uses the registry to
  decide whose cursor it waits for."
  [consumer-id]
  (contains? cf/flags (get-in consumers-by-id [consumer-id :flag])))

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
  two passes of the same consumer serialize here, and no row lock on
  `audit_log` is needed (the GC never locks the cursor). The tick mutex
  is the advisory lock of `with-tick-lock`; this row lock is the safety
  net for a pass that bypasses the loop (a REPL against a running
  consumer, for instance).

  `xid::text` is the JDBC bridge, not a sorting choice: `xid8` is an
  unsigned 64-bit transaction counter with no Java type and no standard
  driver mapping, so it crosses as text and goes back to SQL as
  `?::xid8`. The alias is `xid_text` on purpose: an output column named
  `xid` would make `ORDER BY xid` sort the text."
  "SELECT xid::text AS xid_text, seq
     FROM audit_log_consumer_cursor
    WHERE consumer = ?::smallint
      FOR UPDATE")

(def ^:private sql:get-cursor
  "SELECT xid::text AS xid_text, seq
     FROM audit_log_consumer_cursor
    WHERE consumer = ?::smallint")

(def ^:private sql:ensure-cursor
  "A consumer with no cursor starts at the beginning of the buffer: it
  processes what the buffer holds, not only what arrives from now on.
  (0, 0) sits below any real position, so the first chunk includes the
  oldest row; the table starts empty because the migration cannot know
  which consumers are enabled. ON CONFLICT keeps an existing position
  untouched."
  "INSERT INTO audit_log_consumer_cursor (consumer, xid, seq)
   VALUES (?::smallint, '0'::xid8, 0)
   ON CONFLICT (consumer) DO NOTHING")

(defn ensure-cursor
  "Create the cursor of a consumer when it does not exist yet: at the
  beginning of the buffer, so the first pass drains what is there. The
  cursor means 'last row processed' and the chunk query is a strict
  `(xid, seq) > cursor`, so the starting position must sit *before* the
  oldest row: (0, 0) is below any real position and needs no extra read.
  Only the drain calls this; the GC treats a missing cursor as position
  0 and waits for the consumer to create it."
  [cfg consumer]
  (db/exec-one! cfg [sql:ensure-cursor consumer]))

(def ^:private sql:chunk
  "The next chunk of events for one consumer.

  The tuple comparison walks the `(xid, seq)` index from the cursor; the
  snapshot cut leaves out every row whose transaction is still in flight,
  and the watermark bounds the pass to what was visible when it started,
  so the pass always ends. The order is the insertion order that commits
  respect: a transaction with a lower `xid` committed (or aborted) before
  a higher one can be visible, and `seq` unties the rows of a single
  transaction.

  No `SKIP LOCKED` and no row lock: the tick mutex and the cursor lock
  already serialize the passes of this consumer, and the rows are only
  read."
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
      AND (a.xid, a.seq) <= (?::xid8, ?::bigint)
      AND a.xid <= pg_snapshot_xmin(pg_current_snapshot())
    ORDER BY a.xid ASC, a.seq ASC
    LIMIT ?")

(def ^:private sql:last-visible
  "The frontier of the pass: the newest row whose transaction is already
  committed, with the same visibility rule the chunk uses. The pass drains
  up to it, so it always ends and every chunk but the last is full.

  The `ORDER BY` names the table column: `xid` alone would resolve to the
  text projection (`xid_text`) and sort '999' above '1000'."
  "SELECT xid::text AS xid_text, seq
     FROM audit_log
    WHERE xid <= pg_snapshot_xmin(pg_current_snapshot())
    ORDER BY audit_log.xid DESC, seq DESC
    LIMIT 1")

(def ^:private sql:advance-cursor
  "UPDATE audit_log_consumer_cursor
      SET xid = ?::xid8, seq = ?::bigint, updated_at = now()
    WHERE consumer = ?::smallint")

(defn- lock-cursor
  [cfg consumer]
  (db/exec-one! cfg [sql:lock-cursor consumer]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TICK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private tick-lock-classid
  "Namespace of the two-key advisory locks of the consumers. The two-key
  form lives in its own lock space, disjoint from the one-key
  `pg_advisory_lock(bigint)` that `update-file` and the setup take, so a
  consumer tick can never block a file update (nor the other way around)
  even if the numbers coincide."
  730000)

(defn- tick-lock-key
  "The two-key advisory lock of one consumer: the namespace of the audit
  log and the consumer id."
  [consumer-id]
  [tick-lock-classid consumer-id])

(defn- with-tick-lock
  "Run `f` with the session advisory lock of the consumer held, passing it
  the connection that holds the lock; answer ::busy without running it
  when another instance is draining.

  The lock lives on a connection borrowed from the pool and is released
  before that connection goes back, because a pooled connection returned
  with a session lock keeps it. A lock an earlier pass failed to release
  is cleared when the connection is borrowed again, so one bad unlock
  cannot stop the consumer for good. A failed unlock is logged and
  swallowed: it must not replace the error of the pass that is unwinding."
  [{:keys [::db/pool]} consumer-id f]
  (with-open [conn (db/open pool)]
    (db/session-unlock-all conn)
    (let [[classid objid] (tick-lock-key consumer-id)]
      (if (db/session-try-lock conn classid objid)
        (try
          (f conn)
          (finally
            (try
              (db/session-unlock conn classid objid)
              (catch Throwable cause
                (l/wrn :hint "unable to release the audit-consumer lock"
                       :consumer (consumer-label consumer-id)
                       :cause cause)))))
        ::busy))))

(defn- get-chunk-rows
  [cfg cursor watermark]
  (->> (db/exec! cfg [sql:chunk
                      (:xid-text cursor) (:seq cursor)
                      (:xid-text watermark) (:seq watermark)
                      chunk-size])
       (not-empty)))

(defn- advance-cursor
  [{:keys [::db/conn]} consumer row]
  (db/exec-one! conn [sql:advance-cursor (:xid-text row) (:seq row) consumer]))

(defn- drain-chunks
  "Drain one consumer cursor chunk by chunk, one transaction per chunk, up
  to the watermark of the pass. The processor receives the rows of each
  chunk and returns {:processed n :skipped n}; the cursor advances past
  every row of the chunk in the same transaction, so a refusal (or a
  throw) rolls the advance back and the chunk is retried on the next pass.
  The send runs inside that transaction too: the `FOR UPDATE` on the
  cursor row assigns an xid, so a slow destination holds
  `pg_snapshot_xmin` back for every consumer until the chunk ends.
  `totals` is updated after each committed chunk, so a pass that aborts
  later still reports what it already archived. Answers the totals."
  [cfg conn consumer process-chunk watermark totals]
  (loop []
    ;; One transaction per chunk on the connection that holds the tick:
    ;; fetch, process and advance commit (or roll back) together, and the
    ;; next chunk sees a fresh snapshot. A pass needs no second connection.
    (if-let [res (db/tx-run! (-> cfg (dissoc ::db/pool) (assoc ::db/conn conn))
                             (fn [cfg]
                               (db/exec! cfg ["SET LOCAL lock_timeout = '30s'"])
                               (let [consumer-id (:id consumer)
                                     cursor      (or (lock-cursor cfg consumer-id)
                                                     (do (ensure-cursor cfg consumer-id)
                                                         (lock-cursor cfg consumer-id)))]
                                 (when-let [rows (get-chunk-rows cfg cursor watermark)]
                                   (let [res (process-chunk cfg rows)]
                                     (advance-cursor cfg consumer-id (peek rows))
                                     res)))))]
      (do
        ;; Committed: its rows count even if a later chunk fails.
        (vswap! totals (fn [{:keys [processed skipped chunks]}]
                         {:processed (+ (long processed) (long (or (:processed res) 0)))
                          :skipped   (+ (long skipped) (long (or (:skipped res) 0)))
                          :chunks    (inc (long chunks))}))
        (px/sleep 100)
        (recur))
      @totals)))

(def ^:private sql:lag
  "Events confirmed in the buffer that this consumer has not processed
  yet, capped: past 10000 the gauge reads 10000, so a long backlog does
  not turn every pass into an O(backlog) count. It rises when the stream
  falls behind (a slow destination, or a big transaction that just
  committed); while a transaction is open its rows are invisible to
  everybody, so they cannot be counted here."
  "SELECT count(*) AS lag
     FROM (SELECT 1
             FROM audit_log a
            WHERE (a.xid, a.seq) > (?::xid8, ?::bigint)
            LIMIT 10000) s")

(defn- record-lag
  [conn metrics consumer]
  (when-let [cursor (db/exec-one! conn [sql:get-cursor (:id consumer)])]
    (let [lag (:lag (db/exec-one! conn [sql:lag (:xid-text cursor) (:seq cursor)]))]
      (mtx/run! metrics
                :id :audit-consumer-lag
                :labels [(consumer-label (:id consumer))]
                :val lag))))

(defn- record-totals
  "Record the event counters of the chunks a pass committed. Called on the
  happy path and from the catch, so a pass that aborts on chunk N still
  counts chunks 1..N-1."
  [metrics label {:keys [processed skipped]}]
  (when (pos? processed)
    (record-count metrics :audit-consumer-events [label "processed"] processed))
  (when (pos? skipped)
    (record-count metrics :audit-consumer-events [label "skipped"] skipped)))

(defn drain-consumer
  "Run one pass of a consumer: take the tick lock, drain its cursor chunk by
  chunk (one transaction per chunk) up to the frontier of the pass, and
  record the summary. Answers ::busy without doing anything when another
  instance is draining this consumer, and ::disabled without touching the
  data when the consumer's flag is off: the loop is gated at boot, but a
  direct caller (REPL, tests, a helper) must not advance a cursor or send
  events for a consumer the deployment has disabled.

  The processor receives the rows of each chunk and returns
  {:processed n :skipped n}; the cursor advances past every row of the
  chunk, so a selective consumer still advances over the events it does
  not take (they are counted as skipped, not lost: the next chunk
  continues from there).

  The frontier is the newest visible row when the pass starts, so the pass
  always ends and a row committed while it runs waits for the next one. A
  refused chunk (or a throw) aborts the pass: the committed chunks stay
  processed, the rest waits for the next pass. The whole pass runs on the
  connection that holds the tick (frontier, chunk transactions and lag), so
  the pool needs one connection per enabled consumer. The metrics land
  here, outside every chunk transaction: `penpot_audit_consumer_passes_total`
  counts `ok` (drained or empty), `busy` (another instance holds the tick)
  and `failed` (anything the pass throws: the frontier read, a chunk or the
  lag query), so a failure cannot leave the family flat."
  [cfg consumer-id process-chunk]
  (cond
    (not (contains? consumers-by-id consumer-id))
    (ex/raise :type :internal
              :code :unknown-audit-consumer
              :hint "unknown audit-log consumer"
              :consumer consumer-id)

    (not (enabled? consumer-id))
    ::disabled

    :else
    (let [consumer (get consumers-by-id consumer-id)
          label    (consumer-label consumer-id)
          metrics  (mtx/instance cfg)
          totals   (volatile! {:processed 0 :skipped 0 :chunks 0})
          result   (with-tick-lock cfg consumer-id
                     (fn [conn]
                       (try
                         (if-let [watermark (db/exec-one! conn [sql:last-visible])]
                           (let [tpoint    (ct/tpoint)
                                 drained   (drain-chunks cfg conn consumer process-chunk watermark totals)
                                 _         (record-lag conn metrics consumer)
                                 _         (record-totals metrics label drained)
                                 _         (record-count metrics :audit-consumer-passes [label "ok"] 1)
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
                                      :chunks chunks)))
                           ;; Nothing was visible when the pass started: no chunk query.
                           (do
                             (record-lag conn metrics consumer)
                             (record-count metrics :audit-consumer-passes [label "ok"] 1)
                             (l/dbg :hint "no new audit events"
                                    :consumer label)
                             nil))
                         (catch Throwable cause
                           ;; The counter covers the whole pass: a failed frontier
                           ;; read or lag query counts as a failed pass too, not
                           ;; only a refused chunk.
                           (record-totals metrics label @totals)
                           (record-count metrics :audit-consumer-passes [label "failed"] 1)
                           ;; The failed event count comes from the error data: the
                           ;; send fns carry the attempted events as :events. A local
                           ;; DB failure carries none and is not counted as a
                           ;; destination failure.
                           (let [n (:events (ex-data cause))]
                             (when (and (integer? n) (pos? n))
                               (record-count metrics :audit-consumer-events [label "failed"] n)))
                           (throw cause)))))]
      (if (= ::busy result)
        (do
          (record-count metrics :audit-consumer-passes [label "busy"] 1)
          ::busy)
        result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOOP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sleep-ms
  "Pause after every pass. Long enough to keep the database quiet while the
  buffer is empty, short enough that an event waits seconds, not minutes."
  5000)

(defn start-loop
  "Start the daemon thread of one consumer: a pass, a pause, forever.

  `run-pass` gets the cfg and may throw: the loop logs the failure and
  keeps going, because a consumer that stops on the first refused chunk
  stops for good. Answers the thread, to be interrupted on halt."
  [cfg consumer-id run-pass]
  (let [label (consumer-label consumer-id)]
    (px/thread
      {:name (str "penpot/audit-consumer/" label)}
      (l/inf :hint "started" :consumer label)
      (try
        (loop []
          (when (px/interrupted?)
            (throw (InterruptedException. "interrupted")))

          (try
            (run-pass cfg)
            (catch InterruptedException cause
              (throw cause))
            (catch Throwable cause
              (l/err :hint "consumer pass failed, retrying after the pause"
                     :consumer label
                     :cause cause)))

          (px/sleep sleep-ms)
          (recur))

        (catch InterruptedException _
          (l/dbg :hint "interrupted" :consumer label))
        (catch Throwable cause
          (l/err :hint "unexpected exception" :consumer label :cause cause))
        (finally
          (l/inf :hint "terminated" :consumer label))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init-component
  "Start the loop of a consumer when its flag is on and the database is
  writable; nil otherwise. `prepare` runs only on the start path and
  answers the cfg the pass will use (Nexus resolves its uri there)."
  [cfg consumer-id prepare run-pass]
  (cond
    (not (enabled? consumer-id))
    (l/dbg :hint "not started (flag off)" :consumer (consumer-label consumer-id))

    (db/read-only? (::db/pool cfg))
    (l/wrn :hint "not started (db is read-only)" :consumer (consumer-label consumer-id))

    :else
    (start-loop (prepare cfg) consumer-id run-pass)))

(defn halt-component
  "Stop the loop of a consumer and wait for it. Tolerates the nil an init
  that skipped the start answers."
  [thread]
  (when thread
    (px/interrupt! thread)
    (.join ^Thread thread 5000)))
