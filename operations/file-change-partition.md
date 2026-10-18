# Partition `file_change` by `file_id`

Online procedure to turn `file_change` into a table partitioned by
`HASH (file_id)` with 16 partitions, aligned with the ones of `file_data`.

- Migration: `backend/src/app/migrations/sql/0157-partition-file-change.sql`
- Issue: <https://github.com/penpot/penpot/issues/12416>

The result has to be **exactly** the schema of the migration, because on a big
instance the migration is marked as applied and never runs. This procedure
runs the same statements by hand.

## The state of PRO when this was written

2026-10-22: PostgreSQL 16.3, database 3.1 TB, `file_change` 683 GB (10 GB of
heap, 669 GB of TOAST, 3.1 GB of indexes), 1,135,255 rows.

The legacy data is already migrated: the `data` column of `file_change` is
empty and the blobs live in `file_data` (1.56 TB, already partitioned in 16).
What is left in the TOAST is the xlog of the revisions, 4.4 kB on average, so
roughly 2.5 GB of live data against 669 GB on disk: **almost all of the TOAST
is dead space**, the legacy blobs that were emptied plus the churn of the
updates.

So the copy carries about 13 GB (the heap plus the live TOAST) and writes a
table of roughly 7 GB. The disk comes back when the legacy table is dropped,
days later: some 675 GB. The copy itself tells the real numbers; these are
the estimates behind it.

These numbers describe that instance and nothing else; the procedure does not
depend on them.

## What changes

- `file_change` is partitioned by `HASH (file_id)` with 16 partitions, the
  same bounds as `file_data`, so the join between them is resolved partition by
  partition.
- The primary key goes from `(id)` to `(file_id, id)`: PostgreSQL requires the
  partition key in the primary key, and the application already filters by
  `file_id` everywhere.
- The table has 16 columns: `data`, `data_ref_id` and `data_backend` are gone
  (the legacy file data backend that still wrote them no longer exists) and
  `kind` appears (1 = snapshot, 2 = revision).
- The old table is kept as `file_change_legacy`, the way back if something
  goes wrong, and dropped days later.
- Almost all of the size of the table is dead space, so the table shrinks
  from 683 GB to some 7 GB. The disk comes back when the legacy table is
  dropped: some 675 GB, and the database goes from 3.1 TB to about 2.4 TB.

## Why this mechanism

`ATTACH` as a `DEFAULT` partition is not possible: the 16 hash siblings cover
the whole hash space, so the constraint of a `DEFAULT` partition could never
hold, and the `ATTACH` fails when it validates the 1.25 M rows. A `UNION`
view with triggers, dual writes in the application and logical replication
were discarded too.

What is left is a copy in phases, driven by the `updated_at` watermark, and a
cutover of minutes: plain SQL, resumable, no new infrastructure. The heap is
10 GB, so a delta pass without an index scans 10 GB and only detoastes the
rows that changed.

---

## Phase 0 — Before anything

### 0.1 Mark the migration as applied

The runner applies at startup every migration that is not in the `migrations`
table. Without this, it would copy the whole table inside the transaction of the
startup.

```sql
INSERT INTO migrations (module, step)
VALUES ('main', '0157-partition-file-change');
```

The module is `main` and the step has no `.sql` (see
`backend/src/app/util/migrations.clj`).

### 0.2 Add the `kind` column to the current table

Until the cutover, `file_change` is the current table, and the new code writes
`kind` on every insert: without the column the backend does not start.

```sql
ALTER TABLE file_change ADD COLUMN IF NOT EXISTS kind smallint;
```

Nullable and without a default on purpose: it is a metadata-only operation
(it does not rewrite a single row of the 683 GB), the old code keeps inserting
rows, and the values of the rows that are already there are computed by the
copy from their `label`.

### 0.3 Deploy the code

