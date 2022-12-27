ALTER TABLE team_project_profile_rel DROP CONSTRAINT team_project_profile_rel_pkey;
ALTER TABLE team_project_profile_rel ADD COLUMN id uuid DEFAULT uuid_generate_v4() PRIMARY KEY;
ALTER TABLE team_project_profile_rel ADD CONSTRAINT team_project_profile_rel_unique UNIQUE (team_id, project_id, profile_id);
