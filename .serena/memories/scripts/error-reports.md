# Error Reports CLI Tool

`scripts/error-reports.mjs` is a Node.js CLI tool for querying Penpot error reports via the RPC API. Provides access to error logs with filtering, pagination, and multiple output formats.

## When to use

- Querying error reports from the database for debugging or analysis
- Filtering errors by source, kind, tenant, or backend version
- Exporting error data in JSON, NDJSON, or table format
- Computing error statistics (top signatures, per-host breakdown, hourly distribution)
- Investigating specific error reports by ID

## Prerequisites

- Node.js with `commander` and `dotenv` packages installed (in root `package.json`)
- Running Penpot backend with error-reports RPC endpoints
- Access token with `error-reports:read` permission

## Configuration

Create a `.env` file in the project root:

```bash
PENPOT_API_URI=http://localhost:3450
PENPOT_ACCESS_TOKEN=<your-token>
```

Grant the required permission to your access token:

```sql
UPDATE access_token
SET perms = ARRAY['error-reports:read']::text[],
    updated_at = now()
WHERE id = '<token-uuid>';
```

## Usage

```bash
./scripts/error-reports.mjs <command> [options]
```

### Commands

#### `list` - List error reports with pagination and filters

```bash
./scripts/error-reports.mjs list [options]
```

**Options:**

| Flag | Description | Default |
|------|-------------|---------|
| `-l, --limit <n>` | Max items per page (max: 200) | `50` |
| `--from <date>` | ISO timestamp — oldest boundary (items after this) | — |
| `--to <date>` | ISO timestamp — newest boundary (items before this) | — |
| `--since <date>` | ISO timestamp — explicit cursor for manual pagination | — |
| `--since-id <uuid>` | Fetch errors after this ID (cursor pagination) | — |
| `-s, --source <name>` | Filter by source (see source names below) | — |
| `-p, --profile-id <uuid>` | Filter by profile ID | — |
| `-k, --kind <kind>` | Filter by kind (string) | — |
| `-t, --tenant <tenant>` | Filter by tenant (string) | — |
| `--version <version>` | Filter by version | — |
| `--hint <text>` | Filter by hint (ILIKE match) | — |
| `-a, --all` | Fetch all pages automatically (streams output) | `false` |
| `-f, --format <type>` | Output format: `json`, `table`, or `ndjson` | `table` |
| `--normalize-hints` | Normalize hints by stripping dynamic values | `false` |
| `-o, --output <file>` | Write output to file instead of stdout | — |
| `--env <path>` | Custom .env file path | `.env` |
| `-h, --help` | Show help message | — |

**Streaming behavior:** With `--all` or `--format ndjson`, items are printed as they arrive (no buffering). `--all` + `table` prints rows immediately. `--all` + `json` streams NDJSON (one JSON object per line).

#### `get` - Get a single error report by ID

```bash
./scripts/error-reports.mjs get [options]
```

**Options:**

| Flag | Description | Required |
|------|-------------|----------|
| `--id <uuid>` | Error report ID | Yes (or --error-id) |
| `--error-id <id>` | Error report error-id | Yes (or --id) |
| `-f, --format <type>` | Output format: `json` or `table` | No (default: `table`) |
| `--env <path>` | Custom .env file path | No (default: `.env`) |
| `-h, --help` | Show help message | No |

#### `stats` - Compute error report statistics

```bash
./scripts/error-reports.mjs stats [options]
```

Reads from `--input <file>`, stdin (piped), or fetches from API. Computes aggregations by signature, host, tenant, version, source, kind, and hour.

**Options:**

| Flag | Description | Default |
|------|-------------|---------|
| `--from <date>` | Start of interval (ISO timestamp) | — |
| `--to <date>` | End of interval (ISO timestamp) | — |
| `--limit <n>` | Items per page when fetching from API | `200` |
| `--input <file>` | Read from local JSON/NDJSON file instead of API | — |
| `-f, --format <type>` | Output format: `json` or `table` | `table` |
| `--env <path>` | Custom .env file path | `.env` |

## Source Names

The `--source` filter accepts these values:

- `logging`
- `audit-log`
- `rlimit`

## Hint Normalization

With `--normalize-hints` (or always in `stats`), hints are normalized by stripping dynamic values:

1. URIs (`https://...`) → `<uri>`
2. UUIDs (8-4-4-4-12 hex) → `<uuid>`
3. Elapsed times (`7.5s`, `2m3.027s`) → `<elapsed>`
4. Numeric IDs in parentheses `(12345)` → `(<id>)`

## Examples

### List recent errors
```bash
./scripts/error-reports.mjs list --limit 10
```