The `updated_at` watermark has to be alive **before** the bulk copy: the new
code updates it on every mutation (locks, soft deletes), the old one does
not. Rows changed between the bulk and a delta pass would be lost.

```sql
SELECT max(updated_at) FROM file_change;
```

### 0.4 Index for the delta passes

The delta passes filter by `updated_at`. Without an index on that column every
pass scans the whole heap (10 GB); with it, it is an index range that only
touches the rows that changed.

```sql
-- CONCURRENTLY: it does not block the writes. It cannot run inside a
-- transaction: run it alone.
CREATE INDEX CONCURRENTLY IF NOT EXISTS file_change__updated_at__idx
  ON file_change (updated_at);
```

- The build reads the heap once (10 GB sequential) and writes ~25 MB: a few
  minutes, not hours (an index on a column without TOAST does not touch the
  TOAST).
- If it is interrupted, the index is left `INVALID` and has to be dropped and
  built again:

```sql
SELECT i.indisvalid
  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
 WHERE c.relname = 'file_change__updated_at__idx';
```

- The index goes with the old table: at the cutover it is renamed to
  `file_change__updated_at__idx_legacy` and disappears with it. The new table
  does not carry it (nothing queries by `updated_at`; the GC uses
  `deleted_at`).

### 0.5 Check the environment

```sql
-- Space: peak estimate 683 GB (the old table, it does not grow) + ~10 GB
-- (the new table) + WAL ~15 GB during the bulk
SELECT pg_size_pretty(pg_database_size(current_database()));

-- The WAL must not run out of room (note the free space of the mount point):
--   df -h <pg_wal dir> <data dir>

-- Nothing must reference file_change (0 rows expected):
SELECT conrelid::regclass FROM pg_constraint
 WHERE confrelid = 'file_change'::regclass;

-- There must be no triggers on file_change:
SELECT tgname FROM pg_trigger
 WHERE tgrelid = 'file_change'::regclass AND NOT tgisinternal;

-- Replication slots: the bulk generates some 15 GB of WAL.
SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(
         pg_current_wal_lsn(), restart_lsn)) AS retained_wal
  FROM pg_replication_slots;

-- The legacy blobs of the snapshots have to be in `file_data` before the
-- cutover: the new table does not carry the `data` column. This number has
-- to be 0.
SELECT count(*) AS snapshots_without_data
  FROM file_change AS fc
 WHERE fc.data IS NOT NULL
   AND fc.label IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM file_data AS fd
                    WHERE fd.file_id = fc.file_id
                      AND fd.id = fc.id
                      AND fd.type = 'snapshot');

-- The revisions with legacy data are not migrated (they are the ephemeral
-- changes, out of scope). This number is informative: their data stays in
-- the legacy table, which is the way back.
SELECT count(*) AS revisions_with_data
  FROM file_change
 WHERE data IS NOT NULL
   AND label IS NULL;
```

- If the first number is not 0, the blobs of those snapshots have to be
  copied to `file_data` before starting (the same statement the migration
  uses):

```sql
INSERT INTO file_data (file_id, id, created_at, modified_at, deleted_at,
                       type, backend, metadata, data)
SELECT fc.file_id, fc.id, fc.created_at, fc.updated_at, fc.deleted_at,
       'snapshot', 'db', NULL, fc.data
  FROM file_change AS fc
 WHERE fc.data IS NOT NULL
   AND fc.label IS NOT NULL
ON CONFLICT (file_id, id) DO NOTHING;
```

- Check that the backup / PITR is working before starting.
- Pick a low activity window for the cutover: a freeze of the writes to
  `file_change` of a couple of minutes (the final delta inside the lock
  copies only the rows changed since the bulk, so it is quick; the saves of
  files during the freeze fail and the user retries, and the rest of the
  database keeps working). The window has to cover the whole cutover, with
  the backend and the workers stopped and started again (see 4.2).

---

## Phase 1 — Create the partitioned table

