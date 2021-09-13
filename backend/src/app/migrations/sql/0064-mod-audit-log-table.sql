ALTER TABLE audit_log
  ADD COLUMN tracked_at timestamptz NULL DEFAULT clock_timestamp(),
  ADD COLUMN source text NULL,
  ADD COLUMN context jsonb NULL;

ALTER TABLE audit_log
  ALTER COLUMN source SET STORAGE external,
  ALTER COLUMN context SET STORAGE external;

UPDATE audit_log SET source = 'backend', tracked_at=created_at;

-- ALTER TABLE audit_log ALTER COLUMN source SET NOT NULL;
-- ALTER TABLE audit_log ALTER COLUMN tracked_at SET NOT NULL;
