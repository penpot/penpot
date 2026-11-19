-- Migrate legacy payloads to file_data: labelled file_change.data as
-- snapshots, file.data as mains (backend 'db', deleted_at copied so the
-- GC keeps collecting them), then delete orphan file_change rows with
-- data but no label (no read path reaches them).
--
-- Idempotent: INSERTs ignore conflicts, UPDATEs only null the legacy
-- column where the file_data row exists. Big instances pre-migrate by
-- hand (operations/2.19.0-migrate-legacy-file-data.md) and fake this
-- step instead:
--
--   INSERT INTO migrations (module, step)
--   VALUES ('main', '0157-migrate-legacy-file-data');

INSERT INTO file_data (file_id, id, created_at, modified_at, deleted_at,
                       type, backend, metadata, data)
SELECT file_id, id, created_at, updated_at, deleted_at,
       'snapshot', 'db', NULL, data
  FROM file_change
 WHERE data IS NOT NULL
   AND label IS NOT NULL
ON CONFLICT (file_id, id) DO NOTHING;

UPDATE file_change AS fc
   SET data = NULL
 WHERE fc.data IS NOT NULL
   AND fc.label IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM file_data AS fd
                WHERE fd.file_id = fc.file_id
                  AND fd.id = fc.id
                  AND fd.type = 'snapshot');

INSERT INTO file_data (file_id, id, created_at, modified_at, deleted_at,
                       type, backend, metadata, data)
SELECT id, id, created_at, modified_at, deleted_at,
       'main', 'db', NULL, data
  FROM file
 WHERE data IS NOT NULL
ON CONFLICT (file_id, id) DO NOTHING;

UPDATE file AS f
   SET data = NULL
 WHERE f.data IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM file_data AS fd
                WHERE fd.file_id = f.id
                  AND fd.id = f.id
                  AND fd.type = 'main');

-- Orphan revisions: legacy payloads without label. No file_data row
-- corresponds to them (snapshots are told apart by label), so there is
-- nothing to copy; the row itself is garbage. Delete it.
DELETE FROM file_change
 WHERE data IS NOT NULL
   AND label IS NULL;
