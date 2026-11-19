-- Index for the version (snapshot) quota checks of app.rpc.quotes:
-- sql:get-snapshots-per-file and sql:get-snapshots-per-team count the live
-- versions created by a user, and this partial index answers both counts
-- with an index only scan, without walking the whole file_change table.
--
-- The predicate must stay identical to the queries: the planner only uses a
-- partial index when the query where clause implies the index predicate.
-- Versions are told apart by label (every snapshot has one); file_change.data
-- is a legacy column, only written by the legacy-db file data backend.

CREATE INDEX IF NOT EXISTS file_change__user_snapshots__idx
    ON file_change (file_id)
 WHERE label IS NOT NULL
   AND created_by = 'user'
   AND deleted_at IS NULL;

-- NOTE: on instances where file_change is large, run the statement above
-- out of band, before deploying, with CONCURRENTLY instead: this migration
-- runs inside a transaction, and a plain CREATE INDEX locks the table
-- against writes for the whole build (in Penpot every file save writes a row
-- here), while CONCURRENTLY does not. Run it on its own, not inside a
-- transaction block:
--
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS file_change__user_snapshots__idx
--       ON file_change (file_id)
--    WHERE label IS NOT NULL
--      AND created_by = 'user'
--      AND deleted_at IS NULL;
--
-- A CONCURRENTLY build interrupted midway leaves the index INVALID: drop it
-- and retry. Verify with:
--
--   SELECT indexdef FROM pg_indexes
--    WHERE indexname = 'file_change__user_snapshots__idx';
--   SELECT indisvalid FROM pg_index
--    WHERE indexrelid = 'file_change__user_snapshots__idx'::regclass;
--
-- Once the index exists, this migration finds it and does nothing.
