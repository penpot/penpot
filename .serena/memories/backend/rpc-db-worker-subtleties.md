# Backend RPC/DB/Worker Subtleties

## RPC exposure and wrappers

- RPC commands are discovered from vars created by `app.util.services/defmethod`; adding a command namespace is not enough unless `backend/src/app/rpc.clj` includes it in `resolve-methods`.
- `GET`/`HEAD` RPC calls are only allowed for method names starting with `get-`. Other methods are method-not-allowed even if they are read-only internally.
- RPC auth defaults to enabled. Public endpoints must set `::auth false` metadata explicitly.
- The wrapper stack does auth before params validation, then auditing/rate/concurrency/metrics/retry/condition handling, with DB transaction handling inside that stack. `::db/transaction` metadata controls transaction wrapping.
- Params with `::sm/params` are decoded/conformed through the JSON transformer and successful IObj results get `:encode/json` metadata. Legacy spec conforming only applies when no Malli params schema exists.
- Nil RPC bodies become HTTP 204 unless explicit status metadata is present. Stream bodies default to `application/octet-stream` when no content type is set.

## DB helpers

- Most `app.db` helpers accept a pool, connection, or map containing `::db/pool` / `::db/conn`; preserve that convention in shared code.
- `db/tx-run!` uses `next.jdbc.transaction/*nested-tx* :ignore`: nested transaction calls reuse the outer transaction, not a savepoint. Use explicit savepoints when nested rollback semantics matter.
- `db/run!` opens/reuses one connection but does not create a transaction.
- `db/tjson` is Transit JSON for jsonb storage; `db/json` is plain JSON. Job params (`job.props`) are plain JSON, decoded with the job-def decoder (`app.jobs/decode-params`); only the legacy `task` props were Transit (`decode-transit-pgobject`).
- Advisory transaction locks accept UUIDs or ints. UUID locks are hashed using a zero-UUID seeded siphash.

## Workers and cron

- Job queues are tenant-prefixed (`<tenant>:<queue>`). Submit dedupe only removes not-yet-due `new` jobs with the same name/queue/label; it does not dedupe due, scheduled, retry, running, or completed work.
- The dispatcher selects `new`/`retry` jobs with `FOR UPDATE SKIP LOCKED`, marks them `scheduled`, and publishes the JSON payload `[id scheduled-at]` to the `penpot.worker.queue:<tenant>:<queue>` Redis list. The runner skips Redis messages whose scheduled timestamp no longer matches DB state.
- Lost `scheduled` jobs are rescheduled after 5 minutes; `running` jobs untouched longer than `:jobs-lease` (default 30 min) are marked failed with `{"code":"orphan"}`. Long jobs must `heartbeat!`/`progress!`.
- A missing job-def raises (`:no-job-definition`) instead of completing. Throwing with `ex-data :type ::retry` still controls retry behavior; `:strategy ::noop` retries without incrementing retry count.
- Cron entries claim their `scheduled_task` row with `FOR UPDATE SKIP LOCKED`, disable statement/idle-in-transaction timeouts locally, submit one `job` row per entry when no active instance exists (no-overlap), and reschedule themselves in `finally` unless interrupted. Worker, dispatcher, and cron components do not start when the DB pool is read-only.