CREATE TABLE file_library_sync (
  file_id uuid NOT NULL,
  library_file_id uuid NOT NULL,
  synced_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_id, library_file_id)
);

INSERT INTO file_library_sync (file_id, library_file_id, synced_at)
SELECT file_id, library_file_id, synced_at
  FROM file_library_rel;

ALTER TABLE file_library_rel
  DROP COLUMN synced_at;

