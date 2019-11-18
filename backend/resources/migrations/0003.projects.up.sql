-- Table

CREATE TABLE IF NOT EXISTS projects (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS project_shares (
  project uuid PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  token text
);

-- Indexes

CREATE INDEX projects_user_idx
    ON projects(user_id);

CREATE UNIQUE INDEX projects_shares_token_idx
    ON project_shares(token);

-- Triggers

CREATE OR REPLACE FUNCTION handle_project_create()
  RETURNS TRIGGER AS $$
  DECLARE
    token text;
  BEGIN
    SELECT encode(digest(gen_random_bytes(128), 'sha256'), 'hex')
      INTO token;

    INSERT INTO project_shares (project, token)
    VALUES (NEW.id, token);

    RETURN NEW;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_on_create_tgr
 AFTER INSERT ON projects
   FOR EACH ROW EXECUTE PROCEDURE handle_project_create();

CREATE TRIGGER projects_modified_at_tgr
BEFORE UPDATE ON projects
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER project_shares_modified_at_tgr
BEFORE UPDATE ON project_shares
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
