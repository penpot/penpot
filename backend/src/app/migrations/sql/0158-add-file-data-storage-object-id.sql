-- Turn the file data storage pointer into a real column, backed by a
-- foreign key, so the database guarantees that a storage object is never
-- physically deleted while a `file_data` row still references it.
--
-- Until now the pointer lived in `file_data.metadata->>'storage-ref-id'`;
-- the storage GC had to parse the JSON to resolve the reference and nothing
-- prevented `storage-gc-deleted` from deleting the blob of a live row.
--
-- `DEFERRABLE` follows the schema convention (all foreign keys of the
-- project are deferrable, so `th/database-reset` can delete table by table
-- with `SET CONSTRAINTS ALL DEFERRED`). `NO ACTION` keeps the reference
-- strict: deleting a referenced storage object fails instead of cascading.
--
-- NOTE: `NOT VALID` foreign keys on partitioned tables are only supported
-- since PostgreSQL 18, so the constraint is created validated (it scans the
-- heap of every partition of `file_data`). Run the heavy index build out of
-- band before deploying:
--
--   1. ALTER TABLE file_data ADD COLUMN IF NOT EXISTS storage_object_id uuid NULL;
--   2. UPDATE file_data SET storage_object_id = (metadata->>'storage-ref-id')::uuid
--       WHERE backend = 'storage' AND storage_object_id IS NULL
--         AND jsonb_exists(metadata, 'storage-ref-id');
--   3. CREATE INDEX CONCURRENTLY IF NOT EXISTS file_data_NN__storage_object_id__idx
--        ON file_data_NN (storage_object_id);          -- one per partition
--   4. CREATE INDEX IF NOT EXISTS file_data__storage_object_id__idx
--        ON ONLY file_data (storage_object_id);
--      ALTER INDEX file_data__storage_object_id__idx
--        ATTACH PARTITION file_data_NN__storage_object_id__idx;  -- one per partition
--   5. Optionally create the foreign key in a quiet window with the same
--      guard used below.
--
-- `CREATE INDEX CONCURRENTLY` cannot run on a partitioned parent, hence the
-- per-partition build plus `ON ONLY` + `ATTACH PARTITION`. An interrupted
-- concurrent build leaves the index INVALID: drop it and retry. Verify with
-- `pg_index.indisvalid` and `pg_inherits` before deploying; the block below
-- aborts the migration if the index is not ready.
--
-- Once the index exists, this migration finds it and does nothing.

SET LOCAL lock_timeout = '3s';

ALTER TABLE file_data ADD COLUMN IF NOT EXISTS storage_object_id uuid NULL;

UPDATE file_data
   SET storage_object_id = (metadata->>'storage-ref-id')::uuid
 WHERE backend = 'storage'
   AND storage_object_id IS NULL
   AND jsonb_exists(metadata, 'storage-ref-id');

CREATE INDEX IF NOT EXISTS file_data__storage_object_id__idx
    ON file_data (storage_object_id);

DO $$
DECLARE
  idx_valid    boolean;
  idx_def      text;
  n_partitions integer;
  n_attached   integer;
BEGIN
  SELECT i.indisvalid, pg_get_indexdef(i.indexrelid)
    INTO idx_valid, idx_def
    FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
   WHERE c.relname = 'file_data__storage_object_id__idx';

  SELECT count(*) INTO n_partitions
    FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent
   WHERE p.relname = 'file_data';

  SELECT count(*) INTO n_attached
    FROM pg_inherits
   WHERE inhparent = 'file_data__storage_object_id__idx'::regclass;

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
