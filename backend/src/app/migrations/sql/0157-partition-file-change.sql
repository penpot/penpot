-- Partition `file_change` by `file_id`.
--
-- The table grows with every file change and its TOAST holds the blobs of
-- the changes, so it is the biggest relation of the database. Partitioning it
-- by `file_id` lets the queries that filter by file prune to a single
-- partition, and lets the join with `file_data` (already partitioned with the
-- same modulus) be resolved partition by partition.
--
-- The primary key has to include the partition key, so it becomes
-- `(file_id, id)`. Every access by id alone would lose its index, so the
-- access paths of the application filter by `file_id` too.
--
-- The new table is created with the final schema: the columns that nobody
-- used anymore are gone (`data_ref_id` and `data_backend`, dead since the
-- file storage refactor, and `data`, from the legacy file data backend,
-- which no longer exists), and the new `kind` column tells the snapshots and
-- the revisions apart.
--
-- The `kind` column is a reserve: the queries still tell a snapshot from a
-- revision by its label (`label IS NOT NULL`), and `kind` takes over when
-- they are rewritten. That is why no constraint ties the two together: the
-- day `kind` is the source of truth, the constraint would have to go.
--
-- The old table is kept aside as `file_change_legacy`: it is the way back if
-- something goes wrong. It is dropped by hand, days later (or right here,
-- when it is empty: on a new instance there is nothing to go back to).
--
-- This migration is not meant to run on a big instance: the copy of the
-- blobs (the legacy ones and the rows of the table) happens inside the
-- transaction of the migration. On those instances mark the migration as
-- applied and partition the table by hand, with the procedure described in
-- the runbook of the issue.

-- 1. The legacy blobs of the snapshots move to `file_data` before the column
--    disappears. On the instances that already did it (the big ones, by
--    hand, with the procedure of the runbook) this copies nothing; on the
--    others it is the safety net, so no content is left behind when the
--    column goes away. The rows without a label are the ephemeral
--    revisions: they are out of scope, same as in the manual migration.
INSERT INTO file_data (file_id, id, created_at, modified_at, deleted_at,
                       type, backend, metadata, data)
SELECT fc.file_id, fc.id, fc.created_at, fc.updated_at, fc.deleted_at,
       'snapshot', 'db', NULL, fc.data
  FROM file_change AS fc
 WHERE fc.data IS NOT NULL
   AND fc.label IS NOT NULL
ON CONFLICT (file_id, id) DO NOTHING;

-- The migration does not go on if a snapshot with legacy data is left
-- without its blob: the backend is better down than losing content.
DO $$
DECLARE
  missing   bigint;
  revisions bigint;
BEGIN
  SELECT count(*) INTO missing
    FROM file_change AS fc
   WHERE fc.data IS NOT NULL
     AND fc.label IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM file_data AS fd
                      WHERE fd.file_id = fc.file_id
                        AND fd.id = fc.id
                        AND fd.type = 'snapshot');

  IF missing > 0 THEN
    RAISE EXCEPTION
      'file_change has % snapshots with legacy data that are not in file_data: migrate them before running this migration',
      missing;
  END IF;

  -- The revisions with legacy data are not copied. Their data stays in the
  -- legacy table, which is the way back, but the operator has to know that
  -- they are there.
  SELECT count(*) INTO revisions
    FROM file_change AS fc
   WHERE fc.data IS NOT NULL
     AND fc.label IS NULL;

  IF revisions > 0 THEN
    RAISE NOTICE
      'file_change has % revisions with legacy data: their data is not copied to file_data and stays in the legacy table',
      revisions;
  END IF;
END $$;

-- 2. The current table becomes the legacy one. The names of its indexes and
--    constraints live in the schema, not in the table, so they are renamed
--    with a `_legacy` suffix to free the canonical names.
ALTER TABLE file_change RENAME TO file_change_legacy;

ALTER TABLE file_change_legacy RENAME CONSTRAINT file_change_pkey
  TO file_change_pkey_legacy;
ALTER TABLE file_change_legacy RENAME CONSTRAINT file_change_file_id_fkey
  TO file_change_file_id_fkey_legacy;
ALTER TABLE file_change_legacy RENAME CONSTRAINT file_change_locked_by_fkey
  TO file_change_locked_by_fkey_legacy;
ALTER TABLE file_change_legacy RENAME CONSTRAINT file_change_profile_id_fkey
  TO file_change_profile_id_fkey_legacy;

ALTER INDEX file_change__data_ref_id__idx
  RENAME TO file_change__data_ref_id__idx_legacy;
ALTER INDEX file_change__deleted_at__idx
  RENAME TO file_change__deleted_at__idx_legacy;
ALTER INDEX file_change__file_id__revn__idx
  RENAME TO file_change__file_id__revn__idx_legacy;
ALTER INDEX file_change__locked_by__idx
  RENAME TO file_change__locked_by__idx_legacy;
ALTER INDEX file_change__profile_id__idx
  RENAME TO file_change__profile_id__idx_legacy;
ALTER INDEX file_change__system_snapshots__idx
  RENAME TO file_change__system_snapshots__idx_legacy;

