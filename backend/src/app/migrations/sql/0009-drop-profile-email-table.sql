DROP INDEX profile_email__profile_id__idx;
DROP INDEX profile_email__email__idx;
DROP TABLE profile_email;

ALTER TABLE profile
  ADD COLUMN pending_email text NULL;
