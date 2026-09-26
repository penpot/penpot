# Backend Subtleties

## RPC exposure and wrappers

- RPC commands are discovered from vars created by `app.util.services/defmethod`; adding a command namespace is not enough unless `backend/src/app/rpc.clj` includes it in `resolve-methods`.
- `GET`/`HEAD` RPC calls are only allowed for method names starting with `get-`. Other methods are method-not-allowed even if they are read-only internally.
- RPC auth defaults to enabled. Public endpoints must set `::auth false` metadata explicitly.
- The wrapper stack does auth before params validation, then auditing/rate/concurrency/metrics/retry/condition handling, with DB transaction handling inside that stack. `::db/transaction` metadata controls transaction wrapping.
- Params with `::sm/params` are decoded/conformed through the JSON transformer and successful IObj results get `:encode/json` metadata. Legacy spec conforming only applies when no Malli params schema exists. Client params are stripped of qualified keys (`d/without-qualified`) before merging with the server auth context, so request bodies cannot override `::profile-id`, `::auth-type`, or `::token-perms`.
- Params schemas are open by default, so undeclared client keys reach the handler unless the map is `:closed true`. Creation commands (`create-file`, `create-project`, `create-team`, `create-team-with-invitations`, `upload-file-media-object`, `create-file-media-object-from-url`, `assemble-file-media-object`) use closed schemas: a client-provided `:id` fails with `:params-validation`. Their internal creation functions still accept an optional explicit `:id` for imports, duplicates and deterministic test fixtures.
- Nil RPC bodies become HTTP 204 unless explicit status metadata is present. Stream bodies default to `application/octet-stream` when no content type is set.

## DB helpers

- Most `app.db` helpers accept a pool, connection, or map containing `::db/pool` / `::db/conn`; preserve that convention in shared code.
- `db/tx-run!` uses `next.jdbc.transaction/*nested-tx* :ignore`: nested transaction calls reuse the outer transaction, not a savepoint. Use explicit savepoints when nested rollback semantics matter.
- `db/run!` opens/reuses one connection but does not create a transaction.
- `db/tjson` is Transit JSON for jsonb storage; `db/json` is plain JSON. Job params (`job.params`) are plain JSON, decoded with the job-def decoder (`app.jobs/decode-params`); only the legacy `task` props were Transit (`decode-transit-pgobject`).
- Advisory transaction locks accept UUIDs or ints. UUID locks are hashed using a zero-UUID seeded siphash.

## Workers and cron

- Job queues are tenant-prefixed (`<tenant>:<queue>`) in the `job.queue` column, and the dispatcher filters them with `queue ~~* '<tenant>:%'`. That prefix is load-bearing: several instances share one database and the tenant is what keeps an instance from claiming another environment's jobs. Every consumer that shows a queue to anything but the dispatcher strips it (`app.jobs.metrics/queue-label`); a separate `tenant` column is an open item in the board.
- Submit dedupe only removes not-yet-due `new` jobs with the same name/queue/label; it does not dedupe due, scheduled, retry, running, or completed work.
- The dispatcher selects `new`/`retry` jobs with `FOR UPDATE SKIP LOCKED`, marks them `scheduled`, and publishes the JSON payload `[id scheduled-at]` to the `penpot.worker.queue:<tenant>:<queue>` Redis list. The runner skips Redis messages whose scheduled timestamp no longer matches DB state.
- Lost `scheduled` jobs are rescheduled after 5 minutes; `running` jobs untouched longer than `:jobs-lease` (default 30 min) are marked failed with `app.jobs/orphan-error` (`type :internal`, `code :orphan`). Long jobs must `heartbeat` with a `:progress` report.
- An orphan writes NO `job_event`: the process that could report it is dead, so the dispatcher's bulk UPDATE is the whole story and turning it into a per-row loop would only cost a query per orphan.
- `job_event` is append-only and deleted by cascade with the job. `progress` is a row there, not a column: no Redis and no mutable column to keep in sync.
- A missing job-def raises (`:no-job-definition`) instead of completing. Throwing with `ex-data :type ::retry` still controls retry behavior; `:strategy ::noop` retries without incrementing retry count.
- Cron entries claim their `scheduled_task` row with `FOR UPDATE SKIP LOCKED`, disable statement/idle-in-transaction timeouts locally, submit one `job` row per entry when no active instance exists (no-overlap), and reschedule themselves in `finally` unless interrupted. Worker, dispatcher, and cron components do not start when the DB pool is read-only.

## Config and HTTP/session middleware

