# Penpot Backend – Agent Instructions

Clojure backend (RPC) service running on the JVM.

Uses Integrant for dependency injection, PostgreSQL for storage, and
Redis for messaging/caching.

## General Guidelines

To ensure consistency across the Penpot JVM stack, all contributions must adhere
to these criteria:

### 1. Testing & Validation

* **Coverage:** If code is added or modified in `src/`, corresponding
  tests in `test/backend_tests/` must be added or updated.

* **Execution:**
  * **Isolated:** Run `clojure -M:dev:test --focus backend-tests.my-ns-test` for the specific test namespace.
  * **Regression:** Run `clojure -M:dev:test` to ensure the suite passes without regressions in related functional areas.

### 2. Code Quality & Formatting

* **Linting:** All code must pass `clj-kondo` checks (run `pnpm run lint:clj`)
* **Formatting:** All the code must pass the formatting check (run `pnpm run
  check-fmt`). Use `pnpm run fmt` to fix formatting issues. Avoid "dirty"
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
- `app.loggers` – Internal loggers (auditlog, mattermost, etc.) (not to be confused with `app.common.logging`)

### RPC

The RPC methods are implemented using a multimethod-like structure via the
`app.util.services` namespace. The main RPC methods are collected under
`app.rpc.commands` namespace and exposed under `/api/rpc/command/<cmd-name>`.

The RPC method accepts POST and GET requests indistinctly and uses the `Accept`
header to negotiate the response encoding (which can be Transit — the default —
or plain JSON). It also accepts Transit (default) or JSON as input, which should
be indicated using the `Content-Type` header.

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


### Connecting to the Database

Two PostgreSQL databases are used in this environment:

| Database      | Purpose            | Connection string                                  |
|---------------|--------------------|----------------------------------------------------|
| `penpot`      | Development / app  | `postgresql://penpot:penpot@postgres/penpot`       |
| `penpot_test` | Test suite         | `postgresql://penpot:penpot@postgres/penpot_test`  |

**Interactive psql session:**

```bash
# development DB
psql "postgresql://penpot:penpot@postgres/penpot"

# test DB
psql "postgresql://penpot:penpot@postgres/penpot_test"
```

**One-shot query (non-interactive):**

```bash
psql "postgresql://penpot:penpot@postgres/penpot" -c "SELECT id, name FROM team LIMIT 5;"
```

**Useful psql meta-commands:**

```
\dt              -- list all tables
\d <table>       -- describe a table (columns, types, constraints)
\di              -- list indexes
\q               -- quit
```

> **Migrations table:** Applied migrations are tracked in the `migrations` table
> with columns `module`, `step`, and `created_at`. When renaming a migration
> logical name, update this table in both databases to match the new name;
> otherwise the runner will attempt to re-apply the migration on next startup.

```bash
# Example: fix a renamed migration entry in the test DB
psql "postgresql://penpot:penpot@postgres/penpot_test" \
  -c "UPDATE migrations SET step = 'new-name' WHERE step = 'old-name';"
```

### Database Access (Clojure)

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

Almost all methods in the `app.db` namespace accept `pool`, `conn`, or
`cfg` as params.

Migrations live in `src/app/migrations/` as numbered SQL files. They run automatically on startup.


### Error Handling

The exception helpers are defined on Common module, and are available under
`app.common.exceptions` namespace.

Example of raising an exception:

```clojure
(ex/raise :type :not-found
          :code :object-not-found
          :hint "File does not exist"
          :file-id id)
```

Common types: `:not-found`, `:validation`, `:authorization`, `:conflict`, `:internal`.


### Performance Macros (`app.common.data.macros`)

Always prefer these macros over their `clojure.core` equivalents — they provide
optimized implementations:

```clojure
(dm/select-keys m [:a :b])     ;; faster than core/select-keys
(dm/get-in obj [:a :b :c])     ;; faster than core/get-in
(dm/str "a" "b" "c")           ;; string concatenation
```

### Configuration

`src/app/config.clj` reads `PENPOT_*` environment variables, validated with
Malli. Access anywhere via `(cf/get :smtp-host)`. Feature flags: `(cf/flags
:enable-smtp)`.


### Background Tasks

Background tasks live in `src/app/tasks/`. Each task is an Integrant component
that exposes a `::handler` key and follows this three-method pattern:

```clojure
(defmethod ig/assert-key ::handler   ;; validate config at startup
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler   ;; inject defaults before init
  [k v]
  {k (assoc v ::my-option default-value)})

(defmethod ig/init-key ::handler     ;; return the task fn
  [_ cfg]
  (fn [_task]                        ;; receives the task row from the worker
    (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                      ;; … do work …
                      ))))
```

**Wiring a new task** requires two changes in `src/app/main.clj`:

1. **Handler config** – add an entry in `system-config` with the dependencies:

```clojure
:app.tasks.my-task/handler
{::db/pool (ig/ref ::db/pool)}
```

2. **Registry + cron** – register the handler name and schedule it:

```clojure
;; in ::wrk/registry ::wrk/tasks map:
:my-task (ig/ref :app.tasks.my-task/handler)

;; in worker-config ::wrk/cron ::wrk/entries vector:
{:cron #penpot/cron "0 0 0 * * ?"   ;; daily at midnight
 :task :my-task}
```

**Useful cron patterns** (Quartz format — six fields: s m h dom mon dow):

| Expression                   | Meaning            |
|------------------------------|--------------------|
| `"0 0 0 * * ?"`              | Daily at midnight  |
| `"0 0 */6 * * ?"`            | Every 6 hours      |
| `"0 */5 * * * ?"`            | Every 5 minutes    |

**Time helpers** (`app.common.time`):

```clojure
(ct/now)                          ;; current instant
(ct/duration {:hours 1})          ;; java.time.Duration
(ct/minus (ct/now) some-duration) ;; subtract duration from instant
```

`db/interval` converts a `Duration` (or millis / string) to a PostgreSQL
interval object suitable for use in SQL queries:

```clojure
(db/interval (ct/duration {:hours 1}))  ;; → PGInterval "3600.0 seconds"
```
