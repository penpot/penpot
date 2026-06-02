ALTER TABLE project_profile_rel DROP CONSTRAINT project_profile_rel_pkey;
ALTER TABLE project_profile_rel ADD COLUMN id uuid DEFAULT uuid_generate_v4() PRIMARY KEY;
ALTER TABLE project_profile_rel ADD CONSTRAINT project_profile_rel_unique UNIQUE (project_id, profile_id);
