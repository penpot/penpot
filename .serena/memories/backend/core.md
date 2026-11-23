# Backend Architecture and Workflow

JVM Clojure backend. Start in `app.main` for Integrant wiring, `app.rpc.commands` for RPC, `app.http` for HTTP, and `app.config` for environment defaults and feature flags.

## Focused memories

- RPC wrapper order, DB transactions and advisory locks, config and HTTP middleware, metrics, and file data persistence: `mem:backend/subtleties`.
- Job definitions, submission and lifecycle transaction boundaries, dispatch/recovery, user-facing jobs, and metrics: `mem:backend/jobs`.
- File import/export format compatibility, jobs, permissions and cancellation: `mem:backend/binfile`.
- Storage bucket rules, object lifecycle, deduplication, access and GC: `mem:backend/storage`.
- Graph projection parity, debug sessions, access and native resource ownership: `mem:backend/graph-experiment`.
- Session lifetime, token `:exp` and session GC: `mem:backend/session-expiration`.
- Auth, product permissions, teams, projects, invitations, comments and webhooks: `mem:backend/auth-permissions-product-domains`.
- WebSocket connection ownership and revocation: `mem:backend/websocket-subscriptions`.
- Audit collection, fan-out, archival and retention: `mem:audit-log`.
- Service layout, task queues versus Pub/Sub, and horizontal scaling: `mem:prod-infra/core`.
- Public URLs and base subpaths: `mem:common/uri`.

## RPC and DB conventions

- Read RPC names start with `get-`. Return plain maps/vectors or raise structured `app.common.exceptions`. See the adjacent command and tests for schema and permission patterns.
- `db/get*` and `db/query` do not take column names as trailing args. To include logically deleted rows, pass `{::db/remove-deleted false}` as the single options map. For SQL membership use `ANY(?)` with `db/create-array`; a vector in the where-map is not `IN`.
- SQL migrations must work on PostgreSQL 15 even if devenv runs a newer version; for example, `SET STORAGE DEFAULT` requires PostgreSQL 16. For shared-env SQL access or a DDL dump, see `mem:scripts/psql`.
- A table with `deletion_protection__tgr` must be deleted through the owning GC or an explicit transaction-local opt-out; it protects cross-table cleanup that a bare `DELETE` bypasses. Tests use `th/db-force-delete`.
- Stream large reads with `db/plan` inside `db/tx-run!`: without a transaction PostgreSQL JDBC ignores fetch size. A row is a view over the result set: copy the needed values inside the reducer before buffering. Use `reduce`/`transduce`, not `sequence`; use separate transactions for chunks that must commit while the scan holds its connection.

## Local verification

- From the repo root, use `./scripts/ci backend` for lint, tests and format. Prefer it over direct tests: `mem:backend/testing` explains the backend assert flag, workspace-specific test services, and focused runs.
- For the running backend use `mem:scripts/nrepl-eval`; starting a second backend can collide with the existing process. For test design and output discipline see `mem:testing`.
- For fixture data and the interactive backend REPL, see `docs/technical-guide/developer/backend.md`.
