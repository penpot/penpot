-- Tables

CREATE TABLE IF NOT EXISTS projects (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS projects_users (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  role text NOT NULL,

  PRIMARY KEY (user_id, project_id)
);

-- Indexes

CREATE INDEX projects_user_idx ON projects(user_id);
CREATE INDEX projects_users_user_id_idx ON projects_users(project_id);
CREATE INDEX projects_users_project_id_idx ON projects_users(user_id);

-- Triggers

CREATE OR REPLACE FUNCTION handle_project_insert()
  RETURNS TRIGGER AS $$
  BEGIN
    INSERT INTO projects_users (user_id, project_id, role)
    VALUES (NEW.user_id, NEW.id, 'owner');

    RETURN NEW;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER projects_on_insert_tgr
 AFTER INSERT ON projects
   FOR EACH ROW EXECUTE PROCEDURE handle_project_insert();

CREATE TRIGGER projects_modified_at_tgr
BEFORE UPDATE ON projects
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
