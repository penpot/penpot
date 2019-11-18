CREATE TABLE IF NOT EXISTS kvstore (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  key text NOT NULL,
  value bytea NOT NULL,

  PRIMARY KEY (key, user_id)
);

CREATE TRIGGER kvstore_modified_at_tgr BEFORE UPDATE ON kvstore
  FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
