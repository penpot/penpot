-- Migration: Replace uuid_generate_v4() defaults with gen_random_uuid()
-- and remove uuid-ossp extension.
--
-- gen_random_uuid() is built into PostgreSQL >= 13 and requires no extension.
-- The application already generates IDs explicitly via uuid/next in all
-- code paths; this migration adds gen_random_uuid() as a safety-net default
-- instead of the extension-dependent uuid_generate_v4().

ALTER TABLE access_token             ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE audit_log                ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE comment                  ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE comment_thread           ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE file                     ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE file_change              ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE file_media_object        ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE profile                  ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE project                  ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE project_profile_rel      ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE scheduled_task_history   ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE share_link               ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE storage_object           ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE task                     ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team                     ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team_access_request      ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team_font_variant        ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team_invitation          ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team_profile_rel         ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE team_project_profile_rel ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE usage_quote              ALTER COLUMN id SET DEFAULT gen_random_uuid();
