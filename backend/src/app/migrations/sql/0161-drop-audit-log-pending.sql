-- The cursor over audit_log (0160) replaces the pending queue: the
-- producer only inserts the row and each consumer keeps its position.
-- The pending table is dropped; the migration file stays separate from
-- 0160 so the schema change is easy to follow.

DROP TABLE IF EXISTS audit_log_pending;
