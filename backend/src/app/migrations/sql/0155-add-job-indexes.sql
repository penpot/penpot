-- Migration: Indexes for the unified `job` table sweep paths.
--
-- The base `0154-add-job-table` migration covers the dispatcher claim
-- (`new`/`retry`), the orphan scan (`running`), the cron no-overlap
-- precheck (name+label) and the storage GC resource check. The remaining
-- sweep paths had no index: the dispatcher reschedule of lost `scheduled`
-- rows, the jobs-GC expiration scan and the jobs-GC retention scan. With
-- a large `job` table those degrade into sequential scans, so each gets
-- its own partial index here.

CREATE INDEX job__scheduled__idx
    ON job (status, scheduled_at)
    WHERE status = 'scheduled';

CREATE INDEX job__expires__idx
    ON job (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX job__retention__idx
    ON job (modified_at)
    WHERE status IN ('completed', 'failed', 'cancelled')
      AND profile_id IS NULL;
