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
-- file storage refactor), the `data` column is kept as-is (the `legacy-db`
-- file data backend reads old payloads from it: migration
-- 0157-migrate-legacy-file-data moved the existing blobs), and the new
-- `kind` column tells the snapshots and the revisions apart.
--
-- The `kind` column is a reserve: the queries still tell a snapshot from a
-- revision by its label (`label IS NOT NULL`), and `kind` takes over when
-- they are rewritten. That is why no constraint ties the two together: the
-- day `kind` is the source of truth, the constraint would have to go.
--
-- The old table is kept aside as `file_change_legacy`: a frozen copy of the
-- previous layout. It is dropped by hand, days later (or right here, when it
-- is empty: on a new instance there is nothing to keep).
--
-- This migration is not meant to run on a big instance: the copy of the
-- rows of the table happens inside the transaction of the migration, and
-- the pool sets a 5 minute statement timeout. On those instances mark the
-- migration as applied and partition the table by hand, with the procedure
-- of `operations/2.20.0-file-change-partition.md`.
--
-- It assumes the service is stopped (a stop-start upgrade): it renames the
-- table, and a process of the previous version would keep writing to
-- `file_change_legacy` through its server-side prepared statements, which
-- hold the OID of the table and survive the rename. A rolling deployment is
-- the scenario of the runbook, not of this path.
--
-- The GRANTs of the table are not carried over: this creates a new table,
-- so an instance with custom grants on `file_change` has to re-apply them
-- after the upgrade.

-- 0. The guard for the big instances, before anything is touched: a copy
--    that does not fit keeps the backend down in a restart loop, and the
--    operator has to learn it from a clear message instead of from a
--    `statement timeout`. Both numbers are catalog reads, not table scans.
--
--    The row count comes from `pg_class.reltuples`, which is -1 on a
--    relation that was never analyzed (a restore without `--analyze`, for
--    instance). Analyze first so the number is real; on a table that fails
--    the guard this costs seconds per boot attempt, not a table scan.
--
--    The copy, the index builds and the `ANALYZE` of an instance that
--    passes the guard can take longer than the 5 minutes of the pool. The
--    timeout is bounded, so a wrong estimate still fails instead of hanging
--    the boot, and it covers the `ANALYZE`: on a cold, large table it can
--    outlast the pool too, and the guard has to be the one that speaks.
SET LOCAL statement_timeout = '30min';

ANALYZE file_change;

DO $$
BEGIN
  IF pg_total_relation_size('file_change'::regclass) > 10::bigint * 1024 * 1024 * 1024
     OR (SELECT reltuples FROM pg_class WHERE oid = 'file_change'::regclass) > 2000000
  THEN
    RAISE EXCEPTION
      'file_change is too large for the automatic partition migration: mark 0158-partition-file-change as applied and follow operations/2.20.0-file-change-partition.md';
  END IF;
END $$;

-- 1. The legacy blobs of the snapshots were already moved to `file_data`
--    by migration 0157-migrate-legacy-file-data (on the big instances, by
--    hand, with its runbook), so there is nothing to copy here. The `data`
--    column travels with the row in step 4 below: the `legacy-db` backend
--    still reads old payloads from it.

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
  data          bytea,
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
--    because the legacy table has columns that the new one does not have
--    (`data_ref_id` and `data_backend`).
--    The kind comes from the label: a row with a label is a snapshot, a row
--    without one is a revision.
INSERT INTO file_change (id, file_id, created_at, session_id, revn, data, changes,
                         profile_id, features, label, version, created_by,
                         updated_at, deleted_at, migrations, locked_by, kind)
SELECT id, file_id, created_at, session_id, revn, data, changes,
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
--    `data IS NOT NULL`, and with the payload living in `file_data` the
--    column no longer tells snapshots apart. The new one could only filter
--    them by `deleted_at IS NULL`, but the system snapshots always carry a
--    deletion date in the future, so it would never match a row (and
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

-- 8. Statistics for the new partitions. The `ANALYZE` of step 0 ran against
--    the old table, before the copy: without this one the planner starts
--    with empty statistics on the partitions and picks plans by guesswork
--    until autovacuum gets to them. The backend starts serving right after
--    this migration, so it is the worst moment to leave them unanalyzed.
ANALYZE file_change;

-- 9. The legacy table is kept as a frozen copy of the previous layout. It is
--    dropped by hand, days after the cutover:
--    DROP TABLE file_change_legacy;
--
--    Its foreign key on `file_id` goes away with the copy: it is `NO
--    ACTION`, so while the legacy table has rows, the objects GC cannot
--    delete a file (it deletes the rows of the file first, and the legacy
--    ones are not its business). The legacy table is frozen: nothing writes
--    to it again.
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
