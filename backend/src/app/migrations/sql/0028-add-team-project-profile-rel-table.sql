CREATE TABLE team_project_profile_rel (
  team_id    uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE,
  profile_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  is_pinned boolean NOT NULL DEFAULT false,

  PRIMARY KEY (team_id, profile_id, project_id)
);