### Time-range query (today)
```bash
./scripts/error-reports.mjs list --from 2026-07-23T00:00:00Z --to 2026-07-23T23:59:59Z --all
```

### Stream all errors as NDJSON
```bash
./scripts/error-reports.mjs list --all --format ndjson > errors.ndjson
```

### Save to file with --output
```bash
./scripts/error-reports.mjs list --all --format json -o errors.json
./scripts/error-reports.mjs list --all --format ndjson -o errors.ndjson
```

### Filter by source
```bash
./scripts/error-reports.mjs list --source audit-log --limit 20
```

### Filter by kind
```bash
./scripts/error-reports.mjs list --kind exception-page
```

### Filter by tenant
```bash
./scripts/error-reports.mjs list --tenant production
```

### Filter by version
```bash
./scripts/error-reports.mjs list --version 2.1.0
```

### Search by hint (partial match)
```bash
./scripts/error-reports.mjs list --hint "NullPointerException"
```

### Fetch all errors with pagination
```bash
./scripts/error-reports.mjs list --all
```

### Get specific error by ID
```bash
./scripts/error-reports.mjs get --id 550e8400-e29b-41d4-a716-446655440000
```

### Output as JSON
```bash
./scripts/error-reports.mjs list --limit 5 --format json
```

### Combine filters
```bash
./scripts/error-reports.mjs list --source audit-log --kind exception-page --tenant production --limit 50
```

### Stats from API
```bash
./scripts/error-reports.mjs stats --from 2026-07-23T00:00:00Z --to 2026-07-23T23:59:59Z
```

### Stats from file
```bash
./scripts/error-reports.mjs stats --input errors.json
```

### Stats from pipe
```bash
./scripts/error-reports.mjs list --all --format json | ./scripts/error-reports.mjs stats
```

## Output Formats

### Table (default)
Human-readable table format for terminal display. With `--all`, rows stream as they arrive.

### JSON
Single page: `{items: [...], nextSince, nextId}`. With `--all`: NDJSON (one JSON object per line).

### NDJSON
One JSON object per line, always streaming. Pipe-friendly: `| jq -c '.hint'`, `| wc -l`.

## Pagination

The server returns items in **ascending** order (oldest first). Cursor pagination uses `--since` / `--since-id` to fetch the next page of newer items.

### Manual pagination
Use `--since` and `--since-id` with values from `nextSince` and `nextId` in the response:

```bash
./scripts/error-reports.mjs list --limit 50
# Use nextSince and nextId from response
./scripts/error-reports.mjs list --limit 50 --since "2026-01-20T10:29:00Z" --since-id "next-uuid"
```

### Automatic pagination
Use `--all` to fetch all pages automatically (streams output):

```bash
./scripts/error-reports.mjs list --all
```

### Time-range queries
Use `--from` and `--to` to bound the query. These map to the server's `--since` and `--until` parameters:

```bash
./scripts/error-reports.mjs list --from 2026-07-20T00:00:00Z --to 2026-07-23T23:59:59Z --all
```

## Key principles

- **Authentication required** - Uses access token with `error-reports:read` permission
- **API endpoint configurable** - Set via `PENPOT_API_URI` in `.env` file
- **Table is default format** - Use `--format json` for structured JSON, `--format ndjson` for streaming
- **Streaming with --all** - Items print as they arrive, no buffering
- **Filters are combinable** - All filter options can be used together
- **Both flag formats supported** - `--option=value` and `--option value` both work
- **Ascending order** - Server returns oldest items first (changed from DESC)

## Error handling

The tool provides helpful error messages for common issues:

- **Missing configuration**: Shows setup instructions for `.env` file
- **Authentication errors (401)**: Indicates invalid or expired token
- **Authorization errors (403)**: Indicates missing `error-reports:read` permission
- **RPC errors**: Displays error code and message from the API

## Integration with other scripts

- **jq**: Pipe NDJSON output to `jq` for further processing
  ```bash
  ./scripts/error-reports.mjs list --all --format ndjson | jq -c '{id, hint}'
  ```
- **stats from pipe**: Fetch data once, compute stats
  ```bash
  ./scripts/error-reports.mjs list --all --format json -o errors.json
  ./scripts/error-reports.mjs stats --input errors.json
  ```
- **stats from NDJSON pipe**: Works with NDJSON format too
  ```bash
  ./scripts/error-reports.mjs list --all --format ndjson | ./scripts/error-reports.mjs stats
  ```
- **grep/search**: Filter output by specific patterns
- **--output**: Save to file without shell redirection
  ```bash
  ./scripts/error-reports.mjs list --all --format ndjson -o errors.ndjson
  ```
