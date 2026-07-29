-- Add index on audit_log (source, created_at) to support efficient
-- queries for the telemetry batch collection mode.

CREATE INDEX IF NOT EXISTS audit_log__source__created_at__idx
    ON audit_log (source, created_at ASC);
