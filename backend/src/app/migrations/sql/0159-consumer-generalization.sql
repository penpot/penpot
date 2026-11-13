-- Consumer generalization: the schema of the whole feature in one step.
--
-- This single migration replaces the steps this branch used to ship
-- separately (exclusive jobs, the audit cursor and dedupe jobs). The
-- migration runner executes every pending step inside ONE transaction, so
-- several files bought no isolation: their locks piled up until the same
-- commit, and pre-applying one of them out of band did not avoid the
-- others.
--
-- The pending-queue step that sat between them never shipped and is gone:
-- released versions never created `audit_log_pending`, so this migration
-- creates neither the table nor the `(created_at, id)` index it added. The
-- table itself is dropped below, for a pre-release database that happens
-- to have it.
--
-- The legacy mark (`archived_at`) and the indexes that served it stay in
-- place on purpose: the old archiver may still be running during the
-- rollout, and a later release removes them. This migration does not build
-- the `(created_at, id)` index either; the consumers order by `(xid, seq)`
-- and telemetry keeps its `(source, created_at)` index plus a sort.
--
-- The migration is idempotent: every statement is IF NOT EXISTS / IF
-- EXISTS, so it is safe to re-run. Applying it out of band does not make
-- it free, though: the guards turn the statements into no-ops, not into
-- lock-free ones (an ALTER still takes ACCESS EXCLUSIVE and the backfill
-- still scans `audit_log`), so the operator registers the step in
-- `migrations` once the schema is pre-applied (step 0 of the runbook,
-- `operations/2.20.0-consumer-generalization.md`).
--
-- The statements take the locks in the same order the audit-log producer
-- uses (audit_log, then audit_log_pending, then job), so a deploy that
-- still has the previous version running cannot deadlock against it: the
-- producer inserts the audit row, then its pending (when that version has
-- it), then submits the webhook job, and this migration walks the same
-- order. What it takes last (`job`) is what the producer takes last.

-- ---------------------------------------------------------------------
-- Audit log cursors: each consumer keeps a position over `audit_log`
-- instead of a pending queue, and the row is deleted when every enabled
-- consumer has passed it.
--
-- `seq` is the insertion order (a sequence, so it costs no extra WAL);
-- `xid` is the transaction that wrote the row. The pair gives a total
-- order that real commits respect: the consumer reads only up to
-- pg_snapshot_xmin(), the oldest live transaction, so it never steps
-- over a row whose transaction is still open. `xid8` (64 bits) does not
-- wrap, unlike the row `xmin` (32 bits); it exists since PostgreSQL 13.
-- ---------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS audit_log_seq_seq;

ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS seq bigint,
  -- Fast default (no table rewrite): the rows that already exist get 0
  -- and sort before every real xid8. They are the history, and the first
  -- deploy replays them (the consumers create their cursor at (0, 0) on
  -- their first pass).
  ADD COLUMN IF NOT EXISTS xid xid8 NOT NULL DEFAULT '0'::xid8;

ALTER SEQUENCE audit_log_seq_seq OWNED BY audit_log.seq;

-- Reserve the low range for the history: the new rows start above the
-- backfill, so any `ORDER BY seq` stays chronological and the backfill
-- can never collide with a value produced by nextval. The margin covers
-- the rows the previous version may insert between this count and the
-- SET DEFAULT (a few statements away).
--
-- Only the first application sets it: a sequence that was never called
-- (`last_value = 1` with `is_called = false`) is intact; any other state
-- (reserved by the operator, in use, or already reserved by a previous
-- run) must be left alone, because moving it back would reuse values above
-- the history and break the reserve.
SELECT setval('audit_log_seq_seq',
              (SELECT count(*) + 1000000 FROM audit_log),
              false)
 WHERE (SELECT last_value FROM audit_log_seq_seq) = 1
   AND NOT (SELECT is_called FROM audit_log_seq_seq);

-- From here no new row can be NULL: the previous version keeps inserting
-- during the rollout and must leave the columns filled.
ALTER TABLE audit_log
  ALTER COLUMN seq SET DEFAULT nextval('audit_log_seq_seq'),
  ALTER COLUMN xid SET DEFAULT pg_current_xact_id();

-- Backfill the history with 1..N, in the order the previous version sent
-- it (`created_at, id`), so the replay keeps the archive order. A single
-- statement: it takes row locks, never a table lock, so the previous
-- version keeps inserting and the statement can be interrupted and
-- re-run (it filters `seq IS NULL`). The sort is on purpose: the
-- `(created_at, id)` index belonged to the pending queue and never
-- shipped, so there is no index to serve this order.
UPDATE audit_log
   SET seq = ordered.rn
  FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
          FROM audit_log
         WHERE seq IS NULL) ordered
 WHERE audit_log.id = ordered.id;

ALTER TABLE audit_log
  ALTER COLUMN seq SET NOT NULL;

-- `source` is nullable since 0064 (its backfill only covered the rows of
-- that moment). The producer has written it ever since, but the GC excludes
-- telemetry shadows with `source NOT LIKE 'telemetry:%'`, and with a NULL
-- source that predicate is unknown: neither the GC nor the telemetry task
-- would ever delete such a row. Fill what is left and close the column.
UPDATE audit_log SET source = 'backend' WHERE source IS NULL;

