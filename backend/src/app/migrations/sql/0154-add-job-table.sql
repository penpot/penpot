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
-- `params` carries the business payload (plain JSON) of the job. Progress is
-- not a column: it is an append-only `job_event` row, so a job keeps its whole
-- progress history instead of only the last value.
--
-- The legacy `task` table stays in place (dormant). Its cleanup remains
-- the responsibility of the parallel legacy version during migration.

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
    params       jsonb NOT NULL DEFAULT '{}',

    -- optional ledger columns (user-facing jobs)
    profile_id   uuid NULL REFERENCES profile(id) ON DELETE NO ACTION DEFERRABLE,
    error        jsonb,
    result       jsonb,
    resource_id  uuid NULL REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE,
    expires_at   timestamptz
);

-- Dispatcher claim index: the worker selects due `new`/`retry` rows,
-- orders them by priority and scheduled time, and locks the batch with
-- SKIP LOCKED. The partial predicate keeps terminal rows out; the queue
-- prefix filter is applied after using this ordering index.
CREATE INDEX job__dispatcher__idx
    ON job (priority DESC, scheduled_at)
    WHERE status IN ('new', 'retry');

-- Dispatcher orphan sweep: the worker finds `running` rows whose
-- modified_at is older than the lease and marks them failed.
CREATE INDEX job__orphan__idx
    ON job (status, modified_at)
    WHERE status = 'running';

-- User-facing job ledger index: it supports filtering by profile and
-- ordering a user's jobs by newest first. No current production query
-- uses it yet; it is kept for the user-facing ledger path.
CREATE INDEX job__profile__idx
    ON job (profile_id, created_at DESC)
    WHERE profile_id IS NOT NULL;

-- Cron no-overlap check: the scheduler counts active jobs with the same
-- name and label before submitting another instance of a cron entry.
CREATE INDEX job__name_label__idx
    ON job (name, label)
    WHERE status IN ('new', 'scheduled', 'running', 'retry');

-- Storage GC reference check: it answers whether any job still points
-- to a candidate storage object. Rows without a resource are excluded.
CREATE INDEX job__resource__idx
    ON job (resource_id)
    WHERE resource_id IS NOT NULL;

-- Dispatcher recovery: it finds `scheduled` jobs that were pushed to
-- Redis but not claimed within the recovery window.
CREATE INDEX job__scheduled__idx
    ON job (status, scheduled_at)
    WHERE status = 'scheduled';

-- Jobs GC expiration: it scans jobs past expires_at while excluding
-- running and retry jobs from the expiration delete.
CREATE INDEX job__expires__idx
    ON job (expires_at)
    WHERE expires_at IS NOT NULL;

-- Jobs GC retention: it finds old internal terminal jobs with no
-- profile, which are eligible for removal after the retention delay.
CREATE INDEX job__retention__idx
    ON job (modified_at)
    WHERE status IN ('completed', 'failed', 'cancelled')
      AND profile_id IS NULL;

-- Append-only job event log: `start`, `progress`, `retry` and `end` rows in
-- insertion order. This is the only source of truth for progress; there is no
-- Redis key and no mutable progress column. The foreign key cascades so
-- deleting a job removes its whole history, and it is DEFERRABLE like every
-- other new reference of the substrate.
CREATE TABLE job_event (
    id         bigserial PRIMARY KEY,
    job_id     uuid NOT NULL REFERENCES job(id) ON DELETE CASCADE DEFERRABLE,
    kind       text NOT NULL
               CHECK (kind IN ('start', 'progress', 'retry', 'end')),
    payload    jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Event history read: all events of a job, and the last progress report
-- (`WHERE job_id = ? AND kind = 'progress' ORDER BY created_at DESC LIMIT 1`).
CREATE INDEX job_event__job_kind_created_idx
    ON job_event (job_id, kind, created_at DESC, id DESC);
