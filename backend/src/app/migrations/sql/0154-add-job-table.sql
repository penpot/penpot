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
-- `tenant` is the instance the job belongs to and `queue` is its bare queue
-- name. They are separate columns because several instances can share one
-- database and the tenant is what keeps them from claiming each other's work:
-- as an equality the filter is something an index can serve, where a
-- `<tenant>:<queue>` prefix could only be matched with a LIKE over text. Every
-- query that acts on one instance's work (dispatch, orphan sweep, reschedule,
-- cron no-overlap check, submit dedupe) filters on `tenant` for that reason.
-- The prefix still exists, but only in the Redis key the queue's payloads
-- travel through, composed in one place by `app.worker/queue-key`.
--
-- The legacy `task` table stays in place (dormant) on purpose until the
-- next version, so PRE (legacy) and HOURLY (jobs) can run in parallel on
-- the same database and the deploy can roll back. During that window
-- each table is cleaned by its own version: `task` rows by the parallel
-- legacy `tasks-gc`, `job` rows by the new `:jobs-gc`.

CREATE TABLE job (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text NOT NULL,
    tenant       text NOT NULL,
    queue        text NOT NULL,
    label        text,
    priority     int NOT NULL DEFAULT 100,
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    retry_num    int NOT NULL DEFAULT 0,
    max_retries  int NOT NULL DEFAULT 3,
    status       text NOT NULL DEFAULT 'new'
                 CHECK (status IN ('new', 'scheduled', 'running', 'retry',
                                   'completed', 'failed', 'cancelled',
                                   'aborted')),
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

-- Dispatcher claim index: the worker selects due `new`/`retry` rows of
-- its own tenant, orders them by priority and scheduled time, and locks
-- the batch with SKIP LOCKED. The partial predicate keeps terminal rows
-- out and `status` needs no key column for that reason; `tenant` leads
-- because it is an equality the index can answer, and the leading
-- columns then carry the ORDER BY unchanged. `queue` does not lead: the
-- dispatcher claims every queue of its tenant and groups them when
-- pushing, so filtering on one queue would be wrong.
CREATE INDEX job__dispatcher__idx
    ON job (tenant, priority DESC, scheduled_at)
    WHERE status IN ('new', 'retry');

-- Dispatcher orphan sweep: the worker finds the `running` rows of its
-- own tenant whose modified_at is older than the lease and marks them
-- `aborted` (system-side terminal, never retried).
CREATE INDEX job__orphan__idx
    ON job (tenant, modified_at)
    WHERE status = 'running';

-- User-facing job ledger index: it supports filtering by profile and
-- ordering a user's jobs by newest first. No current production query
-- uses it yet; it is kept for the user-facing ledger path.
CREATE INDEX job__profile__idx
    ON job (profile_id, created_at DESC)
    WHERE profile_id IS NOT NULL;

-- Cron no-overlap check and submit dedupe: both look for jobs of one
-- tenant with the same name and queue and label that have not started
-- yet, so one index serves the two queries.
CREATE INDEX job__name_label__idx
    ON job (tenant, name, queue, label)
    WHERE status IN ('new', 'scheduled', 'running', 'retry');

-- Storage GC reference check: it answers whether any job still points
-- to a candidate storage object. Rows without a resource are excluded.
CREATE INDEX job__resource__idx
    ON job (resource_id)
    WHERE resource_id IS NOT NULL;

-- Dispatcher recovery: it finds the `scheduled` jobs of its own tenant
-- that were pushed to Redis but not claimed within the recovery window.
CREATE INDEX job__scheduled__idx
    ON job (tenant, scheduled_at)
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
    WHERE status IN ('completed', 'failed', 'cancelled', 'aborted')
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

-- Deletion protection, like the domain tables (`team`, `profile`, the
-- file thumbnail tables). The only intended deleter of `job` rows is the
-- jobs GC, which also marks the referenced storage objects as touched in
-- the same transaction; a stray `DELETE FROM job` would drop the row
-- (and cascade `job_event`) without touching the object, orphaning it
-- forever. The GC and the submit dedupe disable the guard inside their
-- transaction with `SET LOCAL rules.deletion_protection TO off`, the
-- same escape hatch used by `app.tasks.objects-gc`.
CREATE OR REPLACE TRIGGER deletion_protection__tgr
BEFORE DELETE ON job FOR EACH STATEMENT
  WHEN ((current_setting('rules.deletion_protection', true) IN ('on', '')) OR
        (current_setting('rules.deletion_protection', true) IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();
