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

# Test database
scripts/psql --test -c "SELECT * FROM migrations;"

# Custom host/user/database
scripts/psql --host myhost --user myuser --db mydb
```

`scripts/psql` must be invoked from the repo root so the path resolves.

## Native Tool Available (opencode)

A native opencode tool `penpot-psql` is available. The LLM can call it directly
with:
- `sql`: SQL command string to execute
- `test`: Boolean flag to use the `penpot_test` database

Example usage by the LLM:
```
penpot-psql(sql="SELECT version();")
penpot-psql(sql="SELECT * FROM migrations;", test=true)
```
