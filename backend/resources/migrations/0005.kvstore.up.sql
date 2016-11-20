CREATE TABLE IF NOT EXISTS kvstore (
  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  version bigint NOT NULL DEFAULT 0,

  key text NOT NULL,
  value bytea NOT NULL,

  PRIMARY KEY (key, "user")
);

CREATE TRIGGER kvstore_occ_tgr BEFORE UPDATE ON kvstore
   FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER kvstore_modified_at_tgr BEFORE UPDATE ON kvstore
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
