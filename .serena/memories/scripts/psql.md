# Psql

`scripts/psql` is a wrapper around `psql` that connects to the Penpot PostgreSQL
database using environment variables (`PENPOT_DB_HOST`, `PENPOT_DB_USER`,
`PENPOT_DB_PASSWORD`, `PENPOT_DB_NAME`) with sensible defaults for local
development.

## When to use

- Running ad-hoc SQL queries against the Penpot database.
- Inspecting schema, migrations, or data during development or debugging.

## How to use (CLI)

```bash
# Default connection (penpot db, localhost)
scripts/psql -c "SELECT version();"

# Test database (ws0)
scripts/psql --test -c "SELECT * FROM migrations;"

# Isolated test database of another instance (ws1+)
scripts/psql --test --ws 1 -c "SELECT * FROM migrations;"

# Custom host/user/database
scripts/psql --host myhost --user myuser --db mydb
```

`--ws N` only applies together with `--test` (the main database is
shared by all instances): it selects `penpot_test` on `--ws 0` and
`penpot_test_wsN` on `--ws N`. `scripts/db-schema` accepts the same flags.

`scripts/psql` must be invoked from the repo root so the path resolves.

## Native Tool Available (OpenCode V2)

A native OpenCode V2 tool `penpot-psql` is available. It is defined in
`.opencode/plugins/penpot.js` and registered through `setup()`. The LLM can
call it directly with:
- `sql`: SQL command string to execute
- `test`: Boolean flag to use the `penpot_test` database

Example usage by the LLM:
```
penpot-psql(sql="SELECT version();")
penpot-psql(sql="SELECT * FROM migrations;", test=true)
```
