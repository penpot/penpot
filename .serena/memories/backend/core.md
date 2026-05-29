# Backend Architecture and Workflow

Backend: JVM Clojure; Integrant; PostgreSQL; Redis/Valkey; RPC; HTTP; storage; mail; audit/logging; workers.

Focused routing: RPC/DB/workers -> `mem:backend/rpc-db-worker-subtleties`; HTTP/session/storage/media/file-data -> `mem:backend/http-storage-filedata-subtleties`; auth/permissions/product domains -> `mem:backend/auth-permissions-product-domains`.

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
- `app.srepl.*`: development REPL helpers for manual backend operations.
- `app.nitrate`, `app.rpc.commands.nitrate`, and `app.rpc.management.nitrate`: external Nitrate subscription/organization integration, gated by the `:nitrate` feature flag and shared-key HTTP calls.

## RPC conventions

RPC commands are defined with `app.util.services/defmethod` and schemas. Use `get-` prefixes for read operations. Command metadata usually includes auth, docs version, params schema, and result schema. Return plain maps/vectors or raise structured exceptions from `app.common.exceptions`.

Backend RPC command areas without focused memories include access tokens, binfile, demo, feedback, file snapshots, fonts, management, Nitrate, and webhooks beyond the notes in `mem:backend/auth-permissions-product-domains`; inspect nearby command tests and command metadata before changing them.

## DB conventions

`app.db` helpers accept cfg, pool, or conn in most places and convert kebab-case to snake_case:
- `db/get`, `db/get*`, `db/query`, `db/insert!`, `db/update!`, `db/delete!`.
- Use `db/run!` for multiple operations on one connection.
- Use `db/tx-run!` for transactions.

Development DB: `postgresql://penpot:penpot@postgres/penpot`.
Test DB: `postgresql://penpot:penpot@postgres/penpot_test`.
Database migrations live in `backend/src/app/migrations/`; pure SQL migrations are under `backend/src/app/migrations/sql/`. SQL filenames conventionally start with a sequence and verb/table description, e.g. `0026-mod-profile-table-add-is-active-field`. Applied migrations are tracked in the `migrations` table.

To inspect the whole DB schema in devenv, use `pg_dump -h postgres -s > schema.sql` from inside the environment.

## Background tasks

A task handler is an Integrant component with `ig/assert-key`, `ig/expand-key`, and `ig/init-key`, returning the function run by the worker. New tasks also need wiring in `app.main`: handler config, worker registry entry, and cron entry if scheduled.

## REPL and fixtures

In devenv, backend nREPL is exposed on port 6064. `backend/scripts/nrepl` starts a REPLy client.

For an in-process backend REPL, stop the running backend first so port 9090 is free, then run `backend/scripts/repl`. Useful top-level helpers include `(start)`, `(stop)`, `(restart)`, `(run-tests)`, and `(repl/refresh-all)`. Many `app.srepl.main` helpers accept the global `system` var, e.g. manual email or maintenance operations.

Fixtures can populate local data for manual testing/perf work. From the backend REPL, run `(app.cli.fixtures/run {:preset :small})`; fixture users conventionally look like `profileN@example.com` with password `123123`. Standalone fixture aliases may exist, but check current `backend/deps.edn` before relying on old command names.

## Commands

From `backend/`:
- Focused test: `clojure -M:dev:test --focus backend-tests.some-ns-test`.
- Full backend test suite: `clojure -M:dev:test` or `pnpm run test`.
- Watch/focused testing is also available through `(run-tests ...)` in the backend REPL.
- Lint: `pnpm run lint`.
- Format check: `pnpm run check-fmt`.
- Format fix: `pnpm run fmt`.

Use JVM type hints in performance-critical paths to avoid reflection.