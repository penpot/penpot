# Production infrastructure (services Penpot depends on)

Backend (`app.config`, `PENPOT_*` env vars) is parameterized; deployments choose providers.

## Services

- **PostgreSQL**: durable store. Profiles, teams, files, sessions, audit, `storage_object` metadata, the `task` queue, `scheduled_task` cron registry, migrations. File-data also lives here when the file-data backend is `legacy-db`/`db`. One shared DB across all backends.
- **Redis (Valkey-compatible)**: per-backend message bus and cache. Concrete uses: msgbus Pub/Sub for collaborative-editing broadcasts and team/profile-org notifications fired by RPC handlers (`app.rpc.notifications`, `files_update`, `teams`, `websocket`); file-summary cache gated by `enable-redis-cache`; rate-limit counters; and the dispatcher→runner work hand-off list `penpot.worker.queue:<tenant>:<queue>`. `PENPOT_REDIS_URI`.
- **Object storage**: backends `:s3` and `:fs`. S3 in prod; devenv uses MinIO. Holds uploaded media, file-data when the file-data backend is `storage`, exports. Backend-side details (resolve, dedup, bucket set, file-data backends): `mem:backend/http-storage-filedata-subtleties`.
- **SMTP mailer**: invitations, password resets, email verification (sent via the `:sendmail` worker task).
- **LDAP** (optional auth provider): helpers in `app.auth.*`, gated by `enable-login-with-ldap`.

## Task queue and worker model

Async tasks are enqueued via `wrk/submit!` (`app.worker`), which inserts a row into the shared Postgres `task` table tagged with `queue = "<tenant>:<queue-name>"`. Submission is **fire-and-forget** — RPC handlers never poll, never wait, and workers never publish to msgbus. The only completion signal is the `task` row's `status` / `completed_at` columns, which nothing in `rpc/` reads. Soft-delete RPCs return immediately after marking the top-level row, leaving the cascade and reaping to workers.

Workers run on backends with `enable-backend-worker` in `PENPOT_FLAGS`. Each worker-enabled backend has a `dispatcher` (polls `task` with `FOR UPDATE SKIP LOCKED`, marks status='scheduled', RPUSHes claimed task IDs into **its own** Redis list) and one or more `runner`s per queue (BLPOP from that same local list, execute, update the Postgres row). The Redis hand-off list is purely intra-backend — cross-backend coordination happens at the Postgres row level.

## Cross-backend safety

Postgres row locking is the only correctness primitive: `task` claims via `FOR UPDATE SKIP LOCKED`, cron firing via `FOR UPDATE SKIP LOCKED` on the `scheduled_task` row, plus task-handler-internal locks (e.g. `file_gc_scheduler` locks candidate file rows). This makes the work-claim path safe across any number of worker-enabled backends.

Two known race patterns survive multi-backend operation:

- **Cron dedup is best-effort.** The lock on `scheduled_task` is released when the task body finishes. If two backends' cron timers fire for the same scheduled instant with a gap larger than the task body's runtime, both execute it. Penpot's cron entries are idempotent (`session-gc`, `objects-gc`, `storage-gc-*`, `tasks-gc`, `upload-session-gc`, `file-gc-scheduler`); the exceptions are `:telemetry` (would double-report) and `:audit-log-archive` (depends on archive target idempotency).
- **`wrk/submit! ::dedupe true`** does a non-atomic `DELETE` then `INSERT`. Concurrent cross-backend submits can both bypass the `DELETE` (each sees the other's uncommitted insert as absent) and end up with duplicate `'new'` rows. Each row claims and runs once independently, so the underlying work is fine; the "at most one pending" guarantee weakens.

Penpot in production lives with both: horizontal-scale deployments accept "exactly-once" as "essentially-once for idempotent operations." Devenv parallel instances handle it by running workers only on ws0 (see `mem:devenv/core`).

## See also

- Devenv composition and the ws0-only worker placement: `mem:devenv/core`.
- Storage backend resolution, dedup, file-data lifecycle: `mem:backend/http-storage-filedata-subtleties`.
