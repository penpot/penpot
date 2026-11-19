-- Turn the file data storage pointer into a column with a foreign key.
--
-- Until now the pointer lived in `file_data.metadata->>'storage-ref-id'`.
-- The storage GC parsed the JSON, and nothing stopped it from deleting the
-- blob of a live row. With the column and the foreign key the database
-- refuses that delete.
--
-- With the storage backend the release is a coordinated restart: the column is
-- the only source of truth, and the previous code cannot read the rows this
-- release writes. With the default `db` backend the new code writes the same
-- `file_data.data` column the previous code reads, so a rolling deploy is
-- safe.
--
-- This migration runs on a fresh database and on small instances. A large
-- instance builds the column and the index out of band, runs this same
-- backfill and foreign key with the instances stopped, and marks this step as
-- applied; the end state is identical. See
-- `operations/2.20.0-file-data-storage-object-id.md`.
--
-- `DEFERRABLE` follows the schema convention (all foreign keys of the project
-- are deferrable, so `th/database-reset` can delete table by table with
-- `SET CONSTRAINTS ALL DEFERRED`). It is `INITIALLY IMMEDIATE`, not
-- `DEFERRED`: the storage GC catches the violation inside the chunk
-- transaction and retries the chunk, and a deferred check would fire at
-- commit, outside that transaction, so the GC would never move past it.

ALTER TABLE file_data
  ADD COLUMN IF NOT EXISTS storage_object_id uuid;

-- The CTE is MATERIALIZED so the uuid cast only sees rows that passed the
-- regex. A plain subquery can be inlined, and a corrupt value would reach the
-- cast and abort the migration.
WITH candidates AS MATERIALIZED (
  SELECT file_id,
         id,
         (metadata->>'storage-ref-id')::uuid AS soid
    FROM file_data
   WHERE backend = 'storage'
     AND storage_object_id IS NULL
     AND (metadata->>'storage-ref-id') ~*
         '^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$'
)
UPDATE file_data AS fd
   SET storage_object_id = c.soid,
       metadata = fd.metadata - 'storage-ref-id'
  FROM candidates AS c
 WHERE fd.file_id = c.file_id
   AND fd.id = c.id
   AND EXISTS (SELECT 1 FROM storage_object AS so WHERE so.id = c.soid);

-- The deletion check of the foreign key looks a row up by this column for
-- every deleted object; without the index it scans all partitions.
CREATE INDEX IF NOT EXISTS file_data__storage_object_id__idx
    ON file_data (storage_object_id);

-- Rebuild the constraint so the definition is always the one above. This is
-- also how a constraint created by hand with the wrong shape (CASCADE, not
-- deferrable, or `INITIALLY DEFERRED`) gets repaired.
ALTER TABLE file_data
  DROP CONSTRAINT IF EXISTS file_data_storage_object_id_fkey;

ALTER TABLE file_data
  ADD CONSTRAINT file_data_storage_object_id_fkey
  FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
  ON DELETE NO ACTION DEFERRABLE INITIALLY IMMEDIATE;