- `app.config/config` and `flags` are dynamic `defonce` vars populated from `PENPOT_*` env vars through the shared schema string transformer. Tests and tooling can bind them.
- `parse-flags` automatically adds `:disable-secure-session-cookies` when `public-uri` is plain HTTP and not localhost. This changes cookie defaults without an explicit env flag.
- The backend sets Clojure `*assert*` globally from the `:backend-asserts` feature flag. Assertion-dependent checks can therefore differ by runtime flags.
- Request body parsing is mostly POST-oriented and supports Transit JSON plus plain JSON. Plain JSON request keys are kebab-decoded before being merged into `:params`.
- Response formatting negotiates with `Accept` or `_fmt=json`. Transit is the default for collection/boolean bodies; JSON encoding has special pointer-map handling.
- Auth prefers the session cookie token before the `Authorization` header. Headers may be `Token` or `Bearer`; JWTs with `kid=1` and `ver=1` are decoded as v1 session tokens, otherwise they are treated as legacy tokens.
- Shared-key auth requires `x-shared-key` as `<key-id> <key>` and stores the lowercased key id on the request. If no shared keys are configured it always rejects.
- Session management uses DB storage unless the DB pool is read-only, then falls back to the in-memory manager. DB sessions support both legacy string ids and v2 UUID session ids.
- Session cookies are renewed when using a legacy string id or when `modified-at` is older than the renewal interval. SameSite is `none` for CORS, otherwise strict/lax based on config.

## HTTP server self-metrics

- `app.http` enables Undertow connection statistics via the yetti option `:server/statistics` (requires yetti ≥ v11.11, which exposes it; before the patch `ListenerInfo#getConnectorStatistics` returned `nil`).
- A daemon sampler built on `promesa.exec` (`px/scheduled-executor` plus a self-rescheduling `px/schedule` chain, so a failing sample never cancels the next one; 15 s, started with the server in `ig/init-key` and stopped with `px/shutdown-now` in `halt-key!`) publishes worker and listener state: `penpot_http_worker_queue_size`, `busy_threads`, `pool_size`, `max_pool_size`, `penpot_http_connector_active_connections`, `requests_total`, `errors_total`. Definitions live in `app.main/default-metrics`.
- The xnio worker MXBean can return transient `-1` (e.g. busy-thread count); negative samples are discarded (gauge keeps its previous value). Undertow exposes absolute request/error totals, so the sampler keeps a watermark atom and publishes deltas; a counter reset (decreasing totals) skips the negative delta and moves the watermark forward.
- The `process_*` families (`process_open_fds`, `process_max_fds`, `process_cpu_seconds_total`, …) come from the prometheus client `StandardExports`, registered by `app.metrics/create-registry`. They read the OS MXBean reflectively and need the `jdk.management` module: on a pruned `jlink` JRE the MXBean is `sun.management.BaseOperatingSystemImpl`, the getters throw `NoSuchMethodException` and `StandardExports#collect` swallows it, so those families silently vanish from `/metrics`. `docker/images/Dockerfile.backend` keeps `jdk.management` in the `--add-modules` list, and `backend-tests.metrics-test` pins the contract.

## Metrics recording

- `app.metrics/run!` is safe by default: a recording failure never throws (a metrics bug must not change the behavior of the measured operation). The first failure per metric id logs at `warn`, later ones at `debug`. The `instance` precondition is a plain assert (the backend enables `:backend-asserts`), and the collector lookup sits outside the recording guard, so a missing instance fails hard even when asserts are disabled. `::mtx/metrics` is required by the storage, s3-backend, db-pool and jobs APIs; jobs helpers never silently skip a missing metrics instance.
- `app.db/after-commit!` is the boundary for SQL-backed metrics: callbacks registered in nested transactions are drained only by the outermost successful commit and are discarded on rollback.

## Storage and media

- Storage abstraction, backend configuration, logical buckets, object lifecycle, deduplication, access rules, and garbage collection: `mem:backend/storage`.
- SVG validation strips DOCTYPE and uses secure SAX parsing. Basic SVG info falls back to 100x100 dimensions when width/height/viewBox are missing.
- Raster metadata is shell-derived with ImageMagick `identify`, verifies detected MIME against the supplied MIME, and swaps dimensions for EXIF orientations 6/8.
- Remote image download requires 2xx status, `content-length`, a known MIME, and size under the configured maximum before writing the temp file; mismatched byte count is an internal error.
- Font processing shells out to FontForge and WOFF conversion tools and can derive TTF/OTF/WOFF variants from uploaded fonts.

## File data persistence

- File data backends are `legacy-db`, `db`, and `storage`. The storage backend keeps encoded file data in storage bucket `file-data`; the DB row stores metadata with `storage-ref-id` and nil data.
- `fdata/upsert!` touches any storage object referenced by incoming metadata before storing the new row/blob.
- Pointer-map fragments are persisted separately as type `fragment`, and only modified pointer maps are written.
- `fdata/realize` combines pointer realization and object-map realization. Use it before operations that need complete in-memory file data instead of pointer placeholders.