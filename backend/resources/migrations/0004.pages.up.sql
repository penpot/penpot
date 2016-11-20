-- Tables

CREATE TABLE IF NOT EXISTS pages (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,
  version bigint DEFAULT 0,

  name text NOT NULL,
  data bytea NOT NULL,
  metadata bytea NOT NULL
);

CREATE TABLE IF NOT EXISTS pages_history (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),

  "user" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  page uuid NOT NULL REFERENCES pages(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL,
  modified_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,

  pinned bool NOT NULL DEFAULT false,
  label text NOT NULL DEFAULT '',
  data bytea NOT NULL
);

-- Indexes

CREATE INDEX pages_project_idx ON pages(project);
CREATE INDEX pages_user_idx ON pages("user");
CREATE INDEX pages_history_page_idx ON pages_history(page);
CREATE INDEX pages_history_user_idx ON pages_history("user");

-- Triggers

CREATE OR REPLACE FUNCTION handle_page_update()
  RETURNS TRIGGER AS $pagechange$
  BEGIN
    --- Update projects modified_at attribute when a
    --- page of that project is modified.
    UPDATE projects SET modified_at = clock_timestamp()
      WHERE id = OLD.project;

    --- Register a new history entry if the data
    --- property is changed.
    IF (OLD.data != NEW.data) THEN
      INSERT INTO pages_history (page, "user", created_at,
                                 modified_at, data, version)
        VALUES (OLD.id, OLD."user", OLD.modified_at,
                OLD.modified_at, OLD.data, OLD.version);
    END IF;

    RETURN NEW;
  END;
$pagechange$ LANGUAGE plpgsql;

CREATE TRIGGER page_on_update_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE handle_page_update();

CREATE TRIGGER page_occ_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE handle_occ();

CREATE TRIGGER pages_modified_at_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER pages_history_modified_at_tgr BEFORE UPDATE ON pages
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
