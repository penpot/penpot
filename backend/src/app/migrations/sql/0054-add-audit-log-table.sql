CREATE TABLE audit_log (
   id uuid NOT NULL DEFAULT uuid_generate_v4(),

   name text NOT NULL,
   type text NOT NULL,

   created_at timestamptz DEFAULT clock_timestamp() NOT NULL,
   archived_at timestamptz NULL,

   profile_id uuid NOT NULL,
   props jsonb,

   PRIMARY KEY (created_at, profile_id)
) PARTITION BY RANGE (created_at);

ALTER TABLE audit_log
  ALTER COLUMN name SET STORAGE external,
  ALTER COLUMN type SET STORAGE external,
  ALTER COLUMN props SET STORAGE external;

CREATE INDEX audit_log_id_archived_at_idx ON audit_log (id, archived_at);

CREATE TABLE audit_log_default (LIKE audit_log INCLUDING ALL);

ALTER TABLE audit_log ATTACH PARTITION audit_log_default DEFAULT;