The new indexes and constraints carry a `_new` suffix because they live with
the ones of the old table until the cutover (the names live in the schema,
not in the table). At the cutover they are swapped for the canonical ones.
The partitions are the exception: they are created with their final name,
because their indexes are named after the partition and not after the
table.

```sql
CREATE TABLE file_change_new (
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
  CONSTRAINT file_change_new_kind_check CHECK (kind IN (1, 2))
) PARTITION BY HASH (file_id);

-- the columns keep the storage of PostgreSQL by default (migration
-- 0156-reset-pg-storage-defaults dropped the explicit EXTERNAL overrides)

COMMENT ON COLUMN file_change_new.kind IS
  'The kind of the row: 1 is a snapshot (it has a label and its data lives in file_data) and 2 is a revision (it has no label and it holds the changes).';
COMMENT ON COLUMN file_change_new.locked_by IS
  'Profile ID of user who has locked this version. Only the creator can lock/unlock their own versions. Locked versions cannot be deleted by others.';

-- 16 partitions, the same bounds as file_data. The name of the table is
-- temporary (the old one holds it until the cutover) but the name of the
-- partitions is not: it is the final one, so their indexes are born with
-- the canonical names (renaming a partition does not rename its indexes)
-- and the result is exactly the schema of the migration.
CREATE TABLE file_change_00 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE file_change_01 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 1);
CREATE TABLE file_change_02 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 2);
CREATE TABLE file_change_03 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 3);
CREATE TABLE file_change_04 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 4);
CREATE TABLE file_change_05 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 5);
CREATE TABLE file_change_06 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 6);
CREATE TABLE file_change_07 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 7);
CREATE TABLE file_change_08 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 8);
CREATE TABLE file_change_09 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 9);
CREATE TABLE file_change_10 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE file_change_11 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE file_change_12 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE file_change_13 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE file_change_14 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE file_change_15 PARTITION OF file_change_new
  FOR VALUES WITH (MODULUS 16, REMAINDER 15);

-- The primary key includes the partition key (required on a partitioned table)
ALTER TABLE file_change_new ADD PRIMARY KEY (file_id, id);

-- Foreign keys (no CREATE TABLE copies them)
ALTER TABLE file_change_new ADD FOREIGN KEY (file_id)
  REFERENCES file(id) DEFERRABLE;
ALTER TABLE file_change_new ADD FOREIGN KEY (locked_by)
  REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;
ALTER TABLE file_change_new ADD FOREIGN KEY (profile_id)
  REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;

-- Secondary indexes: the canonical names, with the _new suffix. There is no
-- index for the system snapshots: the old one filtered them by
-- `data IS NOT NULL`, and the column is gone. The new one could only filter
-- them by `deleted_at IS NULL`, and the system snapshots always carry a
-- deletion date in the future, so it would never match a row.
CREATE INDEX file_change_new__deleted_at__idx
  ON file_change_new (deleted_at, file_id, id) WHERE deleted_at IS NOT NULL;
CREATE INDEX file_change_new__file_id__revn__idx
  ON file_change_new (file_id, revn);
CREATE INDEX file_change_new__locked_by__idx
  ON file_change_new (locked_by) WHERE locked_by IS NOT NULL;
CREATE INDEX file_change_new__profile_id__idx
  ON file_change_new (profile_id) WHERE profile_id IS NOT NULL;
CREATE INDEX file_change_new__user_snapshots__idx
  ON file_change_new (file_id)
 WHERE label IS NOT NULL AND created_by = 'user' AND deleted_at IS NULL;
```

The `deleted_at` index carries `file_id` (the primary key is `(file_id, id)`):
the objects GC needs the `file_id` to lock by pair, and with the index it
reads the rows it has to delete without touching the table.

```sql
SELECT count(*) AS partitions FROM pg_partition_tree('file_change_new') t
 WHERE t.isleaf;
-- 16
```

---

