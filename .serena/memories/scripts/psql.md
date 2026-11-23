# PostgreSQL wrappers

- `scripts/psql` invokes `psql`; `scripts/db-schema` invokes schema-only `pg_dump`. Both use `PENPOT_DB_HOST`, `PENPOT_DB_USER`, `PENPOT_DB_PASSWORD` and `PENPOT_DB_NAME`, not the backend test URI. The default host is `postgres`; check the effective destination before executing SQL.
- Both wrappers accept `--test --ws N` (also `--ws wsN`): workspace 0 selects `penpot_test`, and workspace N selects `penpot_test_wsN`. `--ws` without `--test` is rejected because the main database is shared. Check the actual `PENPOT_TEST_DATABASE_URI` for the workspace rather than assuming the wrapper's defaults match it; see `mem:devenv/core`.
- The `penpot-psql` tool's `test` option selects `penpot_test`; it has no workspace selector. Consult the active tool catalog for its invocation and use the wrapper or another explicitly configured client when the target differs.
