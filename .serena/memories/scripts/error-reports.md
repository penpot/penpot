# Error reports CLI

- `scripts/error-reports.mjs` reads reports through the RPC API; use `list --help`, `get --help` and `stats --help` for options. Configure `PENPOT_API_URI` and a token with `error-reports:read` in the environment or an env file. Do not write credentials into tracked files.
- Results are oldest first. For manual pagination use both `nextSince` and `nextId` as `--since` and `--since-id`; `--since` overrides `--from`. `--to` supplies the upper boundary.
- `--all --format json` is rejected. Use NDJSON for streaming. Selecting `--format ndjson` itself follows every page, even without `--all`; `--limit` controls page size, not the total number of reports.
- `get` currently requires `--id`; `--error-id` alone is not accepted by the CLI. `stats` loads API configuration even when reading `--input` or stdin, and reads all input into memory. If stdin is not a TTY, it reads stdin rather than fetching from the API.
- To fetch once and aggregate, use `./scripts/error-reports.mjs list --all --format ndjson | ./scripts/error-reports.mjs stats`. Both processes need configuration. Statistics normalize hints, so signatures group dynamic values rather than preserving each exact hint.
