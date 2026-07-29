CREATE INDEX audit_log__created_at__idx ON audit_log(created_at) WHERE archived_at IS NULL;
CREATE INDEX audit_log__archived_at__idx ON audit_log(archived_at) WHERE archived_at IS NOT NULL;