## Phase 2 — Bulk copy

Copies every row (including the soft deleted ones: the GC manages them the
same way in the new table). The `kind` is computed from the `label`: a row
with a label is a snapshot, a row without one is a revision.

The pointer of the copy lives in the database, not in the terminal: a batch
can be interrupted and resumed without losing anything.

```sql
CREATE TABLE IF NOT EXISTS file_change_copy_state (
  name    text PRIMARY KEY,
  last_id uuid
);

INSERT INTO file_change_copy_state (name, last_id)
VALUES ('bulk', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (name) DO NOTHING;
```

One batch (idempotent). It returns how many rows it copied:

```sql
WITH batch AS MATERIALIZED (
  SELECT id FROM file_change
   WHERE id > (SELECT last_id FROM file_change_copy_state WHERE name = 'bulk')
   ORDER BY id
   LIMIT 10000
),
copied AS (
  INSERT INTO file_change_new (id, file_id, created_at, session_id, revn, changes,
                               profile_id, features, label, version, created_by,
                               updated_at, deleted_at, migrations, locked_by, kind)
  SELECT id, file_id, created_at, session_id, revn, changes,
         profile_id, features, label, version, created_by,
         updated_at, deleted_at, migrations, locked_by,
         CASE WHEN label IS NOT NULL THEN 1 ELSE 2 END
    FROM file_change
   WHERE id IN (SELECT id FROM batch)
  ON CONFLICT (file_id, id) DO UPDATE
     SET created_at = EXCLUDED.created_at,
         session_id = EXCLUDED.session_id,
         revn       = EXCLUDED.revn,
         changes    = EXCLUDED.changes,
         profile_id = EXCLUDED.profile_id,
         features   = EXCLUDED.features,
         label      = EXCLUDED.label,
         version    = EXCLUDED.version,
         created_by = EXCLUDED.created_by,
         updated_at = EXCLUDED.updated_at,
         deleted_at = EXCLUDED.deleted_at,
         migrations = EXCLUDED.migrations,
         locked_by  = EXCLUDED.locked_by,
         kind       = EXCLUDED.kind
  RETURNING 1
)
UPDATE file_change_copy_state
   SET last_id = (SELECT id FROM batch ORDER BY id DESC LIMIT 1)
 WHERE name = 'bulk'
   AND EXISTS (SELECT 1 FROM copied)
RETURNING (SELECT count(*) FROM copied) AS copied;
```

Repeat until it returns `0`:

```bash
until [ "$(psql -At -f batch-bulk.sql)" = "0" ]; do sleep 0.3; done
```

- The pointer is a `uuid` (random order): the pagination is stable but it does
  not group by physical position. The total read is the 10 GB of the heap, in
  a scattered order.
- **Estimated duration**: minutes, not hours. The bulk reads about 13 GB (the
  heap plus the live TOAST, the dead space of the TOAST is never read) and
  writes a table of roughly 7 GB. With 1.14 M rows and batches of 10 K it is
  some 114 batches.
- Adjust the throttle (`sleep`) if the streaming replica falls behind or the
  backend sees latency. Every batch detoastes its share of the TOAST: the cost
  is I/O, not CPU.
- Watch: replica lag, `pg_stat_activity` (locks between the bulk and
  autovacuum), the space of `pg_wal` and of the data dir.

```sql
SELECT (SELECT count(*) FROM file_change)     AS old_rows,
       (SELECT count(*) FROM file_change_new) AS new_rows;
```

They match except for the rows written during the bulk (the delta passes pick
them up).

---

## Phase 3 — Delta passes

Every pass copies the rows modified since the bulk started. The window is a
constant in the script (not the output of the previous pass): the hour of
margin absorbs the clock drift between the application and the database, and
the drift between batches.

