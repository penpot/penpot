# PostgreSQL client wrapper

`tools/psql` is a wrapper around the `psql` command with defaults preconfigured
for the Penpot development environment.

## When to use

- Running SQL queries against the dev database (`penpot`) or test database
  (`penpot_test`).
- Inspecting table structures, running migrations manually, or debugging
  database state.
- Any time you need PostgreSQL access and want the correct host/user/password
  without typing them each time.

## How to use

```bash
# Interactive session (penpot database)
tools/psql

# Interactive session (penpot_test database)
tools/psql --test

# Inline query (penpot)
tools/psql -c "SELECT 1"

# Inline query (penpot_test)
tools/psql --test -c "SELECT 1"

# Override defaults
tools/psql -h other-host -U other-user -d other-db

# Pipe SQL from a file
tools/psql -f some-query.sql
```

All standard `psql` flags are passed through after the wrapper's own flags.

## Defaults

| Setting  | Default    | Env override       |
|----------|-----------|---------------------|
| Host     | `postgres` | `PENPOT_DB_HOST`  |
| User     | `penpot`   | `PENPOT_DB_USER`  |
| Password | `penpot`   | `PENPOT_DB_PASSWORD` |
| Database | `penpot`   | `PENPOT_DB_NAME`  |

## See also

`tools/db-schema` — a companion script that dumps the current DDL schema
using `pg_dump --schema-only`, with the same defaults and `--test` flag.
