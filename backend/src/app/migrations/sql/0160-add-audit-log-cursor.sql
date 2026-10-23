-- Consumer positions instead of a pending queue: each consumer keeps a
-- cursor over audit_log, and the row is deleted when every enabled
-- consumer has passed it.
--
-- `seq` is the insertion order (a sequence, so it costs no extra WAL);
-- `xid` is the transaction that wrote the row. The pair gives a total
-- order that real commits respect: the consumer reads only up to
-- pg_snapshot_xmin(), the oldest live transaction, so it never steps
-- over a row whose transaction is still open. `xid8` (64 bits) does not
-- wrap, unlike the row `xmin` (32 bits); it exists since PostgreSQL 13.

CREATE SEQUENCE IF NOT EXISTS audit_log_seq_seq;

ALTER TABLE audit_log
  ADD COLUMN seq bigint,
  -- Fast default (no table rewrite): pre-existing rows belong to
  -- transactions that already finished, and 0 sorts before every real
  -- xid8. They are never processed: the cursor starts after them.
  ADD COLUMN xid xid8 NOT NULL DEFAULT '0'::xid8;

-- Backfill in batches: the buffer is small in normal operation, but a
-- backlog left by an incident must not hold one long update and one
-- long lock on the whole table.
DO $$
DECLARE
  moved integer;
BEGIN
  LOOP
    UPDATE audit_log
       SET seq = nextval('audit_log_seq_seq')
     WHERE id IN (SELECT id FROM audit_log WHERE seq IS NULL LIMIT 10000);
    GET DIAGNOSTICS moved = ROW_COUNT;
    EXIT WHEN moved = 0;
  END LOOP;
END $$;

ALTER TABLE audit_log
  ALTER COLUMN seq SET DEFAULT nextval('audit_log_seq_seq'),
  ALTER COLUMN seq SET NOT NULL,
  ALTER COLUMN xid SET DEFAULT pg_current_xact_id();

ALTER SEQUENCE audit_log_seq_seq OWNED BY audit_log.seq;

-- Covers both predicates of the scan: the cursor comparison and the
-- snapshot cut are Index Cond, so the planner can do an Index Only Scan
-- in (xid, seq) order.
CREATE UNIQUE INDEX audit_log__xid__seq__idx ON audit_log (xid, seq);

-- One row per consumer: its position, updated in the same transaction
-- that sends the chunk. Consumer ids live in app.loggers.audit.consumers
-- and are never reused: 1 = nexus, 2 = admin console, 3 = team storage.
CREATE TABLE audit_log_consumer_cursor (
  consumer   smallint    NOT NULL,
  xid        xid8        NOT NULL,
  seq        bigint      NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer));

-- The table starts empty on purpose: a migration cannot know which
-- consumers are enabled. Each consumer creates its position on its
-- first drain, at the current end of the buffer (it does not process
-- the history). Until then the GC treats the missing row as position 0
-- and deletes nothing for that consumer.

-- The old queue is dropped by 0161, in a later deployment: the rolling
-- upgrade keeps the previous code version inserting into it for a few
-- minutes.
