--- Drop duplicate indexes

DROP INDEX IF EXISTS project_profile_rel__project_id__idx;
DROP INDEX IF EXISTS project_profile_rel__profile_id__idx;
