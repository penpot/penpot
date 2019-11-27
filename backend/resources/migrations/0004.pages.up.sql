-- Tables

CREATE TABLE IF NOT EXISTS pages (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  ordering smallint,

  name text NOT NULL,
  data bytea NOT NULL,
  metadata bytea NOT NULL
);

CREATE TABLE IF NOT EXISTS pages_history (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  page_id uuid NOT NULL REFERENCES pages(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL,
  modified_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,

  pinned bool NOT NULL DEFAULT false,
  label text NOT NULL DEFAULT '',
  data bytea NOT NULL,
  metadata bytea NOT NULL
);

-- Indexes

CREATE INDEX pages_project_idx ON pages(project_id);
CREATE INDEX pages_user_idx ON pages(user_id);
CREATE INDEX pages_history_page_idx ON pages_history(page_id);
CREATE INDEX pages_history_user_idx ON pages_history(user_id);

-- Triggers

CREATE OR REPLACE FUNCTION handle_page_update()
  RETURNS TRIGGER AS $pagechange$
  BEGIN
    --- Update projects modified_at attribute when a
    --- page of that project is modified.
    UPDATE projects SET modified_at = clock_timestamp()
     WHERE id = OLD.project_id;

    RETURN NEW;
  END;
$pagechange$ LANGUAGE plpgsql;

CREATE TRIGGER page_on_update_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE handle_page_update();

CREATE TRIGGER pages_modified_at_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER pages_history_modified_at_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
