# Backend Subtleties

## RPC exposure and wrappers

- RPC commands are discovered from vars created by `app.util.services/defmethod`; adding a command namespace is not enough unless `backend/src/app/rpc.clj` includes it in `resolve-methods`.
- `GET`/`HEAD` RPC calls are only allowed for method names starting with `get-`. Other methods are method-not-allowed even if they are read-only internally.
- RPC auth defaults to enabled. Public endpoints must set `::auth false` metadata explicitly.
- The wrapper stack does auth before params validation, then auditing/rate/concurrency/metrics/retry/condition handling, with DB transaction handling inside that stack. `::db/transaction` metadata controls transaction wrapping.
- Params with `::sm/params` are decoded/conformed through the JSON transformer and successful IObj results get `:encode/json` metadata. Legacy spec conforming only applies when no Malli params schema exists. Client params are stripped of qualified keys (`d/without-qualified`) before merging with the server auth context, so request bodies cannot override `::profile-id`, `::auth-type`, or `::token-perms`.
- Nil RPC bodies become HTTP 204 unless explicit status metadata is present. Stream bodies default to `application/octet-stream` when no content type is set.

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

## Config and HTTP/session middleware

- `app.config/config` and `flags` are dynamic `defonce` vars populated from `PENPOT_*` env vars through the shared schema string transformer. Tests and tooling can bind them.
- `parse-flags` automatically adds `:disable-secure-session-cookies` when `public-uri` is plain HTTP and not localhost. This changes cookie defaults without an explicit env flag.
- The backend sets Clojure `*assert*` globally from the `:backend-asserts` feature flag. Assertion-dependent checks can therefore differ by runtime flags.
- Request body parsing is mostly POST-oriented and supports Transit JSON plus plain JSON. Plain JSON request keys are kebab-decoded before being merged into `:params`.
- Response formatting negotiates with `Accept` or `_fmt=json`. Transit is the default for collection/boolean bodies; JSON encoding has special pointer-map handling.
- Auth prefers the session cookie token before the `Authorization` header. Headers may be `Token` or `Bearer`. Only `kid=1`/`ver=1` tokens are decoded as session tokens; anything else is left unauthenticated (legacy v1 tokens were removed).
- Shared-key auth requires `x-shared-key` as `<key-id> <key>` and stores the lowercased key id on the request. If no shared keys are configured it always rejects.
- Session management uses DB storage unless the DB pool is read-only, then falls back to the in-memory manager. Sessions use only the v2 UUID model (`http_session_v2`); legacy string ids were removed.
- Session cookies are renewed when `modified-at` is older than the 6h renewal interval. SameSite is `none` for CORS, otherwise strict/lax based on config. Session lifetime config and GC: `mem:backend/session-expiration`.

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