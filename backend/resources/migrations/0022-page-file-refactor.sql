ALTER TABLE file
  ADD COLUMN revn bigint NOT NULL DEFAULT 0,
  ADD COLUMN data bytea NULL;

CREATE TABLE file_change (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  session_id uuid NULL DEFAULT NULL,
  revn bigint NOT NULL DEFAULT 0,
  data bytea NOT NULL,
  changes bytea NULL DEFAULT NULL
);

CREATE TABLE file_share_token (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  page_id uuid NOT NULL,
  token   text NOT NULL,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_id, token)
);

CREATE INDEX page_change_file_id_idx
    ON file_change(file_id);

CREATE FUNCTION handle_file_update()
  RETURNS TRIGGER AS $pagechange$
  DECLARE
    current_dt timestamptz := clock_timestamp();
  BEGIN
    NEW.modified_at := current_dt;

    --- Update projects modified_at attribute when a
    --- page of that project is modified.
    UPDATE project
       SET modified_at = current_dt
     WHERE id = OLD.project_id;

    RETURN NEW;
  END;
$pagechange$ LANGUAGE plpgsql;

CREATE TRIGGER file_on_update_tgr
BEFORE UPDATE ON file
   FOR EACH ROW EXECUTE PROCEDURE handle_file_update();

