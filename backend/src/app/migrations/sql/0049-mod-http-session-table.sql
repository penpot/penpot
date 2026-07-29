ALTER TABLE http_session
  ADD COLUMN updated_at timestamptz NULL;

CREATE INDEX http_session__updated_at__idx
    ON http_session (updated_at)
 WHERE updated_at IS NOT NULL;
