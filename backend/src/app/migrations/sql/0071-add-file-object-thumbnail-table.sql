CREATE TABLE file_object_thumbnail (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  object_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  data text NULL,

  PRIMARY KEY(file_id, object_id)
);

ALTER TABLE file_object_thumbnail
  ALTER COLUMN data SET STORAGE external;
