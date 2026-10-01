# Backend Architecture and Workflow

Backend: JVM Clojure; Integrant; PostgreSQL; Redis/Valkey; RPC; HTTP; storage; mail; audit/logging; workers.

## Focused memories

- Cross-cutting backend subtleties (RPC, DB, workers, cron, HTTP/sessions, storage, file data): `mem:backend/subtleties`
- Storage abstraction, logical buckets, object lifecycle, deduplication, access, and garbage collection: `mem:backend/storage`.
- Embedded Ladybug graph experiment, projection, incremental sync, console, and risks: `mem:backend/graph-experiment`
- Session lifetime config, token `:exp`, and idle/absolute GC: `mem:backend/session-expiration`.
- Auth flows, permission model, teams, projects, invitations, comments, webhooks, audit: `mem:backend/auth-permissions-product-domains`
- Audit-log event collection (RPC wrapper, frontend ingestion), telemetry duality, webhook fan-out, error reporters, Nexus archival and retention: `mem:backend/audit-log`
- Services, task-queue/Pub-Sub topology constraints -> `mem:prod-infra/core`.

## Stable namespace map

- `app.rpc.commands.*`: RPC command implementations exposed under `/api/rpc/command/<cmd-name>`.
- `app.rpc.permissions`: permission predicate/check helper factories.
- `app.http.*`: HTTP routes and middleware.
- `app.auth.*`: provider-specific authentication helpers such as LDAP/OIDC.
- `app.loggers.*`: audit, webhook, database, and external log integrations.
- `app.db.*` / `app.db`: next.jdbc wrapper and SQL helpers.
- `app.tasks.*`: background task handlers.
- `app.worker`: task execution/cron plumbing.
- `app.main`: Integrant system map and component wiring.
- `app.config`: `PENPOT_*` env config and feature flags.
- `app.srepl.*`: development REPL helpers for manual backend operations (data inspection, migration helpers, one-off admin tasks).
- `app.nitrate`, `app.rpc.commands.nitrate`, and `app.rpc.management.nitrate`: external Nitrate subscription/organization integration, gated by the `:nitrate` feature flag and shared-key HTTP calls.

## RPC conventions

RPC commands are defined with `app.util.services/defmethod` and schemas. Use `get-` prefixes for read operations. Command metadata usually includes auth, docs version, params schema, and result schema. Return plain maps/vectors or raise structured exceptions from `app.common.exceptions`.

Backend RPC command areas without focused memories include access tokens, binfile, demo, feedback, file snapshots, fonts, management, Nitrate, and webhooks beyond the notes in `mem:backend/auth-permissions-product-domains`; inspect nearby command tests and command metadata before changing them.

## DB conventions

`app.db` helpers accept cfg, pool, or conn in most places and convert kebab-case to snake_case:
- `db/get`, `db/get*`, `db/query`, `db/insert!`, `db/update!`, `db/delete!`.
- `db/get*`/`db/query` signature is `(ds table where-params & {:as opts})`: trailing keywords become an opts MAP (ignored by the select builder — use `{::db/remove-deleted false}` map as the single vararg to also see rows with `deleted_at` set), while a trailing ODD number of keywords crashes with "Don't know how to create ISeq from: Keyword". Extra keywords are NOT a column filter: rows always come back with all columns.
- Next.js/JDBC caveat: where-maps don't support value vectors (no `IN`); use `status = ANY(?)` with `db/create-array` inside `db/tx-run!`.
- Use `db/run!` for multiple operations on one connection.
- Use `db/tx-run!` for transactions.
- `job`-substrate specifics (unified jobs): the only writers of a job status are `claim`, `retry-job`, `complete`, `fail` and `cancel` in `app.jobs`; each one is a conditional update plus its `job_event` row in the same transaction, so history can never contradict the row. A writer that affects no row writes no event and no metric. They never join the caller's transaction, because a lifecycle write reports on work that already happened: a rolled back caller cannot undo a transition, and an independent connection cannot see the caller's uncommitted job row, so a job must be committed before it can be claimed. GC deletes return resource ids via `RETURNING` inside one transaction.
- Every public function of `app.jobs` takes a cfg map. A bare pool or connection is a caller bug and is not coerced: the substrate has no conversion of its own, and the ones in `app.db` are the db layer's business.
- Every new foreign key of the substrate is `DEFERRABLE`, `job_event.job_id` included.
- Some tables carry a SQL deletion guard (`raise_deletion_protection` + a `deletion_protection__tgr` statement trigger): `profile`, `team`, `team_font_variant`, `file_media_object`, `file_thumbnail`, `file_tagged_object_thumbnail` and `job`. A `DELETE` raises unless the transaction runs `SET LOCAL rules.deletion_protection TO off` (the guard is on when the GUC is unset, empty or `on`); see `app.tasks.objects-gc`, `app.jobs.gc` and `th/db-force-delete!`.

