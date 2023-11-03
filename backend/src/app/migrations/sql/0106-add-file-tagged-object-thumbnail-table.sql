CREATE TABLE file_tagged_object_thumbnail (
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  tag text DEFAULT 'frame',
  object_id text NOT NULL,

  media_id uuid NOT NULL REFERENCES storage_object(id) ON DELETE CASCADE DEFERRABLE,
  created_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY(file_id, tag, object_id)
);
