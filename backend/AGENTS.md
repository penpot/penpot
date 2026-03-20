# Penpot Backend – Agent Instructions

Clojure backend (RPC) service running on the JVM.

Uses Integrant for dependency injection, PostgreSQL for storage, and
Redis for messaging/caching.

## General Guidelines

This is a golden rule for backend development standards. To ensure consistency
across the Penpot JVM stack, all contributions must adhere to these criteria:

### 1. Testing & Validation

* **Coverage:** If code is added or modified in `src/`, corresponding
  tests in `test/backend_tests/` must be added or updated.

* **Execution:**
  * **Isolated:** Run `clojure -M:dev:test --focus backend-tests.my-ns-test` for the specific task.
  * **Regression:** Run `clojure -M:dev:test` for ensure the suite passes without regressions in related functional areas.

### 2. Code Quality & Formatting

* **Linting:** All code must pass `clj-kondo` checks (run `pnpm run lint:clj`)
* **Formatting:** All the code must pass the formatting check (run `pnpm run
  check-fmt`). Use the `pnpm run fmt` fix the formatting issues. Avoid "dirty"
  diffs caused by unrelated whitespace changes.
* **Type Hinting:** Use explicit JVM type hints (e.g., `^String`, `^long`) in
  performance-critical paths to avoid reflection overhead.

## Code Conventions

### Namespace Overview

The source is located under `src` directory and this is a general overview of
namespaces structure:

- `app.rpc.commands.*` – RPC command implementations (`auth`, `files`, `teams`, etc.)
- `app.http.*` – HTTP routes and middleware
- `app.db.*` – Database layer
- `app.tasks.*` – Background job tasks
- `app.main` – Integrant system setup and entrypoint
- `app.loggers` – Internal loggers (auditlog, mattermost, etc) (do not be confused with `app.common.loggin`)

### RPC

The PRC methods are implement in a some kind of multimethod structure using
`app.util.serivices` namespace. The main RPC methods are collected under
`app.rpc.commands` namespace and exposed under `/api/rpc/command/<cmd-name>`.

The RPC method accepts POST and GET requests indistinctly and uses `Accept`
header for negotiate the response encoding (which can be transit, the defaut or
plain json). It also accepts transit (defaut) or json as input, which should be
indicated using `Content-Type` header.

The main convention is: use `get-` prefix on RPC name when we want READ
operation.

Example of RPC method definition:

```clojure
(sv/defmethod ::my-command
  {::rpc/auth true            ;; requires auth
   ::doc/added "1.18"
   ::sm/params [:map ...]     ;; malli input schema
   ::sm/result [:map ...]}    ;; malli output schema
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  ;; return a plain map or throw
  {:id (uuid/next)})
```

Look under `src/app/rpc/commands/*.clj` to see more examples.

### Tests

Test namespaces match `.*-test$` under `test/`. Config is in `tests.edn`.


### Integrant System

The `src/app/main.clj` declares the system map. Each key is a component; values
are config maps with `::ig/ref` for dependencies. Components implement
`ig/init-key` / `ig/halt-key!`.


### Database Access

`app.db` wraps next.jdbc. Queries use a SQL builder that auto-converts kebab-case ↔ snake_case.

```clojure
;; Query helpers
(db/get cfg-or-pool :table {:id id})                    ; fetch one row (throws if missing)
(db/get* cfg-or-pool :table {:id id})                   ; fetch one row (returns nil)
(db/query cfg-or-pool :table {:team-id team-id})        ; fetch multiple rows
(db/insert! cfg-or-pool :table {:name "x" :team-id id}) ; insert
(db/update! cfg-or-pool :table {:name "y"} {:id id})    ; update
(db/delete! cfg-or-pool :table {:id id})                ; delete

;; Run multiple statements/queries on single connection
(db/run! cfg (fn [{:keys [::db/conn]}]
               (db/insert! conn :table row1)
               (db/insert! conn :table row2))


;; Transactions
(db/tx-run! cfg (fn [{:keys [::db/conn]}]
                  (db/insert! conn :table row)))
```

Almost all methods on `app.db` namespace accepts `pool`, `conn` or
`cfg` as params.

Migrations live in `src/app/migrations/` as numbered SQL files. They run automatically on startup.


### Error Handling

The exception helpers are defined on Common module, and are available under
`app.commin.exceptions` namespace.

Example of raising an exception:

```clojure
(ex/raise :type :not-found
          :code :object-not-found
          :hint "File does not exist"
          :file-id id)
```

Common types: `:not-found`, `:validation`, `:authorization`, `:conflict`, `:internal`.


### Performance Macros (`app.common.data.macros`)

Always prefer these macros over their `clojure.core` equivalents — they compile to faster JavaScript:

```clojure
(dm/select-keys m [:a :b])     ;; ~6x faster than core/select-keys
(dm/get-in obj [:a :b :c])     ;; faster than core/get-in
(dm/str "a" "b" "c")           ;; string concatenation
```

### Configuration

`src/app/config.clj` reads `PENPOT_*` environment variables, validated with
Malli. Access anywhere via `(cf/get :smtp-host)`. Feature flags: `(cf/flags
:enable-smtp)`.
