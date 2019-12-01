-- Table

CREATE TABLE IF NOT EXISTS projects (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz DEFAULT NULL,

  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS projects_roles (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  role text NOT NULL,

  PRIMARY KEY (user_id, project_id)
);

CREATE TABLE IF NOT EXISTS project_shares (
  project_id uuid PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  token text NOT NULL
);

-- Indexes

CREATE INDEX projects_user_idx ON projects(user_id);
CREATE INDEX projects_roles_user_id_idx ON projects_roles(project_id);
CREATE INDEX projects_roles_project_id_idx ON projects_roles(user_id);

CREATE UNIQUE INDEX projects_shares_token_idx ON project_shares(token);

-- Triggers

CREATE TRIGGER projects_modified_at_tgr
BEFORE UPDATE ON projects
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();

CREATE TRIGGER project_shares_modified_at_tgr
BEFORE UPDATE ON project_shares
   FOR EACH ROW EXECUTE PROCEDURE update_modified_at();
