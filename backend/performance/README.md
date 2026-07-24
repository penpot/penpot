# Penpot Performance Tests

k6-based load and performance test suite for the Penpot backend. Measures HTTP RPC latency, throughput, and error rates under synthetic user load.

## Prerequisites

- **k6** — Install from https://k6.io/docs/get-started/installation/ (also included in `devenv` image)
- **Running Penpot backend** — Local devenv (`http://localhost:6060`) or a remote instance

## Quick Start

```bash
# Smoke test — 1 VU, 1 iteration, demo mode
./run.sh smoke

# Full lifecycle with 10 VUs, 5 iterations each
./run.sh lifecycle -v 10 -n 5

# Use registration flow instead of demo profiles
./run.sh lifecycle -m register -v 5 -n 1

# Point to a remote backend
./run.sh lifecycle -u https://penpot.example.com

# Show all options
./run.sh help
```

## Commands

| Command | Description |
|---|---|
| `smoke` | 1 VU, 1 iteration smoke test of the lifecycle flow |
| `lifecycle` | Full user lifecycle (register → CRUD → delete) |
| `workspace-open` | Read-heavy: repeatedly open a file (get-file, libraries, thumbnails) |
| `workspace-edit` | Write-heavy: repeatedly edit a file (get-file + update-file loop) |
| `media-upload` | Upload images of varying sizes (direct + chunked) |
| `font-upload` | Upload fonts via chunked upload + create-font-variant |
| `concurrent-edit` | Concurrent editing: same-file or multi-file mode |
| `file-size-matrix` | Measure latency vs file size (10, 100, 500, 1000 shapes) |
| `compare` | Compare two k6 JSON results for regression |
| `all` | Run all scenarios together (orchestrator) |
| `clean` | Remove test results |

## Options

| Flag | Env Variable | Default | Description |
|---|---|---|---|
| `-u URL` | `PENPOT_BASE_URL` | `http://localhost:6060` | Penpot backend URL |
| `-v NUM` | — | per-script default | Number of virtual users |
| `-n NUM` | — | per-script default | k6 iterations |
| `-d DUR` | `PENPOT_DURATION` | k6 default | Test duration (e.g. `30s`, `5m`, `2h`) |
| `-m MODE` | `PENPOT_REGISTER_MODE` | `demo` | Register mode: `demo` or `register` |
| `-k PATH` | `K6` | `k6` | Path to k6 binary |

### Concurrent-edit / file-size-matrix options

| Flag | Env Variable | Default | Description |
|---|---|---|---|
| `--mode MODE` | `PENPOT_EDIT_MODE` | `same-file` | `same-file` or `multi-file` |
| `--files NUM` | `PENPOT_FILE_COUNT` | `1` | Number of files for multi-file mode |
| `--vus-per-file NUM` | `PENPOT_VUS_PER_FILE` | `1` | VUs per file for multi-file mode |
| `--edit-iterations NUM` | `PENPOT_EDIT_ITERATIONS` | `10` | Per-VU edit loop iterations |

`--edit-iterations` controls the per-VU edit loop in both `concurrent-edit` and `file-size-matrix`. It is **independent** of `-n` (which controls k6's shared-iterations executor).

### Register Modes

- **`demo`** (default): Uses the `create-demo-profile` RPC endpoint. Requires the `demo-users` feature flag to be enabled on the backend. Fastest for testing.
- **`register`**: Uses the full two-step registration flow (`prepare-register-profile` + `register-profile`). Works without any feature flags but is slower.

## Examples

```bash
# Same-file concurrent edit: 5 VUs editing the same file
./run.sh concurrent-edit --mode same-file -v 5 -n 10 --edit-iterations 20

# Multi-file concurrent edit: 3 files, 4 VUs each
./run.sh concurrent-edit --mode multi-file --files 3 --vus-per-file 4 -n 10

# File size matrix: 50 iterations per size tier
./run.sh file-size-matrix --edit-iterations 50

# Duration-based test: 5 VUs for 30 seconds
./run.sh lifecycle -v 5 -d 30s

# Run all scenarios with 50 VUs
./run.sh all -v 50

# Compare baseline vs current results
./run.sh compare results/baseline/20250625-120000-lifecycle/k6-summary.json \
                 results/current/20250625-130000-lifecycle/k6-summary.json
```

## Shared Client (`lib/penpot-client.js`)

The shared client module wraps the Penpot backend RPC API using plain JSON (not Transit). Key features:

- **JSON transport**: Uses `Content-Type: application/json` for POST bodies and `Accept: application/json` (or `_fmt=json` for GET) for responses.
- **Cookie-based auth**: k6 automatically manages session cookies per VU.
- **Session headers**: Generates `x-session-id` and `x-external-session-id` UUIDs per VU.
- **Tagged metrics**: Every request is tagged with `rpc_command` for k6 metric slicing.

## Results

Test results are written to `results/<timestamp>/` as JSON. k6 also prints a summary to stdout with percentile breakdowns per RPC command.

## Thresholds

Each script includes built-in thresholds that cause k6 to exit with a non-zero code if exceeded:

- `http_req_duration p95 < 5000ms` (global)
- `http_req_failed < 1%` (global)
- Per-command thresholds for login, profile, project, file, and update operations

## Adding New Flows

1. Create `scripts/<flow-name>.js`
2. Import the shared client: `import { createClient } from "../lib/penpot-client.js";`
3. Implement the flow using the client methods
4. Add a command in `run.sh`

## Architecture Notes

- The backend supports both Transit JSON and plain JSON. This test suite uses **plain JSON** for simplicity (no Transit encoder needed in k6).
- JSON request keys are in **kebab-case** (matching Clojure conventions). JSON response keys are in **camelCase** (backend's default JSON encoding).
- `update-file` sends the `id` parameter both in the query string and in the POST body, matching the frontend's behavior.
- The backend uses optimistic concurrency control (`revn`) for file updates. The test retries once on conflict.