-- This one comes from another branch, so an instance may not have it.
ALTER INDEX IF EXISTS file_change__user_snapshots__idx
  RENAME TO file_change__user_snapshots__idx_legacy;

-- 3. The new table, partitioned with the same bounds as `file_data`, so both
--    relations can be joined partition by partition.
CREATE TABLE file_change (
  file_id       uuid NOT NULL,
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  session_id    uuid,
  revn          bigint NOT NULL DEFAULT 0,
  changes       bytea,
  profile_id    uuid,
  features      text[],
  label         text,
  version       integer,
  created_by    text NOT NULL DEFAULT 'system',
  updated_at    timestamptz DEFAULT now(),
  deleted_at    timestamptz,
  migrations    text[],
  locked_by     uuid,
  kind          smallint NOT NULL,
  CONSTRAINT file_change_kind_check CHECK (kind IN (1, 2))
) PARTITION BY HASH (file_id);

-- the columns keep the storage of PostgreSQL by default (migration
-- 0156-reset-pg-storage-defaults dropped the explicit EXTERNAL overrides)

COMMENT ON COLUMN file_change.kind IS
  'The kind of the row: 1 is a snapshot (it has a label and its data lives in file_data) and 2 is a revision (it has no label and it holds the changes).';
COMMENT ON COLUMN file_change.locked_by IS
  'Profile ID of user who has locked this version. Only the creator can lock/unlock their own versions. Locked versions cannot be deleted by others.';

CREATE TABLE file_change_00 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE file_change_01 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 1);
CREATE TABLE file_change_02 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 2);
CREATE TABLE file_change_03 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 3);
CREATE TABLE file_change_04 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 4);
CREATE TABLE file_change_05 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 5);
CREATE TABLE file_change_06 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 6);
CREATE TABLE file_change_07 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 7);
CREATE TABLE file_change_08 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 8);
CREATE TABLE file_change_09 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 9);
CREATE TABLE file_change_10 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE file_change_11 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE file_change_12 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE file_change_13 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE file_change_14 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE file_change_15 PARTITION OF file_change
  FOR VALUES WITH (MODULUS 16, REMAINDER 15);

-- 4. The rows move to the partitioned table. The column list is explicit
--    because the legacy table has columns that the new one does not have.
--    The kind comes from the label: a row with a label is a snapshot, a row
--    without one is a revision.
INSERT INTO file_change (id, file_id, created_at, session_id, revn, changes,
                         profile_id, features, label, version, created_by,
                         updated_at, deleted_at, migrations, locked_by, kind)
SELECT id, file_id, created_at, session_id, revn, changes,
       profile_id, features, label, version, created_by,
       updated_at, deleted_at, migrations, locked_by,
       CASE WHEN label IS NOT NULL THEN 1 ELSE 2 END
  FROM file_change_legacy;

-- 5. The primary key includes the partition key now.
ALTER TABLE file_change ADD PRIMARY KEY (file_id, id);

-- 6. The foreign keys.
ALTER TABLE file_change ADD FOREIGN KEY (file_id)
  REFERENCES file(id) DEFERRABLE;
ALTER TABLE file_change ADD FOREIGN KEY (locked_by)
  REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;
ALTER TABLE file_change ADD FOREIGN KEY (profile_id)
  REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;

-- 7. The indexes, with the canonical names. The one of the deleted rows now
--    carries the `file_id`, so the garbage collector can read the rows it has
--    to delete without touching the table.
--
--    There is no index for the system snapshots: the old one filtered them by
--    `data IS NOT NULL`, and the column is gone. The new one could only
--    filter them by `deleted_at IS NULL`, and the system snapshots always
--    carry a deletion date in the future, so it would never match a row (and
--    the query that would use it filters that date with an "or", which no
--    partial index can serve).
CREATE INDEX file_change__deleted_at__idx
  ON file_change (deleted_at, file_id, id) WHERE deleted_at IS NOT NULL;
CREATE INDEX file_change__file_id__revn__idx
  ON file_change (file_id, revn);
CREATE INDEX file_change__locked_by__idx
  ON file_change (locked_by) WHERE locked_by IS NOT NULL;
CREATE INDEX file_change__profile_id__idx
  ON file_change (profile_id) WHERE profile_id IS NOT NULL;
CREATE INDEX file_change__user_snapshots__idx
  ON file_change (file_id)
 WHERE label IS NOT NULL
   AND created_by = 'user'
   AND deleted_at IS NULL;

-- 8. The legacy table is kept: it is the way back. It is dropped by hand,
--    days after the cutover:
--    DROP TABLE file_change_legacy;
--
--    Its foreign key on `file_id` goes away with the copy: it is `NO
--    ACTION`, so while the legacy table has rows, the objects GC cannot
--    delete a file (it deletes the rows of the file first, and the legacy
--    ones are not its business). The legacy table is frozen, and the
--    rollback adds the constraint back with `NOT VALID`.
ALTER TABLE file_change_legacy
  DROP CONSTRAINT file_change_file_id_fkey_legacy;

--    On a new instance there is nothing to go back to, so the empty table
--    is dropped here.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM file_change_legacy) THEN
    DROP TABLE file_change_legacy;
  END IF;
END $$;
