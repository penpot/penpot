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
-- NOTE: `SET STORAGE DEFAULT` would express the same thing (it restores
-- the storage mode of the column's data type), but it only exists since
-- PostgreSQL 16 and the project also runs on PostgreSQL 15. Here every
-- column below has a type whose default storage mode is EXTENDED
-- (text, text[], jsonb and bytea), so `SET STORAGE EXTENDED` reaches the
-- same catalog state on any supported version. RESET STORAGE is not
-- valid syntax for a column.

ALTER TABLE access_token
  ALTER COLUMN name SET STORAGE EXTENDED,
  ALTER COLUMN perms SET STORAGE EXTENDED,
  ALTER COLUMN token SET STORAGE EXTENDED;

ALTER TABLE audit_log
  ALTER COLUMN context SET STORAGE EXTENDED,
  ALTER COLUMN name SET STORAGE EXTENDED,
  ALTER COLUMN props SET STORAGE EXTENDED,
  ALTER COLUMN source SET STORAGE EXTENDED,
  ALTER COLUMN type SET STORAGE EXTENDED;

ALTER TABLE comment
  ALTER COLUMN content SET STORAGE EXTENDED;

ALTER TABLE comment_thread
  ALTER COLUMN page_name SET STORAGE EXTENDED,
  ALTER COLUMN participants SET STORAGE EXTENDED;

ALTER TABLE file
  ALTER COLUMN data SET STORAGE EXTENDED,
  ALTER COLUMN data_backend SET STORAGE EXTENDED,
  ALTER COLUMN features SET STORAGE EXTENDED,
  ALTER COLUMN name SET STORAGE EXTENDED;

ALTER TABLE file_change
  ALTER COLUMN changes SET STORAGE EXTENDED,
  ALTER COLUMN data SET STORAGE EXTENDED,
  ALTER COLUMN features SET STORAGE EXTENDED,
  ALTER COLUMN label SET STORAGE EXTENDED;

ALTER TABLE file_data_fragment
  ALTER COLUMN metadata SET STORAGE EXTENDED;

ALTER TABLE file_media_object
  ALTER COLUMN mtype SET STORAGE EXTENDED,
  ALTER COLUMN name SET STORAGE EXTENDED;

ALTER TABLE file_thumbnail
  ALTER COLUMN data SET STORAGE EXTENDED,
  ALTER COLUMN props SET STORAGE EXTENDED;

ALTER TABLE global_complaint_report
  ALTER COLUMN content SET STORAGE EXTENDED,
  ALTER COLUMN type SET STORAGE EXTENDED;

ALTER TABLE profile
  ALTER COLUMN auth_backend SET STORAGE EXTENDED,
  ALTER COLUMN email SET STORAGE EXTENDED,
  ALTER COLUMN fullname SET STORAGE EXTENDED,
  ALTER COLUMN lang SET STORAGE EXTENDED,
  ALTER COLUMN password SET STORAGE EXTENDED,
  ALTER COLUMN props SET STORAGE EXTENDED,
  ALTER COLUMN theme SET STORAGE EXTENDED;

ALTER TABLE profile_complaint_report
  ALTER COLUMN content SET STORAGE EXTENDED,
  ALTER COLUMN type SET STORAGE EXTENDED;

ALTER TABLE project
  ALTER COLUMN name SET STORAGE EXTENDED;

ALTER TABLE scheduled_task
  ALTER COLUMN cron_expr SET STORAGE EXTENDED,
  ALTER COLUMN id SET STORAGE EXTENDED;

ALTER TABLE server_error_report
  ALTER COLUMN content SET STORAGE EXTENDED;

ALTER TABLE server_prop
  ALTER COLUMN content SET STORAGE EXTENDED,
  ALTER COLUMN id SET STORAGE EXTENDED;

ALTER TABLE storage_object
  ALTER COLUMN backend SET STORAGE EXTENDED,
  ALTER COLUMN metadata SET STORAGE EXTENDED;

ALTER TABLE task
  ALTER COLUMN error SET STORAGE EXTENDED,
  ALTER COLUMN label SET STORAGE EXTENDED,
  ALTER COLUMN name SET STORAGE EXTENDED,
  ALTER COLUMN props SET STORAGE EXTENDED,
  ALTER COLUMN queue SET STORAGE EXTENDED,
  ALTER COLUMN status SET STORAGE EXTENDED;

ALTER TABLE team
  ALTER COLUMN name SET STORAGE EXTENDED;

ALTER TABLE team_font_variant
  ALTER COLUMN font_family SET STORAGE EXTENDED,
  ALTER COLUMN font_style SET STORAGE EXTENDED;

ALTER TABLE team_invitation
  ALTER COLUMN email_to SET STORAGE EXTENDED,
  ALTER COLUMN role SET STORAGE EXTENDED;

ALTER TABLE usage_quote
  ALTER COLUMN target SET STORAGE EXTENDED;

ALTER TABLE webhook
  ALTER COLUMN error_code SET STORAGE EXTENDED,
  ALTER COLUMN mtype SET STORAGE EXTENDED,
  ALTER COLUMN secret_key SET STORAGE EXTENDED,
  ALTER COLUMN uri SET STORAGE EXTENDED;

ALTER TABLE webhook_delivery
  ALTER COLUMN error_code SET STORAGE EXTENDED,
  ALTER COLUMN req_data SET STORAGE EXTENDED,
  ALTER COLUMN rsp_data SET STORAGE EXTENDED;
