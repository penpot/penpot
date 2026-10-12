# Backend RPC/DB/Worker Subtleties

## RPC exposure and wrappers

- RPC commands are discovered from vars created by `app.util.services/defmethod`; adding a command namespace is not enough unless `backend/src/app/rpc.clj` includes it in `resolve-methods`.
- `GET`/`HEAD` RPC calls are only allowed for method names starting with `get-`. Other methods are method-not-allowed even if they are read-only internally.
- RPC auth defaults to enabled. Public endpoints must set `::auth false` metadata explicitly.
- The wrapper stack does auth before params validation, then auditing/rate/concurrency/metrics/retry/condition handling, with DB transaction handling inside that stack. `::db/transaction` metadata controls transaction wrapping.
- Params with `::sm/params` are decoded/conformed through the JSON transformer and successful IObj results get `:encode/json` metadata. Legacy spec conforming only applies when no Malli params schema exists.
- Nil RPC bodies become HTTP 204 unless explicit status metadata is present. Stream bodies default to `application/octet-stream` when no content type is set.
- `::climit/id` is either a plain keyword, which gives one shared limiter per process, or the vector form `[[id key-fn]]`, which gives one limiter per key. `climit/wrap` takes the key only from that positional `key-fn` (`climit/get-limits` maps a plain keyword to a single-element vector), so `::climit/key-fn` is metadata that nothing reads: a command declaring `::climit/id :x/by-profile` next to it ends up with a single shared limiter, and the missing key is visible in the `app.rpc.climit` log lines, whose `id` carries no key suffix. That is what happened to `push-audit-events`, the last user of the dead metadata, from `a5c6d78ee5` (2024-01-27, "fix some fundamental bugs on climit module", which moved the key to the vector form without updating it) until it was fixed; nothing declares `::climit/key-fn` any more, so keep using the vector form for anything that must be per profile.
- The `penpot_rpc_climit_queue`/`permits` gauges carry only the limiter name, never the key, so every per-profile limiter of the same name collapses into one series and the last call wins: a per-key pile-up is invisible there and a healthy looking `cola=0` proves nothing. A stuck limiter does show up in `penpot_rpc_climit_timing_count` (the acquired path), which is a counter: compare the same limiter name across instances and the stuck one reads 0 acquisitions while its peer reads thousands.
- Nothing in the limiter path has a timeout unless the config sets one, and `psm/acquire` then calls `Semaphore.acquire`, which parks the calling thread forever. That is what turns a wait into a lost worker thread: a permanently parked thread still counts as in-system for every outer limiter of its chain, so the outermost one (the method's own by-profile limiter) stops granting permits and answers 429 to everything. A limiter without `:queue` in the config gets an unlimited queue and therefore never rejects: its pile-up is silent, with no 429 and no log line except the WARN `enqueued`/`acquired` lines of the keyed id, and its per-key nature is invisible in the metrics. Note that a request whose profile limit is exhausted parks *inside* the outer limiter while holding its permit, and handlers that assign `::climit/id` dynamically (media, profile) re-acquire the same keyed limiters, so a chain of such waits can close into a cycle that no timeout ever breaks.

## DB helpers

- Most `app.db` helpers accept a pool, connection, or map containing `::db/pool` / `::db/conn`; preserve that convention in shared code.
- `db/tx-run!` uses `next.jdbc.transaction/*nested-tx* :ignore`: nested transaction calls reuse the outer transaction, not a savepoint. Use explicit savepoints when nested rollback semantics matter.
- `db/run!` opens/reuses one connection but does not create a transaction.
- `db/tjson` is Transit JSON for jsonb storage; `db/json` is plain JSON. Worker task props use Transit and are decoded with `decode-transit-pgobject`.
- Advisory transaction locks accept UUIDs or ints. UUID locks are hashed using a zero-UUID seeded siphash.

## Workers and cron

- Task queues are tenant-prefixed. Submit dedupe only removes not-yet-due `new` tasks with the same name/queue/label; it does not dedupe due, scheduled, retry, running, or completed work.
- The dispatcher selects `new`/`retry` tasks with `FOR UPDATE SKIP LOCKED`, marks them `scheduled`, and publishes Redis payload `[id scheduled-at]`. The runner skips Redis messages whose scheduled timestamp no longer matches DB state.
- Lost `scheduled` tasks are rescheduled after about 5 minutes; `running` tasks older than about 24 hours are marked failed as orphans.
- A task handler that is missing or returns an invalid result currently defaults to completed after warning. Throwing with `ex-data :type ::retry` controls retry behavior; `:strategy ::noop` retries without incrementing retry count.
- Cron jobs lock their `scheduled_task` row with `FOR UPDATE SKIP LOCKED`, disable statement/idle-in-transaction timeouts locally, and reschedule themselves in `finally` unless interrupted. Worker, dispatcher, and cron components do not start when the DB pool is read-only.