Database migrations live in `backend/src/app/migrations/`; pure SQL migrations are under `backend/src/app/migrations/sql/`. SQL filenames conventionally start with a sequence and verb/table description, e.g. `0026-mod-profile-table-add-is-active-field`. Applied migrations are tracked in the `migrations` table.

For interactive PostgreSQL access with correct dev defaults, use `scripts/psql`; to dump the current DDL schema, use `scripts/db-schema` (see `mem:scripts/psql`).

For deeper details on transaction semantics, advisory locks, Transit vs JSON helpers, and dev/test DB URLs: `mem:backend/subtleties`.

## Background tasks (unified jobs)

Every background job is a job-def: a plain `(defn execute-X [cfg params] ...)` in its namespace + a malli params schema + an `ig/init-key` that returns the job-def map `{::jobs/name, ::jobs/schema, ::jobs/handler, ::jobs/decoder, ::jobs/validator}` (decoder/validator precompiled at init). The registry is the `::jobs/defs` wiring in `app.main`, which also populates a module-level registry used by `jobs/submit` as fallback — job-def components cannot ig/ref `::jobs/defs` (wiring cycle). Submit from RPC code passes its RPC cfg (it carries `::jobs/defs` via ig/ref).

- `::jobs/handler` is `(fn [context params] ...)`. It is NOT given the runner cfg: a job-def closes over its own dependencies, and handing it the runner cfg would let a handler reach the pool or any other component. A wrapper passes the context to its implementation only when the implementation takes it; most do not, and their implementation stays `[cfg params]`.
- The context is built by `jobs/make-context` and has exactly five keys: `id`, `name`, `label`, `resource-id`, `profile-id`, behind a closed schema. `profile-id` is the owner of a user-facing job and nil for an internal one, and it is there so a handler can revalidate permissions and check who owns its resource without a second query. `queue` is absent on purpose (a routing detail of the dispatcher, not a property of the job) and so are `retry-num`/`max-retries`/`attempt` (retry policy belongs to the runner, and an attempt number cannot be durable because the `noop` strategy re-runs without incrementing the counter).
- `jobs/invoke` is the in-process escape hatch: it delivers a nil context without a row, validates and delivers `::jobs/context` when given, and keeps `::jobs/job-id` independent. `::jobs/context` and `::jobs/job-id` are cfg options, not params.
- Business params live in the `params` column (plain JSON, decoded with the job-def decoder). `submit` takes optional `::jobs/profile-id`, `::jobs/resource-id` (both validated as UUIDs) and `::jobs/expires-at` (an instant: the retention the jobs GC sweeps by), all stored as columns; nothing is inferred from `params`. No caller passes a profile-id yet.
- A job-def may carry two optional metadata keys read from the registry by its consumers, never by the substrate: `::jobs/family` (e.g. `:export`) groups the jobs of a family for quotes, and `::jobs/resource-role` (`:input` or `:output`) says whether the job's `resource_id` is what it consumes or what it produces.
- Job resources (`app.jobs.storage`) are the objects a job owns through `job.resource_id`: they live in the `job-resource` bucket, are written with the profile of the job as owner (only that profile can read them back from `/assets/by-id`) and are never deduplicated. They carry no expiry of their own, because `expired-at` is stored as `deleted_at` and an object with it is unreadable from birth: the retention of the artifact is the retention of its job row. The writer marks the object as touched right away, so an artifact that no row references (a crash between the upload and the completion) is reclaimed by `storage-gc-touched`, and a row that does reference it freezes it until the jobs GC deletes the row and touches the object.
- The binfile jobs (`:export-binfile` and `:import-binfile`) run the core of `bf.v1`/`bf.v3` through `app.binfile.jobs`, which turns the `:progress` taps of the core into `progress` events of the job with `jobs/heartbeat` (at most one per second, `current` plus the section as `stage`) and reads the job status between units: a cancelled job stops reporting and the run is discarded, so it never completes. That listener runs on a thread of its own, so the adapter passes the job id from the handler context: the `*job-id*` binding the runner installs does not cross threads. Their job-def components carry pool, storage, metrics and msgbus, because a job with a `profile_id` cannot store an event without a msgbus on its cfg. Both readers answer with the same shape (`:file-ids` and, in version 3, the resolution of the libraries), because the version 1 reader returns a bare set of file ids.
- Both binfile handlers revalidate their permission at run time, not only when the job is created, because it can be revoked while the job waits: the export checks the read permission of every file before reading anything and stores the package in `job-resource` as the resource of the job (answering with the completion envelope), and the import checks the edition permission of the destination and runs the core inside a transaction, which is what makes a cancelled import roll back instead of leaving half of a file. The import releases its package and deletes its temporary copy once the job is terminal, whatever the outcome. Both handlers check their own result against a schema before returning it, because the job-def has no result schema of its own.
- The domain cfg of a binfile request lives in `app.binfile.common` (`export-cfg`, `import-cfg` and `resolve-export-type`), so the legacy RPC and the jobs freeze the same ids, resolve the same team features and apply the same anti-zip-bomb limits.
- Two retention knobs, easy to confuse: `:jobs-retention` sweeps terminal internal rows (the ones without a profile), while `:jobs-user-ttl` is the default expiry a user-facing job gets when it is created; the jobs GC deletes the row once `expires_at` passes and touches its resource. The heavy work of a user job runs on its own `:binfile` queue (`:worker-binfile-parallelism`, default 1) so it does not block the default runner.
- The quote of a family of jobs (`::export-jobs-per-profile`, `::import-jobs-per-profile`) counts the jobs of the profile that are still on their way (`new`/`scheduled`/`running`/`retry`) whose name the registry gives for that family, so a new job of the family is covered without touching the quote.
- A user-facing job is created through `app.rpc.commands.jobs` with the envelope `{:name <job> :params {...}}`: the registry decides whether `:name` is a job of the family the command serves, and the job-def of that name is the only place that validates the params, so a new type of job needs no change in the command. Both commands check the permission and the quote of the family before the expensive part, submit with `max-retries 0` on the `:binfile` queue and an expiry from `:jobs-user-ttl`, and answer with a summary of the row. The import command assembles the upload, reads the manifest of a version 3 package for the audit (never its content) and stores the package in `job-resource` as the resource of the job, releasing it when the job never comes to own it; the version is what the caller says or what the header of the package says.
- `heartbeat` uses named options `[cfg & {:keys [job-id progress] :as options}]` and returns the number of durable writes. The job id falls back to `::jobs/job-id` on the cfg and then to the runner-bound `*job-id*`. A progress report needs `current`, allows optional `total` and `stage` (max 250 chars) and takes no other key.
- `job.error` conforms to `app.jobs/schema:job-error` (`type` and `code` keywords, `hint` text, extra details allowed). Read it back with `jobs/decode-job-error`; a plain `db/decode-json-pgobject` leaves the keywords as strings. `complete` takes named options and an optional `resource-id`, which is only ever set, never replaced: it reads the row locked inside its own transaction and applies the rules in a fixed order, skipping a job that is no longer completable before refusing one for its resource, so the answer is a function of the row and not of the order two callers arrive in.

