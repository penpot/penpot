---
name: penpot-backend
description: Guidelines and workflows for the Penpot Clojure JVM backend.
---

# Penpot Backend Skill

This skill provides guidelines and workflows for the Penpot Clojure JVM backend.

## Testing & Validation
- **Isolated tests:** `clojure -M:dev:test --focus backend-tests.my-ns-test` (for a specific test namespace)
- **Regression tests:** `clojure -M:dev:test` (ensure the suite passes without regressions)
- **Eval expresion:** `clojure -M:dev -e "(here-the-expresion)"`

## Code Quality
- **Linting:** `pnpm run lint:clj`
- **Formatting:** 
  - Check: `pnpm run check-fmt`
  - Fix: `pnpm run fmt`
- **Type Hinting:** Use explicit JVM type hints (e.g., `^String`, `^long`) in performance-critical paths to avoid reflection overhead.

## Architecture & Conventions
- Uses Integrant for dependency injection (`src/app/main.clj`).
- PostgreSQL for storage, Redis for messaging/caching.
- **RPC:** Commands are under `app.rpc.commands.*`. Use the `get-` prefix on RPC names when we want READ operations.
- **Database:** `app.db` wraps next.jdbc. Queries use a SQL builder.
  - Helpers: `db/get`, `db/query`, `db/insert!`, `db/update!`, `db/delete!`
- **Performance Macros:** Always prefer these macros from `app.common.data.macros` over `clojure.core` equivalents: `dm/select-keys`, `dm/get-in`, `dm/str`.