```sql
-- :bulk_start = the time the bulk started, minus one hour
INSERT INTO file_change_new (id, file_id, created_at, session_id, revn, changes,
                             profile_id, features, label, version, created_by,
                             updated_at, deleted_at, migrations, locked_by, kind)
SELECT id, file_id, created_at, session_id, revn, changes,
       profile_id, features, label, version, created_by,
       updated_at, deleted_at, migrations, locked_by,
       CASE WHEN label IS NOT NULL THEN 1 ELSE 2 END
  FROM file_change
 WHERE updated_at > TIMESTAMPTZ '2026-10-25 08:00:00+02'   -- :bulk_start
ON CONFLICT (file_id, id) DO UPDATE
   SET created_at = EXCLUDED.created_at,
       session_id = EXCLUDED.session_id,
       revn       = EXCLUDED.revn,
       changes    = EXCLUDED.changes,
       profile_id = EXCLUDED.profile_id,
       features   = EXCLUDED.features,
       label      = EXCLUDED.label,
       version    = EXCLUDED.version,
       created_by = EXCLUDED.created_by,
       updated_at = EXCLUDED.updated_at,
       deleted_at = EXCLUDED.deleted_at,
       migrations = EXCLUDED.migrations,
       locked_by  = EXCLUDED.locked_by,
       kind       = EXCLUDED.kind;
```

- Repeat every 1-6 h (depending on the activity) until the pass copies very
  few rows. With the index of phase 0.4 the pass is an index range over the
  rows changed since the mark, so it takes seconds; without it, it scans the
  10 GB of the heap. To see how many are left:

```sql
SELECT count(*) FROM file_change WHERE updated_at > TIMESTAMPTZ '2026-10-25 08:00:00+02';
```

- The objects GC can keep running: its soft deletes are covered by the
  watermark, and its hard deletes leave a row with `deleted_at` in the new
  table that the GC itself cleans up later. The hard delete of a **file** is
  blocked while the old table (or the new one) has rows for it, because the
  foreign key on `file_id` is `NO ACTION`: the file stays in the trash and
  the GC picks it up in the next run. **Do not run the cutover at the same
  time as a big batch of the GC.**

---

## Phase 4 — Cutover (a freeze of minutes)

### 4.1 Before the lock

- One last delta pass.
- Prepare and rehearse the cutover script in staging.

### 4.2 The cutover (one transaction, a brief exclusive lock)

- **Stop the backend and the workers before the transaction, and start them
  again after it.** The pool keeps server-side prepared statements, and a
  prepared statement holds the OID of the table it was prepared with:
  renaming the table does not change that OID. A connection that had already
  run `insert into file_change` five times (the default threshold of the
  driver) would keep writing to `file_change_legacy` after the cutover, and
  the pool recycles its connections every 30 minutes, so the window of
  silent writes is long.
- Run the transaction.
- Start the backend and the workers again.