ALTER TABLE audit_log
  ALTER COLUMN source SET NOT NULL;

-- Covers both predicates of the scan: the cursor comparison and the
-- snapshot cut are Index Cond, so the index serves the range and the
-- (xid, seq) order of the scan.
CREATE UNIQUE INDEX IF NOT EXISTS audit_log__xid__seq__idx
    ON audit_log (xid, seq);

-- One row per consumer: its position, updated in the same transaction
-- that sends the chunk. Consumer ids live in app.loggers.audit.consumers
-- and are never reused: 1 = nexus, 2 = admin console.
--
-- The table starts empty on purpose: the migration does not know which
-- consumers are enabled. `ensure-cursor` creates the row at (0, 0) on a
-- consumer's first pass, so the first deploy **replays** the unarchived
-- buffer instead of dropping it (the old archiver is disabled and the
-- already-archived rows are deleted before deploying, see the NOTE).
CREATE TABLE IF NOT EXISTS audit_log_consumer_cursor (
  consumer   smallint    NOT NULL,
  xid        xid8        NOT NULL,
  seq        bigint      NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer));

-- ---------------------------------------------------------------------
-- The old pending queue never shipped: this release's producer does not
-- fill it, and no released version ever created the table. Only a
-- pre-release database that ran the pending-queue step has it, and that
-- one may keep rows left behind. Drop the table: after the rollout nobody
-- drains the queue, so a pending row would make the new GC fail on every
-- pass when it deletes the row it references. A released database does
-- not have it, so this is a no-op there.
-- ---------------------------------------------------------------------

DROP TABLE IF EXISTS audit_log_pending;

-- ---------------------------------------------------------------------
-- Jobs: at most one live exclusive job and at most one not-started
-- dedupe job per (tenant, name, queue, label).
--
-- `exclusive` marks the row of a `::jobs/skip-if-active` submit, and the
-- index admits a single live row (new, scheduled, running or retry) for
-- its key. `dedupe` marks a `::jobs/dedupe`/`::jobs/debounce` submit, and
-- its index admits a single `new` row. The submit no longer counts or
-- deletes before inserting: the partial unique index decides and the
-- insert resolves the conflict with `ON CONFLICT`, so the exclusion holds
-- across backends.
--
-- Both columns default to false, so the rows that never asked for the
-- option stay out of the indexes (the label stays a label) and the
-- indexes start empty: no cleanup of duplicates is needed.
-- ---------------------------------------------------------------------

ALTER TABLE job
    ADD COLUMN IF NOT EXISTS exclusive boolean NOT NULL DEFAULT false;

ALTER TABLE job
    ADD COLUMN IF NOT EXISTS dedupe boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS job__live_exclusive__uniq
    ON job (tenant, name, queue, label)
    WHERE exclusive
      AND status IN ('new', 'scheduled', 'running', 'retry');

CREATE UNIQUE INDEX IF NOT EXISTS job__new_dedupe__uniq
    ON job (tenant, name, queue, label)
    WHERE dedupe
      AND status = 'new';

-- The plain index over (tenant, name, queue, label) served the old cron
-- no-overlap probe. Nothing reads it now: the cron resolves the overlap at
-- insert time with a `::jobs/skip-if-active` submit (that is,
-- `job__live_exclusive__uniq`), which is one index instead of a probe plus
-- a race. Bases built from an early 0154 drop it here.
DROP INDEX IF EXISTS job__name_label__idx;

-- The lost sweep looks for `scheduled` rows by their hand-off time
-- (`modified_at`), not by their due time: the old index served the removed
-- reschedule and only narrows by tenant now. The `job` table is not in
-- production yet, so the rebuild does not need CONCURRENTLY.
DROP INDEX IF EXISTS job__scheduled__idx;

CREATE INDEX IF NOT EXISTS job__scheduled__idx
    ON job (tenant, modified_at)
 WHERE status = 'scheduled';

-- ---------------------------------------------------------------------
-- NOTE: on an instance with a large `job` or `audit_log` table, apply this
-- migration out of band before deploying. The runner executes all pending
-- steps in a single transaction, so the plain indexes below hold a SHARE
-- lock on the table for the whole build and every ALTER keeps its ACCESS
-- EXCLUSIVE until that one commit; on tables that keep taking writes (every
-- file save inserts a job and an audit row) that is minutes of stalled
-- writes.
--
-- The complete procedure (lock timeouts, the `CHECK NOT VALID` dance, the
-- `indisvalid` gate, registering the step and the optional post-rollout
-- drop for a pre-release database) lives in
-- `operations/2.20.0-consumer-generalization.md`. Pre-applying does not make
-- the migration free by itself: the guards skip the work, not the locks, so
-- registering the step is what makes the runner skip the group.
--
-- The new consumers read only rows below
-- `pg_snapshot_xmin(pg_current_snapshot())`, the oldest transaction of the
-- whole database: a long write transaction (a binfile import runs in one for
-- up to 30 minutes) pauses archiving and the GC until it commits. Nothing is
-- lost and the lag gauge shows it; it is not a stuck consumer.
