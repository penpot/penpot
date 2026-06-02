CREATE TABLE file_data_fragment (
  id uuid NOT NULL,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,

  created_at timestamptz NOT NULL DEFAULT now(),

  metadata jsonb NULL,
  content bytea NOT NULL,

  PRIMARY KEY (file_id, id)
);

ALTER TABLE file_data_fragment
  ALTER COLUMN metadata SET STORAGE external,
  ALTER COLUMN content SET STORAGE external;
