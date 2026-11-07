-- Work queue for the audit log consumers: one row per (record,
-- consumer) pair still pending. Inserting a record creates its
-- pendings; each consumer deletes its own on completion, and whoever
-- deletes the last pending also deletes the record. Consumer ids are
-- fixed in code and never reused: 1 = nexus, 2 = admin console.

CREATE TABLE audit_log_pending (
  -- DEFERRABLE so the test-suite reset (which deletes table by table
  -- with SET CONSTRAINTS ALL DEFERRED) keeps working; the guard still
  -- fires when a record with pendings is deleted. Plain DEFERRABLE
  -- (initially immediate) is enough: every deleter removes pendings
  -- before rows, so the check passes at each statement.
  audit_log_id uuid     NOT NULL REFERENCES audit_log(id) DEFERRABLE,
  consumer     smallint NOT NULL CHECK (consumer BETWEEN 1 AND 20),
  PRIMARY KEY (consumer, audit_log_id));

-- Lets the final delete ask "is anything still pending for this
-- record?" without scanning the whole queue.
CREATE INDEX ON audit_log_pending (audit_log_id);

-- NOTE: no backfill on purpose. Rows predating this queue (sent or
-- not) get no pendings, so the orphan sweep deletes them unsent.
-- Losing the unarchived ones at upgrade time is accepted: this
-- table is a minutes/hours buffer, not a store.

-- The pending queue replaces the archived_at mark: whoever deletes
-- the last pending deletes the record, so the flag column and the
-- indexes that served the old mark-and-sweep scans go away.
DROP INDEX IF EXISTS audit_log__created_at__idx;
DROP INDEX IF EXISTS audit_log__archived_at__idx;
DROP INDEX IF EXISTS audit_log_id_archived_at_idx;
ALTER TABLE audit_log DROP COLUMN IF EXISTS archived_at;

-- Serves the consumers' chronological scan (ORDER BY created_at, id):
-- one total order for every consumer, so concurrent drains cannot
-- deadlock on row locks. Covers the whole ORDER BY (bulk ingest
-- shares request-at constantly, so ties are the norm).
CREATE INDEX IF NOT EXISTS audit_log__created_at__idx
    ON audit_log (created_at ASC, id);