```sql
BEGIN;

LOCK TABLE file_change IN ACCESS EXCLUSIVE MODE;

-- The final delta: the rows modified since the bulk started
INSERT INTO file_change_new (id, file_id, created_at, session_id, revn, changes,
                             profile_id, features, label, version, created_by,
                             updated_at, deleted_at, migrations, locked_by, kind)
SELECT id, file_id, created_at, session_id, revn, changes,
       profile_id, features, label, version, created_by,
       updated_at, deleted_at, migrations, locked_by,
       CASE WHEN label IS NOT NULL THEN 1 ELSE 2 END
  FROM file_change
 WHERE updated_at > TIMESTAMPTZ '2026-10-25 08:00:00+02'
ON CONFLICT (file_id, id) DO UPDATE
   SET updated_at = EXCLUDED.updated_at,
       deleted_at = EXCLUDED.deleted_at,
       label      = EXCLUDED.label,
       locked_by  = EXCLUDED.locked_by,
       created_by = EXCLUDED.created_by,
       changes    = EXCLUDED.changes,
       kind       = EXCLUDED.kind;

-- Check inside the lock
SELECT (SELECT count(*) FROM file_change)     AS old_rows,
       (SELECT count(*) FROM file_change_new) AS new_rows;

-- The old table frees the canonical names (the same names the migration
-- 0157 uses, with the _legacy suffix)
ALTER TABLE file_change RENAME CONSTRAINT file_change_pkey
  TO file_change_pkey_legacy;
ALTER TABLE file_change RENAME CONSTRAINT file_change_file_id_fkey
  TO file_change_file_id_fkey_legacy;
ALTER TABLE file_change RENAME CONSTRAINT file_change_locked_by_fkey
  TO file_change_locked_by_fkey_legacy;
ALTER TABLE file_change RENAME CONSTRAINT file_change_profile_id_fkey
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
ALTER INDEX IF EXISTS file_change__user_snapshots__idx
  RENAME TO file_change__user_snapshots__idx_legacy;
ALTER INDEX IF EXISTS file_change__updated_at__idx
  RENAME TO file_change__updated_at__idx_legacy;

-- And the new table takes them
ALTER TABLE file_change_new RENAME CONSTRAINT file_change_new_pkey
  TO file_change_pkey;
ALTER TABLE file_change_new RENAME CONSTRAINT file_change_new_file_id_fkey
  TO file_change_file_id_fkey;
ALTER TABLE file_change_new RENAME CONSTRAINT file_change_new_locked_by_fkey
  TO file_change_locked_by_fkey;
ALTER TABLE file_change_new RENAME CONSTRAINT file_change_new_profile_id_fkey
  TO file_change_profile_id_fkey;
ALTER TABLE file_change_new RENAME CONSTRAINT file_change_new_kind_check
  TO file_change_kind_check;

ALTER INDEX file_change_new__deleted_at__idx
  RENAME TO file_change__deleted_at__idx;
ALTER INDEX file_change_new__file_id__revn__idx
  RENAME TO file_change__file_id__revn__idx;
ALTER INDEX file_change_new__locked_by__idx
  RENAME TO file_change__locked_by__idx;
ALTER INDEX file_change_new__profile_id__idx
  RENAME TO file_change__profile_id__idx;
ALTER INDEX file_change_new__user_snapshots__idx
  RENAME TO file_change__user_snapshots__idx;

-- And the tables are swapped. The partitions and their indexes keep the
-- names they were born with (they are named after the partition, not after
-- the table), so there is nothing else to rename.
ALTER TABLE file_change     RENAME TO file_change_legacy;
ALTER TABLE file_change_new RENAME TO file_change;

-- The foreign key of the legacy table goes away: it is `NO ACTION`, so
-- while the legacy table has rows for a file, the objects GC cannot delete
-- that file (it deletes the rows of the file first, and the legacy ones are
-- not its business). The legacy table is frozen, and the rollback adds the
-- constraint back.
ALTER TABLE file_change_legacy
  DROP CONSTRAINT file_change_file_id_fkey_legacy;

COMMIT;

ANALYZE file_change;
```

- `file_change_legacy` keeps its data and its indexes (with the `_legacy`
  suffix): it stays frozen as the way back. Its foreign key on `file_id` is
  dropped, so the objects GC can delete the files again.
- The writes to `file_change` during the lock fail: they are seconds-minutes,
  the affected saves are retried.
- The objects GC and the four RPC commands keep working: same table name and
  same index names after the rename.
- Once the backend is up again, check that the saves go to the partitioned
  table and that nothing went to the table that is going away:

```sql
-- the rows written after the cutover: this one grows with every save
SELECT count(*) FROM file_change
 WHERE updated_at > TIMESTAMPTZ '2026-10-25 08:00:00+02'   -- :cutover

-- the rows that went to the legacy table: this one has to stay at 0
SELECT count(*) FROM file_change_legacy
 WHERE updated_at > TIMESTAMPTZ '2026-10-25 08:00:00+02';  -- :cutover
```

