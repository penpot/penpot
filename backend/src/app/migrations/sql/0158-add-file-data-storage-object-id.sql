-- Turn the file data storage pointer into a real column, backed by a
-- foreign key, so the database guarantees that a storage object is never
-- physically deleted while a `file_data` row still references it.
--
-- Until now the pointer lived in `file_data.metadata->>'storage-ref-id'`;
-- the storage GC had to parse the JSON to resolve the reference and nothing
-- prevented `storage-gc-deleted` from deleting the blob of a live row.
--
-- The release is a coordinated restart: the column is the only source of
-- truth, the metadata pointer is no longer written, and a rollback to the
-- previous code cannot read the rows written by this release.
--
-- `DEFERRABLE` follows the schema convention (all foreign keys of the
-- project are deferrable, so `th/database-reset` can delete table by table
-- with `SET CONSTRAINTS ALL DEFERRED`). `NO ACTION` keeps the reference
-- strict: deleting a referenced storage object fails instead of cascading.
--
-- NOTE: `NOT VALID` foreign keys on partitioned tables are only supported
-- since PostgreSQL 18, so the constraint is created validated (it scans the
-- heap of every partition of `file_data`). Run the heavy work out of band
-- before deploying; the guards below make this migration a near no-op when
-- the column, the index and the FK already exist:
--
--   1. ALTER TABLE file_data ADD COLUMN IF NOT EXISTS storage_object_id uuid NULL;
--   2. UPDATE file_data SET storage_object_id = (metadata->>'storage-ref-id')::uuid,
--                           metadata = metadata - 'storage-ref-id'
--       WHERE backend = 'storage' AND storage_object_id IS NULL
--         AND jsonb_exists(metadata, 'storage-ref-id')
--         AND metadata->>'storage-ref-id' ~* '<uuid regex>'
--         AND EXISTS (SELECT 1 FROM storage_object so WHERE so.id::text = metadata->>'storage-ref-id');
--   3. CREATE INDEX CONCURRENTLY IF NOT EXISTS file_data_NN__storage_object_id__idx
--        ON file_data_NN (storage_object_id);          -- one per partition
--   4. CREATE INDEX IF NOT EXISTS file_data__storage_object_id__idx
--        ON ONLY file_data (storage_object_id);
--      ALTER INDEX file_data__storage_object_id__idx
--        ATTACH PARTITION file_data_NN__storage_object_id__idx;  -- one per partition
--   5. Create the foreign key in a quiet window (mandatory on large
--      instances) with the same guard used below:
--
--      BEGIN;
--      SET LOCAL lock_timeout = '3s';
--      DO $$ BEGIN
--        IF NOT EXISTS (SELECT 1 FROM pg_constraint
--                        WHERE conname = 'file_data_storage_object_id_fkey'
--                          AND conrelid = 'file_data'::regclass) THEN
--          ALTER TABLE file_data
--            ADD CONSTRAINT file_data_storage_object_id_fkey
--            FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
--            ON DELETE NO ACTION DEFERRABLE;
--        END IF;
--      END $$;
--      COMMIT;
--
-- `CREATE INDEX CONCURRENTLY` cannot run on a partitioned parent, hence the
-- per-partition build plus `ON ONLY` + `ATTACH PARTITION`. An interrupted
-- concurrent build leaves the index INVALID: drop it and retry. Verify with
-- `pg_index.indisvalid` and `pg_inherits` before deploying; the block below
-- aborts the migration if the index is not ready. The guards resolve the
-- table and the index through `search_path` (`to_regclass`, `::regclass`), so
-- they also work when the application schema is not `public`. With the column
-- and the index already present, `ADD COLUMN` and `CREATE INDEX` are skipped by
-- the catalog guards and take no table lock.

SET LOCAL lock_timeout = '3s';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_attribute
                  WHERE attrelid = 'file_data'::regclass
                    AND attname = 'storage_object_id'
                    AND NOT attisdropped) THEN
    ALTER TABLE file_data ADD COLUMN storage_object_id uuid NULL;
  END IF;
END $$;

WITH candidates AS MATERIALIZED (
  SELECT file_id, id, (metadata->>'storage-ref-id')::uuid AS soid
    FROM file_data
   WHERE backend = 'storage'
     AND storage_object_id IS NULL
     AND jsonb_exists(metadata, 'storage-ref-id')
     AND metadata->>'storage-ref-id' ~*
         '^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$'
)
UPDATE file_data AS fd
   SET storage_object_id = c.soid,
       -- The column is the only source of truth: drop the old pointer from
       -- the metadata so the row cannot keep two of them.
       metadata = fd.metadata - 'storage-ref-id'
  FROM candidates AS c
 WHERE fd.file_id = c.file_id
   AND fd.id = c.id
   AND EXISTS (SELECT 1 FROM storage_object AS so WHERE so.id = c.soid);

DO $$
BEGIN
  IF to_regclass('file_data__storage_object_id__idx') IS NULL THEN
    CREATE INDEX file_data__storage_object_id__idx
        ON file_data (storage_object_id);
  END IF;
END $$;

DO $$
DECLARE
  idx_valid    boolean;
  idx_def      text;
  n_partitions integer;
  n_attached   integer;
BEGIN
  -- Resolve the index and the table through `search_path` (no `public.`) so
  -- the guard works on deployments whose objects live in another schema.
  SELECT i.indisvalid, pg_get_indexdef(i.indexrelid)
    INTO idx_valid, idx_def
    FROM pg_index AS i
   WHERE i.indexrelid = to_regclass('file_data__storage_object_id__idx')
     AND i.indrelid   = 'file_data'::regclass;

  SELECT count(*) INTO n_partitions
    FROM pg_inherits
   WHERE inhparent = 'file_data'::regclass;

  SELECT count(*) INTO n_attached
    FROM pg_inherits
   WHERE inhparent = to_regclass('file_data__storage_object_id__idx');

  IF NOT coalesce(idx_valid, false)
     OR idx_def NOT LIKE '%(storage_object_id)%'
     OR n_attached <> n_partitions THEN
    RAISE EXCEPTION 'index file_data__storage_object_id__idx is not ready (valid=%, definition=%, attached=%, partitions=%); finish the runbook step or drop it and retry',
                    idx_valid, idx_def, n_attached, n_partitions;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'file_data_storage_object_id_fkey'
                    AND conrelid = 'file_data'::regclass) THEN
    ALTER TABLE file_data
      ADD CONSTRAINT file_data_storage_object_id_fkey
      FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
      ON DELETE NO ACTION DEFERRABLE;
  END IF;
END $$;

RESET lock_timeout;
