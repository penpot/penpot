-- Reset explicit `STORAGE EXTERNAL` settings back to the PostgreSQL defaults.
--
-- Those were set when PostgreSQL ran on top of ZFS with transparent
-- compression (see 0041-mod-pg-storage-options.sql and the per-table
-- migrations that followed it): skipping the TOAST compression done by
-- PostgreSQL made sense there. On regular filesystems it only wastes
-- space and I/O bandwidth, so restore the default storage strategy of
-- each column type (EXTENDED for every column below: text, text[],
-- jsonb and bytea).
--
-- This only touches the catalog, it does not rewrite the tables:
-- already stored TOAST values keep their strategy until the row is
-- rewritten, new writes use the default.
--
-- NOTE: DEFAULT restores the storage mode of the column's data type
-- (EXTENDED for every column below: text, text[], jsonb and bytea). It
-- requires PostgreSQL 16 or newer, which is the minimum version the
-- project supports (see the database section of the technical guide).
-- RESET STORAGE is not valid syntax for a column.

ALTER TABLE access_token
  ALTER COLUMN name SET STORAGE DEFAULT,
  ALTER COLUMN perms SET STORAGE DEFAULT,
  ALTER COLUMN token SET STORAGE DEFAULT;

ALTER TABLE audit_log
  ALTER COLUMN context SET STORAGE DEFAULT,
  ALTER COLUMN name SET STORAGE DEFAULT,
  ALTER COLUMN props SET STORAGE DEFAULT,
  ALTER COLUMN source SET STORAGE DEFAULT,
  ALTER COLUMN type SET STORAGE DEFAULT;

ALTER TABLE comment
  ALTER COLUMN content SET STORAGE DEFAULT;

ALTER TABLE comment_thread
  ALTER COLUMN page_name SET STORAGE DEFAULT,
  ALTER COLUMN participants SET STORAGE DEFAULT;

ALTER TABLE file
  ALTER COLUMN data SET STORAGE DEFAULT,
  ALTER COLUMN data_backend SET STORAGE DEFAULT,
  ALTER COLUMN features SET STORAGE DEFAULT,
  ALTER COLUMN name SET STORAGE DEFAULT;

ALTER TABLE file_change
  ALTER COLUMN changes SET STORAGE DEFAULT,
  ALTER COLUMN data SET STORAGE DEFAULT,
  ALTER COLUMN features SET STORAGE DEFAULT,
  ALTER COLUMN label SET STORAGE DEFAULT;

ALTER TABLE file_data_fragment
  ALTER COLUMN metadata SET STORAGE DEFAULT;

ALTER TABLE file_media_object
  ALTER COLUMN mtype SET STORAGE DEFAULT,
  ALTER COLUMN name SET STORAGE DEFAULT;

ALTER TABLE file_thumbnail
  ALTER COLUMN data SET STORAGE DEFAULT,
  ALTER COLUMN props SET STORAGE DEFAULT;

ALTER TABLE global_complaint_report
  ALTER COLUMN content SET STORAGE DEFAULT,
  ALTER COLUMN type SET STORAGE DEFAULT;

ALTER TABLE profile
  ALTER COLUMN auth_backend SET STORAGE DEFAULT,
  ALTER COLUMN email SET STORAGE DEFAULT,
  ALTER COLUMN fullname SET STORAGE DEFAULT,
  ALTER COLUMN lang SET STORAGE DEFAULT,
  ALTER COLUMN password SET STORAGE DEFAULT,
  ALTER COLUMN props SET STORAGE DEFAULT,
  ALTER COLUMN theme SET STORAGE DEFAULT;

ALTER TABLE profile_complaint_report
  ALTER COLUMN content SET STORAGE DEFAULT,
  ALTER COLUMN type SET STORAGE DEFAULT;

ALTER TABLE project
  ALTER COLUMN name SET STORAGE DEFAULT;

ALTER TABLE scheduled_task
  ALTER COLUMN cron_expr SET STORAGE DEFAULT,
  ALTER COLUMN id SET STORAGE DEFAULT;

ALTER TABLE server_error_report
  ALTER COLUMN content SET STORAGE DEFAULT;

ALTER TABLE server_prop
  ALTER COLUMN content SET STORAGE DEFAULT,
  ALTER COLUMN id SET STORAGE DEFAULT;

ALTER TABLE storage_object
  ALTER COLUMN backend SET STORAGE DEFAULT,
  ALTER COLUMN metadata SET STORAGE DEFAULT;

ALTER TABLE task
  ALTER COLUMN error SET STORAGE DEFAULT,
  ALTER COLUMN label SET STORAGE DEFAULT,
  ALTER COLUMN name SET STORAGE DEFAULT,
  ALTER COLUMN props SET STORAGE DEFAULT,
  ALTER COLUMN queue SET STORAGE DEFAULT,
  ALTER COLUMN status SET STORAGE DEFAULT;

ALTER TABLE team
  ALTER COLUMN name SET STORAGE DEFAULT;

ALTER TABLE team_font_variant
  ALTER COLUMN font_family SET STORAGE DEFAULT,
  ALTER COLUMN font_style SET STORAGE DEFAULT;

ALTER TABLE team_invitation
  ALTER COLUMN email_to SET STORAGE DEFAULT,
  ALTER COLUMN role SET STORAGE DEFAULT;

ALTER TABLE usage_quote
  ALTER COLUMN target SET STORAGE DEFAULT;

ALTER TABLE webhook
  ALTER COLUMN error_code SET STORAGE DEFAULT,
  ALTER COLUMN mtype SET STORAGE DEFAULT,
  ALTER COLUMN secret_key SET STORAGE DEFAULT,
  ALTER COLUMN uri SET STORAGE DEFAULT;

ALTER TABLE webhook_delivery
  ALTER COLUMN error_code SET STORAGE DEFAULT,
  ALTER COLUMN req_data SET STORAGE DEFAULT,
  ALTER COLUMN rsp_data SET STORAGE DEFAULT;