A number above zero in the second query means that some connection was
still writing with a prepared statement of the old table: those rows are
lost when the legacy table is dropped, so they have to be copied over
before that.

### 4.3 Rollback

- **Before the `COMMIT`**: `ROLLBACK` and that is it.
- **After the cutover**: the new writes go to the partitioned table. To revert,
  rename the tables back and copy the delta the other way
  (`INSERT INTO file_change_legacy ... WHERE updated_at > :cutover ON CONFLICT
  (id) DO UPDATE`). The foreign key on `file_id` was dropped at the cutover,
  so it goes back with `NOT VALID` (the existing rows are not checked) and it
  is validated later:

```sql
ALTER TABLE file_change_legacy
  ADD CONSTRAINT file_change_file_id_fkey
  FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE NOT VALID;
```

  Reasonable window: only if there is a serious defect in the first hours.
  After that, treat any problem the normal way (the partitioned table is the
  good state).

---

## Phase 5 — After the cutover

1. Check that the access paths prune to one partition (when the planner
   chooses an index scan):

```sql
EXPLAIN SELECT * FROM file_change
 WHERE file_id = '00000000-0000-0000-0000-000000000000'
   AND id     = '00000000-0000-0000-0000-000000000000';
```

It has to show a single partition (`file_change_NN`), not an `Append` of 16.

2. Check that the schema is the one of the migration `0157`. Every row of this
   query has to show `got = expected`:

```sql
SELECT 'columns' AS what, count(*) AS got, 16 AS expected
  FROM pg_attribute
 WHERE attrelid = 'file_change'::regclass AND attnum > 0 AND NOT attisdropped
UNION ALL
SELECT 'partitions', count(*), 16
  FROM pg_partition_tree('file_change') t WHERE t.isleaf
UNION ALL
SELECT 'primary key', count(*), 1
  FROM pg_constraint
 WHERE conrelid = 'file_change'::regclass AND contype = 'p'
UNION ALL
SELECT 'foreign keys', count(*), 3
  FROM pg_constraint
 WHERE conrelid = 'file_change'::regclass AND contype = 'f'
UNION ALL
SELECT 'check constraints', count(*), 1
  FROM pg_constraint
 WHERE conrelid = 'file_change'::regclass AND contype = 'c'
UNION ALL
SELECT 'indexes', count(*), 6
  FROM pg_indexes
 WHERE tablename = 'file_change' AND schemaname = current_schema()
UNION ALL
SELECT 'partitions out of name', count(*), 0
  FROM pg_class
 WHERE relkind = 'r' AND relname ~ '^file_change_new[0-9]'
UNION ALL
SELECT 'child indexes out of name', count(*), 0
  FROM pg_class c
  JOIN pg_index i ON i.indexrelid = c.oid
  JOIN pg_class p ON p.oid = i.indrelid
 WHERE p.relname ~ '^file_change_[0-9]{2}$'
   AND c.relname NOT LIKE p.relname || '_%';
```

   And the names, to compare them with the migration:

```sql
SELECT a.attname AS column
  FROM pg_attribute a
 WHERE a.attrelid = 'file_change'::regclass AND a.attnum > 0
   AND NOT a.attisdropped
 ORDER BY a.attnum;

SELECT indexname FROM pg_indexes
 WHERE tablename = 'file_change' AND schemaname = current_schema()
 ORDER BY indexname;

-- the partitions, with their name
SELECT c.relname AS partition
  FROM pg_class c
  JOIN pg_partition_tree('file_change') t ON t.relid = c.oid
 WHERE t.isleaf
 ORDER BY c.relname;

-- the indexes of the partitions: their name starts with the partition
SELECT c.relname AS child_index
  FROM pg_class c
  JOIN pg_index i ON i.indexrelid = c.oid
  JOIN pg_class p ON p.oid = i.indrelid
 WHERE p.relname ~ '^file_change_[0-9]{2}$'
 ORDER BY c.relname;
```

