CREATE TABLE server_error_report (
  id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  content jsonb,

  PRIMARY KEY (id, created_at)
);

ALTER TABLE server_error_report
  ALTER COLUMN content SET STORAGE external;
