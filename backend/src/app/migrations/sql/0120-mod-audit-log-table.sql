CREATE TABLE new_audit_log (LIKE audit_log INCLUDING ALL);
INSERT INTO new_audit_log SELECT * FROM audit_log;
ALTER TABLE audit_log RENAME TO old_audit_log;
ALTER TABLE new_audit_log RENAME TO audit_log;
DROP TABLE old_audit_log;

DROP INDEX new_audit_log_id_archived_at_idx;
ALTER TABLE audit_log DROP CONSTRAINT new_audit_log_pkey;
ALTER TABLE audit_log ADD PRIMARY KEY (id);
ALTER TABLE audit_log ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE audit_log ALTER COLUMN tracked_at SET DEFAULT now();