3. The partition-wise join is already on: the pool of the backend sets
   `enable_partitionwise_join = on` on every connection (it is in the code,
   there is nothing to change in the database role).

4. Autovacuum: the 16 partitions have TOASTs of ~18 GB; with
   `autovacuum_vacuum_scale_factor = 0.2` the threshold is reached earlier
   and in steps. Nothing to change, just watch `last_autovacuum` of the
   partitions the first week.

5. The aggregations that do not filter by `file_id`. Measured with 1.2 M rows,
   60 K files and 16 partitions:

   | query | plain table (PRO today) | partitioned | |
   |---|---|---|---|
   | team snapshot quota | 186 ms | 228 ms | +22% |
   | team snapshot quota, as rewritten in the code | 204 ms | **130 ms** | -30% |
   | telemetry `count(*)` | 90 ms | 129 ms | +43% |
   | telemetry `count(distinct file_id)` | 116 ms | 113 ms | 0% |

   - The team quota is already fixed in the code: it resolves the `file_id` of
     the team first, which lets the planner prune the partitions when it
     chooses an index scan (with a sequential scan it reads all of them).
   - The telemetry does not get worse in practice: both metrics scan the whole
     heap (10 GB in PRO), the same before and after. The +43% of the bench is
     the cost per partition on a table that fits in the cache. To remove that
     scan, the index `file_change__created_at__idx` turns both metrics into an
     index scan over the rows of the day, at the cost of one more index on the
     biggest table. Decide it before phase 1, the index has to exist in both
     tables.

6. Drop the helper table of the copy and, days later, the old table:

```sql
DROP TABLE IF EXISTS file_change_copy_state;
DROP TABLE file_change_legacy;   -- out of hours: it generates WAL by
                                  -- dropping the pages, let it roll
```

That is where the dead space comes back: some 675 GB, and the database goes
from 3.1 TB to about 2.4 TB.

---

## Risks

| Risk | Mitigation |
|---|---|
| Massive WAL during the bulk (some 15 GB) | Throttle between batches; watch `pg_wal`, the replication slots and the replica lag |
| Latency seen by the application during the bulk | Throttle; low traffic hours; the bulk is I/O and does not block writes |
| `updated_at` not reliable in the rows changed before the deploy | The deploy goes before the bulk (0.3) and the delta passes cover from the start of the bulk |
| Lock/unlock/soft delete during the passes | Covered by the watermark; the cutover includes the final delta inside the lock |
| Hard delete of the GC between passes | The row is left with `deleted_at` in the new table; the GC cleans it up later |
| Orphan rows if a `file` is deleted halfway | The foreign key on `file_id` is `NO ACTION`: the GC deletes the rows of the file before the file, so a chunk that fails leaves the file in the trash and the next run deletes it |
| The files in the trash cannot be deleted while the legacy table has rows for them | The foreign key of the legacy table is dropped at the cutover; until then the files wait in the trash |
| Saves failing during the lock of the cutover | Freeze of minutes in a low traffic window; manual retries; the rollback is documented |
| Planner regression after the cutover | Immediate `ANALYZE` + the pruning `EXPLAIN`; the pool already sets the GUC |
| Clock drift between the application and the database | The delta window starts an hour before the bulk; the margin absorbs the drift |

## Success metrics

- After the cutover: `file_change` (partitioned) of some 7 GB, against 683 GB.
- After the `DROP` of the old table: the database goes from 3.1 TB to about
  2.4 TB.
- The access paths that filter by `file_id` (the lookup of a snapshot, the
  quota of a file and the lock of the GC by pair) prune to one partition when
  the planner chooses an index scan (`EXPLAIN`). The quota of the team and
  the discovery of the GC do not filter by a single file: they read all the
  16 partitions.
- No visible change for the user.
