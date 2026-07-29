CREATE TABLE file_library_sync (
  file_id uuid NOT NULL,
  library_file_id uuid NOT NULL,
  synced_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  PRIMARY KEY (file_id, library_file_id)
);

INSERT INTO file_library_sync (file_id, library_file_id, synced_at)
SELECT file_id, library_file_id, synced_at
  FROM file_library_rel;

-- DEPRECATED: the `synced_at` column on `file_library_rel` is deprecated
-- and will be removed in a future migration. It's kept temporarily
-- for backward compatibility while data is migrated to `file_library_sync`.
COMMENT ON COLUMN file_library_rel.synced_at IS
  'DEPRECATED: will be removed in a future migration; kept temporarily for backward compatibility';


