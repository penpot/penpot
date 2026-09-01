-- Migration: Add unified `job` table.
--
-- A single row per unit of work, replacing the generic `task` table as the
-- durable substrate for backend jobs. Columns combine the dispatch/lifecycle
-- fields of `task` with optional user-facing ledger fields (borrowed from the
-- planned export_job/import_job tables, which this migration supersedes).
--
-- Job kinds are identified by `name` (the job-def registry key); user jobs are
-- marked by `profile_id IS NOT NULL` and expirable jobs by `expires_at IS NOT
-- NULL`. There is no `modified_at` trigger: the application code updates it on
-- every UPDATE (used for the unified lease/orphan detection).
--
-- The legacy `task` table stays in place (dormant): it keeps historical rows
-- and keeps being cleaned by tasks-gc until its eventual removal.

CREATE TABLE job (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text NOT NULL,
    queue        text NOT NULL,
    label        text,
    priority     int NOT NULL DEFAULT 100,
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    retry_num    int NOT NULL DEFAULT 0,
    max_retries  int NOT NULL DEFAULT 3,
    status       text NOT NULL DEFAULT 'new'
                 CHECK (status IN ('new', 'scheduled', 'running', 'retry',
                                   'completed', 'failed', 'cancelled')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    modified_at  timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    completed_at timestamptz,
    props        jsonb NOT NULL DEFAULT '{}',

    -- optional ledger columns (user-facing jobs)
    profile_id   uuid NULL REFERENCES profile(id) ON DELETE CASCADE,
    target       jsonb,
    progress     jsonb,
    error        jsonb,
    result       jsonb,
    resource_id  uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL,
    expires_at   timestamptz
);

ALTER TABLE job
  ALTER COLUMN name SET STORAGE external,
  ALTER COLUMN queue SET STORAGE external,
  ALTER COLUMN props SET STORAGE external;

CREATE INDEX job__dispatcher__idx
    ON job (status, scheduled_at)
    WHERE status IN ('new', 'retry');

CREATE INDEX job__orphan__idx
    ON job (status, modified_at)
    WHERE status = 'running';

CREATE INDEX job__profile__idx
    ON job (profile_id, created_at DESC)
    WHERE profile_id IS NOT NULL;
