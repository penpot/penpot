# Error Reports CLI Tool

`scripts/error-reports.mjs` is a Node.js CLI tool for querying Penpot error reports via the RPC API. Provides access to error logs with filtering, pagination, and multiple output formats.

## When to use

- Querying error reports from the database for debugging or analysis
- Filtering errors by source, kind, tenant, or backend version
- Exporting error data in JSON or table format
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
| `--since <date>` | ISO timestamp (fetch errors before this date) | — |
| `--since-id <uuid>` | Fetch errors before this ID (cursor pagination) | — |
| `-s, --source <name>` | Filter by source (see source names below) | — |
| `-p, --profile-id <uuid>` | Filter by profile ID | — |
| `-k, --kind <kind>` | Filter by kind (string) | — |
| `-t, --tenant <tenant>` | Filter by tenant (string) | — |
| `--version <version>` | Filter by version | — |
| `--hint <text>` | Filter by hint (ILIKE match) | — |
| `-a, --all` | Fetch all pages automatically | `false` |
| `-f, --format <type>` | Output format: `json` or `table` | `json` |
| `--env <path>` | Custom .env file path | `.env` |
| `-h, --help` | Show help message | — |

#### `get` - Get a single error report by ID

```bash
./scripts/error-reports.mjs get [options]
```

**Options:**

| Flag | Description | Required |
|------|-------------|----------|
| `--id <uuid>` | Error report ID | Yes (or --error-id) |
| `--error-id <id>` | Error report error-id | Yes (or --id) |
| `-f, --format <type>` | Output format: `json` or `table` | No (default: `json`) |
| `--env <path>` | Custom .env file path | No (default: `.env`) |
| `-h, --help` | Show help message | No |

## Source Names

The `--source` filter accepts these values:

- `logging`
- `audit-log`
- `rlimit`

## Examples

### List recent errors
```bash
./scripts/error-reports.mjs list --limit 10
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
./scripts/error-reports.mjs list --all --format json
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

## Output Formats

### Table (default)
Human-readable table format for terminal display:

```
Found 15 error reports

ID                                   | Created At          | Source    | Profile ID                           | Kind           | Hint
-------------------------------------+---------------------+-----------+--------------------------------------+----------------+------------------
550e8400-e29b-41d4-a716-446655440000 | 2026-01-20 10:30:00 | audit-log | e98bb95f-573d-8137-8008-252580aa456d | exception-page | Error description
abc12345-e29b-41d4-a716-446655440001 | 2026-01-20 10:29:00 | logging   | -                                    | error          | Another error that is very long and ne...

More results: use --since 2026-01-20T10:28:00Z --since-id def45678-e29b-41d4-a716-446655440002
```

### JSON
Returns structured JSON with error details and pagination metadata:

```json
{
  "items": [
    {
      "id": "uuid",
      "createdAt": "2026-01-20T10:30:00Z",
      "source": "audit-log",
      "profileId": "e98bb95f-573d-8137-8008-252580aa456d",
      "kind": "exception-page",
      "tenant": "production",
      "version": "2.1.0",
      "hint": "Error description"
    }
  ],
  "nextSince": "2026-01-20T10:29:00Z",
  "nextId": "next-uuid"
}
```

## Pagination

### Manual pagination
Use `--since` and `--since-id` with values from `nextSince` and `nextId` in the response:

```bash
./scripts/error-reports.mjs list --limit 50
# Use nextSince and nextId from response
./scripts/error-reports.mjs list --limit 50 --since "2026-01-20T10:29:00Z" --since-id "next-uuid"
```

### Automatic pagination
Use `--all` to fetch all pages automatically:

```bash
./scripts/error-reports.mjs list --all
```

## Key principles

- **Authentication required** - Uses access token with `error-reports:read` permission
- **API endpoint configurable** - Set via `PENPOT_API_URI` in `.env` file
- **Table is default format** - Use `--format json` for structured JSON output
- **Pagination is automatic with --all** - Fetches all pages without manual cursor management
- **Filters are combinable** - All filter options can be used together
- **Both flag formats supported** - `--option=value` and `--option value` both work

## Error handling

The tool provides helpful error messages for common issues:

- **Missing configuration**: Shows setup instructions for `.env` file
- **Authentication errors (401)**: Indicates invalid or expired token
- **Authorization errors (403)**: Indicates missing `error-reports:read` permission
- **RPC errors**: Displays error code and message from the API

## Integration with other scripts

- **jq**: Pipe JSON output to `jq` for further processing
  ```bash
  ./scripts/error-reports.mjs list --all --format json | jq '.items[] | {id, kind, hint}'
  ```
- **grep/search**: Filter output by specific patterns
- **Redirect**: Save output to files for analysis
  ```bash
  ./scripts/error-reports.mjs list --all --format json > errors.json
  ```
