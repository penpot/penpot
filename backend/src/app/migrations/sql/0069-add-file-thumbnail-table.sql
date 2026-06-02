CREATE TABLE file_thumbnail (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  revn bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz NULL,
  data text NULL,
  props jsonb NULL,
  PRIMARY KEY(file_id, revn)
);

ALTER TABLE file_thumbnail
  ALTER COLUMN data SET STORAGE external,
  ALTER COLUMN props SET STORAGE external;
