# Backend Architecture and Workflow

Backend: JVM Clojure; Integrant; PostgreSQL; Redis/Valkey; RPC; HTTP; storage; mail; audit/logging; workers.

## Focused memories

- RPC, DB helpers, workers, cron: `mem:backend/rpc-db-worker-subtleties`
- HTTP sessions, config, storage, media, file data persistence: `mem:backend/http-storage-filedata-subtleties`
- Auth flows, permission model, teams, projects, invitations, comments, webhooks, audit: `mem:backend/auth-permissions-product-domains`
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
- Use `db/run!` for multiple operations on one connection.
- Use `db/tx-run!` for transactions.

Database migrations live in `backend/src/app/migrations/`; pure SQL migrations are under `backend/src/app/migrations/sql/`. SQL filenames conventionally start with a sequence and verb/table description, e.g. `0026-mod-profile-table-add-is-active-field`. Applied migrations are tracked in the `migrations` table.

For deeper details on transaction semantics, advisory locks, Transit vs JSON helpers, and dev/test DB URLs: `mem:backend/rpc-db-worker-subtleties`.

## Background tasks

A task handler is an Integrant component with `ig/assert-key`, `ig/expand-key`, and `ig/init-key`, returning the function run by the worker. New tasks also need wiring in `app.main`: handler config, worker registry entry, and cron entry if scheduled.

For worker dispatch, cron, retry semantics, deduplication, and queue internals: `mem:backend/rpc-db-worker-subtleties`.

## REPL

In devenv, backend nREPL is exposed on port 6064.

### Non-interactive eval (preferred for agents)

`./tools/nrepl-eval.mjs` connects to an already-running nREPL server and evaluates code. Session state (defs, `in-ns`) persists across invocations via a stored session ID in `/tmp/penpot-nrepl-session-<host>-<port>`.

```bash
./tools/nrepl-eval.mjs '(+ 1 2)'                            # single expression
./tools/nrepl-eval.mjs "(require '[my.ns :as ns] :reload)"  # reload after edits
./tools/nrepl-eval.mjs -e                                   # inspect last exception (*e)
./tools/nrepl-eval.mjs --reset-session '(def x 0)'          # discard session, start fresh
./tools/nrepl-eval.mjs <<'EOF'                              # multi-expression heredoc
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

* **Linting:** `pnpm run lint` from the repository root.
* **Formatting:** `pnpm run check-fmt`. Use `pnpm run fmt` to fix. Avoid unrelated whitespace diffs.

**Before linting:** if delimiter errors are suspected (after LLM edits), run
`tools/paren-repair.bb` on the affected files first. Delimiter errors produce
misleading linter/compiler output. See `mem:tools/paren-repair`.

## Testing

IMPORTANT: all CLI commands must be executed from the `backend/` subdirectory.

* **Coverage:** If code is added or modified in `src/`, corresponding tests in `test/backend_tests/` must be added or updated.
* **Isolated run:** `clojure -M:dev:test --focus backend-tests.my-ns-test` for a specific test namespace.
* **Regression run:** `clojure -M:dev:test` to ensure no regressions in related functional areas.
