CREATE TABLE file_frame_thumbnail (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  frame_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  data text NULL,

  PRIMARY KEY(file_id, frame_id)
);

ALTER TABLE file_frame_thumbnail
  ALTER COLUMN data SET STORAGE external;
