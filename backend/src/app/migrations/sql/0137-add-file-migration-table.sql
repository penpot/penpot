CREATE TABLE file_migration (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY(file_id, name)
);
