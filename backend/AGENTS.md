# backend – Agent Instructions

Clojure service running on the JVM. Uses Integrant for dependency injection, PostgreSQL for storage, and Redis for messaging/caching.

## Commands

```bash
# REPL (primary dev workflow)
./scripts/repl              # Start nREPL + load dev/user.clj utilities

# Tests (Kaocha)
clojure -M:dev:test                                         # Full suite
clojure -M:dev:test --focus backend-tests.my-ns-test        # Single namespace

# Lint / Format
pnpm run lint:clj
pnpm run fmt:clj
```

Test namespaces match `.*-test$` under `test/`. Config is in `tests.edn`.

## Integrant System

`src/app/main.clj` declares the system map. Each key is a component;
values are config maps with `::ig/ref` for dependencies. Components
implement `ig/init-key` / `ig/halt-key!`.

From the REPL (`dev/user.clj` is auto-loaded):
```clojure
(start!)   ; boot the system
(stop!)    ; halt the system
(restart!) ; stop + reload namespaces + start
```

## RPC Commands

All API calls: `POST /api/rpc/command/<cmd-name>`.

```clojure
(sv/defmethod ::my-command
  {::rpc/auth true             ;; requires authentication (default)
   ::doc/added "1.18"
   ::sm/params [:map ...]      ;; malli input schema
   ::sm/result [:map ...]}     ;; malli output schema
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  ;; return a plain map; throw via ex/raise for errors
  {:id (uuid/next)})
```

Add new commands in `src/app/rpc/commands/`.

## Database

`app.db` wraps next.jdbc. Queries use a SQL builder that auto-converts kebab-case ↔ snake_case.

```clojure
;; Query helpers
(db/get pool :table {:id id})                    ; fetch one row (throws if missing)
(db/get* pool :table {:id id})                   ; fetch one row (returns nil)
(db/query pool :table {:team-id team-id})        ; fetch multiple rows
(db/insert! pool :table {:name "x" :team-id id}) ; insert
(db/update! pool :table {:name "y"} {:id id})    ; update
(db/delete! pool :table {:id id})                ; delete
;; Transactions
(db/tx-run cfg (fn [{:keys [::db/conn]}]
                 (db/insert! conn :table row)))
```

Almost all methods on `app.db` namespace accepts `pool`, `conn` or
`cfg` as params.

Migrations live in `src/app/migrations/` as numbered SQL files. They run automatically on startup.

## Error Handling

```clojure
(ex/raise :type :not-found
          :code :object-not-found
          :hint "File does not exist"
          :context {:id file-id})
```

Common types: `:not-found`, `:validation`, `:authorization`, `:conflict`, `:internal`.

## Configuration

`src/app/config.clj` reads `PENPOT_*` environment variables, validated with Malli. Access anywhere via `(cf/get :smtp-host)`. Feature flags: `(cf/flags :enable-smtp)`.