For worker dispatch, cron, retry semantics (`ex/raise :type ::wrk/retry` with `:delay`/`:strategy`), deduplication, job events, and queue internals: `mem:backend/subtleties`.

## Jobs metrics

- `app.jobs.metrics` owns the jobs metric names, bounded labels and the periodic backlog sampler.
- Metrics use only `name`, `queue`, `outcome`, `reason`, `stage` and `kind` labels. Job IDs, profile IDs, params, reply bodies and exception text must never be labels. Every label goes as-is (lowercased where case varies): values come from fixed call sites, so a new value in Prometheus means new code, not noise. Only a missing value falls back (`other`, or `failed` for outcomes).
- The canonical job name is a keyword (`app.jobs/schema:job-name`): `submit` and `invoke` reject strings. The row stores text, so `jobs/get-job-def` still coerces (it is the runner's boundary), cron entries carry the job under `:job` with `d/name` only at the SQL/log boundary, and test helpers keyword-ize row names before invoking.
- The durable lifecycle metrics are submitted, dispatched, completed, retries, orphaned and rescheduled counters; queue-wait, execution and total-time histograms; dispatcher and backlog state; GC, cron and ephemeral request metrics.
- `app.metrics/run!` is safe for recording failures, but metrics components still require a valid metrics instance. Durable submit call sites carry `::mtx/metrics` through RPC or job-def configuration. The jobs substrate takes the **cfg**, not the instance, and resolves it with `app.metrics/instance`, which raises `:missing-metrics` when absent: `app.jobs`, `app.jobs.metrics`, the runner, the dispatcher, cron and the jobs GC all follow that. `app.http`, `app.rpc.climit` and `app.storage.s3.metrics` still take the instance and were left out of scope on purpose, so do not read the codebase as uniform. The six job writers resolve at the top of their body, before writing, because their metric lands in a `db/after-commit!` callback that swallows exceptions. The GC, the dispatcher and cron resolve inside that callback instead, so their guarantee rests on the component schema instead. A job that calls `heartbeat` from a component cfg must carry `::mtx/metrics` on it, and the event helper records the counter from the cfg the transaction hands it.
- SQL-backed job metrics are registered with `app.db/after-commit!` from INSIDE the transaction that changed the row, so a rollback cannot inflate submitted, terminal, retry, event or dispatcher counters. Every lifecycle writer owns that transaction (`app.jobs/own-transaction` drops `::db/conn`), and `db/transact!` gives a transaction that joins no other its own callback context, so the write and its metric commit or vanish together. `submit` is the exception and joins the caller on purpose: creating a job is part of the caller's business work.
- `penpot_jobs_events_total` counts stored job events with no labels: the interesting split is `kind`, and grouping a growing append-only table to build a metric is not worth the query on the write path.
- The backlog sampler is `::jobs-metrics/sampler`; it runs every 30 seconds on worker-enabled, writable systems, groups by status and publishes the oldest pending age, and does not start on a read-only database.
- `penpot_tasks_timing` remains exported for compatibility while the jobs-specific histograms are adopted.


## REPL

In devenv, backend nREPL is exposed on port 6064.

### Non-interactive eval (preferred for agents)

`./scripts/nrepl-eval.mjs` connects to an already-running nREPL server and evaluates code. Session state (defs, `in-ns`) persists across invocations via a stored session ID in `/tmp/penpot-nrepl-session-<host>-<port>`.

```bash
./scripts/nrepl-eval.mjs '(+ 1 2)'                            # single expression
./scripts/nrepl-eval.mjs "(require '[my.ns :as ns] :reload)"  # reload after edits
./scripts/nrepl-eval.mjs -e                                   # inspect last exception (*e)
./scripts/nrepl-eval.mjs --reset-session '(def x 0)'          # discard session, start fresh
./scripts/nrepl-eval.mjs <<'EOF'                              # multi-expression heredoc
(def x 10)
(+ x 20)
EOF
```

Default port is 6064. Use `-p <PORT>` for a different port. Use `-t <MS>` to override the 120s timeout. Do not start the nREPL server — assume it is already running.

### Interactive REPL

`backend/scripts/nrepl` starts a REPLy client connected to the running nREPL.

For an in-process backend REPL (where you control the JVM lifecycle), stop the running backend first so port 9090 is free, then run `backend/scripts/repl`. Useful top-level helpers include `(start)`, `(stop)`, `(restart)`, `(run-tests)`, and `(repl/refresh-all)`. Many `app.srepl.main` helpers accept the global `system` var, e.g. manual email or maintenance operations.

## Fixtures

Fixtures can populate local data for manual testing/perf work. From the backend REPL, run `(app.cli.fixtures/run {:preset :small})`; fixture users conventionally look like `profileN@example.com` with password `123123`. Standalone fixture aliases may exist, but check current `backend/deps.edn` before relying on old command names.

## Performance

* **Type Hinting:** Use explicit JVM type hints (e.g. `^String`, `^long`) in performance-critical paths to avoid reflection overhead.
* **Batch inserts:** Use `db/insert-many!` for bulk row inserts — generates a single SQL with multiple parameter tuples. Avoid on very large datasets (SQL length / parameter count limits).
* **Server-side cursors:** Use `db/plan` (fetch-size 1000, forward-only, read-only) or `db/cursor` for large result sets. Never fetch large collections into memory at once.
* **Transaction discipline:** Use `tx-run!` for writes (opens a transaction), `run!` for reads (single connection, no transaction). Set `:read-only` on `tx-run!` when applicable to let PostgreSQL optimize.

## Lint and Format

IMPORTANT: all CLI commands must be executed from the `backend/` subdirectory.

* **Linting:** `pnpm run lint:clj`.
* **Formatting:** `pnpm run check-fmt:clj` to check, `pnpm run fmt:clj` to fix. After running `fmt:clj`, `check-fmt:clj` is redundant. Avoid unrelated whitespace diffs.

**Before linting:** if delimiter errors are suspected (after LLM edits), run `scripts/paren-repair` on the affected files first. Delimiter errors produce misleading linter/compiler output. See `mem:scripts/paren-repair`.

## Testing

Backend test commands, coverage rules, and conventions: `mem:backend/testing`.
Cross-cutting testing principles, anti-patterns, and verification checklist: `mem:testing`.
